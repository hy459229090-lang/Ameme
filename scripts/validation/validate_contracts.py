# Purpose: validate Ameme MVP JSON Schema, OpenAPI and synthetic contract examples without network access.
# Input: packages/contracts/schemas, packages/contracts/api/openapi.yaml and packages/contracts/examples/synthetic-day.json.
# Output: JSON verdict with check count and actionable errors; exits non-zero on any contract failure.

from __future__ import annotations

import json
import re
import sys
from copy import deepcopy
from datetime import date, datetime
from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = ROOT / "packages" / "contracts" / "schemas" / "ameme-domain.schema.json"
OPENAPI_PATH = ROOT / "packages" / "contracts" / "api" / "openapi.yaml"
EXAMPLE_PATH = ROOT / "packages" / "contracts" / "examples" / "synthetic-day.json"
INVALID_PATH = ROOT / "packages" / "contracts" / "examples" / "invalid-contracts.json"

TYPE_TO_DEF = {
    "acquisition_contract": "AcquisitionContract",
    "source_object": "SourceObjectEnvelope",
    "observation": "Observation",
    "user_addendum": "UserAddendum",
    "event_candidate": "EventCandidate",
    "event": "Event",
    "event_revision": "EventRevision",
    "episode": "Episode",
    "episode_revision": "EpisodeRevision",
    "artifact": "Artifact",
    "day_ledger": "DayLedger",
    "summary": "Summary",
    "feedback_event": "FeedbackEvent",
    "access_grant": "AccessGrant",
    "recall_query": "RecallQuery",
    "recall_page": "RecallPage",
    "context_pack": "ContextPack",
    "lineage_edge": "LineageEdge",
    "sync_envelope": "SyncEnvelope",
    "deletion_job": "DeletionJob",
    "export_job": "ExportJob",
}


