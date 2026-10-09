import { _android } from "../../../node_modules/@playwright/test/index.mjs";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
const device = (await _android.devices()).find((value) => value.serial() === "emulator-5558");
if (!device) throw new Error("Only use the isolated API35 emulator");
try {
  const pkg = process.env.ECHO_QA_PACKAGE ?? "xyz.leedaud.echo.qa";
  if (!["xyz.leedaud.echo", "xyz.leedaud.echo.qa"].includes(pkg)) throw new Error("Unexpected package");
  const page = await (await device.webView({ pkg })).page();
  if (!page.url().startsWith("https://echo-app.local/")) throw new Error("Not the APK local page");
  const out = resolve("apps/android/app/build/android-qa/local-ui/android35"); await mkdir(out, { recursive: true });
  const errors: string[] = []; page.on("pageerror", (error) => errors.push(error.message));
  await page.reload();
  await page.locator(".cm-content").waitFor({ timeout: 30000 });
  const title = `W3-QA 原组件真机核验 ${Date.now()}`;
  const body = `${title}\n\n- [ ] 任务一\n- [x] 任务二\n\n#迁移`;
  await page.locator(".cm-content").fill(body);
  await page.getByRole("button", { name: "暂存到当前设备", exact: true }).click();
  await page.waitForTimeout(700);
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await page.locator("[data-memo-content]").filter({ hasText: title }).waitFor({ timeout: 15000 });
  const memo = page.locator("article").filter({ hasText: title }).first();
  await memo.locator("[role=checkbox]").first().click();
  await page.waitForTimeout(500);
  await memo.locator("[data-memo-content]").dblclick();
  const editing = page.locator('[data-echo-editor-key*="inline-memo-editor"] .cm-content').first();
  await editing.waitFor();
  if (!(await editing.innerText()).includes("W3-QA")) throw new Error("Unpublished double tap did not restore the source");
  await page.screenshot({ path: `${out}/editing.png` });
  await page.reload();
  const editor = page.locator('.cm-content').first();
  const formatTitle = `W3-QA 格式 ${Date.now()}`;
  const formatted = formatTitle + "\n\n| 项目 | 值 |\n| --- | --- |\n| 表格 | 保留 |\n\n公式 $x^2$\n\n```javascript\nconst value = 42;\n```\n\n```mermaid\ngraph TD\n A[开始] --> B[结束]\n```\n\n脚注[^1]\n\n[^1]: 注释";
  await editor.fill(formatted); await page.getByRole('button', { name: '保存', exact: true }).click();
  const format = page.locator('[data-memo-content]').filter({ hasText: formatTitle }).first();
  await format.locator('table').waitFor();
  await format.locator('.katex').waitFor();
  await format.locator('pre').first().waitFor();
  await format.locator('svg').first().waitFor({ timeout: 30000 });
  await page.screenshot({ path: `${out}/formats.png` });
  const attachmentTitle = `W3-QA 附件范围 ${Date.now()}`;
  await page.locator('.cm-content').first().fill(attachmentTitle);
  await page.locator('input[type="file"][accept=""]').first().setInputFiles({
    name: "range-fixture.txt", mimeType: "text/plain", buffer: Buffer.from("0123456789"),
  });
  await page.getByText("range-fixture.txt", { exact: true }).first().waitFor();
  await page.getByRole('button', { name: '暂存到当前设备', exact: true }).click();
  await page.getByRole('button', { name: '保存', exact: true }).click();
  const attachment = page.locator('article').filter({ hasText: attachmentTitle }).first();
  const url = await attachment.locator('a[href*="/file/attachments/"]').first().getAttribute('href');
  if (!url) throw new Error("Original attachment link missing");
  const ranges = await page.evaluate(async (url) => {
    const results = [];
    for (const [request, expected] of [["bytes=2-4", "234"], ["bytes=-3", "789"], ["bytes=8-", "89"], ["bytes=0-0", "0"], ["bytes=0-99", "0123456789"]]) {
      const response = await fetch(url, { headers: { Range: request } });
      results.push({ request, expected, status: response.status, contentRange: response.headers.get("Content-Range"), body: await response.text() });
    }
    return results;
  }, url);
  if (ranges.some((range) => range.status !== 206 || range.body !== range.expected) || ranges[0].contentRange !== "bytes 2-4/10") throw new Error(`Invalid native attachment ranges: ${JSON.stringify(ranges)}`);
  await writeFile(`${out}/result.json`, JSON.stringify({ pkg, url: page.url(), ranges, errors }, null, 2));
  if (errors.length) throw new Error(errors.join("\n"));
  console.log("Real Android WebView local editor/save/task/edit/attachment range passed");
} finally { await device.close(); }
