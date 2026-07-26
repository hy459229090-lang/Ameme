import XCTest

final class AmemeUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testMockExperienceCoversTodaySearchAndSettings() throws {
        let app = XCUIApplication()
        app.launchArguments = [
            "-AppleLanguages", "(zh-Hans)",
            "-AppleLocale", "zh_CN",
        ]
        app.launchEnvironment["AMEME_UI_TEST_RESET_ONBOARDING"] = "1"
        app.launch()

        let onboardingContinue = app.buttons["查看今天"]
        XCTAssertTrue(onboardingContinue.waitForExistence(timeout: 10))
        onboardingContinue.tap()
        let settingsButton = app.buttons["today.settings"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 5))
        XCTAssertTrue(settingsButton.isHittable)
        XCTAssertFalse(onboardingContinue.exists)
        attachScreenshot(named: "01-today-empty")

        settingsButton.tap()

        XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 5))
        let loadDemo = app.buttons["载入演示数据"]
        XCTAssertTrue(scrollToElement(loadDemo, in: app))
        loadDemo.tap()
        let dismissNotice = app.alerts["提示"].buttons["知道了"]
        XCTAssertTrue(dismissNotice.waitForExistence(timeout: 5))
        dismissNotice.tap()

        app.navigationBars["设置"].buttons.firstMatch.tap()
        XCTAssertTrue(app.staticTexts["演示数据"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["today.search"].isHittable)
        XCTAssertTrue(app.buttons["today.settings"].isHittable)
        attachScreenshot(named: "02-today-demo-overview")
        let todayDemoEvent = app.staticTexts["整理今天的产品问题"]
        XCTAssertTrue(scrollToElement(todayDemoEvent, in: app))
        app.swipeUp()
        XCTAssertTrue(todayDemoEvent.waitForExistence(timeout: 5))
        attachScreenshot(named: "03-today-demo-events")

        app.swipeDown()
        let search = app.buttons["搜索历史记录"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        XCTAssertTrue(app.navigationBars["搜索"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.searchFields["搜索历史记录"].waitForExistence(timeout: 5))
        XCTAssertTrue(scrollToElement(app.staticTexts["记录一段晚间想法"], in: app))
        attachScreenshot(named: "04-search-demo")

        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.navigationBars["搜索"].waitForExistence(timeout: 5))
        let searchResults = app.scrollViews["search.results"]
        XCTAssertTrue(searchResults.waitForExistence(timeout: 5))
        let eveningEvent = app.buttons["event.00000000-0000-4000-8000-000000000005"]
        XCTAssertTrue(
            scrollToElement(
                eveningEvent,
                in: app,
                scrollContainer: searchResults
            )
        )
        let landscapeSettings = app.buttons["search.settings"]
        XCTAssertTrue(landscapeSettings.waitForExistence(timeout: 5))
        XCTAssertTrue(landscapeSettings.isHittable)
        attachScreenshot(named: "05-search-demo-landscape")
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    private func scrollToElement(
        _ element: XCUIElement,
        in app: XCUIApplication,
        scrollContainer: XCUIElement? = nil
    ) -> Bool {
        let scroller = scrollContainer ?? app
        for _ in 0..<16 {
            if element.isHittable { return true }
            scroller.swipeUp()
        }
        return element.exists && element.isHittable
    }

    @MainActor
    private func attachScreenshot(named name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
