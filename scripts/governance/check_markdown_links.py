# Purpose: verify that relative Markdown links in Ameme governance and product documentation resolve locally.
# Input: tracked-style Markdown files under the workspace root; HTTP, mail, anchors and app URIs are ignored.
# Output: JSON verdict with checked link count and missing/escaping targets; exits non-zero on errors.

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[2]
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
IGNORE_DIRS = {".git", ".venv", "venv", "node_modules", "artifacts", "data", "vault", "build", "dist", "out"}


def markdown_files() -> list[Path]:
    return sorted(
        path
        for path in ROOT.rglob("*.md")
        if not any(part in IGNORE_DIRS for part in path.relative_to(ROOT).parts)
    )


def main() -> int:
    errors: list[dict[str, str | int]] = []
    checked = 0
    root_resolved = ROOT.resolve()
    for document in markdown_files():
        text = document.read_text(encoding="utf-8")
        for line_number, line in enumerate(text.splitlines(), start=1):
            for match in LINK_RE.finditer(line):
                raw = match.group(1).strip().strip("<>")
                if not raw or raw.startswith(("#", "http://", "https://", "mailto:", "chatgpt-conversation://", "app://")):
                    continue
                target_text = unquote(raw.split("#", 1)[0])
                if not target_text:
                    continue
                checked += 1
                target = (document.parent / target_text).resolve() if not Path(target_text).is_absolute() else Path(target_text).resolve()
                try:
                    target.relative_to(root_resolved)
                except ValueError:
                    errors.append({"file": str(document.relative_to(ROOT)), "line": line_number, "target": raw, "error": "target escapes workspace"})
                    continue
                if not target.exists():
                    errors.append({"file": str(document.relative_to(ROOT)), "line": line_number, "target": raw, "error": "target missing"})

    result = {"ok": not errors, "checked_links": checked, "errors": errors}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
