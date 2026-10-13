import { strict as assert } from "node:assert";
import { chromium } from "../../../node_modules/@playwright/test/index.mjs";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const origin = "http://127.0.0.1:3016";
const out = resolve("apps/android/app/build/android-qa/local-ui/optimizations");
await mkdir(out, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe" });
const results: any[] = [];
try {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, locale: "zh-CN" });
  await context.route("**/*", (route) => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
  await context.addInitScript({ content: "globalThis.__name = (value) => value;" });
  await context.addInitScript(() => {
    const user = { name: "users/device", username: "device", displayName: "本机", role: "USER", state: "NORMAL" };
    const palette = ["😀", "😄", "🥰", "😎", "🤔", "😌", "😴", "😢", "😡", "😮", "🎉", "❤️", "👍", "👀", "✅", "💪"];
    const seed = Array.from({ length: 52 }, (_, index) => ({ name: `memos/fixture${index}`, creator: user.name,
      content: index === 0 ? "心情合成笔记" : index === 51 ? "# 完整列表来源\n\n- [ ] 分页外的任务\n- [x] 已完成的任务\n\n```md\n- [ ] 代码中的假任务\n```" : `合成记录 ${index}`,
      state: "NORMAL", visibility: "PRIVATE", createTime: "2026-10-13T00:00:00Z", updateTime: "2026-10-13T00:00:00Z",
      localRevision: 1, reactions: [] as any[], localMetadata: { remote: false, frozen: index === 0 } }));
    seed.push({ ...seed[0], name: "memos/r-history", content: "历史来源\n\n- [ ] 原账号待办", localMetadata: { remote: true, frozen: true } });
    seed.push({ ...seed[0], name: "memos/archived", content: "- [ ] 归档假任务", state: "ARCHIVED" });
    const memos = JSON.parse(localStorage.getItem("echo-optimization-fixtures") ?? "null") ?? seed;
    const persist = () => localStorage.setItem("echo-optimization-fixtures", JSON.stringify(memos));
    const calls: any[] = [];
    (window as any).__optimizationCalls = calls;
    const call = (command: string, input: any): any => {
      calls.push({ command, input });
      const method = command.split("/").pop();
      const setting = (name: string) => name.endsWith("MEMO_RELATED") ? { name, memoRelatedSetting: { reactions: palette } } : { name };
      if (command === "local.bootstrap") return { drafts: {} };
      if (command === "local.list") {
        if ((window as any).__failTasks) throw new Error("任务读取失败");
        const offset = input.offset ?? 0;
        return { memos: memos.slice(offset, offset + 50), next: offset + 50 < memos.length ? offset + 50 : -1 };
      }
      if (command === "local.draft" || command === "local.clear-draft" || command === "platform.theme") return {};
      if (command === "echo.status") return { enabled: false };
      if (command === "echo.memo-statuses") return memos.map((memo: any) => ({ memo: memo.name, state: "unknown" }));
      switch (method) {
        case "GetCurrentUser": return { user };
        case "GetUser": return user;
        case "GetInstanceProfile": return { version: "test", admin: user, accessMode: "PRIVATE" };
        case "GetInstanceSetting": return setting(input.name);
        case "BatchGetInstanceSettings": return { settings: input.names.map(setting) };
        case "ListUserSettings": return { settings: [{ name: "users/device/settings/GENERAL", generalSetting: { locale: "zh-Hans", theme: localStorage.getItem("echo-optimization-theme") ?? "default", memoVisibility: "PRIVATE" } }] };
        case "GetUserStats": return { name: user.name, memoCount: memos.length, memoDisplayTimestamps: [], tagCount: {} };
        case "ListAllUserStats": return { stats: [] };
        case "ListSpaces": return { spaces: [] };
        case "ListMemoViews": return { views: [] };
        case "ListUserNotifications": return { notifications: [] };
        case "ListMemoComments": return { memos: [] };
        case "ListMemoRelations": return { relations: [] };
        case "ListAttachments": return { attachments: [] };
        case "GetMemo": return memos.find((memo: any) => memo.name === input.name);
        case "UpsertMemoReaction": {
          if ((window as any).__failReaction) throw new Error("心情保存失败，请重试");
          const memo = memos.find((memo: any) => memo.name === input.name);
          if (memo.localMetadata.remote) throw new Error("原账号只读");
          const reaction = { name: `${memo.name}/reactions/test`, creator: user.name, reactionType: input.reaction.reactionType };
          memo.reactions = [reaction]; persist(); return reaction;
        }
        case "DeleteMemoReaction": {
          const memo = memos.find((memo: any) => input.name.startsWith(memo.name + "/reactions/"));
          memo.reactions = []; persist(); return {};
        }
        case "CreateMemo": {
          const memo = { ...seed[0], name: `memos/created${memos.length}`, content: input.memo.content, localMetadata: { remote: false, frozen: false } };
          memos.unshift(memo); persist(); return memo;
        }
        case "UpdateMemo": {
          const memo = memos.find((memo: any) => memo.name === input.memo.name);
          Object.assign(memo, input.memo); persist(); return memo;
        }
        default: throw new Error(`Unsupported synthetic command: ${command}`);
      }
    };
    document.addEventListener("DOMContentLoaded", () => {
      const channel = new MessageChannel();
      channel.port1.onmessage = (event) => {
        const request = JSON.parse(event.data);
        try { channel.port1.postMessage(JSON.stringify({ id: request.id, result: call(request.command, request.input) })); }
        catch (error) { channel.port1.postMessage(JSON.stringify({ id: request.id, error: (error as Error).message })); }
      };
      channel.port1.start(); window.postMessage("echo-local-port", location.origin, [channel.port2]);
    }, { once: true });
  });
  const page = await context.newPage();
  const errors: string[] = []; page.on("pageerror", (error) => errors.push(error.message));
  await page.goto(origin); await page.locator(".cm-content").waitFor();
  const card = page.locator("article").filter({ has: page.locator('[data-memo-name="memos/fixture0"]') });
  await card.getByRole("button", { name: "添加表态" }).click();
  const picker = page.locator("[data-echo-reaction-picker]");
  await picker.waitFor(); assert.equal(await picker.getByRole("button").count(), 16);
  await picker.getByRole("button", { name: "心情 😄", exact: true }).click();
  await card.getByRole("button", { name: "取消心情 😄", exact: true }).waitFor();
  await page.reload(); await card.getByRole("button", { name: "取消心情 😄", exact: true }).waitFor();
  await card.getByRole("button", { name: "取消心情 😄", exact: true }).click();
  await card.getByRole("button", { name: "取消心情 😄", exact: true }).waitFor({ state: "detached" });
  await page.evaluate(() => { (window as any).__failReaction = true; });
  await card.getByRole("button", { name: "添加表态" }).click();
  await picker.getByRole("button", { name: "心情 ❤️", exact: true }).click();
  await page.getByText(/心情保存失败，请重试/).waitFor(); assert.ok(await picker.isVisible());
  await page.evaluate(() => { (window as any).__failReaction = false; });
  await picker.getByRole("button", { name: "心情 ❤️", exact: true }).click();
  await card.getByRole("button", { name: "取消心情 ❤️", exact: true }).waitFor();
  results.push({ check: "reaction add/reopen/remove/failure retry", passed: true });

  const editor = page.locator(".cm-content");
  const longText = Array.from({ length: 100 }, (_, index) => `长文第${index}行，测试光标与滚动`).join("\n");
  await editor.fill(longText);
  const scroll = await page.locator(".cm-scroller").evaluate((node) => {
    const style = getComputedStyle(node); node.scrollTop = node.scrollHeight;
    return { scrollbar: style.scrollbarWidth, webkit: getComputedStyle(node, "::-webkit-scrollbar").display,
      overflow: style.overflowY, scrollTop: node.scrollTop, height: node.clientHeight, full: node.scrollHeight };
  });
  assert.equal(scroll.scrollbar, "none"); assert.equal(scroll.webkit, "none"); assert.ok(scroll.scrollTop > 0);
  await editor.press("Control+End"); await editor.press("Enter"); await editor.press("End"); await page.keyboard.insertText("光标末尾输入");
  assert.ok((await editor.innerText()).includes("光标末尾输入"));
  results.push({ check: "hidden scrollbar retains scrolling and caret editing", scroll });
  await editor.fill("");
  await page.reload(); await page.locator(".cm-content").waitFor();

  const openSidebar = async () => {
    try { await page.locator("[data-mobile-navigation-trigger]").click(); await page.locator("[data-echo-sidebar-tasks]").filter({ visible: true }).waitFor(); }
    catch (error) {
      console.log(JSON.stringify(await page.evaluate(() => ({ text: document.body.innerText,
        calls: (window as any).__optimizationCalls.slice(-20) }))));
      await page.screenshot({ path: `${out}/failure.png` }); throw error;
    }
  };
  await openSidebar();
  const tasks = page.locator("[data-echo-sidebar-tasks]").filter({ visible: true });
  await tasks.getByRole("link", { name: /分页外的任务/ }).waitFor();
  await tasks.getByRole("link", { name: /原账号待办/ }).waitFor();
  assert.equal(await tasks.getByRole("link", { name: /代码中的假任务|归档假任务/ }).count(), 0);
  assert.equal(await tasks.getByRole("link", { name: /已完成的任务/ }).count(), 0);
  await tasks.getByRole("button", { name: /已完成/ }).click();
  await tasks.getByRole("link", { name: /已完成的任务/ }).waitFor();
  assert.equal(await page.getByRole("button", { name: "任务", exact: true }).count(), 0);
  for (const theme of ["light", "dark"]) {
    await page.evaluate((theme) => { localStorage.setItem("echo-optimization-theme", theme === "dark" ? "default-dark" : "default"); }, theme);
    await page.reload(); await page.locator(".cm-content").waitFor(); await openSidebar();
    await tasks.getByRole("button", { name: /已完成/ }).click();
    await page.waitForFunction((theme) => document.documentElement.dataset.theme === theme, theme === "dark" ? "default-dark" : "default");
    for (const width of [320, 390, 430]) {
      await page.setViewportSize({ width, height: 844 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      const layout = await tasks.evaluate((node) => {
        const link = node.querySelector("a")!; const section = node.parentElement!;
        return { tasksY: node.getBoundingClientRect().y, calendarY: section.querySelector("section")!.getBoundingClientRect().y,
          taskWidth: node.scrollWidth, width: node.clientWidth, target: link.getBoundingClientRect().toJSON() };
      });
      assert.ok(layout.tasksY > layout.calendarY); assert.ok(layout.taskWidth <= layout.width); assert.ok(layout.target.height >= 44);
      await page.screenshot({ path: `${out}/sidebar-${theme}-${width}.png` });
      results.push({ theme, width, layout });
    }
  }
  await tasks.getByRole("link", { name: /分页外的任务/ }).click();
  await page.waitForURL("**/memos/fixture51");
  await page.locator('[data-memo-name="memos/fixture51"]').waitFor();
  await page.locator('[data-memo-name="memos/fixture51"] [role="checkbox"]').first().click();
  await page.goto(origin); await page.locator(".cm-content").waitFor(); await openSidebar();
  assert.equal(await tasks.getByRole("link", { name: /^待办：分页外的任务/ }).count(), 0);
  await tasks.getByRole("button", { name: /已完成/ }).click();
  await tasks.getByRole("link", { name: /^已完成：分页外的任务/ }).waitFor();
  await tasks.getByRole("link", { name: /原账号待办/ }).click();
  await page.waitForURL("**/memos/r-history");
  const remote = page.locator("article").filter({ has: page.locator('[data-memo-name="memos/r-history"]') });
  assert.equal(await remote.getByRole("button", { name: "添加表态" }).count(), 0);
  await page.goto(origin); await page.locator(".cm-content").waitFor();
  await page.evaluate(() => { (window as any).__failTasks = true; }); await openSidebar();
  await tasks.getByRole("alert").waitFor();
  await page.evaluate(() => { (window as any).__failTasks = false; });
  await tasks.getByRole("button", { name: "重试", exact: true }).click();
  await tasks.getByRole("link", { name: /原账号待办/ }).waitFor();
  results.push({ check: "cross-page tasks, completion, source navigation, remote read-only, error retry", passed: true });
  for (const theme of ["light", "dark"]) {
    await page.evaluate((theme) => { localStorage.setItem("echo-optimization-theme", theme === "dark" ? "default-dark" : "default"); }, theme);
    await page.goto(origin); await page.locator(".cm-content").waitFor();
    await card.locator('[data-slot="memo-header-actions"]').getByRole("button", { name: "添加表态" }).click(); await picker.waitFor();
    for (const width of [320, 390, 430]) {
      await page.setViewportSize({ width, height: 844 });
      await page.waitForFunction(() => {
        const buttons = [...document.querySelectorAll("[data-echo-reaction-picker] button")];
        return buttons.length === 16 && buttons.every((button) => {
          const box = button.getBoundingClientRect(); return box.width >= 43.99 && box.height >= 43.99;
        });
      });
      const targets = await picker.getByRole("button").evaluateAll((buttons) => buttons.map((button) => {
        const box = button.getBoundingClientRect(); return { width: box.width, height: box.height };
      }));
      assert.ok(targets.every((target) => target.width >= 43.99 && target.height >= 43.99), JSON.stringify({ theme, width, targets }));
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      await page.screenshot({ path: `${out}/reactions-${theme}-${width}.png` });
    }
    await page.keyboard.press("Escape");
  }
  assert.deepEqual(errors, []);
  await writeFile(`${out}/result.json`, JSON.stringify({ results, errors }, null, 2));
  console.log(JSON.stringify({ result: "passed", checks: results.length, errors }));
} finally { await browser.close(); }
