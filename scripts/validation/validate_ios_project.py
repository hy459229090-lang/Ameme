# Purpose: validate the reproducible installable iOS project, embedded extension, UI tests, and CI gate.
# Input: apps/ios project spec/generated project/assets plus the iOS GitHub Actions workflow.
# Output: JSON static-contract verdict; real Xcode build and Simulator execution remain CI responsibilities.

from __future__ import annotations

import json
from pathlib import Path
import plistlib
import struct
import sys

import yaml


ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / "apps" / "ios"


def require(condition: bool, message: str, checks: list[str]) -> None:
    if not condition:
        raise AssertionError(message)
    checks.append(message)


def load_yaml(path: Path) -> dict:
    value = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise AssertionError(f"{path.relative_to(ROOT)} must contain a YAML mapping")
    return value


def load_plist(path: Path) -> dict:
    with path.open("rb") as handle:
        value = plistlib.load(handle)
    if not isinstance(value, dict):
        raise AssertionError(f"{path.relative_to(ROOT)} must contain a plist dictionary")
    return value


def png_metadata(path: Path) -> tuple[int, int, int]:
    payload = path.read_bytes()
    if len(payload) < 26 or payload[:8] != b"\x89PNG\r\n\x1a\n" or payload[12:16] != b"IHDR":
        raise AssertionError(f"{path.relative_to(ROOT)} must be a valid PNG with an IHDR header")
    width, height = struct.unpack(">II", payload[16:24])
    return width, height, payload[25]


def dependency(target: dict, name: str) -> dict | None:
    for value in target.get("dependencies", []):
        if isinstance(value, dict) and value.get("target") == name:
            return value
    return None


