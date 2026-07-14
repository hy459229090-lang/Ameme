# Purpose: compare current Ameme contracts with the registered immutable baseline and reject breaking changes.
# Input: packages/contracts/versions/manifest.json plus current and snapshot JSON Schema/OpenAPI artifacts.
# Output: JSON verdict with checked baseline hashes, breaking errors and compatibility warnings.

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = ROOT / "packages" / "contracts"
MANIFEST_PATH = CONTRACT_ROOT / "versions" / "manifest.json"
HTTP_METHODS = {"get", "post", "put", "patch", "delete", "options", "head", "trace"}


def _sha256(path: Path) -> str:
    canonical_bytes = path.read_bytes().replace(b"\r\n", b"\n")
    return hashlib.sha256(canonical_bytes).hexdigest()


def _load_artifact(path: Path) -> dict[str, Any]:
    if path.suffix == ".json":
        value = json.loads(path.read_text(encoding="utf-8"))
    else:
        value = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"contract artifact must be an object: {path}")
    return value


def _compare_schema(
    baseline: Any,
    current: Any,
    path: str,
    errors: list[str],
    warnings: list[str],
) -> int:
    checks = 1
    if not isinstance(baseline, dict) or not isinstance(current, dict):
        if baseline != current:
            errors.append(f"{path}: schema changed from {baseline!r} to {current!r}")
        return checks

    for keyword in ("type", "$ref", "format", "const"):
        if keyword in baseline:
            checks += 1
            if current.get(keyword) != baseline[keyword]:
                errors.append(f"{path}: {keyword} changed from {baseline[keyword]!r} to {current.get(keyword)!r}")

    baseline_enum = baseline.get("enum")
    current_enum = current.get("enum")
    if isinstance(baseline_enum, list):
        checks += 1
        if not isinstance(current_enum, list):
            errors.append(f"{path}: enum constraint was removed")
        else:
            removed = [value for value in baseline_enum if value not in current_enum]
            added = [value for value in current_enum if value not in baseline_enum]
            if removed:
                errors.append(f"{path}: enum values removed: {removed!r}")
            if added:
                warnings.append(f"{path}: enum values added; old exhaustive readers require review: {added!r}")

    for keyword in ("minimum", "minLength", "minItems", "minProperties"):
        if keyword in baseline:
            checks += 1
            value = current.get(keyword)
            if value is not None and value > baseline[keyword]:
                errors.append(f"{path}: {keyword} tightened from {baseline[keyword]!r} to {value!r}")
    for keyword in ("maximum", "maxLength", "maxItems", "maxProperties"):
        if keyword in baseline:
            checks += 1
            value = current.get(keyword)
            if value is not None and value < baseline[keyword]:
                errors.append(f"{path}: {keyword} tightened from {baseline[keyword]!r} to {value!r}")

    if "pattern" in baseline:
        checks += 1
        if current.get("pattern") != baseline["pattern"]:
            errors.append(f"{path}: pattern changed and requires a new reviewed baseline")

    baseline_required_value = baseline.get("required", [])
    current_required_value = current.get("required", [])
    baseline_required = set(baseline_required_value) if isinstance(baseline_required_value, list) else set()
    current_required = set(current_required_value) if isinstance(current_required_value, list) else set()
    checks += len(baseline_required) + 1
    removed_required = baseline_required - current_required
    added_required = current_required - baseline_required
    if removed_required:
        errors.append(f"{path}: required fields removed: {sorted(removed_required)!r}")
    if added_required:
        errors.append(f"{path}: required fields added: {sorted(added_required)!r}")

    baseline_properties = baseline.get("properties", {})
    current_properties = current.get("properties", {})
    if isinstance(baseline_properties, dict):
        checks += len(baseline_properties)
        if not isinstance(current_properties, dict):
            errors.append(f"{path}: properties map was removed")
        else:
            removed = sorted(set(baseline_properties) - set(current_properties))
            if removed:
                errors.append(f"{path}: properties removed: {removed!r}")
            for name in sorted(set(baseline_properties) & set(current_properties)):
                checks += _compare_schema(
                    baseline_properties[name], current_properties[name], f"{path}.properties.{name}", errors, warnings
                )

    baseline_defs = baseline.get("$defs", {})
    current_defs = current.get("$defs", {})
    if isinstance(baseline_defs, dict):
        checks += len(baseline_defs)
        if not isinstance(current_defs, dict):
            errors.append(f"{path}: $defs map was removed")
        else:
            removed = sorted(set(baseline_defs) - set(current_defs))
            if removed:
                errors.append(f"{path}: definitions removed: {removed!r}")
            for name in sorted(set(baseline_defs) & set(current_defs)):
                checks += _compare_schema(baseline_defs[name], current_defs[name], f"{path}.$defs.{name}", errors, warnings)

    if isinstance(baseline.get("items"), dict):
        current_items = current.get("items")
        checks += 1
        if not isinstance(current_items, dict):
            errors.append(f"{path}: array items schema was removed")
        else:
            checks += _compare_schema(baseline["items"], current_items, f"{path}.items", errors, warnings)

    if baseline.get("additionalProperties") is not False and current.get("additionalProperties") is False:
        checks += 1
        errors.append(f"{path}: additionalProperties became false")
    return checks


