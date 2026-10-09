import { fromJson, toJson } from "../../../../upstream/memos/web/node_modules/@bufbuild/protobuf/dist/esm/index.js";
import { AttachmentSchema, MotionMediaSchema, MediaMetadataSchema } from "@/types/proto/api/v1/attachment_service_pb";
import { LocationSchema, MemoRelationSchema } from "@/types/proto/api/v1/memo_service_pb";
import type { EditorState } from "@/components/MemoEditor/state";
import type { LocalFile } from "@/components/MemoEditor/types/attachment";
import { nativeCall } from "./bridge";
import { editorChangeToken } from "./model";

const uploaded = new WeakMap<File, Promise<any>>();
const urls = new Map<string, string>();
export function rewriteLocalFiles(content: string): string { for (const [before, after] of urls) content = content.replaceAll(before, after); return content; }
const sequence = new Map<string, number>();
export function encodeBytes(bytes: Uint8Array): string {
  const parts = [];
  for (let offset = 0; offset < bytes.length; offset += 32768) parts.push(String.fromCharCode(...bytes.subarray(offset, offset + 32768)));
  return btoa(parts.join(""));
}
export async function uploadFile(file: File, purpose = "attachment", extra: Record<string, unknown> = {}): Promise<any> {
  const initial = await nativeCall("memos.api.v1.AttachmentService/UploadAttachment", { spec: { attachment: { filename: file.name, type: file.type || "application/octet-stream", ...extra }, totalSize: String(file.size), purpose } });
  for (let offset = 0; ; ) {
    const end = Math.min(offset + initial.maxChunkSize, file.size);
    const response = await nativeCall("memos.api.v1.AttachmentService/UploadAttachment", { uploadId: initial.uploadId, writeOffset: String(offset), data: encodeBytes(new Uint8Array(await file.slice(offset, end).arrayBuffer())), finishWrite: end === file.size });
    if (Number(response.committedSize) !== end) throw new Error("附件分块核验失败");
    if (end === file.size) return response.attachment;
    offset = end;
  }
}
export function uploadLocalFile(local: LocalFile): Promise<any> {
  let pending = uploaded.get(local.file);
  if (!pending) {
    pending = Promise.resolve(local.mediaMetadata).then((metadata) => uploadFile(local.file, "attachment", {
      ...(local.motionMedia ? { motionMedia: toJson(MotionMediaSchema, local.motionMedia) } : {}),
      ...(metadata ? { mediaMetadata: toJson(MediaMetadataSchema, metadata) } : {}),
    })).then((value) => { urls.set(local.previewUrl, `/file/${value.name}/${encodeURIComponent(value.filename)}`); return value; });
    uploaded.set(local.file, pending);
    pending.catch(() => uploaded.delete(local.file));
  }
  return pending;
}
export async function persistEditorDraft(key: string, state: EditorState, baseRevision?: string): Promise<void> {
  const changeToken = editorChangeToken(key);
  const generation = (sequence.get(key) ?? 0) + 1; sequence.set(key, generation);
  if (state.localFiles.reduce((size, file) => size + file.file.size, 0) + state.metadata.attachments.reduce((size, file) => size + Number(file.size), 0) > 25 * 1024 * 1024) throw new Error("附件总量不能超过 25 MiB");
  const local: any[] = []; let next = 0;
  await Promise.all(Array.from({ length: Math.min(4, state.localFiles.length) }, async () => {
    for (let index = next++; index < state.localFiles.length; index = next++) local[index] = await uploadLocalFile(state.localFiles[index]);
  }));
  if (sequence.get(key) !== generation) return;
  let content = state.content;
  for (const [index, file] of state.localFiles.entries()) content = content.replaceAll(file.previewUrl, `/file/${local[index].name}/${encodeURIComponent(local[index].filename)}`);
  const value = JSON.stringify({ kind: "memos.editor-cache", version: 4, content,
    attachments: [...state.metadata.attachments.map((item) => toJson(AttachmentSchema, item)), ...local],
    location: state.metadata.location ? toJson(LocationSchema, state.metadata.location) : null,
    relations: state.metadata.relations.map((item) => toJson(MemoRelationSchema, item)), baseRevision, changeToken });
  localStorage.setItem(key, value);
  await nativeCall("local.draft", { key, value });
}
export function clearEditorDraft(key: string) {
  sequence.set(key, (sequence.get(key) ?? 0) + 1); localStorage.removeItem(key);
  return nativeCall("local.clear-draft", { key });
}
export function cachedRelations(key: string) {
  try { return (JSON.parse(localStorage.getItem(key) ?? "{}").relations ?? []).map((value: any) => fromJson(MemoRelationSchema, value)); } catch { return []; }
}
