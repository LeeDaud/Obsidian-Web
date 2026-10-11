import { chromium } from "../../../node_modules/@playwright/test/index.mjs";
import { mkdir, writeFile, readFile } from "node:fs/promises";
import { resolve } from "node:path";

const origin = "http://127.0.0.1:3016";
const out = resolve("apps/android/app/build/android-qa/local-ui");
await mkdir(out, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe" });
try {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, locale: "zh-CN", timezoneId: "Asia/Shanghai" });
  const blocked: string[] = [];
  await context.addInitScript({ content: "globalThis.__name = (value) => value;" });
  await context.route("**/*", (route) => {
    if (new URL(route.request().url()).origin !== origin) { blocked.push(route.request().url()); return route.abort(); }
    return route.continue();
  });
  const image = await readFile("upstream/memos/web/public/logo.webp");
  await context.route("**/file/attachments/r-*/**", (route) => route.fulfill({ status: 200, contentType: "image/webp", body: image }));
  await context.addInitScript(() => {
    const calls: { command: string; input: any }[] = [];
    const user = { name: "users/device", username: "device", displayName: "本机", role: "USER", state: "NORMAL" };
    const time = "2026-10-09T00:00:00Z";
    const make = (name: string, content: string) => ({ name, content, creator: user.name, createTime: time, updateTime: time, state: "NORMAL", visibility: "PRIVATE", localRevision: 1 });
    const memos = [make("memos/fixture", "原文短记录"), make("memos/format", "## 原组件格式\n\n| 名称 | 值 |\n| --- | --- |\n| 表格 | 保留 |\n\n公式：$x^2$\n\n脚注[^1]\n\n[^1]: 原文脚注")];
    const historyAttachment = `r-${"a".repeat(64)}`;
    memos.push({ ...make("memos/r-history", '![历史图片](https://memos.example.test/file/attachments/source_1?download=1#view "原题")\n\n`/file/attachments/source_1`'),
      attachments: [{ name: `attachments/${historyAttachment}`, filename: "历史图片.webp", type: "image/webp", size: "37158" }],
      localMetadata: { remote: true, frozen: true, sourceOrigin: "https://memos.example.test", attachmentMappings: [{ name: "attachments/source_1", filename: "历史图片.webp", replacement: `/file/attachments/${historyAttachment}` }] }
    } as any);
    const call = (command: string, input: any): any => {
      calls.push({ command, input });
      const method = command.split('/').pop();
      if (command === "local.bootstrap") return { drafts: {} };
      if (command === "local.list") return { memos, next: -1 };
      if (command === "local.memos-copy-source") return { content: memos.find((memo) => memo.name === "memos/r-history")!.content,
        version: "synthetic-version", origin: "https://memos.example.test", attachments: [{ name: "attachments/source_1", filename: "历史图片.webp" }] };
      if (command === "local.memos-copy") {
        if (input.version !== "synthetic-version" || input.destinations.length !== 1 || input.destinations[0].name !== "attachments/source_1") throw new Error("Copy parser protocol invalid");
        return { key: "users/device-home-memo-editor", value: JSON.stringify({ kind: "memos.editor-cache", version: 4,
          content: '![历史图片](/file/attachments/copied-image "原题")\n\n`/file/attachments/source_1`', attachments: [], location: null }) };
      }
      if (command === "local.import-source" && input.uploadId === "synthetic-cancel") (window as any).__importAbort.abort();
      if (command === "local.import-source") return input.index === 0 ? { uid: "imported", content: "![图](/file/attachments/import-image?x=1)\n\n`/file/attachments/import-image`",
        origin: location.origin, sha256: "synthetic-hash", attachments: [{ name: "attachments/import-image", filename: "image.webp" }] } : { done: true };
      if (command === "local.import-destinations") {
        if (input.sha256 !== "synthetic-hash" || input.destinations.length !== 1 || input.destinations[0].name !== "attachments/import-image") throw new Error("Import parser protocol invalid");
        return { saved: true };
      }
      if (command === "memos.api.v1.UserService/ImportMemos") {
        if (!input.parsedDestinations) throw new Error("Import bypassed parsed destinations");
        return { report: { created: 1, updated: 0, skipped: 0, failed: 0, failures: [] } };
      }
      if (command === "platform.theme") return {};
      if (command === "local.draft" && (window as any).__failLocalDraft) throw new Error("Synthetic draft failure");
      if (command === "local.draft" || command === "local.clear-draft") return { saved: true };
      if (command === "echo.status") return { enabled: true };
      if (command === "echo.memo-statuses") return memos.map((memo) => ({ memo: memo.name, state: "unknown" }));
      switch (method) {
        case "GetCurrentUser": return { user };
        case "GetUser": return user;
        case "GetInstanceProfile": return { version: "local-test", admin: user, accessMode: "PRIVATE" };
        case "GetInstanceSetting": return { name: input.name };
        case "BatchGetInstanceSettings": return { settings: [] };
        case "ListUserSettings": return { settings: [{ name: "users/device/settings/GENERAL", generalSetting: { locale: "zh-Hans", theme: "default", memoVisibility: "PRIVATE" } }] };
        case "ListMemos": return { memos };
        case "GetMemo": return memos.find((memo) => memo.name === input.name);
        case "GetUserStats": return { name: user.name, memoCount: memos.length, memoDisplayTimestamps: [time], tagCount: {} };
        case "ListAllUserStats": return { stats: [] };
        case "ListSpaces": return { spaces: [] };
        case "ListUserMemoViews": case "ListMemoViews": return { views: [] };
        case "ListUserNotifications": return { notifications: [] };
        case "ListMemoComments": return { memos: [] };
        case "ListMemoRelations": return { relations: [] };
        case "ListAttachments": return { attachments: [] };
        case "CreateMemo": { const memo = make(`memos/new${memos.length}`, input.memo.content); memos.unshift(memo); return memo; }
        default: throw new Error("Unsupported synthetic operation");
      }
    };
    (window as any).__localCalls = calls;
    document.addEventListener("DOMContentLoaded", () => {
      const channel = new MessageChannel();
      channel.port1.onmessage = (event) => {
        const request = JSON.parse(event.data);
        try { channel.port1.postMessage(JSON.stringify({ id: request.id, result: call(request.command, request.input) })); }
        catch { channel.port1.postMessage(JSON.stringify({ id: request.id, error: "synthetic unsupported" })); }
      };
      channel.port1.start();
      window.postMessage("echo-local-port", location.origin, [channel.port2]);
    }, { once: true });
  });
  const page = await context.newPage();
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const consoleErrors: string[] = [];
  page.on("console", (message) => { if (message.type() === "error") consoleErrors.push(message.text()); });
  await page.goto(origin);
  try { await page.locator(".cm-content").waitFor({ timeout: 15000 }); }
  catch (error) {
    const diagnostic = await page.evaluate(() => ({ calls: (window as any).__localCalls, text: document.body.innerText, ready: document.readyState }));
    console.log(JSON.stringify({ errors, consoleErrors, diagnostic }));
    await page.screenshot({ path: `${out}/initialization-failure.png` });
    throw error;
  }
  await page.locator('[data-memo-name="memos/fixture"]').waitFor();
  await page.locator('[data-memo-name="memos/format"] .katex').waitFor();
  const historicalImage = page.locator('[data-memo-name="memos/r-history"] img[src*="/file/attachments/r-"]');
  try { await historicalImage.waitFor(); }
  catch (error) {
    console.log(JSON.stringify(await page.evaluate(async () => ({ text: document.body.innerText, html: document.querySelector('[data-memo-name="memos/r-history"]')?.innerHTML, images: [...document.images].map((image) => image.src), memos: await (await fetch("/memos.api.v1.MemoService/ListMemos", { method: "POST", body: "{}" })).json() }))));
    throw error;
  }
  await page.waitForFunction(() => document.querySelector<HTMLImageElement>('[data-memo-name="memos/r-history"] img')?.naturalWidth);
  if (await page.locator('article').filter({ has: historicalImage }).locator('img[src*="/file/attachments/r-"]').count() !== 1) throw new Error("Historical inline image duplicated in attachment gallery");
  if (!(await page.locator('[data-memo-name="memos/r-history"] code').innerText()).includes("/file/attachments/source_1")) throw new Error("Historical code text changed");
  const measurements = [];
  for (const width of [320, 390, 430]) {
    await page.setViewportSize({ width, height: 844 });
    const result = await page.evaluate(() => {
      const body = document.querySelector('[data-memo-name="memos/fixture"]')!;
      return { width: innerWidth, body: body.getBoundingClientRect().toJSON(), card: body.closest("article")!.getBoundingClientRect().toJSON(), overflow: document.documentElement.scrollWidth > innerWidth };
    });
    if (result.overflow || result.body.height !== 24 || result.card.height !== 102) throw new Error("Original short-card metrics changed");
    measurements.push(result);
    await page.screenshot({ path: `${out}/original-local-home-${width}.png`, fullPage: true });
  }
  await page.locator('[data-memo-name="memos/format"] table').waitFor();
  await page.locator('[data-memo-name="memos/format"] .katex').waitFor();
  await page.locator('[data-memo-name="memos/format"] [data-footnote-ref]').waitFor();
  await page.locator(".cm-content").fill("合成原组件正文");
  await page.waitForTimeout(1000);
  const before = await page.evaluate(() => (window as any).__localCalls.filter((call: any) => call.command.endsWith("/CreateMemo")).length);
  if (before !== 0) throw new Error("Typing unexpectedly saved a note");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await page.locator('[data-memo-content]').filter({ hasText: "合成原组件正文" }).waitFor();
  const commands = await page.evaluate(() => (window as any).__localCalls.map((call: any) => call.command));
  if (!commands.includes("local.draft") || !commands.includes("local.clear-draft")) throw new Error("Native draft lifecycle did not run");
  await page.evaluate(() => { (window as any).__failLocalDraft = true; });
  await page.locator(".cm-content").fill("合成暂存故障，原文保留");
  await page.getByRole("button", { name: "暂存到当前设备", exact: true }).click();
  await page.getByText("synthetic unsupported", { exact: true }).waitFor();
  if (!(await page.locator(".cm-content").innerText()).includes("合成暂存故障，原文保留")) throw new Error("Failed stash cleared editor");
  await page.evaluate(() => { (window as any).__failLocalDraft = false; });
  await page.getByRole("button", { name: "暂存到当前设备", exact: true }).click();
  await page.getByText("已暂存到当前设备", { exact: true }).waitFor();
  await page.locator('input[type="file"][accept=""]').setInputFiles("upstream/memos/web/public/logo.webp");
  const attachmentMenu = page.getByRole("button", { name: "附件操作", exact: true });
  await attachmentMenu.waitFor();
  for (const width of [320, 390, 430]) {
    await page.setViewportSize({ width, height: 844 });
    await attachmentMenu.click();
    await page.getByRole("menuitem", { name: "分享", exact: true }).waitFor();
    await page.getByRole("menuitem", { name: "移除", exact: true }).waitFor();
    if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error("Attachment menu overflow");
    await page.screenshot({ path: `${out}/attachment-menu-${width}.png`, fullPage: true });
    await page.keyboard.press("Escape");
  }
  await attachmentMenu.click();
  await page.getByRole("menuitem", { name: "移除", exact: true }).click();
  await attachmentMenu.waitFor({ state: "detached" });
  const historyCard = page.locator("article").filter({ has: page.locator('[data-memo-name="memos/r-history"]') });
  await historyCard.getByRole("button", { name: "更多", exact: true }).click();
  await page.getByRole("menuitem", { name: "另存设备草稿", exact: true }).click();
  await page.waitForFunction(() => document.querySelector(".cm-content")?.textContent?.includes("/file/attachments/copied-image"));
  if (!(await page.locator(".cm-content").innerText()).includes("`/file/attachments/source_1`")) throw new Error("Copy modified code address");
  const importReport = await page.evaluate(async () => {
    const response = await fetch("/memos.api.v1.UserService/ImportMemos", { method: "POST", body: JSON.stringify({ uploadId: "synthetic-import", writeOffset: "0", finishWrite: true, conflictPolicy: "DUPLICATE" }) });
    return response.json();
  });
  if (importReport.report?.created !== 1) throw new Error("Parsed import integration failed");
  const cancelled = await page.evaluate(async () => {
    const controller = new AbortController(); (window as any).__importAbort = controller;
    const before = (window as any).__localCalls.length;
    await fetch("/memos.api.v1.UserService/ImportMemos", { method: "POST", signal: controller.signal,
      body: JSON.stringify({ uploadId: "synthetic-cancel", writeOffset: "0", finishWrite: true, conflictPolicy: "DUPLICATE" }) });
    return (window as any).__localCalls.slice(before).map((call: any) => call.command);
  });
  if (cancelled.includes("local.import-destinations") || cancelled.includes("memos.api.v1.UserService/ImportMemos")) throw new Error("Cancelled parsing still committed import");
  if (errors.length) throw new Error(`Page errors: ${errors.join(", ")}`);
  await writeFile(`${out}/browser-verification.json`, JSON.stringify({ measurements, errors, blocked, commands }, null, 2));
  console.log(JSON.stringify({ result: "passed", measurements, errors, blocked: blocked.length, commands }));
} finally { await browser.close(); }