class ContractValidator:
    def __init__(self, root_schema: dict[str, Any]) -> None:
        self.root_schema = root_schema
        self.errors: list[str] = []
        self.checks = 0

    def check(self, condition: bool, message: str) -> None:
        self.checks += 1
        if not condition:
            self.errors.append(message)

    def resolve_ref(self, ref: str) -> dict[str, Any] | None:
        self.check(ref.startswith("#/$defs/"), f"unsupported JSON Schema ref: {ref}")
        if not ref.startswith("#/$defs/"):
            return None
        name = ref.removeprefix("#/$defs/")
        target = self.root_schema.get("$defs", {}).get(name)
        self.check(isinstance(target, dict), f"missing JSON Schema definition: {name}")
        return target if isinstance(target, dict) else None

    def validate_schema_shape(self) -> None:
        self.check(self.root_schema.get("$schema") == "https://json-schema.org/draft/2020-12/schema", "schema draft must be 2020-12")
        defs = self.root_schema.get("$defs")
        self.check(isinstance(defs, dict) and bool(defs), "$defs must be non-empty")
        for object_type, def_name in TYPE_TO_DEF.items():
            self.check(def_name in defs, f"{object_type} has no $defs/{def_name}")
        self._walk_refs(self.root_schema, "schema")
        for name, definition in defs.items():
            if isinstance(definition, dict) and definition.get("type") == "object":
                properties = definition.get("properties", {})
                for field in definition.get("required", []):
                    self.check(field in properties, f"$defs/{name} requires undefined property {field}")
                for prop_name, prop_schema in properties.items():
                    if isinstance(prop_schema, dict) and isinstance(prop_schema.get("enum"), list):
                        values = prop_schema["enum"]
                        self.check(len(values) == len(set(map(str, values))), f"$defs/{name}.{prop_name} enum contains duplicates")

    def _walk_refs(self, node: Any, path: str) -> None:
        if isinstance(node, dict):
            if "$ref" in node and isinstance(node["$ref"], str):
                self.resolve_ref(node["$ref"])
            for key, value in node.items():
                self._walk_refs(value, f"{path}.{key}")
        elif isinstance(node, list):
            for index, value in enumerate(node):
                self._walk_refs(value, f"{path}[{index}]")

    def validate_instance(self, instance: Any, schema: dict[str, Any], path: str) -> None:
        if "$ref" in schema:
            target = self.resolve_ref(schema["$ref"])
            if target is not None:
                self.validate_instance(instance, target, path)
            return

        if "const" in schema:
            self.check(instance == schema["const"], f"{path}: expected const {schema['const']!r}, got {instance!r}")
        if "enum" in schema:
            self.check(instance in schema["enum"], f"{path}: {instance!r} not in enum {schema['enum']!r}")

        expected = schema.get("type")
        type_ok = True
        if expected == "object":
            type_ok = isinstance(instance, dict)
        elif expected == "array":
            type_ok = isinstance(instance, list)
        elif expected == "string":
            type_ok = isinstance(instance, str)
        elif expected == "integer":
            type_ok = isinstance(instance, int) and not isinstance(instance, bool)
        elif expected == "number":
            type_ok = isinstance(instance, (int, float)) and not isinstance(instance, bool)
        elif expected == "boolean":
            type_ok = isinstance(instance, bool)
        self.check(type_ok, f"{path}: expected {expected}, got {type(instance).__name__}")
        if not type_ok:
            return

        if isinstance(instance, dict):
            for field in schema.get("required", []):
                self.check(field in instance, f"{path}: missing required field {field}")
            properties = schema.get("properties", {})
            if schema.get("additionalProperties") is False:
                unknown = sorted(set(instance) - set(properties))
                self.check(not unknown, f"{path}: unknown properties {unknown}")
            for field, value in instance.items():
                child = properties.get(field)
                if isinstance(child, dict):
                    self.validate_instance(value, child, f"{path}.{field}")
            if "minProperties" in schema:
                self.check(len(instance) >= schema["minProperties"], f"{path}: fewer than {schema['minProperties']} properties")

        if isinstance(instance, list):
            if "minItems" in schema:
                self.check(len(instance) >= schema["minItems"], f"{path}: fewer than {schema['minItems']} items")
            if schema.get("uniqueItems"):
                canonical = [json.dumps(item, sort_keys=True, ensure_ascii=False) for item in instance]
                self.check(len(canonical) == len(set(canonical)), f"{path}: duplicate array items")
            item_schema = schema.get("items")
            if isinstance(item_schema, dict):
                for index, value in enumerate(instance):
                    self.validate_instance(value, item_schema, f"{path}[{index}]")

        if isinstance(instance, str):
            if "minLength" in schema:
                self.check(len(instance) >= schema["minLength"], f"{path}: shorter than minLength")
            if "maxLength" in schema:
                self.check(len(instance) <= schema["maxLength"], f"{path}: longer than maxLength")
            if "pattern" in schema:
                self.check(re.search(schema["pattern"], instance) is not None, f"{path}: does not match pattern")
            if schema.get("format") == "date-time":
                try:
                    datetime.fromisoformat(instance.replace("Z", "+00:00"))
                except ValueError:
                    self.check(False, f"{path}: invalid date-time")
            if schema.get("format") == "date":
                try:
                    date.fromisoformat(instance)
                except ValueError:
                    self.check(False, f"{path}: invalid date")

        if isinstance(instance, (int, float)) and not isinstance(instance, bool):
            if "minimum" in schema:
                self.check(instance >= schema["minimum"], f"{path}: below minimum")
            if "maximum" in schema:
                self.check(instance <= schema["maximum"], f"{path}: above maximum")


def validate_openapi(validator: ContractValidator, api: dict[str, Any]) -> None:
    validator.check(api.get("openapi") == "3.1.0", "OpenAPI version must be 3.1.0")
    paths = api.get("paths")
    validator.check(isinstance(paths, dict) and bool(paths), "OpenAPI paths must be non-empty")
    operation_ids: list[str] = []
    for path, path_item in (paths or {}).items():
        validator.check(path.startswith("/v1/"), f"API path is not versioned: {path}")
        for method in ("get", "post", "put", "patch", "delete"):
            operation = path_item.get(method) if isinstance(path_item, dict) else None
            if not isinstance(operation, dict):
                continue
            operation_id = operation.get("operationId")
            validator.check(isinstance(operation_id, str) and bool(operation_id), f"{method.upper()} {path} missing operationId")
            if isinstance(operation_id, str):
                operation_ids.append(operation_id)
            responses = operation.get("responses")
            validator.check(isinstance(responses, dict) and bool(responses), f"{method.upper()} {path} missing responses")
            if method in {"post", "put", "patch", "delete"}:
                params = operation.get("parameters", [])
                has_idempotency = any(isinstance(item, dict) and item.get("$ref") == "#/components/parameters/IdempotencyKey" for item in params)
                validator.check(has_idempotency or operation_id in {"recall", "createContextPack"}, f"{method.upper()} {path} missing Idempotency-Key")
    validator.check(len(operation_ids) == len(set(operation_ids)), "OpenAPI operationId values must be unique")
    walk_openapi_refs(validator, api, OPENAPI_PATH.parent)


