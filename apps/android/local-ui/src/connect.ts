import { createClient } from "../../../../upstream/memos/web/node_modules/@connectrpc/connect/dist/esm/index.js";
import { createConnectTransport } from "../../../../upstream/memos/web/node_modules/@connectrpc/connect-web/dist/esm/index.js";
import { AIService } from "@/types/proto/api/v1/ai_service_pb";
import { AttachmentService } from "@/types/proto/api/v1/attachment_service_pb";
import { AuthService } from "@/types/proto/api/v1/auth_service_pb";
import { IdentityProviderService } from "@/types/proto/api/v1/idp_service_pb";
import { InstanceService } from "@/types/proto/api/v1/instance_service_pb";
import { MemoService } from "@/types/proto/api/v1/memo_service_pb";
import { SpaceService } from "@/types/proto/api/v1/space_service_pb";
import { UserService } from "@/types/proto/api/v1/user_service_pb";
import { nativeCall } from "./bridge";
import { activeEditorKey, editorChangeToken, registerMetadata } from "./model";
import { browserFetch } from "./platform";
import { rewriteLocalFiles } from "./drafts";
import { profileMemo } from "./profile";
import { publicResource } from "./public-resources";
const snapshots = new Map<string, any[]>();
let queryWorker: Worker | undefined;
const queries = new Map<string, { resolve: (names: string[]) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }>();
function query(records: any[], input: any): Promise<string[]> {
  if (!queryWorker) {
    queryWorker = new Worker(new URL("./query-worker.ts", import.meta.url), { type: "module" });
    queryWorker.onmessage = (event) => {
      const request = queries.get(event.data.id); if (!request) return;
      queries.delete(event.data.id); clearTimeout(request.timer);
      if (event.data.error) request.reject(new Error(event.data.error)); else request.resolve(event.data.names);
    };
  }
  return new Promise((resolve, reject) => {
    const id = crypto.randomUUID();
    const timer = setTimeout(() => { queryWorker?.terminate(); queryWorker = undefined; queries.delete(id); reject(new Error("筛选计算超时，请简化表达式")); }, 5000);
    queries.set(id, { resolve, reject, timer });
    queryWorker!.postMessage({ id, memos: records, filter: input.filter, state: input.state, orderBy: input.orderBy });
  });
}
async function allMemos(): Promise<any[]> {
  const memos: any[] = []; let offset = 0;
  for (let page = 0; page < 100; page++) {
    const response = await nativeCall("local.list", { offset });
    memos.push(...response.memos.map(profileMemo));
    if (JSON.stringify(memos).length > 16 * 1024 * 1024) throw new Error("列表较大，请分批读取");
    if (response.next < 0) return memos;
    if (response.next <= offset) throw new Error("分页没有推进，请刷新");
    offset = response.next;
  }
  throw new Error("记录较多，请缩小查询范围");
}
async function listMemos(input: any) {
  let rows: any[]; let id: string; let offset = 0;
  if (input.pageToken) {
    const token = JSON.parse(input.pageToken); id = token.id; offset = token.offset;
    rows = snapshots.get(id)!; if (!rows || !Number.isInteger(offset) || offset < 0 || offset > rows.length) throw new Error("列表已变化，请刷新");
  } else {
    const records = await allMemos(); registerMetadata(records);
    const names = await query(records, input); const byName = new Map(records.map((record) => [record.name, record])); rows = names.map((name) => byName.get(name));
    id = crypto.randomUUID(); if (snapshots.size >= 32) snapshots.delete(snapshots.keys().next().value!); snapshots.set(id, rows);
  }
  const count = Math.max(1, Math.min(100, input.pageSize || 30));
  return { memos: rows.slice(offset, offset + count), nextPageToken: offset + count < rows.length ? JSON.stringify({ id, offset: offset + count }) : "" };
}
const revisions = new Map<string, number>();
function stripRevision(value: any): any {
  if (value && typeof value === "object") {
    if (typeof value.localRevision === "number" && typeof value.name === "string") { revisions.set(value.name, value.localRevision); delete value.localRevision; }
    for (const item of Object.values(value)) if (item && typeof item === "object") stripRevision(item);
  }
  return value;
}