def _parameters(path_item: dict[str, Any], operation: dict[str, Any]) -> dict[tuple[str, str], dict[str, Any]]:
    result: dict[tuple[str, str], dict[str, Any]] = {}
    for parameter in [*path_item.get("parameters", []), *operation.get("parameters", [])]:
        if isinstance(parameter, dict) and "$ref" not in parameter:
            name = parameter.get("name")
            location = parameter.get("in")
            if isinstance(name, str) and isinstance(location, str):
                result[(name, location)] = parameter
    return result


def _compare_content(
    baseline: dict[str, Any],
    current: dict[str, Any],
    path: str,
    errors: list[str],
    warnings: list[str],
) -> int:
    checks = 0
    old_content = baseline.get("content", {})
    new_content = current.get("content", {})
    if not isinstance(old_content, dict):
        return checks
    if not isinstance(new_content, dict):
        errors.append(f"{path}: content map was removed")
        return checks + 1
    for media_type, old_media in old_content.items():
        checks += 1
        new_media = new_content.get(media_type)
        if not isinstance(old_media, dict) or not isinstance(new_media, dict):
            errors.append(f"{path}: media type removed: {media_type}")
            continue
        old_schema = old_media.get("schema")
        new_schema = new_media.get("schema")
        if isinstance(old_schema, dict):
            if not isinstance(new_schema, dict):
                errors.append(f"{path} {media_type}: schema was removed")
            else:
                checks += _compare_schema(old_schema, new_schema, f"{path}.content.{media_type}.schema", errors, warnings)
    return checks


