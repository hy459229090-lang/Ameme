# Purpose: validate the checked-in inputs required to assemble Ameme's iOS Share Extension.
# Input: apps/ios Share Extension source, plist, entitlements and Shared incoming-share contract.
# Output: JSON static-contract verdict; this does not claim Xcode signing or device execution.

from __future__ import annotations

import json
from pathlib import Path
import plistlib
import sys


ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / "apps" / "ios"
APP_GROUP = "group.com.ameme.ios"


def load_plist(path: Path) -> dict:
    with path.open("rb") as handle:
        value = plistlib.load(handle)
    if not isinstance(value, dict):
        raise AssertionError(f"{path} must contain a plist dictionary")
    return value


def require(condition: bool, message: str, checks: list[str]) -> None:
    if not condition:
        raise AssertionError(message)
    checks.append(message)


def main() -> int:
    checks: list[str] = []
    source = IOS / "ShareExtension" / "ShareViewController.swift"
    shared = IOS / "Ameme" / "Shared" / "IncomingShare.swift"
    extension_plist_path = IOS / "ShareExtension" / "Info.plist"
    app_entitlements_path = IOS / "Ameme" / "Ameme.entitlements"
    extension_entitlements_path = IOS / "ShareExtension" / "ShareExtension.entitlements"

    for path in (
        source,
        shared,
        extension_plist_path,
        app_entitlements_path,
        extension_entitlements_path,
    ):
        require(path.is_file(), f"required file exists: {path.relative_to(ROOT)}", checks)

    extension_plist = load_plist(extension_plist_path)
    extension = extension_plist.get("NSExtension")
    require(isinstance(extension, dict), "Share Extension NSExtension dictionary exists", checks)
    require(
        extension.get("NSExtensionPointIdentifier") == "com.apple.share-services",
        "Share Extension point is com.apple.share-services",
        checks,
    )
    require(
        extension.get("NSExtensionPrincipalClass") == "$(PRODUCT_MODULE_NAME).ShareViewController",
        "Share Extension principal class is ShareViewController",
        checks,
    )
    activation = extension.get("NSExtensionAttributes", {}).get("NSExtensionActivationRule", {})
    require(
        activation.get("NSExtensionActivationSupportsText") is True,
        "activation accepts text",
        checks,
    )
    require(
        activation.get("NSExtensionActivationSupportsWebURLWithMaxCount") == 1,
        "activation accepts one web URL",
        checks,
    )
    require(
        activation.get("NSExtensionActivationSupportsImageWithMaxCount") == 1,
        "activation accepts one image",
        checks,
    )
    require(
        activation.get("NSExtensionActivationSupportsFileWithMaxCount") == 1,
        "activation accepts one file",
        checks,
    )

    app_groups = load_plist(app_entitlements_path).get("com.apple.security.application-groups")
    extension_groups = load_plist(extension_entitlements_path).get("com.apple.security.application-groups")
    require(app_groups == [APP_GROUP], "App entitlements contain the expected single App Group", checks)
    require(
        extension_groups == [APP_GROUP],
        "Extension entitlements contain the same expected single App Group",
        checks,
    )

    source_text = source.read_text(encoding="utf-8")
    shared_text = shared.read_text(encoding="utf-8")
    for needle, description in (
        ("IncomingShareHandoffStore.appGroupRoot()", "extension resolves the shared App Group root"),
        ("store.writeText", "extension writes bounded text handoffs"),
        ("store.writeFile", "extension writes bounded file handoffs"),
        ("extensionContext?.open", "extension opens the opaque App URL"),
        ("extensionContext?.completeRequest", "extension completes or cancels its request"),
        ("didStart", "extension guards duplicate lifecycle callbacks"),
        ("retryButton", "extension exposes a failed-read retry action"),
    ):
        require(needle in source_text, description, checks)
    for needle, description in (
        ('appGroupIdentifier = "group.com.ameme.ios"', "Shared Core owns the App Group identifier"),
        ('"ameme://incoming-share/\\(id.uuidString)"', "handoff URL contains only the UUID"),
        ("maxTextLength = 16_384", "text handoff has a bounded size"),
        ("maxFileBytes = 32 * 1024 * 1024", "file handoff has a bounded size"),
    ):
        require(needle in shared_text, description, checks)

    print(json.dumps({"ok": True, "checks": len(checks), "errors": []}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, plistlib.InvalidFileException) as error:
        print(json.dumps({"ok": False, "errors": [str(error)]}, indent=2))
        raise SystemExit(1)