def walk_openapi_refs(validator: ContractValidator, node: Any, base: Path) -> None:
    if isinstance(node, dict):
        ref = node.get("$ref")
        if isinstance(ref, str) and not ref.startswith("#/"):
            file_part, _, fragment = ref.partition("#")
            target_path = (base / file_part).resolve()
            validator.check(target_path.exists(), f"OpenAPI external ref file missing: {ref}")
            if target_path.exists() and fragment.startswith("/$defs/"):
                definition = fragment.removeprefix("/$defs/")
                validator.check(definition in validator.root_schema.get("$defs", {}), f"OpenAPI ref definition missing: {definition}")
        for value in node.values():
            walk_openapi_refs(validator, value, base)
    elif isinstance(node, list):
        for value in node:
            walk_openapi_refs(validator, value, base)


def main() -> int:
    try:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        api = yaml.safe_load(OPENAPI_PATH.read_text(encoding="utf-8"))
        example = json.loads(EXAMPLE_PATH.read_text(encoding="utf-8"))
        invalid = json.loads(INVALID_PATH.read_text(encoding="utf-8"))
    except Exception as exc:  # syntax and parser errors must be explicit
        print(json.dumps({"ok": False, "checks": 0, "errors": [f"load failed: {exc}"]}, ensure_ascii=False, indent=2))
        return 1

    validator = ContractValidator(schema)
    validator.validate_schema_shape()
    validate_openapi(validator, api)

    validator.check(example.get("example_version") == 1, "synthetic example_version must be 1")
    objects = example.get("objects")
    validator.check(isinstance(objects, list) and bool(objects), "synthetic objects must be non-empty")
    seen_types: set[str] = set()
    for index, wrapper in enumerate(objects or []):
        validator.check(isinstance(wrapper, dict), f"objects[{index}] must be an object")
        if not isinstance(wrapper, dict):
            continue
        object_type = wrapper.get("object_type")
        payload = wrapper.get("object")
        validator.check(object_type in TYPE_TO_DEF, f"objects[{index}] unsupported object_type {object_type!r}")
        validator.check(isinstance(payload, dict), f"objects[{index}].object must be an object")
        if object_type in TYPE_TO_DEF and isinstance(payload, dict):
            seen_types.add(object_type)
            validator.validate_instance(payload, schema["$defs"][TYPE_TO_DEF[object_type]], f"objects[{index}].object")
    validator.check(seen_types == set(TYPE_TO_DEF), f"synthetic bundle missing object types: {sorted(set(TYPE_TO_DEF) - seen_types)}")

    valid_by_type = {wrapper["object_type"]: wrapper["object"] for wrapper in objects or [] if isinstance(wrapper, dict) and wrapper.get("object_type") in TYPE_TO_DEF}
    validator.check(invalid.get("example_version") == 1, "invalid fixture example_version must be 1")
    cases = invalid.get("cases")
    validator.check(isinstance(cases, list) and bool(cases), "invalid fixture cases must be non-empty")
    for index, case in enumerate(cases or []):
        validator.check(isinstance(case, dict), f"invalid cases[{index}] must be an object")
        if not isinstance(case, dict):
            continue
        object_type = case.get("object_type")
        validator.check(object_type in valid_by_type, f"invalid cases[{index}] source type missing: {object_type}")
        if object_type not in valid_by_type:
            continue
        payload = deepcopy(valid_by_type[object_type])
        path = case.get("path")
        operation = case.get("operation")
        validator.check(isinstance(path, str) and path, f"invalid cases[{index}] path missing")
        if not isinstance(path, str) or not path:
            continue
        if operation == "remove":
            payload.pop(path, None)
        elif operation == "set":
            payload[path] = case.get("value")
        else:
            validator.check(False, f"invalid cases[{index}] unsupported operation {operation}")
            continue
        negative = ContractValidator(schema)
        negative.validate_instance(payload, schema["$defs"][TYPE_TO_DEF[object_type]], f"invalid[{case.get('name', index)}]")
        expected = case.get("expected_error")
        validator.check(isinstance(expected, str) and any(expected in error for error in negative.errors), f"invalid case {case.get('name', index)} did not produce expected error {expected!r}; got {negative.errors}")

    result = {"ok": not validator.errors, "checks": validator.checks, "errors": validator.errors}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
