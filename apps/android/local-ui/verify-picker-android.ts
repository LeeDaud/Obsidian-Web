import { _android } from "../../../node_modules/@playwright/test/index.mjs";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
const device = (await _android.devices()).find((item) => item.serial() === "emulator-5558");
if (!device) throw new Error("Only use isolated emulator");
const out = resolve("apps/android/app/build/android-qa/local-ui/picker");
await mkdir(out, { recursive: true });
try {
  await device.shell("am force-stop xyz.leedaud.echo");
  await device.shell("am start -n xyz.leedaud.echo/.NativeActivity --ez local-ui-preview true");
  const page = await (await device.webView({ pkg: "xyz.leedaud.echo" })).page();
  const errors: string[] = []; page.on("pageerror", (error) => errors.push(error.message));
  await page.locator(".cm-content").first().waitFor();
  const marker = `E1-QA 文件选择 ${Date.now()}`;
  await page.locator(".cm-content").first().fill(marker);
  const open = async () => {
    await page.getByRole("button", { name: "增加", exact: true }).click();
    await page.getByRole("menuitem", { name: "添加附件", exact: true }).click();
    for (let i = 0; i < 40; i++) {
      const state = (await device.shell("dumpsys activity activities")).toString();
      if (state.split("\n").some((line) => /topResumedActivity|mResumedActivity/.test(line) && line.includes("documentsui"))) return;
      await page.waitForTimeout(250);
    }
    throw new Error("Picker did not open through main-frame bridge");
  };
  const tap = async (label: string) => {
    await device.shell("uiautomator dump /sdcard/echo-picker-qa.xml");
    const xml = (await device.shell("cat /sdcard/echo-picker-qa.xml")).toString();
    const bounds = await page.evaluate(({ xml, label }) => {
      const document = new DOMParser().parseFromString(xml, "application/xml");
      return [...document.querySelectorAll("node")].find((node) => node.getAttribute("text") === label || node.getAttribute("content-desc") === label)?.getAttribute("bounds");
    }, { xml, label });
    const match = bounds?.match(/^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/);
    if (!match) throw new Error(`Native item missing: ${label}`);
    await device.shell(`input tap ${Math.floor((Number(match[1]) + Number(match[3])) / 2)} ${Math.floor((Number(match[2]) + Number(match[4])) / 2)}`);
  };
  await open(); await device.shell("input keyevent 4");
  await page.locator(".cm-content").first().waitFor();
  if (!(await page.locator(".cm-content").first().innerText()).includes(marker)) throw new Error("Cancel changed draft");
  await open();
  await tap("Show roots"); await tap("Downloads"); await tap("echo-picker-qa.txt");
  await page.getByText("echo-picker-qa.txt", { exact: true }).first().waitFor({ timeout: 30000 });
  await page.getByRole("button", { name: "暂存到当前设备", exact: true }).click();
  await page.getByText("已暂存到当前设备", { exact: true }).waitFor();
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await page.locator("article").filter({ hasText: marker }).first().waitFor();
  const memo = await page.evaluate(async (marker) => {
    const response = await fetch("/memos.api.v1.MemoService/ListMemos", { method: "POST", body: JSON.stringify({ filter: `content.contains("${marker}")` }) });
    return (await response.json()).memos[0];
  }, marker);
  if (memo.attachments.length !== 1 || memo.content !== marker) throw new Error("Original attachment save failed");
  const file = memo.attachments[0];
  const text = await page.evaluate(async (file) => (await fetch(`/file/${file.name}/${encodeURIComponent(file.filename)}`)).text(), file);
  const expected = await readFile("apps/android/app/build/android-qa/picker-fixture.txt", "utf8");
  if (text !== expected || errors.length) throw new Error(`Picker content/error mismatch: ${JSON.stringify(errors)}`);
  await page.evaluate(() => {
    // Bypass only the app's document-level bridge handler to exercise the generic WebChrome callback.
    window.addEventListener("click", (event) => {
      if (event.target instanceof HTMLInputElement && event.target.type === "file") event.stopImmediatePropagation();
    }, true);
  });
  await page.getByRole("button", { name: "增加", exact: true }).click();
  await page.getByRole("menuitem", { name: "添加附件", exact: true }).click();
  await page.waitForTimeout(700);
  const activity = (await device.shell("dumpsys activity activities")).toString();
  if (activity.split("\n").some((line) => /topResumedActivity|mResumedActivity/.test(line) && line.includes("documentsui"))) throw new Error("Generic WebChrome picker was allowed");
  await page.screenshot({ path: `${out}/saved.png` });
  await writeFile(`${out}/result.json`, JSON.stringify({ marker, size: file.size, errors, canceled: true, nativeSelection: true, genericPickerRejected: true }, null, 2));
  console.log("PASS main-frame picker cancel/selection -> original attachment stash/save -> exact bytes");
} catch (error) {
  await writeFile(`${out}/result.json`, JSON.stringify({ failure: String(error) }, null, 2)); throw error;
} finally { await device.close(); }
