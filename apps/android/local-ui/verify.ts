import { chromium } from "../../../node_modules/@playwright/test/index.mjs";
import { mkdir, writeFile } from "node:fs/promises";
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
  await context.addInitScript(() => {
    const calls: { command: string; input: any }[] = [];
    const user = { name: "users/device", username: "device", displayName: "本机", role: "USER", state: "NORMAL" };
    const time = "2026-10-09T00:00:00Z";
    const make = (name: string, content: string) => ({ name, content, creator: user.name, createTime: time, updateTime: time, state: "NORMAL", visibility: "PRIVATE", localRevision: 1 });
    const memos = [make("memos/fixture", "原文短记录"), make("memos/format", "## 原组件格式\n\n| 名称 | 值 |\n| --- | --- |\n| 表格 | 保留 |\n\n公式：$x^2$\n\n脚注[^1]\n\n[^1]: 原文脚注")];
    const call = (command: string, input: any): any => {
      calls.push({ command, input });
      const method = command.split('/').pop();
      if (command === "local.bootstrap") return { drafts: {} };
      if (command === "local.list") return { memos, next: -1 };
      if (command === "platform.theme") return {};
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
  if (errors.length) throw new Error(`Page errors: ${errors.join(", ")}`);
  await writeFile(`${out}/browser-verification.json`, JSON.stringify({ measurements, errors, blocked, commands }, null, 2));
  console.log(JSON.stringify({ result: "passed", measurements, errors, blocked: blocked.length, commands }));
} finally { await browser.close(); }
