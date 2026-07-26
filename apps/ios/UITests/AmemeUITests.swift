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
        let demoNotice = app.descendants(matching: .any)["today.demoNotice"]
        XCTAssertTrue(demoNotice.waitForExistence(timeout: 5))
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
        // The portrait pass above already proves that the final demo event is
        // reachable. After an XXXL rotation SwiftUI may virtualize that distant
        // row, so the landscape contract is a visible result plus the separate
        // settings action rather than a second fixed end-of-list lookup.
        let visibleResult = app.buttons
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "event."))
            .firstMatch
        XCTAssertTrue(visibleResult.waitForExistence(timeout: 5))
        let landscapeSettings = app.buttons["search.settings"]
        XCTAssertTrue(landscapeSettings.waitForExistence(timeout: 5))
        XCTAssertTrue(landscapeSettings.isHittable)
        attachScreenshot(named: "05-search-demo-landscape")
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    func testRealLocalExperienceCoversCaptureSearchRevisionDeleteAndDemoIsolation() throws {
        let app = XCUIApplication()
        app.launchArguments = [
            "-AppleLanguages", "(zh-Hans)",
            "-AppleLocale", "zh_CN",
        ]
        app.launchEnvironment["AMEME_UI_TEST_RESET_ONBOARDING"] = "1"
        app.launch()

        let realTitle = "real-local-\(UUID().uuidString.prefix(8))"
        let addendum = "revision-\(UUID().uuidString.prefix(8))"

        let onboardingContinue = app.buttons["查看今天"]
        XCTAssertTrue(onboardingContinue.waitForExistence(timeout: 10))
        onboardingContinue.tap()
        XCTAssertTrue(app.buttons["记录一件事"].waitForExistence(timeout: 5))

        app.buttons["记录一件事"].tap()
        let textChoice = app.buttons["capture.text"]
        XCTAssertTrue(textChoice.waitForExistence(timeout: 5))
        textChoice.tap()
        let editor = app.textViews["写下一句话"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        editor.tap()
        editor.typeText(realTitle)
        let save = app.buttons["保存到本机"]
        XCTAssertTrue(save.isEnabled)
        save.tap()
        dismissNotice(in: app)

        let realEvent = eventButton(containing: realTitle, in: app)
        XCTAssertTrue(scrollToElement(realEvent, in: app))
        attachScreenshot(named: "06-real-local-captured")

        app.buttons["today.settings"].tap()
        XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 5))
        let loadDemo = app.buttons["载入演示数据"]
        XCTAssertTrue(scrollToElement(loadDemo, in: app))
        loadDemo.tap()
        dismissNotice(in: app)
        app.navigationBars["设置"].buttons.firstMatch.tap()
        XCTAssertTrue(app.descendants(matching: .any)["today.demoNotice"].waitForExistence(timeout: 5))
        XCTAssertFalse(eventButton(containing: realTitle, in: app).exists)
        attachScreenshot(named: "07-demo-isolates-real-local-event")

        app.buttons["today.settings"].tap()
        let exitDemo = app.buttons["退出演示数据"]
        XCTAssertTrue(scrollToElement(exitDemo, in: app))
        exitDemo.tap()
        dismissNotice(in: app)
        app.navigationBars["设置"].buttons.firstMatch.tap()
        XCTAssertTrue(scrollToElement(eventButton(containing: realTitle, in: app), in: app))

        app.buttons["today.search"].tap()
        let searchField = app.searchFields["搜索历史记录"]
        XCTAssertTrue(searchField.waitForExistence(timeout: 5))
        searchField.tap()
        searchField.typeText(realTitle)
        let searchResult = eventButton(containing: realTitle, in: app)
        XCTAssertTrue(scrollToElement(searchResult, in: app))
        searchResult.tap()
        XCTAssertTrue(app.navigationBars["事件详情"].waitForExistence(timeout: 5))

        let addendumField = app.textFields["补充一句原话或说明"]
        XCTAssertTrue(scrollToElement(addendumField, in: app))
        addendumField.tap()
        addendumField.typeText(addendum)
        let saveAddendum = app.buttons["保存补充"]
        XCTAssertTrue(scrollToElement(saveAddendum, in: app))
        saveAddendum.tap()
        dismissNotice(in: app)
        XCTAssertTrue(scrollToElement(app.staticTexts["Personal 空间 · Revision 2"], in: app))
        attachScreenshot(named: "08-real-local-revision")

        let deleteImpact = app.buttons["查看删除影响"]
        XCTAssertTrue(scrollToElement(deleteImpact, in: app))
        deleteImpact.tap()
        XCTAssertTrue(app.navigationBars["删除影响与进度"].waitForExistence(timeout: 5))
        let confirmDelete = app.buttons["确认删除"]
        XCTAssertTrue(scrollToElement(confirmDelete, in: app))
        confirmDelete.tap()
        let returnToday = app.buttons["返回今天"]
        XCTAssertTrue(returnToday.waitForExistence(timeout: 5))
        attachScreenshot(named: "09-real-local-delete-complete")
        returnToday.tap()

        XCTAssertTrue(app.buttons["today.search"].waitForExistence(timeout: 5))
        app.buttons["today.search"].tap()
        let deletedSearch = app.searchFields["搜索历史记录"]
        XCTAssertTrue(deletedSearch.waitForExistence(timeout: 5))
        deletedSearch.tap()
        deletedSearch.typeText(realTitle)
        XCTAssertTrue(app.staticTexts["当前条件没有结果"].waitForExistence(timeout: 5))
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
    private func dismissNotice(in app: XCUIApplication) {
        let dismissNotice = app.alerts["提示"].buttons["知道了"]
        XCTAssertTrue(dismissNotice.waitForExistence(timeout: 5))
        dismissNotice.tap()
    }

    @MainActor
    private func eventButton(containing text: String, in app: XCUIApplication) -> XCUIElement {
        app.buttons
            .matching(NSPredicate(format: "label CONTAINS %@", text))
            .firstMatch
    }

    @MainActor
    private func attachScreenshot(named name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
