import { createHash } from 'node:crypto';

export const MAX_BYTES = 256 * 1024;
export const ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export class AppError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}
export interface SaveInput { id: string; createdAt: string; revision: number; content: string }
export interface Attempt { commit: string; blob: string; parent: string }
export interface Delivery { revision: number; content: string; hash: string; expectedBlob?: string; attempt?: Attempt }
export interface NoteRecord {
  id: string; filename: string; createdAt: string; revision: number; hash: string;
  content?: string; publishedRevision: number; remoteBlob?: string; commit?: string;
  delivery?: Delivery; error?: string; retryAt?: number; failures?: number;
  lastActive?: number; retired?: boolean;
}
export function digest(content: string) { return createHash('sha256').update(content).digest('hex'); }
export function validate(input: SaveInput) {
  if (!input || !ID_PATTERN.test(input.id) || typeof input.content !== 'string' ||
      !Number.isSafeInteger(input.revision) || input.revision < 1 ||
      typeof input.createdAt !== 'string' || !Number.isFinite(Date.parse(input.createdAt)) ||
      new Date(input.createdAt).toISOString() !== input.createdAt) {
    throw new AppError(400, 'INVALID_NOTE', '笔记格式不正确，请保留草稿后重试。');
  }
  if (Buffer.byteLength(input.content) > MAX_BYTES) throw new AppError(413, 'TOO_LARGE', '单篇笔记不能超过 256 KB，请拆分记录。');
}
export function filename(createdAt: string, _id?: string, offsetSeconds = 0) {
  const china = new Date(Date.parse(createdAt) + (8 * 60 * 60 + offsetSeconds) * 1000).toISOString();
  return `${china.slice(0, 10).replaceAll('-', '')}-${china.slice(11, 19).replaceAll(':', '')}.md`;
}
export function publicStatus(note: NoteRecord) {
  return { id: note.id, filename: note.filename, revision: note.revision,
    publishedRevision: note.publishedRevision, error: note.error ?? null,
    state: note.revision === 0 ? 'empty' : note.publishedRevision === note.revision ? 'backed-up' : 'staged' };
}
