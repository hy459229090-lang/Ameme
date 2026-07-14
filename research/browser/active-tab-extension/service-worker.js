// Purpose: Capture a small page envelope only after an extension click or command.
// Input: The active tab granted temporarily through Chrome activeTab.
// Output: A local lastCapture result used only by the synthetic capability test.
async function captureTab(tab) {
  const capturedAt = new Date().toISOString();
  if (!tab || typeof tab.id !== "number") {
    await chrome.storage.local.set({
      lastCapture: { ok: false, capturedAt, error: "active_tab_unavailable" }
    });
    return;
  }

  try {
    const [injection] = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: () => ({
        url: window.location.href,
        title: document.title,
        language: document.documentElement.lang || "",
        textLength: (document.body?.innerText || "").length,
        selectedTextLength: String(window.getSelection?.() || "").length
      })
    });
    await chrome.storage.local.set({
      lastCapture: {
        ok: true,
        capturedAt,
        tabId: tab.id,
        envelope: injection.result
      }
    });
  } catch (error) {
    await chrome.storage.local.set({
      lastCapture: {
        ok: false,
        capturedAt,
        tabId: tab.id,
        error: error instanceof Error ? error.message : String(error)
      }
    });
  }
}

chrome.action.onClicked.addListener(captureTab);

chrome.commands.onCommand.addListener(async (command) => {
  if (command !== "capture-current-page") return;
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  await captureTab(tab);
});
