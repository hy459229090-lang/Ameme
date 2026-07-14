from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
VALIDATION_DIR = ROOT / "scripts" / "validation"
sys.path.insert(0, str(VALIDATION_DIR))

from check_contract_compatibility import (  # noqa: E402
    _compare_openapi,
    _compare_schema,
    _sha256,
    check_compatibility,
)


class ContractCompatibilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.snapshot_root = ROOT / "packages" / "contracts" / "versions" / "v0.1"
        cls.schema = json.loads((cls.snapshot_root / "schemas" / "ameme-domain.schema.json").read_text(encoding="utf-8"))
        cls.openapi = yaml.safe_load((cls.snapshot_root / "api" / "openapi.yaml").read_text(encoding="utf-8"))

    def test_registered_snapshot_hashes_and_current_contract_are_compatible(self) -> None:
        result = check_compatibility()
        self.assertTrue(result["ok"], result)
        self.assertGreater(result["checks"], 1000)

    def test_snapshot_hash_is_stable_across_windows_and_lf_line_endings(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artifact.json"
            path.write_bytes(b"first\r\nsecond\r\n")
            self.assertEqual(hashlib.sha256(b"first\nsecond\n").hexdigest(), _sha256(path))

    def test_snapshot_openapi_external_refs_are_self_contained(self) -> None:
        refs: list[str] = []

        def collect(value) -> None:
            if isinstance(value, dict):
                if isinstance(value.get("$ref"), str) and not value["$ref"].startswith("#/"):
                    refs.append(value["$ref"])
                for child in value.values():
                    collect(child)
            elif isinstance(value, list):
                for child in value:
                    collect(child)

        collect(self.openapi)
        self.assertTrue(refs)
        for ref in refs:
            file_part, _, fragment = ref.partition("#")
            target = (self.snapshot_root / "api" / file_part).resolve()
            with self.subTest(ref=ref):
                self.assertTrue(target.is_relative_to(self.snapshot_root))
                self.assertTrue(target.exists())
                document = json.loads(target.read_text(encoding="utf-8"))
                if fragment.startswith("/$defs/"):
                    self.assertIn(fragment.removeprefix("/$defs/"), document["$defs"])

    def test_additive_optional_property_is_compatible(self) -> None:
        current = deepcopy(self.schema)
        current["$defs"]["Event"]["properties"]["optional_future_note"] = {"type": "string"}
        errors: list[str] = []
        warnings: list[str] = []
        _compare_schema(self.schema, current, "schema", errors, warnings)
        self.assertEqual([], errors)

    def test_required_property_addition_is_breaking(self) -> None:
        current = deepcopy(self.schema)
        current["$defs"]["Event"]["properties"]["required_future_note"] = {"type": "string"}
        current["$defs"]["Event"]["required"].append("required_future_note")
        errors: list[str] = []
        _compare_schema(self.schema, current, "schema", errors, [])
        self.assertTrue(any("required fields added" in error for error in errors))

    def test_required_property_removal_is_breaking_for_old_readers(self) -> None:
        current = deepcopy(self.schema)
        current["$defs"]["Event"]["required"].remove("sensitivity")
        errors: list[str] = []
        _compare_schema(self.schema, current, "schema", errors, [])
        self.assertTrue(any("required fields removed" in error for error in errors))

    def test_property_removal_and_constraint_tightening_are_breaking(self) -> None:
        current = deepcopy(self.schema)
        del current["$defs"]["Event"]["properties"]["description"]
        current["$defs"]["Event"]["properties"]["title"]["maxLength"] = 40
        errors: list[str] = []
        _compare_schema(self.schema, current, "schema", errors, [])
        self.assertTrue(any("properties removed" in error for error in errors))
        self.assertTrue(any("maxLength tightened" in error for error in errors))

    def test_enum_value_removal_is_breaking(self) -> None:
        current = deepcopy(self.schema)
        current["$defs"]["AccessGrant"]["properties"]["status"]["enum"].remove("revoked")
        errors: list[str] = []
        _compare_schema(self.schema, current, "schema", errors, [])
        self.assertTrue(any("enum values removed" in error for error in errors))

    def test_openapi_operation_removal_is_breaking(self) -> None:
        current = deepcopy(self.openapi)
        del current["paths"]["/v1/events/{event_id}"]["delete"]
        errors: list[str] = []
        _compare_openapi(self.openapi, current, errors, [])
        self.assertTrue(any("operation removed" in error for error in errors))

    def test_openapi_response_schema_change_is_breaking(self) -> None:
        current = deepcopy(self.openapi)
        current["paths"]["/v1/today"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]["$ref"] = "../schemas/ameme-domain.schema.json#/$defs/Event"
        errors: list[str] = []
        _compare_openapi(self.openapi, current, errors, [])
        self.assertTrue(any("$ref changed" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
