"""Runner for the synthetic core-day fixture used by tests and the demo."""

from __future__ import annotations

from copy import deepcopy
from typing import Any

from .core import CoreOracle


def load_synthetic_day(core: CoreOracle, fixture: dict[str, Any]) -> dict[str, Any]:
    """Load one synthetic day through public commands and return stable handles."""
    contracts = [deepcopy(fixture["contract"])] + deepcopy(
        fixture.get("additional_contracts", [])
    )
    contract_by_id = {contract["contract_id"]: contract for contract in contracts}
    for index, contract in enumerate(contracts, 1):
        core.create_contract(
            contract, idempotency_key=f"fixture-contract-{index:03d}"
        )
    day = fixture["day"]
    ledger = core.set_day_coverage(
        owner_id=fixture["owner_id"],
        space_id=fixture["space_id"],
        local_date=day["local_date"],
        timezone_name=day["timezone"],
        coverage_state=day["coverage_state"],
        partial_reasons=day.get("partial_reasons", []),
        idempotency_key="fixture-coverage-001",
    )
    handles: dict[str, Any] = {"ledger": ledger, "records": {}, "episodes": {}}
    for index, record in enumerate(fixture["records"], 1):
        key = f"fixture-{record['name']}-{index:03d}"
        source = deepcopy(record["source"])
        contract = contract_by_id[record.get("contract_id", fixture["contract"]["contract_id"])]
        source.update(
            {
                "contract_id": contract["contract_id"],
                "owner_id": fixture["owner_id"],
                "space_id": fixture["space_id"],
                "device_id": contract["device_id"],
            }
        )
        source.setdefault("sync_mode", contract["sync_mode"])
        source.setdefault(
            "retention",
            {"retention_class": "structured_active", "deletion_state": "active"},
        )
        capture = core.capture_source(source, idempotency_key=f"{key}-capture")
        addendum = None
        if record.get("addendum"):
            addendum_data = deepcopy(record["addendum"])
            addendum_data.update(
                {
                    "source_object_id": capture["source_object_id"],
                    "owner_id": fixture["owner_id"],
                    "space_id": fixture["space_id"],
                    "target_id": ledger["day_ledger_id"],
                }
            )
            addendum = core.add_user_addendum(
                addendum_data, idempotency_key=f"{key}-addendum"
            )
        observation_data = deepcopy(record["observation"])
        observation_data.update(
            {
                "source_object_id": capture["source_object_id"],
                "space_id": fixture["space_id"],
            }
        )
        observation = core.create_observation(
            observation_data, idempotency_key=f"{key}-observation"
        )
        event_data = deepcopy(record["event"])
        event_data.update(
            {"owner_id": fixture["owner_id"], "space_id": fixture["space_id"]}
        )
        evidences = []
        for item in record["evidences"]:
            evidence = deepcopy(item)
            evidence["observation_ids"] = [observation["observation_id"]]
            evidences.append(evidence)
        event = core.accept_event(event_data, evidences, idempotency_key=f"{key}-event")
        handles["records"][record["name"]] = {
            "capture": capture,
            "addendum": addendum,
            "observation": observation,
            "event": event,
        }
    for index, episode in enumerate(fixture.get("episodes", []), 1):
        refs = [
            {
                "event_id": handles["records"][ref["record"]]["event"]["event_id"],
                "role": ref.get("role", "middle"),
            }
            for ref in episode["event_refs"]
        ]
        episode_data = deepcopy(episode)
        episode_data.pop("name")
        episode_data.pop("event_refs")
        episode_data.update(
            {"owner_id": fixture["owner_id"], "space_id": fixture["space_id"]}
        )
        handles["episodes"][episode["name"]] = core.create_episode(
            episode_data,
            refs,
            idempotency_key=f"fixture-episode-{index:03d}",
        )
    return handles
