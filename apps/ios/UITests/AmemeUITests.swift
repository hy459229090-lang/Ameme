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
        let settingsMenu = app.buttons["today.settings"]
        XCTAssertTrue(settingsMenu.waitForExistence(timeout: 5))
        XCTAssertTrue(settingsMenu.isHittable)
        XCTAssertFalse(onboardingContinue.exists)
        attachScreenshot(named: "01-today-empty")

        settingsMenu.tap()
        let settingsItem = app.buttons["设置"]
        XCTAssertTrue(settingsItem.waitForExistence(timeout: 3))
        settingsItem.tap()

        XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 5))
        let loadDemo = app.buttons["载入演示数据"]
        XCTAssertTrue(scrollToElement(loadDemo, in: app))
        loadDemo.tap()
        let dismissNotice = app.alerts["提示"].buttons["知道了"]
        XCTAssertTrue(dismissNotice.waitForExistence(timeout: 5))
        dismissNotice.tap()

        app.navigationBars["设置"].buttons.firstMatch.tap()
        XCTAssertTrue(app.staticTexts["演示数据"].waitForExistence(timeout: 5))
        XCTAssertTrue(scrollToElement(app.staticTexts["整理今天的产品问题"], in: app))
        attachScreenshot(named: "02-today-demo")

        app.swipeDown()
        let search = app.buttons["搜索历史记录"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        XCTAssertTrue(app.navigationBars["搜索"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.searchFields["搜索历史记录"].waitForExistence(timeout: 5))
        XCTAssertTrue(scrollToElement(app.staticTexts["记录一段晚间想法"], in: app))
        attachScreenshot(named: "03-search-demo")

        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.navigationBars["搜索"].waitForExistence(timeout: 5))
        attachScreenshot(named: "04-search-demo-landscape")
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    private func scrollToElement(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        for _ in 0..<8 {
            if element.exists && element.isHittable { return true }
            app.swipeUp()
        }
        return element.exists
    }

    @MainActor
    private func attachScreenshot(named name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