export const localFetch: typeof fetch = async (input, init) => {
  const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url, location.origin);
  if (url.origin !== location.origin) {
    if (publicResource(url) && (init?.method ?? (input instanceof Request ? input.method : "GET")) === "GET") return browserFetch(input, { ...init, credentials: "omit", referrerPolicy: "no-referrer" });
    throw new Error("本地界面不直接访问外部服务");
  }
  let command: string;
  let payload: unknown = {};
  const method = init?.method ?? (input instanceof Request ? input.method : "GET");
  const body = init?.body ?? (input instanceof Request ? await input.clone().text() : undefined);
  if ((url.protocol === "blob:" || /^\/(assets|file\/attachments)\//.test(url.pathname)) && method === "GET") return browserFetch(input, init);
  if (/^\/memos\.api\.v1\.[A-Za-z]+Service\/[A-Za-z]+$/.test(url.pathname)) {
    if (method !== "POST" || body == null) throw new Error("请求格式不支持");
    const text = typeof body === "string" ? body : body instanceof Uint8Array || body instanceof ArrayBuffer ? new TextDecoder().decode(body) : undefined;
    if (text == null) throw new Error("请求格式不支持");
    command = url.pathname.slice(1); payload = JSON.parse(text);
    if (command === "memos.api.v1.MemoService/UpdateMemo") (payload as any).localRevision = revisions.get((payload as any).memo?.name);
    if (command === "memos.api.v1.MemoService/CreateMemo" || command === "memos.api.v1.MemoService/UpdateMemo") {
      (payload as any).editorKey = activeEditorKey;
      (payload as any).changeToken = editorChangeToken(activeEditorKey);
      if (typeof (payload as any).memo?.content === "string") (payload as any).memo.content = rewriteLocalFiles((payload as any).memo.content);
    }
  } else if (url.pathname === "/api/echo/v1/status") command = "echo.status";
  else if (url.pathname === "/api/echo/v1/memo-statuses") { command = "echo.memo-statuses"; payload = body ? JSON.parse(String(body)) : {}; }
  else if (/^\/api\/echo\/v1\/memos\/[A-Za-z0-9_-]+\/submissions$/.test(url.pathname) && method === "POST") { command = "echo.submit"; payload = { name: `memos/${url.pathname.split('/')[5]}` }; }
  else throw new Error("该接口尚未适配，不会发送到服务器");
  try {
    let result;
    if (command === "memos.api.v1.MemoService/ListMemos") result = await listMemos(payload);
    else if (command === "memos.api.v1.UserService/GetUserStats") {
      const records = (await allMemos()).filter((memo) => memo.state !== "ARCHIVED");
      const tagCount: Record<string, number> = {};
      for (const memo of records) for (const tag of memo.tags ?? []) tagCount[tag] = (tagCount[tag] ?? 0) + 1;
      result = { name: "users/device", memoCount: records.length, memoDisplayTimestamps: records.map((memo) => memo.createTime), tagCount };
    } else result = await nativeCall(command, payload);
    if (result?.name?.startsWith("memos/")) result = profileMemo(result);
    registerMetadata(result); stripRevision(result);
    if (/(Create|Update|Delete)Memo$/.test(command) || command === "echo.submit") snapshots.clear();
    if (command === "memos.api.v1.AuthService/SignOut") setTimeout(() => location.replace("/"), 0);
    return new Response(JSON.stringify(result), { status: 200, headers: { "Content-Type": "application/json" } });
  } catch (error) {
    return new Response(JSON.stringify({ code: "failed_precondition", message: error instanceof Error ? error.message : "本机操作未完成" }),
      { status: 400, headers: { "Content-Type": "application/json" } });
  }
};
const transport = createConnectTransport({ baseUrl: location.origin, useBinaryFormat: false, fetch: localFetch, useHttpGet: false });
export const instanceServiceClient = createClient(InstanceService, transport);
export const authServiceClient = createClient(AuthService, transport);
export const userServiceClient = createClient(UserService, transport);
export const memoServiceClient = createClient(MemoService, transport);
export const attachmentServiceClient = createClient(AttachmentService, transport);
export const aiServiceClient = createClient(AIService, transport);
export const spaceServiceClient = createClient(SpaceService, transport);
export const identityProviderServiceClient = createClient(IdentityProviderService, transport);
export async function refreshAccessToken(): Promise<void> {}
export async function getRequestToken(): Promise<null> { return null; }
