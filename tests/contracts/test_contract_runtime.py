from __future__ import annotations

import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[2]
VALIDATION_DIR = ROOT / "scripts" / "validation"
sys.path.insert(0, str(VALIDATION_DIR))

from contract_semantics import validate_bundle  # noqa: E402
from validate_contracts import ContractValidator, TYPE_TO_DEF  # noqa: E402


SCHEMA_PATH = ROOT / "packages" / "contracts" / "schemas" / "ameme-domain.schema.json"
EXAMPLE_PATH = ROOT / "packages" / "contracts" / "examples" / "synthetic-day.json"


def _resolve(schema: dict[str, Any], root_schema: dict[str, Any]) -> dict[str, Any]:
    while "$ref" in schema:
        schema = root_schema["$defs"][schema["$ref"].removeprefix("#/$defs/")]
    return schema


def _enum_paths(instance: Any, schema: dict[str, Any], root_schema: dict[str, Any], path: tuple[Any, ...] = ()) -> Iterable[tuple[tuple[Any, ...], list[Any]]]:
    schema = _resolve(schema, root_schema)
    if isinstance(instance, dict):
        properties = schema.get("properties", {})
        for key, value in instance.items():
            child = properties.get(key)
            if not isinstance(child, dict):
                continue
            resolved = _resolve(child, root_schema)
            if isinstance(resolved.get("enum"), list):
                yield path + (key,), resolved["enum"]
            yield from _enum_paths(value, child, root_schema, path + (key,))
    elif isinstance(instance, list) and isinstance(schema.get("items"), dict):
        for index, value in enumerate(instance):
            yield from _enum_paths(value, schema["items"], root_schema, path + (index,))


def _set_path(instance: Any, path: tuple[Any, ...], value: Any) -> None:
    target = instance
    for part in path[:-1]:
        target = target[part]
    target[path[-1]] = value


class ContractRuntimeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        cls.bundle = json.loads(EXAMPLE_PATH.read_text(encoding="utf-8"))["objects"]
        cls.valid_by_type = {item["object_type"]: item["object"] for item in cls.bundle}
        source = cls.valid_by_type["source_object"]
        event = cls.valid_by_type["event"]
        episode = cls.valid_by_type["episode"]
        contract = cls.valid_by_type["acquisition_contract"]
        grant = cls.valid_by_type["access_grant"]
        deletion = cls.valid_by_type["deletion_job"]
        export = cls.valid_by_type["export_job"]
        cls.valid_by_definition = {definition: cls.valid_by_type[object_type] for object_type, definition in TYPE_TO_DEF.items()}
        cls.valid_by_definition.update(
            {
                "TimeRange": event["time_range"],
                "Retention": source["retention"],
                "SourceLocator": source["source_locator"],
                "FieldEvidence": event["field_evidence"][0],
                "EpisodeEventRef": episode["event_refs"][0],
                "TodayView": {
                    "schema_version": 1,
                    "ledger": cls.valid_by_type["day_ledger"],
                    "events": [event],
                    "episodes": [episode],
                    "summary": cls.valid_by_type["summary"],
                    "range_state": "partial",
                    "generated_at": "2026-07-13T20:11:01+08:00",
                },
                "AcquisitionContractRequest": {field: contract[field] for field in ("owner_id", "space_id", "device_id", "source_type", "purpose", "data_types", "processing_locations", "sync_mode")},
                "AccessGrantRequest": {field: grant[field] for field in ("caller_id", "purposes", "spaces", "data_types", "not_before", "expires_at", "key_fingerprint")},
                "DeletionRequest": {field: deletion[field] for field in ("space_id", "target_type", "target_id", "scope")},
                "ExportRequest": {field: export[field] for field in ("spaces", "date_range", "formats", "include_selected_raw")},
            }
        )

    def _schema_errors(self, object_type: str, payload: dict[str, Any]) -> list[str]:
        validator = ContractValidator(self.schema)
        validator.validate_instance(payload, self.schema["$defs"][TYPE_TO_DEF[object_type]], object_type)
        return validator.errors

    def _semantic_errors(self, mutate) -> list[str]:
        bundle = deepcopy(self.bundle)
        by_type = {item["object_type"]: item["object"] for item in bundle}
        mutate(by_type)
        return validate_bundle(bundle).errors

    def test_every_public_object_has_a_valid_positive_fixture(self) -> None:
        self.assertEqual(set(TYPE_TO_DEF), set(self.valid_by_type))
        public_object_definitions = {
            name for name, definition in self.schema["$defs"].items() if definition.get("type") == "object"
        }
        self.assertEqual(public_object_definitions, set(self.valid_by_definition))
        for definition_name, payload in self.valid_by_definition.items():
            validator = ContractValidator(self.schema)
            validator.validate_instance(payload, self.schema["$defs"][definition_name], definition_name)
            with self.subTest(definition=definition_name):
                self.assertEqual([], validator.errors)
        self.assertEqual([], validate_bundle(self.bundle).errors)

    def test_every_required_field_has_a_negative_regression(self) -> None:
        checked = 0
        for definition_name, payload in self.valid_by_definition.items():
            definition = self.schema["$defs"][definition_name]
            for field_name in definition.get("required", []):
                checked += 1
                invalid = deepcopy(payload)
                invalid.pop(field_name)
                validator = ContractValidator(self.schema)
                validator.validate_instance(invalid, definition, definition_name)
                with self.subTest(definition=definition_name, field=field_name):
                    self.assertTrue(any(f"missing required field {field_name}" in error for error in validator.errors))
        self.assertGreaterEqual(checked, 150)

    def test_every_public_object_rejects_unknown_fields(self) -> None:
        for definition_name, payload in self.valid_by_definition.items():
            invalid = deepcopy(payload)
            invalid["unknown_contract_field"] = "must-not-be-ignored"
            validator = ContractValidator(self.schema)
            validator.validate_instance(invalid, self.schema["$defs"][definition_name], definition_name)
            with self.subTest(definition=definition_name):
                self.assertTrue(any("unknown properties" in error for error in validator.errors))

    def test_every_reachable_enum_rejects_an_unknown_value(self) -> None:
        checked = 0
        for definition_name, payload in self.valid_by_definition.items():
            definition = self.schema["$defs"][definition_name]
            for path, allowed in _enum_paths(payload, definition, self.schema):
                checked += 1
                invalid = deepcopy(payload)
                sentinel = "__unknown_enum_value__"
                self.assertNotIn(sentinel, allowed)
                _set_path(invalid, path, sentinel)
                validator = ContractValidator(self.schema)
                validator.validate_instance(invalid, definition, definition_name)
                with self.subTest(definition=definition_name, path=path):
                    self.assertTrue(any("not in enum" in error for error in validator.errors))
        self.assertGreaterEqual(checked, 40)

    def test_grant_window_and_query_scope_invariants(self) -> None:
        errors = self._semantic_errors(lambda objects: objects["access_grant"].update(expires_at="2026-07-13T19:00:00+08:00"))
        self.assertTrue(any("INV-GRANT-001" in error for error in errors))

        errors = self._semantic_errors(lambda objects: objects["recall_query"].update(requested_spaces=["space_other"]))
        self.assertTrue(any("INV-GRANT-007" in error for error in errors))

    def test_source_locator_and_space_invariants(self) -> None:
        errors = self._semantic_errors(lambda objects: objects["source_object"]["source_locator"].update(opaque_locator_ref="C:\\Users\\demo\\secret.txt"))
        self.assertTrue(any("INV-SOURCE-003" in error for error in errors))

        errors = self._semantic_errors(lambda objects: objects["observation"].update(space_id="space_other"))
        self.assertTrue(any("INV-SPACE-001" in error for error in errors))

    def test_event_evidence_revision_and_sensitivity_invariants(self) -> None:
        errors = self._semantic_errors(lambda objects: objects["event"]["field_evidence"][0].update(observation_ids=[]))
        self.assertTrue(any("INV-EVIDENCE-001" in error for error in errors))

        def remove_evidence_field(objects: dict[str, dict[str, Any]], field_name: str) -> None:
            objects["event"]["field_evidence"] = [
                evidence for evidence in objects["event"]["field_evidence"] if evidence["field"] != field_name
            ]

        errors = self._semantic_errors(lambda objects: remove_evidence_field(objects, "time"))
        self.assertTrue(any("INV-EVIDENCE-003" in error for error in errors))

        errors = self._semantic_errors(lambda objects: remove_evidence_field(objects, "description"))
        self.assertTrue(any("INV-EVIDENCE-005" in error for error in errors))

        def planned_upgrade(objects: dict[str, dict[str, Any]]) -> None:
            for evidence in objects["event"]["field_evidence"]:
                evidence["status"] = "planned"
            objects["event"]["fact_status"] = "confirmed"

        errors = self._semantic_errors(planned_upgrade)
        self.assertTrue(any("INV-FACT-001" in error for error in errors))

        errors = self._semantic_errors(lambda objects: objects["event"].update(revision=2))
        self.assertTrue(any("INV-REV-002" in error for error in errors))

        errors = self._semantic_errors(lambda objects: objects["event"].update(sensitivity="public"))
        self.assertTrue(any("INV-SENS-001" in error for error in errors))

    def test_episode_ledger_and_lineage_invariants(self) -> None:
        errors = self._semantic_errors(lambda objects: objects["episode"].update(space_id="space_other"))
        self.assertTrue(any("INV-EPISODE-002" in error for error in errors))

        errors = self._semantic_errors(lambda objects: objects["day_ledger"].update(partial_reasons=[]))
        self.assertTrue(any("INV-RANGE-001" in error for error in errors))

        def reverse_lineage(objects: dict[str, dict[str, Any]]) -> None:
            edge = objects["lineage_edge"]
            edge.update(from_type="source_object", from_id="src_text_001", to_type="event", to_id="evt_walk_001")

        errors = self._semantic_errors(reverse_lineage)
        self.assertTrue(any("INV-LINEAGE-006" in error for error in errors))

    def test_context_sync_deletion_and_addendum_invariants(self) -> None:
        def denied_with_content(objects: dict[str, dict[str, Any]]) -> None:
            objects["context_pack"].update(state="denied", denial_reason="policy_blocked")

        errors = self._semantic_errors(denied_with_content)
        self.assertTrue(any("INV-CONTEXT-005" in error for error in errors))

        errors = self._semantic_errors(lambda objects: objects["sync_envelope"].pop("payload"))
        self.assertTrue(any("INV-SYNC-001" in error for error in errors))

        def false_completion(objects: dict[str, dict[str, Any]]) -> None:
            objects["deletion_job"].update(state="completed")

        errors = self._semantic_errors(false_completion)
        self.assertTrue(any("INV-DELETE-003" in error for error in errors))
        self.assertTrue(any("INV-DELETE-004" in error for error in errors))
        self.assertTrue(any("INV-DELETE-005" in error for error in errors))

        errors = self._semantic_errors(lambda objects: objects["user_addendum"].update(target_id="day_missing"))
        self.assertTrue(any("INV-ADDENDUM-001" in error for error in errors))

    def test_time_range_and_ephemeral_retention_invariants(self) -> None:
        errors = self._semantic_errors(lambda objects: objects["event"]["time_range"].update(end="2026-07-13T18:00:00+08:00"))
        self.assertTrue(any("INV-TIME-001" in error for error in errors))

        def missing_expiry(objects: dict[str, dict[str, Any]]) -> None:
            objects["source_object"]["retention"] = {"retention_class": "ephemeral_recovery", "deletion_state": "active"}

        errors = self._semantic_errors(missing_expiry)
        self.assertTrue(any("INV-RET-001" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