def _compare_openapi(
    baseline: dict[str, Any], current: dict[str, Any], errors: list[str], warnings: list[str]
) -> int:
    checks = 1
    if baseline.get("openapi") != current.get("openapi"):
        errors.append(f"openapi: version changed from {baseline.get('openapi')!r} to {current.get('openapi')!r}")
    baseline_paths = baseline.get("paths", {})
    current_paths = current.get("paths", {})
    if not isinstance(baseline_paths, dict) or not isinstance(current_paths, dict):
        errors.append("openapi: paths must remain objects")
        return checks

    removed_paths = sorted(set(baseline_paths) - set(current_paths))
    checks += len(baseline_paths)
    if removed_paths:
        errors.append(f"openapi: paths removed: {removed_paths!r}")
    for path in sorted(set(baseline_paths) & set(current_paths)):
        old_path_item = baseline_paths[path]
        new_path_item = current_paths[path]
        if not isinstance(old_path_item, dict) or not isinstance(new_path_item, dict):
            errors.append(f"openapi {path}: path item changed shape")
            continue
        for method in sorted(HTTP_METHODS & set(old_path_item)):
            checks += 1
            old_operation = old_path_item[method]
            new_operation = new_path_item.get(method)
            if not isinstance(old_operation, dict) or not isinstance(new_operation, dict):
                errors.append(f"openapi {method.upper()} {path}: operation removed")
                continue
            checks += 1
            if old_operation.get("operationId") != new_operation.get("operationId"):
                errors.append(
                    f"openapi {method.upper()} {path}: operationId changed from "
                    f"{old_operation.get('operationId')!r} to {new_operation.get('operationId')!r}"
                )

            old_parameters = _parameters(old_path_item, old_operation)
            new_parameters = _parameters(new_path_item, new_operation)
            for key, old_parameter in old_parameters.items():
                checks += 1
                if key not in new_parameters:
                    errors.append(f"openapi {method.upper()} {path}: parameter removed: {key!r}")
                elif old_parameter.get("required") is not True and new_parameters[key].get("required") is True:
                    errors.append(f"openapi {method.upper()} {path}: parameter became required: {key!r}")
                elif isinstance(old_parameter.get("schema"), dict) and isinstance(new_parameters[key].get("schema"), dict):
                    checks += _compare_schema(
                        old_parameter["schema"],
                        new_parameters[key]["schema"],
                        f"openapi {method.upper()} {path} parameter {key!r}",
                        errors,
                        warnings,
                    )
            for key, new_parameter in new_parameters.items():
                if key not in old_parameters and new_parameter.get("required") is True:
                    errors.append(f"openapi {method.upper()} {path}: new required parameter: {key!r}")

            old_body = old_operation.get("requestBody")
            new_body = new_operation.get("requestBody")
            if isinstance(old_body, dict):
                checks += 1
                if not isinstance(new_body, dict):
                    errors.append(f"openapi {method.upper()} {path}: request body removed")
                elif old_body.get("required") is not True and new_body.get("required") is True:
                    errors.append(f"openapi {method.upper()} {path}: request body became required")
                else:
                    checks += _compare_content(old_body, new_body, f"openapi {method.upper()} {path} requestBody", errors, warnings)
            elif isinstance(new_body, dict) and new_body.get("required") is True:
                errors.append(f"openapi {method.upper()} {path}: new required request body")

            old_responses = old_operation.get("responses", {})
            new_responses = new_operation.get("responses", {})
            if isinstance(old_responses, dict) and isinstance(new_responses, dict):
                old_success = {str(code) for code in old_responses if str(code).startswith("2")}
                new_success = {str(code) for code in new_responses if str(code).startswith("2")}
                checks += len(old_success)
                removed_success = sorted(old_success - new_success)
                if removed_success:
                    errors.append(f"openapi {method.upper()} {path}: success responses removed: {removed_success!r}")
                for response_code, old_response in old_responses.items():
                    new_response = new_responses.get(response_code)
                    if not isinstance(old_response, dict) or not isinstance(new_response, dict):
                        continue
                    checks += _compare_schema(
                        old_response,
                        new_response,
                        f"openapi {method.upper()} {path} response {response_code}",
                        errors,
                        warnings,
                    )
                    checks += _compare_content(
                        old_response,
                        new_response,
                        f"openapi {method.upper()} {path} response {response_code}",
                        errors,
                        warnings,
                    )

    old_components = baseline.get("components", {})
    new_components = current.get("components", {})
    if isinstance(old_components, dict) and isinstance(new_components, dict):
        for section in ("parameters", "responses", "schemas"):
            old_section = old_components.get(section, {})
            new_section = new_components.get(section, {})
            if not isinstance(old_section, dict) or not isinstance(new_section, dict):
                continue
            removed = sorted(set(old_section) - set(new_section))
            checks += len(old_section)
            if removed:
                errors.append(f"openapi components.{section}: entries removed: {removed!r}")
            for name in sorted(set(old_section) & set(new_section)):
                checks += _compare_schema(
                    old_section[name],
                    new_section[name],
                    f"openapi components.{section}.{name}",
                    errors,
                    warnings,
                )
                if section == "responses":
                    checks += _compare_content(
                        old_section[name],
                        new_section[name],
                        f"openapi components.responses.{name}",
                        errors,
                        warnings,
                    )
    return checks


def check_compatibility(manifest_path: Path = MANIFEST_PATH) -> dict[str, Any]:
    errors: list[str] = []
    warnings: list[str] = []
    checks = 0
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        versions = manifest.get("versions", [])
        current_version = manifest.get("current_contract_version")
        entry = next(item for item in versions if item.get("version") == current_version)
    except Exception as exc:
        return {"ok": False, "checks": checks, "errors": [f"manifest load failed: {exc}"], "warnings": warnings}

    for artifact_name, artifact in entry.get("artifacts", {}).items():
        checks += 1
        try:
            snapshot_path = CONTRACT_ROOT / artifact["snapshot_path"]
            current_path = CONTRACT_ROOT / artifact["current_path"]
            expected_hash = artifact["sha256"]
            actual_hash = _sha256(snapshot_path)
            if actual_hash != expected_hash:
                errors.append(
                    f"{artifact_name}: immutable snapshot hash mismatch; expected {expected_hash}, got {actual_hash}"
                )
                continue
            baseline = _load_artifact(snapshot_path)
            current = _load_artifact(current_path)
            if artifact_name == "domain_schema":
                checks += _compare_schema(baseline, current, "schema", errors, warnings)
            elif artifact_name == "openapi":
                checks += _compare_openapi(baseline, current, errors, warnings)
            else:
                warnings.append(f"{artifact_name}: no semantic comparator registered")
        except Exception as exc:
            errors.append(f"{artifact_name}: compatibility check failed: {exc}")

    return {"ok": not errors, "checks": checks, "errors": errors, "warnings": warnings}


def main() -> int:
    result = check_compatibility()
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
