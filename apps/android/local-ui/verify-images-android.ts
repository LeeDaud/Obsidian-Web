import { _android } from "../../../node_modules/@playwright/test/index.mjs";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
const device = (await _android.devices()).find((item) => item.serial() === "emulator-5558");
if (!device) throw new Error("Only use isolated emulator");
const out = resolve("apps/android/app/build/android-qa/local-ui/images"); await mkdir(out, { recursive: true });
try {
  await device.shell("am force-stop com.google.android.documentsui");
  await device.shell("am force-stop xyz.leedaud.echo");
  await device.shell("am start -n xyz.leedaud.echo/.NativeActivity --ez local-ui-preview true");
  const page = await (await device.webView({ pkg: "xyz.leedaud.echo" })).page();
  const errors: string[] = []; page.on("pageerror", (error) => errors.push(error.message));
  await page.locator(".cm-content").first().waitFor();
  const tap = async (label: string) => {
    await device.shell("uiautomator dump /sdcard/echo-images-qa.xml");
    const xml = (await device.shell("cat /sdcard/echo-images-qa.xml")).toString();
    const bounds = await page.evaluate(({ xml, label }) => [...new DOMParser().parseFromString(xml, "application/xml").querySelectorAll("node")]
      .filter((node) => node.getAttribute("text") === label || node.getAttribute("content-desc") === label || node.getAttribute("content-desc")?.startsWith(label + ","))
      .at(-1)?.getAttribute("bounds"), { xml, label });
    const match = bounds?.match(/^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/);
    if (!match) throw new Error(`Native item missing: ${label}`);
    await device.shell(`input tap ${Math.floor((Number(match[1]) + Number(match[3])) / 2)} ${Math.floor((Number(match[2]) + Number(match[4])) / 2)}`);
    await page.waitForTimeout(400);
  };
  const results = [];
  for (const inline of [true, false]) {
    const marker = `E1-QA ${inline ? "正文图片" : "图片附件"} ${Date.now()}`;
    await page.locator(".cm-content").first().fill(marker + "\n\n");
    await page.getByRole("button", { name: "增加", exact: true }).click();
    await page.getByRole("menuitem", { name: inline ? "插入图片" : "添加附件", exact: true }).click();
    await page.waitForTimeout(500);
    await tap("Show roots"); await tap("Downloads"); await tap("echo-image-qa.webp");
    if (inline) await page.waitForFunction(() => [...document.querySelectorAll(".cm-content .cm-line")].map((node) => node.textContent).join("\n").includes("/file/attachments/"));
    else await page.getByText("echo-image-qa.webp", { exact: true }).first().waitFor();
    await page.getByRole("button", { name: "暂存到当前设备", exact: true }).click();
    await page.getByText("已暂存到当前设备", { exact: true }).waitFor();
    await page.getByRole("button", { name: "保存", exact: true }).click();
    const card = page.locator("article").filter({ hasText: marker }).first(); await card.waitFor();
    await card.locator('img[src*="/file/attachments/"]').first().waitFor();
    await page.waitForFunction((marker) => [...document.querySelectorAll("article")].find((node) => node.textContent?.includes(marker))
      ?.querySelector<HTMLImageElement>('img[src*="/file/attachments/"]')?.naturalWidth, marker);
    if (await card.locator('img[src*="/file/attachments/"]').count() !== 1) throw new Error("Inline/attachment gallery duplicate");
    const memo = await page.evaluate(async (marker) => {
      const response = await fetch("/memos.api.v1.MemoService/ListMemos", { method: "POST", body: JSON.stringify({ filter: `content.contains("${marker}")` }) });
      return (await response.json()).memos[0];
    }, marker);
    if (memo.attachments.length !== 1 || (inline && !memo.content.includes(`/file/${memo.attachments[0].name}`))) throw new Error("Image metadata/reference missing");
    const bytes = await page.evaluate(async (file) => Array.from(new Uint8Array(await (await fetch(`/file/${file.name}`)).arrayBuffer())), memo.attachments[0]);
    const expected = await readFile("upstream/memos/web/public/logo.webp");
    if (!Buffer.from(bytes).equals(expected)) throw new Error("Image bytes changed");
    await page.reload(); await page.locator(".cm-content").first().waitFor();
    const reopened = page.locator("article").filter({ hasText: marker }).first();
    await reopened.locator('img[src*="/file/attachments/"]').first().waitFor();
    await page.screenshot({ path: `${out}/${inline ? "inline" : "attachment"}.png` });
    if (!inline) {
      await reopened.locator('img[src*="/file/attachments/"]').first().click();
      await page.getByRole("dialog").waitFor();
      await page.waitForFunction(() => [...document.querySelectorAll<HTMLImageElement>('[role="dialog"] img')].some((image) => image.naturalWidth > 0));
      await page.screenshot({ path: `${out}/gallery-preview.png` });
      await page.getByRole("button", { name: "Close preview", exact: true }).click();
    }
    results.push({ inline, name: memo.name, size: memo.attachments[0].size, images: await reopened.locator('img[src*="/file/attachments/"]').count() });
  }
  if (errors.length) throw new Error(errors.join("\n"));
  await writeFile(`${out}/result.json`, JSON.stringify({ results, errors }, null, 2));
  console.log("PASS native inline image and image attachment -> stash/save/reopen/exact bytes/no duplicate");
} catch (error) { await writeFile(`${out}/result.json`, JSON.stringify({ failure: String(error) }, null, 2)); throw error; }
finally { await device.close(); }
