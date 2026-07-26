# Purpose: validate the checked-in iOS accessibility contract for core user flows.
# Input: apps/ios/AmemeApp/AmemeApp.swift SwiftUI source.
# Output: JSON static accessibility verdict; this does not claim VoiceOver or device execution.

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE_PATH = ROOT / "apps" / "ios" / "AmemeApp" / "AmemeApp.swift"


def require(condition: bool, message: str, checks: list[str]) -> None:
    if not condition:
        raise AssertionError(message)
    checks.append(message)


def section(source: str, name: str, next_name: str | None = None) -> str:
    start = source.index(name)
    end = source.index(next_name, start) if next_name else len(source)
    return source[start:end]


def main() -> int:
    checks: list[str] = []
    require(SOURCE_PATH.is_file(), "iOS App source exists", checks)
    source = SOURCE_PATH.read_text(encoding="utf-8")

    for needle, description in (
        ('accessibilityLabel("搜索历史记录")', "Today/Search exposes a named search action"),
        ('accessibilityLabel("打开设置")', "Today/Search exposes a named settings action"),
        ('accessibilityLabel("选择日期")', "Search exposes a named date-range action"),
        ('accessibilityLabel("记录一件事")', "Today exposes a named capture action"),
        ('accessibilityLabel("写下一句话")', "Text capture exposes a named editor"),
        ('Button("确认删除")', "Delete confirmation has a visible action label"),
        ('Button("重试", action: onAction)', "Recoverable state exposes a retry action"),
        ('Button(recorder.isRecording ? "结束录音" : "开始录音")', "Voice capture exposes start/stop labels"),
        ('accessibilityLabel("配对码")', "QR pairing exposes a named manual-code field"),
        ('Button("验证配对码")', "QR pairing exposes a visible manual verification action"),
        ('AgentQRCodeScannerPreview(', "QR pairing includes the camera scanner surface"),
        ('.accessibilityHidden(true)', "Decorative icons are hidden from VoiceOver"),
    ):
        require(needle in source, description, checks)

    state_notice = section(source, "private struct StateNoticeView", "private struct DemoModeNotice")
    require(
        ".accessibilityElement(children: .combine)" in state_notice,
        "State notice status text remains a combined readable element",
        checks,
    )
    require(
        ".accessibilityElement(children: .contain)" in state_notice,
        "State notice preserves retry as a separate accessible child action",
        checks,
    )
    require(
        "dynamicTypeSize.isAccessibilitySize" in state_notice,
        "State notice switches away from a compressed horizontal layout at accessibility sizes",
        checks,
    )
    require(
        ".frame(minWidth: 44, minHeight: 44)" in state_notice,
        "State notice retry action preserves the minimum touch target",
        checks,
    )
    today_view = section(source, "struct TodayView", "struct SearchView")
    require(
        today_view.count(".frame(width: 44, height: 44)") >= 2,
        "Today toolbar actions preserve minimum touch targets",
        checks,
    )
    require(
        'Button(action: onSettings)' in today_view and 'accessibilityIdentifier("today.settings")' in today_view,
        "Today uses a direct accessible settings action instead of a nested single-item menu",
        checks,
    )
    search_view = section(source, "struct SearchView", "struct CaptureSheet")
    require(
        'Button(action: onSettings)' in search_view and 'accessibilityIdentifier("search.settings")' in search_view,
        "Search uses a direct accessible settings action",
        checks,
    )
    require(
        search_view.count(".frame(width: 44, height: 44)") >= 2,
        "Search toolbar actions preserve minimum touch targets",
        checks,
    )
    event_row = section(source, "private struct EventRowView", "private struct DaySummaryView")
    require(
        ".accessibilityElement(children: .combine)" in event_row and ".accessibilityLabel(" in event_row,
        "Event rows expose one concise accessible label",
        checks,
    )
    require(
        "dynamicTypeSize.isAccessibilitySize" in event_row and "accessibilityLayout" in event_row,
        "Event rows switch to a readable vertical layout at accessibility sizes",
        checks,
    )
    require(
        '.safeAreaInset(edge: .bottom, spacing: 0)' in today_view,
        "Today reserves safe-area space for capture instead of covering timeline content",
        checks,
    )
    require(
        ".dynamicTypeSize(.large ... .accessibility1)" in today_view,
        "Today bounds persistent capture chrome while retaining its full accessibility label",
        checks,
    )
    require(
        "usesCompactAccessibilitySearch" in search_view
        and 'TextField("搜索历史记录", text: $query)' in search_view,
        "Search replaces oversized navigation search chrome in compact accessibility layouts",
        checks,
    )
    delete_progress = section(source, "private struct DeleteProgressView")
    require(
        ".accessibilityValue(" in delete_progress,
        "Delete progress exposes the current step as an accessibility value",
        checks,
    )

    print(json.dumps({"ok": True, "checks": len(checks), "errors": []}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, ValueError) as error:
        print(json.dumps({"ok": False, "errors": [str(error)]}, indent=2))
        raise SystemExit(1)
