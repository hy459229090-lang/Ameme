# 用途：机器检查 Ameme 工作区的必需入口、目录边界、索引、正本引用、脚本头和敏感数据风险。
# 输入：仓库当前文件树；无外部依赖。
# 输出：stdout JSON 健康报告；存在错误时返回退出码 1。

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]

REQUIRED_ROOT_FILES = {
    "README.md",
    "AGENTS.md",
    "WORKSPACE_MAP.md",
    "STATUS.md",
    "WAL.md",
    ".gitignore",
    ".gitattributes",
    ".editorconfig",
    ".env.example",
}

ALLOWED_ROOT_FILES = REQUIRED_ROOT_FILES | {"LICENSE", "LICENSE.md"}

REQUIRED_ROOT_DIRS = {
    "JOBS",
    "docs",
    "research",
    "apps",
    "services",
    "packages",
    "connectors",
    "infra",
    "scripts",
    "tests",
    ".github",
}

ALLOWED_ROOT_DIRS = REQUIRED_ROOT_DIRS | {".git", ".codex", ".obsidian"}

DOC_DOMAINS = {
    "product",
    "research",
    "architecture",
    "privacy-security",
    "engineering",
    "quality",
    "release",
    "operations",
    "decisions",
    "governance",
}

FORBIDDEN_DIR_NAMES = {
    "node_modules",
    "__pycache__",
    ".venv",
    "venv",
    ".pytest_cache",
    ".mypy_cache",
    ".ruff_cache",
    "dist",
    "build",
    "coverage",
}

SCRIPT_ROOTS = (ROOT / "scripts", ROOT / "research")
SCRIPT_HEADER_SETS = (
    ("# 用途：", "# 输入：", "# 输出："),
    ("# Purpose:", "# Input:", "# Output:"),
)
LARGE_MARKDOWN_BYTES = 30 * 1024
MAX_TRACKABLE_FILE_BYTES = 10 * 1024 * 1024


class Report:
    def __init__(self) -> None:
        self.checks = 0
        self.errors: list[dict[str, str]] = []
        self.warnings: list[dict[str, str]] = []

    def check(self, condition: bool, code: str, message: str, path: Path | None = None) -> None:
        self.checks += 1
        if not condition:
            item = {"code": code, "message": message}
            if path is not None:
                item["path"] = path.relative_to(ROOT).as_posix()
            self.errors.append(item)

    def warn(self, code: str, message: str, path: Path | None = None) -> None:
        item = {"code": code, "message": message}
        if path is not None:
            item["path"] = path.relative_to(ROOT).as_posix()
        self.warnings.append(item)


def iter_files() -> list[Path]:
    files: list[Path] = []
    for path in ROOT.rglob("*"):
        if ".git" in path.parts:
            continue
        if any(part in FORBIDDEN_DIR_NAMES for part in path.relative_to(ROOT).parts):
            continue
        if path.is_file():
            files.append(path)
    return files


def check_root(report: Report) -> None:
    for filename in sorted(REQUIRED_ROOT_FILES):
        report.check((ROOT / filename).is_file(), "missing_root_file", f"缺少根级文件 {filename}", ROOT / filename)
    for dirname in sorted(REQUIRED_ROOT_DIRS):
        report.check((ROOT / dirname).is_dir(), "missing_root_dir", f"缺少根级目录 {dirname}", ROOT / dirname)

    for child in ROOT.iterdir():
        if child.is_file() and child.name not in ALLOWED_ROOT_FILES:
            report.check(False, "root_file_pollution", f"根目录存在未允许文件 {child.name}", child)
        if child.is_dir() and child.name not in ALLOWED_ROOT_DIRS:
            report.check(False, "root_dir_pollution", f"根目录存在未允许目录 {child.name}", child)


