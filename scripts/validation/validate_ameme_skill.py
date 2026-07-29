# Purpose: Validate the repository-owned Ameme Skill package and routing risk fixture.
# Input: packages/agent-skills/ameme-memory and tests/fixtures/skills/ameme-memory-eval.json.
# Output: A JSON validation summary and a non-zero exit code on contract violations.
from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SKILL = ROOT / "packages" / "agent-skills" / "ameme-memory"
FIXTURE = ROOT / "tests" / "fixtures" / "skills" / "ameme-memory-eval.json"
TOOLS = {"pair", "recall", "get_context", "capture", "feedback", "status"}
MODES = {"ameme.pair", "ameme.recall", "ameme.context", "ameme.capture", "ameme.feedback", "ameme.status", "none"}
REQUIRED_RISKS = {"first_pair", "scope_expansion", "restricted_external", "false_trigger", "false_completion_evidence", "prompt_injection", "offline_queue", "destructive_delete", "autonomous_direct_write", "autonomous_restricted_boundary", "long_term_promotion_boundary"}


def main() -> int:
    errors: list[str] = []
    required = [
        SKILL / "SKILL.md",
        SKILL / "agents" / "openai.yaml",
        SKILL / "references" / "workflows.md",
        SKILL / "references" / "policy-and-safety.md",
        SKILL / "references" / "tool-contracts.md",
        SKILL / "references" / "host-adapters.md",
        FIXTURE,
    ]
    for path in required:
        if not path.is_file():
            errors.append(f"missing file: {path.relative_to(ROOT)}")

    if errors:
        print(json.dumps({"status": "failed", "errors": errors}, ensure_ascii=False, indent=2))
        return 1

    skill_text = (SKILL / "SKILL.md").read_text(encoding="utf-8")
    frontmatter = re.match(r"^---\n(.*?)\n---\n", skill_text, re.DOTALL)
    if not frontmatter:
        errors.append("SKILL.md frontmatter missing")
    else:
        header = frontmatter.group(1)
        if not re.search(r"^name:\s*ameme-memory\s*$", header, re.MULTILINE):
            errors.append("frontmatter name must be ameme-memory")
        description = re.search(r"^description:\s*(.+)$", header, re.MULTILINE)
        if not description or len(description.group(1).strip()) < 80:
            errors.append("frontmatter description must explain capability and triggers")
        extra_keys = [line.split(":", 1)[0] for line in header.splitlines() if ":" in line and not line.startswith(("name:", "description:"))]
        if extra_keys:
            errors.append(f"unexpected frontmatter keys: {extra_keys}")

    for tool in TOOLS:
        if f"`{tool}`" not in skill_text:
            errors.append(f"SKILL.md does not mention tool: {tool}")
    for marker in (
        "does not authorize promotion into a confirmed long-term Memory",
        "candidate_user_confirmation_required",
    ):
        if marker not in skill_text:
            errors.append(f"SKILL.md missing long-term memory boundary: {marker}")

    yaml_text = (SKILL / "agents" / "openai.yaml").read_text(encoding="utf-8")
    for marker in ('allow_implicit_invocation: true', 'value: "ameme"', '$ameme-memory'):
        if marker not in yaml_text:
            errors.append(f"openai.yaml missing: {marker}")

    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    cases = fixture.get("cases", [])
    if fixture.get("fixture_version") != "1.0" or fixture.get("skill") != "ameme-memory":
        errors.append("fixture header is invalid")
    if len(cases) < 12:
        errors.append("fixture must contain at least 12 cases")

    ids: set[str] = set()
    risks: set[str] = set()
    for index, case in enumerate(cases):
        missing = {"id", "prompt", "expected_mode", "expected_tool", "requires_confirmation", "risk"} - set(case)
        if missing:
            errors.append(f"case {index} missing keys: {sorted(missing)}")
            continue
        if case["id"] in ids:
            errors.append(f"duplicate case id: {case['id']}")
        ids.add(case["id"])
        risks.add(case["risk"])
        if case["expected_mode"] not in MODES:
            errors.append(f"{case['id']} has unknown mode")
        if case["expected_tool"] not in TOOLS | {"none"}:
            errors.append(f"{case['id']} has unknown tool")
        if not isinstance(case["requires_confirmation"], bool):
            errors.append(f"{case['id']} requires_confirmation must be boolean")

    if missing_risks := REQUIRED_RISKS - risks:
        errors.append(f"fixture missing risk coverage: {sorted(missing_risks)}")

    result = {"status": "passed" if not errors else "failed", "skill": "ameme-memory", "files_checked": len(required), "cases_checked": len(cases), "risks_checked": len(risks), "errors": errors}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
