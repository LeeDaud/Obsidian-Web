import { chromium } from "../../node_modules/@playwright/test/index.mjs";
import { fromBinary, fromJson, toBinary } from "../../upstream/memos/web/node_modules/@bufbuild/protobuf/dist/esm/index.js";
import { AuthService } from "../../upstream/memos/web/src/types/proto/api/v1/auth_service_pb";
import { InstanceService } from "../../upstream/memos/web/src/types/proto/api/v1/instance_service_pb";
import { MemoService } from "../../upstream/memos/web/src/types/proto/api/v1/memo_service_pb";
import { UserService } from "../../upstream/memos/web/src/types/proto/api/v1/user_service_pb";
import { SpaceService } from "../../upstream/memos/web/src/types/proto/api/v1/space_service_pb";
import { AttachmentService } from "../../upstream/memos/web/src/types/proto/api/v1/attachment_service_pb";
import { mkdir, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { resolve } from "node:path";

// Only a localhost SPA and synthetic Connect responses. No production requests.
const origin = process.env.MEMOS_REFERENCE_ORIGIN ?? "http://127.0.0.1:3015";
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(origin)) throw new Error("Reference server must be local");
const out = resolve("apps/android/app/build/android-qa/ui-reference");
await mkdir(out, { recursive: true });
const user = { name: "users/qa", username: "qa", displayName: "QA", role: "USER", state: "NORMAL" };
const time = "2026-10-08T02:00:00Z";
const memos = [
  { name: "memos/saved", content: "今天的想法\n\n记录一个清晰的念头。 #记录", pinned: false },
  { name: "memos/pending", content: "- [ ] 整理今天的笔记\n- [x] 留下一个想法", pinned: false },
  { name: "memos/verified", content: "**一条已经投递的记录**\n\n内容仍保留在本机。", pinned: false },
].map((m) => ({ ...m, creator: user.name, createTime: time, updateTime: time, state: "NORMAL", visibility: "PRIVATE" }));
const methods = new Map([AuthService, InstanceService, MemoService, UserService, SpaceService, AttachmentService]
  .flatMap((s) => s.methods.map((m) => [`/${s.typeName}/${m.name}`, m] as const)));
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
try {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, locale: "zh-CN", timezoneId: "Asia/Shanghai" });
  await context.addInitScript(() => {
    localStorage.setItem("memos_access_token", "synthetic-local-reference");
    localStorage.setItem("memos_token_expires_at", "2099-01-01T00:00:00.000Z");
  });
  const requests: string[] = [];
  await context.route("**/*", async (route) => {
    const url = new URL(route.request().url());
    if (url.origin !== origin) return route.abort();
    const method = methods.get(url.pathname);
    if (method) {
      const input = fromBinary(method.input, route.request().postDataBuffer() ?? new Uint8Array()) as any;
      let data: any = {};
      requests.push(method.name);
      switch (method.name) {
        case "GetCurrentUser": data = { user }; break;
        case "GetUser": data = user; break;
        case "GetInstanceProfile": data = { version: "qa", admin: user, accessMode: "PRIVATE", instanceUrl: origin }; break;
        case "GetInstanceSetting": data = { name: input.name, accessSetting: { accessMode: "PRIVATE" } }; break;
        case "BatchGetInstanceSettings": data = { settings: [
          { name: "instance/settings/GENERAL", generalSetting: { weekStartDayOffset: 0 } },
          { name: "instance/settings/MEMO_RELATED", memoRelatedSetting: {} },
        ] }; break;
        case "ListUserSettings": data = { settings: [{ name: "users/qa/settings/GENERAL", generalSetting: { locale: "zh-Hans", theme: "default", memoVisibility: "PRIVATE" } }] }; break;
        case "ListMemos": data = { memos: String(input.filter).includes("ARCHIVED") ? [] : memos }; break;
        case "GetMemo": data = memos.find((m) => m.name === input.name) ?? memos[0]; break;
        case "GetUserStats": data = { name: user.name, memoDisplayTimestamps: [time], tagCount: { "记录": 1 }, memoCount: 3 }; break;
        case "ListAllUserStats": data = { stats: [] }; break;
        case "ListSpaces": data = { spaces: [] }; break;
        case "ListMemoComments": data = { memos: [] }; break;
        case "ListMemoRelations": data = { relations: [] }; break;
        case "ListUserMemoViews": data = { views: [] }; break;
        case "ListUserNotifications": data = { notifications: [] }; break;
        case "ListAttachments": data = { attachments: [] }; break;
        default:
          if (/Create|Update|Delete|Sign|Refresh/.test(method.name)) throw new Error(`Unexpected mutation: ${method.name}`);
      }
      const body = toBinary(method.output, fromJson(method.output, data, { ignoreUnknownFields: true }));
      return route.fulfill({ status: 200, contentType: "application/proto", body: Buffer.from(body) });
    }
    if (url.pathname === "/api/echo/v1/status") return route.fulfill({ json: { enabled: true } });
    if (url.pathname === "/api/echo/v1/memo-statuses") return route.fulfill({ json: memos.map((m, i) => ({
      memo: m.name, state: i === 0 ? "unknown" : i === 1 ? "pending" : "verified",
      revision: Date.parse(time) / 1000, sourceHash: createHash("sha256").update(m.content).digest("hex"), attachmentNames: [],
      ...(i === 2 ? { path: "00_Inbox/20261008-100000.md", commit: "a".repeat(40) } : {}),
    })) });
    if (url.pathname.startsWith("/api/")) return route.fulfill({ status: 200, json: {} });
    return route.continue();
  });
  const page = await context.newPage();
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto(origin, { waitUntil: "networkidle" });
  await page.locator(".cm-content").waitFor({ timeout: 60000 });
  await page.locator('[data-slot="memo-delivery-status"]').first().waitFor();
  await page.locator(".cm-content").evaluate((element: HTMLElement) => element.blur());
  const measurements: any[] = [];
  for (const width of [320, 390, 430]) {
    await page.setViewportSize({ width, height: 844 });
    await page.screenshot({ path: `${out}/web-home-${width}.png`, fullPage: true });
    measurements.push(await page.evaluate(() => ({ width: innerWidth, overflow: document.documentElement.scrollWidth > innerWidth,
      header: document.querySelector("header")?.getBoundingClientRect().toJSON(),
      editor: document.querySelector(".cm-editor")?.getBoundingClientRect().toJSON(),
      editorHost: document.querySelector(".cm-editor")?.closest(".group")?.getBoundingClientRect().toJSON(),
      draft: document.querySelector('[data-slot="editor-draft-status"]')?.getBoundingClientRect().toJSON(),
      style: { bodyFont: getComputedStyle(document.body).fontFamily, background: getComputedStyle(document.body).backgroundColor },
    })));
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "Open navigation" }).click();
  await page.locator("aside").waitFor();
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${out}/web-navigation-390.png` });
  await page.keyboard.press("Escape");
  await page.goto(`${origin}/memos/saved`, { waitUntil: "networkidle" });
  await page.screenshot({ path: `${out}/web-detail-390.png`, fullPage: true });
  await page.goto(`${origin}/calendar/2026/10`, { waitUntil: "networkidle" });
  await page.screenshot({ path: `${out}/web-calendar-390.png`, fullPage: true });
  await page.goto(origin, { waitUntil: "networkidle" });
  const { readFile } = await import("node:fs/promises");
  await page.addStyleTag({ content: await readFile("upstream/memos/web/src/themes/default-dark.css", "utf8") });
  await page.screenshot({ path: `${out}/web-home-dark-390.png`, fullPage: true });
  await writeFile(`${out}/web-measurements.json`, JSON.stringify({ measurements, errors, requests }, null, 2));
  console.log(JSON.stringify({ out, measurements, errors }));
} finally { await browser.close(); }