def check_docs(report: Report) -> None:
    current = ROOT / "docs" / "_CURRENT.md"
    index = ROOT / "docs" / "_INDEX.md"
    report.check(current.is_file(), "missing_current", "docs 缺少 _CURRENT.md", current)
    report.check(index.is_file(), "missing_docs_index", "docs 缺少 _INDEX.md", index)

    for domain in sorted(DOC_DOMAINS):
        directory = ROOT / "docs" / domain
        report.check(directory.is_dir(), "missing_doc_domain", f"缺少文档域 {domain}", directory)
        report.check((directory / "_INDEX.md").is_file(), "missing_domain_index", f"文档域 {domain} 缺少 _INDEX.md", directory / "_INDEX.md")

    if current.is_file():
        text = current.read_text(encoding="utf-8")
        refs = sorted(set(re.findall(r"`([^`]+\.md)`", text)))
        report.check(bool(refs), "current_without_refs", "docs/_CURRENT.md 没有可检查的 Markdown 正本引用", current)
        for ref in refs:
            candidate = (current.parent / Path(ref.replace("/", str(Path("/"))))).resolve()
            report.check(candidate.is_file(), "broken_current_ref", f"当前正本引用不存在：{ref}", current)


def check_jobs(report: Report) -> None:
    router = ROOT / "JOBS" / "README.md"
    detail = ROOT / "JOBS" / "JOBS-Ameme.md"
    report.check(router.is_file(), "missing_jobs_router", "缺少 JOBS/README.md", router)
    report.check(detail.is_file(), "missing_jobs_detail", "缺少 JOBS/JOBS-Ameme.md", detail)
    if detail.is_file():
        text = detail.read_text(encoding="utf-8")
        report.check("状态：" in text, "job_without_status", "Job 文件缺少状态字段", detail)
        report.check("下一步" in text or "确认" in text, "job_without_next", "Job 文件缺少下一步或确认项", detail)


def check_script_headers(report: Report) -> None:
    for script_root in SCRIPT_ROOTS:
        if not script_root.exists():
            continue
        for path in script_root.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in {".py", ".ps1"}:
                continue
            if any(part in FORBIDDEN_DIR_NAMES for part in path.parts):
                continue
            try:
                first_lines = path.read_text(encoding="utf-8-sig").splitlines()[:3]
            except UnicodeDecodeError:
                report.check(False, "script_encoding", "脚本不是 UTF-8", path)
                continue
            valid = len(first_lines) == 3 and any(
                all(first_lines[i].startswith(prefix) for i, prefix in enumerate(prefixes))
                for prefixes in SCRIPT_HEADER_SETS
            )
            report.check(valid, "script_header", "脚本前三行必须是用途、输入、输出", path)


def check_large_markdown(report: Report, files: list[Path]) -> None:
    required_markers = ("文档状态", "适合读者", "人类快速阅读", "AI 阅读提示")
    for path in files:
        if path.suffix.lower() != ".md" or path.stat().st_size <= LARGE_MARKDOWN_BYTES:
            continue
        text = path.read_text(encoding="utf-8-sig", errors="replace")
        header = "\n".join(text.splitlines()[:60])
        missing = [marker for marker in required_markers if marker not in header]
        report.check(not missing, "large_doc_header", f"大文档文件头缺少：{', '.join(missing)}", path)


def check_sensitive_and_generated(report: Report, files: list[Path]) -> None:
    for path in files:
        relative = path.relative_to(ROOT)
        if path.name == ".env":
            report.check(False, "real_env", "仓库中存在真实 .env", path)
        if path.suffix.lower() in {".db", ".sqlite", ".sqlite3", ".pem", ".p12", ".pfx"}:
            report.check(False, "sensitive_file", "仓库中存在数据库或密钥类文件", path)
        if path.stat().st_size > MAX_TRACKABLE_FILE_BYTES:
            report.check(False, "large_file", "仓库中存在超过 10 MiB 的文件，应使用外部制品/数据存储", path)


def check_gitignore(report: Report) -> None:
    path = ROOT / ".gitignore"
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8")
    required = (".env", "vault/", "data/raw/", "*.db", "node_modules/", "artifacts/")
    for pattern in required:
        report.check(pattern in text, "gitignore_pattern", f".gitignore 缺少敏感/生成物规则 {pattern}", path)


def main() -> int:
    report = Report()
    files = iter_files()
    check_root(report)
    check_docs(report)
    check_jobs(report)
    check_script_headers(report)
    check_large_markdown(report, files)
    check_sensitive_and_generated(report, files)
    check_gitignore(report)

    payload = {
        "ok": not report.errors,
        "checks": report.checks,
        "errors": report.errors,
        "warnings": report.warnings,
    }
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0 if payload["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
