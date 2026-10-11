import { _android } from "../../../node_modules/@playwright/test/index.mjs";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const device = (await _android.devices()).find((item) => item.serial() === "emulator-5558");
if (!device) throw new Error("Only use the isolated API35 emulator");
const pkg = "xyz.leedaud.echo";
const out = resolve("apps/android/app/build/android-qa/local-ui/lifecycle");
await mkdir(out, { recursive: true });
const errors: string[] = [];
const startupRetries: string[] = [];
const checks: string[] = [];
const pause = (ms: number) => new Promise((done) => setTimeout(done, ms));
async function start() {
  await device!.shell(`am force-stop ${pkg}`);
  for (let i = 0; i < 100 && device!.webViews().some((view) => view.pkg() === pkg); i++) await pause(100);
    await device!.shell(`am start -n ${pkg}/.NativeActivity`);
  for (let attempt = 0; attempt < 4; attempt++) {
    const view = await device!.webView({ pkg }, { timeout: 30000 });
    try {
      const page = await view.page();
      await page.locator(".cm-content").first().waitFor({ timeout: 30000 });
      if (page.url() !== "https://echo-app.local/") throw new Error("Unexpected local origin");
      page.on("pageerror", (error) => errors.push(error.message));
      return page;
    } catch (error) {
      const pid = (await device!.shell(`pidof ${pkg}`)).toString().trim();
      if (!pid || attempt === 3) throw error;
      startupRetries.push(String(error));
      await pause(300);
    }
  }
  throw new Error("No local page");
}
async function api(page: Awaited<ReturnType<typeof start>>, service: string, input: unknown) {
  return page.evaluate(async ({ service, input }) => {
    const response = await fetch(`/memos.api.v1.${service}`, { method: "POST", body: JSON.stringify(input) });
    const value = await response.json();
    if (!response.ok) throw new Error(value.message);
    return value;
  }, { service, input });
}
async function assertDraft(page: Awaited<ReturnType<typeof start>>, expected: string) {
  // innerText adds layout-dependent blank lines to CodeMirror; compare its individual text lines.
  await page.waitForFunction((expected) => [...document.querySelectorAll(".cm-content .cm-line")].map((line) => line.textContent).join("\n") === expected, expected);
}
async function waitPicker() {
  for (let i = 0; i < 40; i++) {
    const state = (await device!.shell("dumpsys activity activities")).toString();
    if (state.split("\n").some((line) => /topResumedActivity|mResumedActivity/.test(line) && line.includes("documentsui"))) return;
    await pause(250);
  }
  throw new Error("System document picker did not open");
}
async function denyPermission(page: Awaited<ReturnType<typeof start>>) {
  for (let i = 0; i < 5; i++) {
    await device!.shell("uiautomator dump /sdcard/echo-ui-qa.xml");
    const xml = (await device!.shell("cat /sdcard/echo-ui-qa.xml")).toString();
    const bounds = await page.evaluate((xml) => {
      const document = new DOMParser().parseFromString(xml, "application/xml");
      return [...document.querySelectorAll("node")].find((node) => node.getAttribute("resource-id")?.endsWith("/permission_deny_button"))?.getAttribute("bounds");
    }, xml);
    const match = bounds?.match(/^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/);
    if (match) {
      await device!.shell(`input tap ${Math.floor((Number(match[1]) + Number(match[3])) / 2)} ${Math.floor((Number(match[2]) + Number(match[4])) / 2)}`);
      return;
    }
    await pause(300);
  }
  throw new Error("Permission denial button missing");
}
try {
  await device.shell(`am force-stop ${pkg}`);
  for (const permission of ["RECORD_AUDIO", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION"]) {
    await device.shell(`pm revoke ${pkg} android.permission.${permission}`);
    await device.shell(`pm clear-permission-flags ${pkg} android.permission.${permission} user-set user-fixed`);
  }
  let page = await start();
  const marker = `W4-QA 恢复 ${Date.now()}`;
  const draft = `${marker}\n\n- [ ] 未保存任务\n\n**格式保留**`;
  await page.locator(".cm-content").first().fill(draft);
  await page.getByRole("button", { name: "暂存到当前设备", exact: true }).click();
  await page.getByText("已暂存到当前设备", { exact: true }).waitFor();
  const before = await api(page, "MemoService/ListMemos", { filter: `content.contains("${marker}")` });
  if (before.memos.length !== 0) throw new Error("Stash created a formal note");
  checks.push("stash-does-not-create-note");
  for (let cycle = 0; cycle < 3; cycle++) {
    page = await start();
    await assertDraft(page, draft);
  }
  checks.push("three-cold-starts-restore-draft");
  await device.shell("svc wifi disable");
  await device.shell("svc data disable");
  page = await start();
  await assertDraft(page, draft);
  checks.push("offline-cold-start");
  console.log("PASS cold starts, draft recovery and offline startup");

  await page.getByRole("button", { name: "增加", exact: true }).click();
  await page.getByRole("menuitem", { name: "添加附件", exact: true }).click();
  await waitPicker();
  await device.shell("input keyevent 4");
  await page.locator(".cm-content").first().waitFor();
  await assertDraft(page, draft);
  checks.push("system-file-picker-cancel");

  await page.getByRole("button", { name: "增加", exact: true }).click();
  await page.getByRole("menuitem", { name: "录制音频", exact: true }).click();
  await denyPermission(page);
  await assertDraft(page, draft);
  await page.waitForFunction(() => !document.querySelector<HTMLButtonElement>('button[aria-label="保存"]')?.disabled);
  checks.push("microphone-denial-preserves-draft");

  await page.getByRole("button", { name: "增加", exact: true }).click();
  await page.getByRole("menuitem", { name: "添加位置", exact: true }).click();
  await denyPermission(page);
  await page.getByRole("dialog").getByRole("button", { name: "关闭", exact: true }).click();
  await assertDraft(page, draft);
  checks.push("location-denial-and-cancel-preserve-draft");
  console.log("PASS system file picker and microphone/location denial");

  await page.getByRole("button", { name: "保存", exact: true }).click();
  await page.locator("article").filter({ hasText: marker }).first().waitFor();
  page = await start();
  if ((await page.locator(".cm-content").first().innerText()).includes(marker)) throw new Error("Saved draft resurrected");
  const formal = await api(page, "MemoService/ListMemos", { filter: `content.contains("${marker}")` });
  if (formal.memos.length !== 1 || formal.memos[0].content !== draft || formal.memos[0].attachments?.length || formal.memos[0].location) throw new Error("Saved source changed after restart");
  checks.push("saved-draft-does-not-resurrect");
  await page.locator("article").filter({ hasText: marker }).first().getByRole("button", { name: "更多", exact: true }).click();
  await page.getByRole("menuitem", { name: "导出 Markdown 和附件", exact: true }).click();
  await waitPicker();
  await device.shell("input keyevent 4");
  await page.getByText("已取消导出，原内容保留", { exact: true }).waitFor();
  checks.push("system-export-cancel");

  const largeMarker = `W4-QA 大消息 ${Date.now()}`;
  const large = `${largeMarker}\n\n${"fragment-safe-text ".repeat(5000)}END-W4`;
  await page.locator(".cm-content").first().fill(large);
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await page.locator("article").filter({ hasText: largeMarker }).first().waitFor();
  const listed = await api(page, "MemoService/ListMemos", { filter: `content.contains("${largeMarker}")` });
  if (listed.memos.length !== 1 || listed.memos[0].content !== large) throw new Error("Fragmented list response corrupted content");
  const detail = await api(page, "MemoService/GetMemo", { name: listed.memos[0].name });
  if (detail.content !== large) throw new Error("Fragmented detail response corrupted content");
  checks.push("large-list-and-detail-message-reassembly");

  const settings = await api(page, "UserService/ListUserSettings", { parent: "users/device" });
  const general = settings.settings.find((item: any) => item.generalSetting);
  for (const theme of ["default", "default-dark"]) {
    await api(page, "UserService/UpdateUserSetting", { setting: { ...general, generalSetting: { ...general.generalSetting, theme } } });
    for (const route of ["/", "/calendar", "/archived", "/attachments", "/setting"]) {
      await page.goto(`https://echo-app.local${route}`);
      await page.waitForFunction((theme) => document.documentElement.dataset.theme === theme, theme);
      await page.waitForTimeout(600);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
      if (overflow) throw new Error(`Overflow in ${theme} ${route}`);
      await page.screenshot({ path: `${out}/${theme}-${route === "/" ? "home" : route.slice(1)}.png` });
    }
  }
  checks.push("light-dark-five-page-screenshots-no-overflow");
  await api(page, "UserService/UpdateUserSetting", { setting: general });
  await page.goto("https://echo-app.local/");
  await page.locator(".cm-content").first().waitFor();
  await page.locator("article").first().waitFor();
  await device.screenshot({ path: `${out}/system-bars.png` });
  await device.shell("uiautomator dump /sdcard/echo-ui-qa.xml");
  const hierarchy = (await device.shell("cat /sdcard/echo-ui-qa.xml")).toString();
  const bounds = await page.evaluate((xml) => new DOMParser().parseFromString(xml, "application/xml")
    .querySelector('node[class="android.webkit.WebView"]')?.getAttribute("bounds"), hierarchy);
  const nativeBounds = bounds?.match(/^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/);
  if (!nativeBounds || Number(nativeBounds[2]) <= 0) throw new Error("WebView did not reserve status bar space");
  const viewport = await page.evaluate(() => ({ width: innerWidth, height: innerHeight }));
  const editor = await page.locator(".cm-content").first().boundingBox();
  if (!editor) throw new Error("Editor geometry missing");
  const scale = (Number(nativeBounds[3]) - Number(nativeBounds[1])) / viewport.width;
  await device.shell(`input tap ${Math.round(Number(nativeBounds[1]) + (editor.x + 12) * scale)} ${Math.round(Number(nativeBounds[2]) + (editor.y + 12) * scale)}`);
  await page.waitForFunction((height) => innerHeight < height - 100, viewport.height);
  const ime = await page.evaluate(() => ({ height: innerHeight, editorBottom: document.querySelector(".cm-content")!.getBoundingClientRect().bottom,
    saveBottom: document.querySelector('button[aria-label="保存"]')!.getBoundingClientRect().bottom }));
  if (ime.editorBottom > ime.height || ime.saveBottom > ime.height) throw new Error("Keyboard overlaps editor controls");
  await device.screenshot({ path: `${out}/system-ime.png` });
  await device.shell("input keyevent 4");
  await page.waitForFunction((height) => innerHeight >= height - 1, viewport.height);
  checks.push("real-ime-resize-and-system-insets");
  await writeFile(`${out}/result.json`, JSON.stringify({ checks, startupRetries, errors, nativeBounds: bounds, viewport, ime }, null, 2));
  if (errors.length) throw new Error(errors.join("\n"));
  console.log(JSON.stringify({ checks, startupRetries: startupRetries.length, errors }));
} catch (error) {
  await writeFile(`${out}/result.json`, JSON.stringify({ checks, startupRetries, errors, failure: String(error) }, null, 2));
  throw error;
} finally {
  await device.shell("svc wifi enable");
  await device.shell("svc data enable");
  await device.close();
}
