# Purpose: validate that external Gate packs are executable, blank, private-by-default, and hold.
# Input: T0/device/release docs, templates, preparation script, and Git ignore policy.
# Output: JSON preparation checks only; never a real-user/device/release evidence claim.

from __future__ import annotations

import csv
import json
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
TEMPLATES = ROOT / "tests/manual/external-gates"
RESEARCH_PACK = ROOT / "docs/research/MVP用户与真机验证执行包.md"
RELEASE_PACK = ROOT / "docs/release/封闭Beta外部门执行包.md"
PREPARE = ROOT / "scripts/validation/prepare_external_gate_run.py"
SUMMARY = TEMPLATES / "external-gate-summary-template.json"

CSV_HEADERS = {
    "t0-participant-day-template.csv": (
        "study_id",
        "participant_id",
        "segment",
        "platform",
        "relative_day",
        "context_type",
        "source_capability",
        "source_state",
        "permission_explanation_seen",
        "permission_granted",
        "important_context_denominator",
        "captured_important_context_numerator",
        "unique_increment_numerator",
        "candidate_shown_count",
        "candidate_denied_count",
        "confirmed_event_count",
        "revised_event_count",
        "deleted_event_count",
        "active_prompt_count",
        "active_operation_count",
        "active_input_seconds",
        "valid_user_day",
    ),
    "t0-reuse-outcome-template.csv": (
        "study_id",
        "participant_id",
        "relative_day",
        "reuse_intent",
        "attempt_id_hash",
        "event_reference_count",
        "memory_reference_count",
        "lineage_reference_count",
        "outcome",
        "user_action",
        "wrong_memory",
        "important_miss",
        "outdated",
        "prompt_count",
        "permission_denied",
        "deletion_failure",
        "recovery_failure",
        "restricted_egress_blocked",
        "effective_cost_currency",
        "effective_cost_minor",
    ),
    "t0-d8-delete-recovery-template.csv": (
        "study_id",
        "participant_id",
        "platform",
        "physical_device_run_id",
        "delete_scope",
        "impact_preview_understood",
        "delete_committed",
        "derived_content_absent",
        "deleted_content_absent_after_restart",
        "backup_health_visible",
        "backup_id_hash",
        "restore_to_empty_target_completed",
        "today_readable_after_restore",
        "search_readable_after_restore",
        "revision_readable_after_restore",
        "deleted_content_absent_after_restore",
        "participant_expectation_met",
        "research_data_deleted_or_retention_consented",
    ),
    "physical-device-run-template.csv": (
        "run_id",
        "build_commit",
        "build_identifier",
        "platform",
        "device_model_bucket",
        "os_version",
        "physical_device_confirmed",
        "signed_distribution_build",
        "real_account_path",
        "voiceover_or_talkback",
        "font_scale",
        "orientation_case",
        "permission_case",
        "process_termination_case",
        "background_case",
        "text_capture",
        "share_input",
        "photo_input",
        "calendar_input",
        "voice_input",
        "agent_real_host",
        "agent_grant_revoke",
        "local_store_encrypted",
        "key_loss_fail_closed",
        "delete_no_resurrection",
        "export_round_trip",
        "backup_create_verify",
        "restore_empty_target",
        "today_search_revision_after_restore",
        "corruption_fail_closed",
        "nonempty_target_fail_closed",
        "lan_physical_peer",
        "restricted_egress_absent",
        "content_secret_log_canary_absent",
        "crash_or_anr_count",
        "blocking_issue_id",
        "evidence_bundle_hash",
        "reviewer_role",
        "verdict",
    ),
    "provider-cost-input-template.csv": (
        "service",
        "provider",
        "region",
        "currency",
        "price_effective_date",
        "source_reference",
        "unit",
        "unit_price_minor",
        "free_tier_excluded",
        "contract_owner_role",
        "verified_at",
        "reviewer_role",
    ),
}


def require(condition: bool, message: str, checks: list[str]) -> None:
    if not condition:
        raise AssertionError(message)
    checks.append(message)


def main() -> int:
    checks: list[str] = []
    required = (
        TEMPLATES / "README.md",
        RESEARCH_PACK,
        RELEASE_PACK,
        PREPARE,
        SUMMARY,
        TEMPLATES / "signing-store-checklist-template.md",
        *(TEMPLATES / name for name in CSV_HEADERS),
    )
    for path in required:
        require(path.is_file(), f"required external Gate file exists: {path.relative_to(ROOT)}", checks)

    for name, expected in CSV_HEADERS.items():
        path = TEMPLATES / name
        with path.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.reader(handle))
        require(len(rows) == 1, f"{name} remains a blank committed template", checks)
        require(tuple(rows[0]) == expected, f"{name} has the frozen content-free header", checks)

    summary = json.loads(SUMMARY.read_text(encoding="utf-8"))
    require(summary["contains_personal_content"] is False, "summary template declares no personal content", checks)
    require(summary["overall_verdict"] == "hold", "summary template starts at hold", checks)
    require(len(summary["gates"]) >= 10, "summary template covers at least ten external Gates", checks)
    for gate, state in summary["gates"].items():
        require(state["status"] == "hold", f"external Gate {gate} starts at hold", checks)
        require(state["evidence_bundle_hashes"] == [], f"external Gate {gate} has no fake evidence", checks)
        require(state["approver_roles"] == [], f"external Gate {gate} has no fake approval", checks)

    research = RESEARCH_PACK.read_text(encoding="utf-8")
    for marker in (
        "T0 8 人 × 7 天",
        "56/56",
        "24/24",
        "8/8 D8",
        "4 名 iOS / 4 名 Android",
        "每类 6 次",
        "wrong memory 不得超过 2/24",
        "data/private/user-studies/<study-id>/",
        "当前 verdict 为 `hold`",
    ):
        require(marker in research, f"T0 execution pack declares {marker}", checks)

    release = RELEASE_PACK.read_text(encoding="utf-8")
    for marker in (
        "I1",
        "I2",
        "A1",
        "A2",
        "24–32 人时",
        "真实 Agent",
        "生产恢复路线 Gate",
        "签名、商店、隐私、付费服务与成本",
        "事故与回滚演练",
        "当前总 verdict 为 `hold`",
        "不能关闭本文件的 Gate",
    ):
        require(marker in release, f"Beta external Gate pack declares {marker}", checks)

    prepare = PREPARE.read_text(encoding="utf-8")
    for marker in (
        "data/private/external-gates",
        "blank_templates_only",
        '"gate_claim": False',
        "FileExistsError",
        "build commit must be an exact lowercase 40-hex reviewed commit",
    ):
        require(marker in prepare, f"external Gate preparation script enforces {marker}", checks)

    ignored = subprocess.run(
        ["git", "check-ignore", "--quiet", "data/private/external-gates/probe"],
        cwd=ROOT,
        check=False,
    ).returncode
    require(ignored == 0, "private external Gate evidence path is Git-ignored", checks)

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "scope": "external_gate_pack_preparation_only",
                "real_participant_claim": False,
                "physical_device_claim": False,
                "signing_store_claim": False,
                "production_recovery_claim": False,
                "paid_provider_claim": False,
                "overall_verdict": "hold",
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, KeyError, json.JSONDecodeError) as error:
        print(json.dumps({"ok": False, "error": str(error)}, indent=2))
        sys.exit(1)
