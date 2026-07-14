// Purpose: Verify Chrome activeTab capture and permission revocation on synthetic pages.
// Input: Bundled Playwright, installed Chrome, and active-tab-extension.
// Output: Redacted JSON evidence; no real profile, history, or user page is accessed.
const http = require("http");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFileSync } = require("child_process");
const { chromium } = require("playwright");

const extensionDir = path.resolve(__dirname, "active-tab-extension");
const browserExecutable =
  process.env.AMEME_CHROMIUM_PATH ||
  "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const manifest = JSON.parse(
  fs.readFileSync(path.join(extensionDir, "manifest.json"), "utf8")
);

const pages = {
  "/synthetic": `<!doctype html><html lang="zh-CN"><head><title>Ameme Synthetic Capture</title></head><body><main><h1>Ameme</h1><p>This is synthetic capability evidence.</p></main></body></html>`,
  "/second": `<!doctype html><html lang="en"><head><title>Permission Revocation Page</title></head><body><p>Navigation should revoke the earlier activeTab grant.</p></body></html>`
};

async function waitForServiceWorker(context) {
  let workers = context.serviceWorkers();
  if (workers.length === 0) {
    await context.waitForEvent("serviceworker", { timeout: 10_000 });
    workers = context.serviceWorkers();
  }
  if (workers.length !== 1) {
    throw new Error(`expected_one_service_worker_got_${workers.length}`);
  }
  return workers[0];
}

function sendBrowserShortcut() {
  if (process.platform !== "win32") {
    throw new Error("browser_level_shortcut_helper_only_implemented_on_windows");
  }
  const command = [
    "Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class AmemeKeySender { [DllImport(\"user32.dll\")] public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra); public static void Send() { const uint Up = 2; keybd_event(0x12,0,0,UIntPtr.Zero); keybd_event(0x10,0,0,UIntPtr.Zero); keybd_event(0x4D,0,0,UIntPtr.Zero); keybd_event(0x4D,0,Up,UIntPtr.Zero); keybd_event(0x10,0,Up,UIntPtr.Zero); keybd_event(0x12,0,Up,UIntPtr.Zero); } }'",
    "$shell = New-Object -ComObject WScript.Shell",
    "if (-not $shell.AppActivate('Ameme Synthetic Capture')) { exit 2 }",
    "Start-Sleep -Milliseconds 250",
    "[AmemeKeySender]::Send()"
  ].join("; ");
  execFileSync("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", command], {
    stdio: "pipe"
  });
}

async function main() {
  const server = http.createServer((request, response) => {
    const body = pages[request.url] || "not found";
    response.writeHead(pages[request.url] ? 200 : 404, {
      "Content-Type": "text/html; charset=utf-8"
    });
    response.end(body);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();

  const profileDir = fs.mkdtempSync(path.join(os.tmpdir(), "ameme-chrome-spike-"));
  let context;
  try {
    context = await chromium.launchPersistentContext(profileDir, {
      executablePath: browserExecutable,
      headless: false,
      args: [
        `--disable-extensions-except=${extensionDir}`,
        `--load-extension=${extensionDir}`,
        "--no-first-run",
        "--no-default-browser-check"
      ]
    });
    const worker = await waitForServiceWorker(context);
    const extensionId = new URL(worker.url()).host;
    const page = context.pages()[0] || (await context.newPage());
    await page.goto(`http://127.0.0.1:${port}/synthetic`);
    await page.bringToFront();

    const before = await worker.evaluate(async () => {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      return {
        tabId: tab?.id,
        urlVisible: typeof tab?.url === "string",
        titleVisible: typeof tab?.title === "string"
      };
    });
    const registeredCommands = await worker.evaluate(() => chrome.commands.getAll());

    sendBrowserShortcut();
    let capture;
    for (let attempt = 0; attempt < 20; attempt += 1) {
      capture = await worker.evaluate(async () => {
        const data = await chrome.storage.local.get("lastCapture");
        return data.lastCapture;
      });
      if (capture) break;
      await page.waitForTimeout(250);
    }
    if (!capture) {
      throw new Error(
        `capture_command_not_observed:${JSON.stringify(registeredCommands)}`
      );
    }

    // Same-origin navigation intentionally retains activeTab. Switch host names
    // to create a different origin and verify that the earlier grant is revoked.
    await page.goto(`http://localhost:${port}/second`);
    await page.waitForTimeout(250);
    const revoked = await worker.evaluate(async (tabId) => {
      try {
        await chrome.scripting.executeScript({
          target: { tabId },
          func: () => document.title
        });
        return { blocked: false };
      } catch (error) {
        return {
          blocked: true,
          errorClass: error?.message?.includes("Cannot access")
            ? "cannot_access_after_navigation"
            : "permission_error"
        };
      }
    }, capture.tabId);

    const expectedPermissions = ["activeTab", "scripting", "storage"];
    const actualPermissions = [...manifest.permissions].sort();
    const assertions = {
      noHostPermissions: !Object.hasOwn(manifest, "host_permissions"),
      permissionsMinimal:
        JSON.stringify(actualPermissions) ===
        JSON.stringify([...expectedPermissions].sort()),
      preGestureUrlHidden: before.urlVisible === false,
      preGestureTitleHidden: before.titleVisible === false,
      captureSucceeded: capture?.ok === true,
      syntheticTitleMatched:
        capture?.envelope?.title === "Ameme Synthetic Capture",
      syntheticUrlMatched:
        capture?.envelope?.url === `http://127.0.0.1:${port}/synthetic`,
      accessRevokedAfterNavigation: revoked.blocked === true
    };
    const ok = Object.values(assertions).every(Boolean);
    process.stdout.write(
      JSON.stringify(
        {
          ok,
          extensionId,
          manifestPermissions: actualPermissions,
          hostPermissionsDeclared: false,
          beforeGesture: before,
          capture: {
            ok: capture?.ok,
            titleMatched: assertions.syntheticTitleMatched,
            urlMatched: assertions.syntheticUrlMatched,
            language: capture?.envelope?.language,
            textLength: capture?.envelope?.textLength,
            selectedTextLength: capture?.envelope?.selectedTextLength
          },
          afterNavigation: revoked,
          assertions,
          privacy: {
            isolatedTemporaryProfile: true,
            syntheticLocalPagesOnly: true,
            realHistoryRead: false,
            realPageContentRead: false,
            profileDeletedAfterRun: true
          }
        },
        null,
        2
      )
    );
    if (!ok) process.exitCode = 1;
  } finally {
    if (context) await context.close();
    await new Promise((resolve) => server.close(resolve));
    fs.rmSync(profileDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exitCode = 1;
});
