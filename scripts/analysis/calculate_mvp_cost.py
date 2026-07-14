# Purpose: calculate comparable Ameme MVP monthly unit-cost scenarios from explicit workload and price variables.
# Input: a JSON file shaped like tests/fixtures/cost/mvp-cost-planning.json; prices may be relative units or supplier quotes.
# Output: JSON with per-profile storage, processing and fully-loaded costs; exits non-zero for missing/invalid variables.

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = ROOT / "tests" / "fixtures" / "cost" / "mvp-cost-planning.json"


def positive(mapping: dict[str, Any], key: str, *, allow_zero: bool = False) -> float:
    value = mapping.get(key)
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError(f"{key} must be numeric")
    if value < 0 or (value == 0 and not allow_zero):
        raise ValueError(f"{key} must be {'non-negative' if allow_zero else 'positive'}")
    return float(value)


def calculate(config: dict[str, Any]) -> dict[str, Any]:
    days = positive(config, "days_per_month")
    sizes = config["object_sizes_bytes"]
    assumptions = config["assumptions"]
    prices = config["unit_prices"]

    event_b = positive(sizes, "event")
    revision_b = positive(sizes, "revision")
    observation_b = positive(sizes, "observation")
    audit_b = positive(sizes, "audit")
    revisions_per_event = positive(assumptions, "revisions_per_event", allow_zero=True)
    observations_per_event = positive(assumptions, "observations_per_event", allow_zero=True)
    audits_per_event = positive(assumptions, "audit_rows_per_event", allow_zero=True)
    index_ratio = positive(assumptions, "index_overhead_ratio", allow_zero=True)
    selected_photo_b = positive(assumptions, "selected_photo_bytes")
    selected_audio_b = positive(assumptions, "selected_audio_bytes_per_min")
    selected_ratio = positive(assumptions, "selected_raw_ratio", allow_zero=True)
    if selected_ratio > 1:
        raise ValueError("selected_raw_ratio must be <= 1")
    in_tokens_task = positive(assumptions, "model_input_tokens_per_task", allow_zero=True)
    out_tokens_task = positive(assumptions, "model_output_tokens_per_task", allow_zero=True)
    tasks_per_event = positive(assumptions, "model_tasks_per_event", allow_zero=True)
    jobs_per_event = positive(assumptions, "jobs_per_event", allow_zero=True)
    egress_ratio = positive(assumptions, "egress_gb_per_structured_gb", allow_zero=True)

    price_keys = [
        "struct_gb_month", "blob_gb_month", "egress_gb", "stt_min", "ocr_image",
        "model_input_million_tokens", "model_output_million_tokens",
        "embedding_million_tokens", "jobs_million", "identity_user_month", "support_hour",
    ]
    p = {key: positive(prices, key, allow_zero=True) for key in price_keys}

    output_profiles: dict[str, Any] = {}
    for name, profile in config["profiles"].items():
        events = positive(profile, "events_per_day") * days
        photos = positive(profile, "photo_refs_per_day", allow_zero=True) * days
        audio_min = positive(profile, "audio_min_per_day", allow_zero=True) * days
        ocr_images = positive(profile, "ocr_images_per_day", allow_zero=True) * days
        recalls = positive(profile, "agent_recalls_per_day", allow_zero=True) * days
        support_hours = positive(profile, "support_hours_per_month", allow_zero=True)

        base_structured = events * (
            event_b
            + revisions_per_event * revision_b
            + observations_per_event * observation_b
            + audits_per_event * audit_b
        )
        structured_bytes = base_structured * (1 + index_ratio)
        structured_gb = structured_bytes / 1_000_000_000
        selected_raw_bytes = selected_ratio * (photos * selected_photo_b + audio_min * selected_audio_b)
        selected_raw_gb = selected_raw_bytes / 1_000_000_000
        model_tasks = events * tasks_per_event + recalls
        input_tokens_m = model_tasks * in_tokens_task / 1_000_000
        output_tokens_m = model_tasks * out_tokens_task / 1_000_000
        jobs_m = events * jobs_per_event / 1_000_000
        egress_gb = structured_gb * egress_ratio + selected_raw_gb

        storage_cost = structured_gb * p["struct_gb_month"] + selected_raw_gb * p["blob_gb_month"]
        model_cost = (
            audio_min * p["stt_min"]
            + ocr_images * p["ocr_image"]
            + input_tokens_m * p["model_input_million_tokens"]
            + output_tokens_m * p["model_output_million_tokens"]
        )
        infra_cost = (
            egress_gb * p["egress_gb"]
            + jobs_m * p["jobs_million"]
            + p["identity_user_month"]
        )
        support_cost = support_hours * p["support_hour"]
        total = storage_cost + model_cost + infra_cost + support_cost

        output_profiles[name] = {
            "monthly_events": round(events, 4),
            "structured_gb_month": round(structured_gb, 6),
            "selected_raw_gb_month": round(selected_raw_gb, 6),
            "egress_gb_month": round(egress_gb, 6),
            "stt_minutes_month": round(audio_min, 4),
            "ocr_images_month": round(ocr_images, 4),
            "model_input_million_tokens": round(input_tokens_m, 6),
            "model_output_million_tokens": round(output_tokens_m, 6),
            "jobs_million": round(jobs_m, 6),
            "storage_cost": round(storage_cost, 6),
            "model_cost": round(model_cost, 6),
            "infra_cost": round(infra_cost, 6),
            "support_cost": round(support_cost, 6),
            "fully_loaded_cost": round(total, 6),
        }

    return {
        "ok": True,
        "status": config.get("status"),
        "currency": config.get("currency"),
        "warning": "Planning fixture values are not observed usage or supplier quotes." if "planning" in str(config.get("status")) else None,
        "profiles": output_profiles,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    args = parser.parse_args()
    try:
        config = json.loads(args.input.read_text(encoding="utf-8"))
        result = calculate(config)
    except (OSError, KeyError, ValueError, json.JSONDecodeError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False, indent=2))
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
