import { createHash } from 'node:crypto';
import { AppError, MAX_BYTES } from '../model';

export const ATTACHMENT_LIMIT = 10 * 1024 * 1024;
export const ATTACHMENTS_LIMIT = 25 * 1024 * 1024;
export interface BundleFile {
  path: string;
  base64: string;
  sha256: string;
  expectedBlob?: string;
}
export interface BundleAttempt {
  parent: string;
  commit: string;
  blobs: Record<string, string>;
}
export interface BundleDelivery {
  submissionId: string;
  revision: number;
  files: BundleFile[];
  attempt?: BundleAttempt;
}
export interface BundleRemote {
  publishBundle(delivery: BundleDelivery, checkpoint: (attempt: BundleAttempt) => Promise<void>): Promise<BundleAttempt>;
}
export function bytesHash(bytes: Uint8Array): string {
  return createHash('sha256').update(bytes).digest('hex');
}
export function bundleFile(path: string, bytes: Uint8Array, expectedBlob?: string): BundleFile {
  return { path, base64: Buffer.from(bytes).toString('base64'), sha256: bytesHash(bytes), expectedBlob };
}
export function validateBundle(delivery: BundleDelivery): void {
  if (!/^[a-zA-Z0-9-]{1,80}$/.test(delivery.submissionId) || !Number.isSafeInteger(delivery.revision) || delivery.revision < 1 ||
      !Array.isArray(delivery.files) || delivery.files.length < 1 || delivery.files.length > 101) {
    throw new AppError(400, 'INVALID_BUNDLE', '投递快照格式无效。');
  }
  const paths = new Set<string>();
  let markdownCount = 0; let attachmentBytes = 0;
  for (const file of delivery.files) {
    const markdown = /^(?:00_Inbox|Memos)\/[0-9]{8}-[0-9]{6}\.md$/.test(file.path);
    const attachment = /^attachments\/memos\/[a-zA-Z0-9-]{1,80}\/[0-9a-f]{64}\.[a-z0-9]{1,10}$/.test(file.path);
    if (!markdown && !attachment) throw new AppError(400, 'INVALID_BUNDLE', `投递路径无效：${file.path}`);
    if (paths.has(file.path.toLowerCase())) throw new AppError(400, 'INVALID_BUNDLE', `投递路径重复：${file.path}`);
    if (typeof file.base64 !== 'string') throw new AppError(400, 'INVALID_BUNDLE', `投递编码无效：${file.path}`);
    if (file.expectedBlob !== undefined && !/^[0-9a-f]{40,64}$/.test(file.expectedBlob)) {
      throw new AppError(400, 'INVALID_BUNDLE', `预期版本无效：${file.path}`);
    }
    paths.add(file.path.toLowerCase());
    // Bound encoded data before allocating its decoded buffer.
    const limit = markdown ? MAX_BYTES : ATTACHMENT_LIMIT;
    if (file.base64.length > Math.ceil(limit / 3) * 4) throw new AppError(413, 'TOO_LARGE', '投递文件超过限制。');
    const bytes = Buffer.from(file.base64, 'base64');
    if (bytes.length > limit) throw new AppError(413, 'TOO_LARGE', '投递文件超过限制。');
    if (bytes.toString('base64') !== file.base64 || bytesHash(bytes) !== file.sha256 ||
        (attachment && !file.path.includes('/' + file.sha256 + '.'))) {
      throw new AppError(400, 'INVALID_BUNDLE', '投递文件摘要或编码无效。');
    }
    if (markdown) markdownCount++; else attachmentBytes += bytes.length;
  }
  if (markdownCount !== 1 || attachmentBytes > ATTACHMENTS_LIMIT) {
    throw new AppError(413, 'INVALID_BUNDLE', '需要一篇正文且附件总量不能超过 25 MiB。');
  }
}