def main() -> int:
    checks: list[str] = []
    spec_path = IOS / "project.yml"
    project_path = IOS / "Ameme.xcodeproj" / "project.pbxproj"
    scheme_path = IOS / "Ameme.xcodeproj" / "xcshareddata" / "xcschemes" / "Ameme.xcscheme"
    workflow_path = ROOT / ".github" / "workflows" / "ios.yml"
    icon_path = IOS / "Ameme" / "Assets.xcassets" / "AppIcon.appiconset" / "AppIcon-1024.png"

    for path in (spec_path, project_path, scheme_path, workflow_path, icon_path):
        require(path.is_file(), f"required file exists: {path.relative_to(ROOT)}", checks)

    spec = load_yaml(spec_path)
    options = spec.get("options", {})
    require(
        options.get("minimumXcodeGenVersion") == "2.46.0",
        "XcodeGen generation is pinned to 2.46.0",
        checks,
    )
    require(
        options.get("deploymentTarget", {}).get("iOS") == "18.0",
        "the generated project has an explicit iOS 18.0 deployment target",
        checks,
    )

    targets = spec.get("targets")
    require(isinstance(targets, dict), "project spec declares targets", checks)
    expected_types = {
        "AmemeApp": "application",
        "AmemeShared": "framework.static",
        "AmemeShareExtension": "app-extension",
        "AmemeSharedTests": "bundle.unit-test",
        "AmemeUITests": "bundle.ui-testing",
    }
    for name, expected_type in expected_types.items():
        require(
            isinstance(targets.get(name), dict) and targets[name].get("type") == expected_type,
            f"{name} has XcodeGen type {expected_type}",
            checks,
        )

    app = targets["AmemeApp"]
    extension = targets["AmemeShareExtension"]
    app_shared = dependency(app, "AmemeShared")
    require(
        app_shared is not None and app_shared.get("embed") is False,
        "App links but does not embed the static AmemeShared framework",
        checks,
    )
    embedded_extension = dependency(app, "AmemeShareExtension")
    require(
        embedded_extension is not None and embedded_extension.get("embed") is True,
        "App embeds the Share Extension",
        checks,
    )
    extension_shared = dependency(extension, "AmemeShared")
    require(
        extension_shared is not None and extension_shared.get("embed") is False,
        "Share Extension links but does not embed the static shared core",
        checks,
    )
    require(
        app.get("settings", {}).get("base", {}).get("CODE_SIGN_ENTITLEMENTS") == "Ameme/Ameme.entitlements",
        "App target uses its App Group entitlements",
        checks,
    )
    require(
        extension.get("settings", {}).get("base", {}).get("CODE_SIGN_ENTITLEMENTS")
        == "ShareExtension/ShareExtension.entitlements",
        "Share Extension target uses matching App Group entitlements",
        checks,
    )

    schemes = spec.get("schemes", {})
    test_targets = schemes.get("Ameme", {}).get("test", {}).get("targets", [])
    test_names = {
        value.get("name") for value in test_targets if isinstance(value, dict)
    }
    require(
        test_names == {"AmemeSharedTests", "AmemeUITests"},
        "shared Ameme scheme runs unit and UI tests",
        checks,
    )
    shared_settings = targets["AmemeShared"].get("settings", {}).get("base", {})
    require(
        shared_settings.get("CODE_SIGNING_ALLOWED") is False,
        "static Shared framework remains unsigned while installable test hosts can use Simulator signing",
        checks,
    )

    width, height, color_type = png_metadata(icon_path)
    require((width, height) == (1024, 1024), "App Icon master is exactly 1024x1024", checks)
    require(color_type not in {4, 6}, "App Icon master is opaque", checks)

    app_plist = load_plist(IOS / "Ameme" / "Info.plist")
    require(app_plist.get("CFBundlePackageType") == "APPL", "App plist declares an application bundle", checks)
    require(
        app_plist.get("CFBundleExecutable") == "$(EXECUTABLE_NAME)",
        "App plist declares the built executable",
        checks,
    )
    require(
        app_plist.get("CFBundleIdentifier") == "$(PRODUCT_BUNDLE_IDENTIFIER)",
        "App plist declares the target bundle identifier",
        checks,
    )
    require(
        app_plist.get("NSCameraUsageDescription"),
        "App plist explains camera use for QR scanning",
        checks,
    )
    require(
        app_plist.get("NSLocalNetworkUsageDescription"),
        "App plist explains local-network pairing",
        checks,
    )

    project_text = project_path.read_text(encoding="utf-8")
    for needle, description in (
        ("AmemeShareExtension.appex in Embed Foundation Extensions", "generated project embeds the extension product"),
        ("AmemeSharedTests.xctest", "generated project contains unit tests"),
        ("AmemeUITests.xctest", "generated project contains UI tests"),
        ("Assets.xcassets in Resources", "generated project compiles the asset catalog"),
    ):
        require(needle in project_text, description, checks)

    scheme_text = scheme_path.read_text(encoding="utf-8")
    require("AmemeSharedTests" in scheme_text, "shared scheme includes unit tests", checks)
    require("AmemeUITests" in scheme_text, "shared scheme includes UI tests", checks)

    workflow_text = workflow_path.read_text(encoding="utf-8")
    for needle, description in (
        ("xcodegen generate", "CI regenerates the Xcode project"),
        ("git diff --exit-code -- Ameme.xcodeproj", "CI rejects generated-project drift"),
        ("CODE_SIGNING_ALLOWED=NO", "CI builds the Simulator app without signing"),
        ("xcodebuild", "CI invokes the real Xcode build system"),
        ("Xcode_26.6.app", "CI selects the current stable Xcode SDK that renders native Liquid Glass"),
        ("AmemeTests-Normal.xcresult", "CI persists normal appearance XCTest evidence"),
        (
            "AmemeTests-Accessibility.xcresult",
            "CI persists dark large-text XCTest evidence separately",
        ),
        ("actions/upload-artifact@v4.6.2", "CI uploads device evidence"),
    ):
        require(needle in workflow_text, description, checks)

    ui_test_text = (IOS / "UITests" / "AmemeUITests.swift").read_text(encoding="utf-8")
    for needle, description in (
        ("载入演示数据", "UI test enters explicit mock mode"),
        ("整理今天的产品问题", "UI test verifies the mock Today flow"),
        (
            "testRealLocalExperienceCoversCaptureSearchRevisionDeleteAndDemoIsolation",
            "UI test verifies the real local core flow and demo isolation",
        ),
        ("capture.text", "real-flow UI test uses the stable text-capture identifier"),
        ("delete.returnToday", "real-flow UI test uses the stable full-width return identifier"),
        ("Personal 空间 · Revision 2", "UI test verifies a durable real-event revision"),
        ("确认删除", "UI test verifies explicit local deletion"),
        ("当前条件没有结果", "UI test verifies deleted events leave local search"),
        ("搜索历史记录", "UI test verifies search"),
        ("XCUIDevice.shared.orientation", "UI test verifies orientation changes"),
        ("XCTAttachment", "UI test captures current-run screenshots"),
    ):
        require(needle in ui_test_text, description, checks)

    print(json.dumps({"ok": True, "checks": len(checks), "errors": []}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, plistlib.InvalidFileException, yaml.YAMLError) as error:
        print(json.dumps({"ok": False, "errors": [str(error)]}, indent=2))
        raise SystemExit(1)
