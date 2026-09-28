import { lstat, mkdir, open, readFile, readdir, realpath, rename, unlink } from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { AppError, digest } from '../model';
import { Mutex } from '../store';
import { validateBundle, type BundleAttempt, type BundleDelivery, type BundleRemote } from './bundle';

// Transport-independent internal queue. Callers must authenticate the owner and
// allocate stable paths before enqueue; this is deliberately not an HTTP API.
export interface Submission {
  instance: string;
  owner: string;
  memo: string;
  delivery: BundleDelivery;
}
export interface SubmissionRecord extends Submission {
  format: 1;
  fingerprint: string;
  state: 'pending' | 'verified' | 'conflict';
  error?: string;
  failures: number;
  retryAt: number;
  receipt?: BundleAttempt;
}
function sourceKey(input: Submission): string { return JSON.stringify([input.instance, input.owner, input.memo]); }
function recordId(input: Submission): string { return digest(JSON.stringify([sourceKey(input), input.delivery.submissionId])); }
function identity(input: Submission): void {
  for (const value of [input.instance, input.owner, input.memo]) {
    if (typeof value !== 'string' || !value.trim() || value.length > 256) throw new AppError(400, 'INVALID_SUBMISSION', '来源标识无效。');
  }
  validateBundle(input.delivery);
}
function fingerprint(input: Submission): string {
  return digest(JSON.stringify([sourceKey(input), input.delivery.revision,
    input.delivery.files.map(f => [f.path, f.sha256]).sort((a, b) => a[0].localeCompare(b[0]))]));
}
const isMarkdownPath = (value: string) => /^(?:00_Inbox|Memos)\/\d{8}-\d{6}\.md$/.test(value);
const normalizeMarkdownPath = (value: string) => {
  const legacy = /^(?:Memos\/)?(\d{8}-\d{6}\.md)$/.exec(value);
  return legacy ? `00_Inbox/${legacy[1]}` : value;
};
function markdownPath(input: Submission): string { return input.delivery.files.find(file => isMarkdownPath(file.path))!.path; }
function nextMarkdownPath(value: string): string {
  const match = /^00_Inbox\/(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})\.md$/.exec(value);
  if (!match) throw new AppError(400, 'INVALID_BUNDLE', '正文路径格式无效。');
  const [year, month, day, hour, minute, second] = match.slice(1).map(Number);
  const date = new Date(Date.UTC(year, month - 1, day, hour, minute, second + 1));
  const stamp = date.toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15);
  return `00_Inbox/${stamp}.md`;
}
function isInside(parent: string, candidate: string): boolean {
  const relative = path.relative(parent, candidate);
  return !relative || (!relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative));
}

export class SubmissionQueue {
  private mutex = new Mutex();
  private closed = false;
  private constructor(readonly root: string, private release: () => Promise<void>) {}

  static async create(root: string, projectRoot: string): Promise<SubmissionQueue> {
    if (!root || !path.isAbsolute(root)) throw new Error('MEMOS_QUEUE_DIR 必须显式配置为项目外绝对路径。');
    const project = await realpath(projectRoot);
    if (isInside(project, path.resolve(root))) throw new Error('Memos 队列不能位于源码目录内。');
    await mkdir(root, { recursive: true, mode: 0o700 });
    const resolved = await realpath(root);
    if (isInside(project, resolved)) throw new Error('Memos 队列解析后位于源码目录内。');
    for (const file of await readdir(resolved)) {
      if (file !== 'instance.lock' && !/^[a-f0-9]{64}\.json$/.test(file) && !/^[a-f0-9-]{36}\.tmp$/.test(file)) {
        throw new Error('Memos 队列目录含非应用文件，请使用独立目录。');
      }
      if (!(await lstat(path.join(resolved, file))).isFile()) throw new Error('队列不能包含目录或符号链接。');
    }
    // Do not automatically remove stale locks: another process may own them.
    const lockPath = path.join(resolved, 'instance.lock');
    const lock = await open(lockPath, 'wx', 0o600);
    try { await lock.writeFile(JSON.stringify({ pid: process.pid })); await lock.sync(); }
    catch (error) { await lock.close(); throw error; }
    return new SubmissionQueue(resolved, async () => { await lock.close(); await unlink(lockPath); });
  }
  private async records(): Promise<SubmissionRecord[]> {
    if (this.closed) throw new Error('Queue is closed');
    const names = (await readdir(this.root)).filter(name => /^[a-f0-9]{64}\.json$/.test(name));
    const records: SubmissionRecord[] = [];
    for (const name of names) {
      const file = path.join(this.root, name);
      if (!(await lstat(file)).isFile()) throw new Error('Unsafe queue record');
      const record = JSON.parse(await readFile(file, 'utf8')) as SubmissionRecord;
      identity(record);
      if (record.format !== 1 || name !== recordId(record) + '.json' || record.fingerprint !== fingerprint(record) ||
          !['pending', 'verified', 'conflict'].includes(record.state) || (record.state === 'verified' && !record.receipt)) {
        throw new Error('Invalid queue record; stop without discarding content');
      }
      records.push(record);
    }
    return records;
  }
  private async write(record: SubmissionRecord): Promise<void> {
    const temp = path.join(this.root, randomUUID() + '.tmp');
    const handle = await open(temp, 'wx', 0o600);
    try { await handle.writeFile(JSON.stringify(record)); await handle.sync(); }
    finally { await handle.close(); }
    await rename(temp, path.join(this.root, recordId(record) + '.json'));
    if (process.platform !== 'win32') {
      const directory = await open(this.root, 'r');
      try { await directory.sync(); } finally { await directory.close(); }
    }
  }
  async enqueue(input: Submission): Promise<SubmissionRecord> {
    // Accept the bridge's earlier candidate prefix during a rolling upgrade,
    // but persist and publish every new Markdown file in 00_Inbox.
    const snapshot = structuredClone(input);
    snapshot.delivery.files = snapshot.delivery.files.map(file => ({ ...file, path: normalizeMarkdownPath(file.path) }));
    identity(snapshot);
    if (snapshot.delivery.attempt || snapshot.delivery.files.some(file => file.expectedBlob !== undefined)) {
      throw new AppError(400, 'INVALID_SUBMISSION', '提交不能指定远端核验状态。');
    }
    return this.mutex.run(async () => {
      const all = await this.records();
      const existing = all.find(record => recordId(record) === recordId(snapshot));
      if (existing) {
        const allocated = markdownPath(existing);
        snapshot.delivery.files = snapshot.delivery.files.map(file => isMarkdownPath(file.path) ? { ...file, path: allocated } : file);
        const hash = fingerprint(snapshot);
        if (existing.fingerprint !== hash) throw new AppError(409, 'IDEMPOTENCY_CONFLICT', '提交标识已用于其他内容。');
        return existing;
      }
      const prior = all.filter(record => sourceKey(record) === sourceKey(snapshot));
      if (prior.some(record => record.delivery.revision >= snapshot.delivery.revision)) {
        throw new AppError(409, 'VERSION_CONFLICT', '源版本必须递增，旧草稿需要另存。');
      }
      const priorPath = prior[0] && markdownPath(prior[0]);
      if (priorPath) {
        if (prior.some(record => markdownPath(record) !== priorPath)) throw new AppError(409, 'PATH_CONFLICT', '笔记路径记录不一致。');
        snapshot.delivery.files = snapshot.delivery.files.map(file => isMarkdownPath(file.path) ? { ...file, path: priorPath } : file);
      } else {
        let allocated = markdownPath(snapshot);
        while (all.some(record => record.delivery.files.some(file => file.path === allocated))) allocated = nextMarkdownPath(allocated);
        snapshot.delivery.files = snapshot.delivery.files.map(file => isMarkdownPath(file.path) ? { ...file, path: allocated } : file);
      }
      const hash = fingerprint(snapshot);
      const record: SubmissionRecord = { ...snapshot, format: 1, fingerprint: hash, state: 'pending', failures: 0, retryAt: 0 };
      await this.write(record); return structuredClone(record);
    });
  }
  async status(identity: Pick<Submission, 'instance' | 'owner' | 'memo'>, submissionId: string) {
    return this.mutex.run(async () => {
      const record = (await this.records()).find(item => item.instance === identity.instance && item.owner === identity.owner &&
        item.memo === identity.memo && item.delivery.submissionId === submissionId);
      return record && { state: record.state, revision: record.delivery.revision, error: record.error ?? null, commit: record.receipt?.commit };
    });
  }
  async deliver(remote: BundleRemote, options: { enabled: boolean; now?: number }): Promise<void> {
    // No environment fallback to Echo's production sync flag.
    if (!options.enabled) return;
    return this.mutex.run(async () => {
      const now = options.now ?? Date.now();
      const all = (await this.records()).sort((a, b) => a.delivery.revision - b.delivery.revision);
      for (const record of all) {
        if (record.state !== 'pending' || record.retryAt > now) continue;
        const prior = all.filter(item => sourceKey(item) === sourceKey(record) && item.delivery.revision < record.delivery.revision);
        if (prior.some(item => item.state !== 'verified')) continue;
        if (!record.delivery.attempt) {
          const expected = Object.assign({}, ...prior.map(item => item.receipt!.blobs)) as Record<string, string>;
          record.delivery.files = record.delivery.files.map(file => ({ ...file, expectedBlob: expected[file.path] }));
        }
        try {
          const receipt = await remote.publishBundle(record.delivery, async attempt => {
            record.delivery.attempt = structuredClone(attempt); await this.write(record);
          });
          record.receipt = receipt; record.state = 'verified'; delete record.error; record.retryAt = 0;
          await this.write(record);
        } catch (error) {
          record.error = error instanceof AppError ? error.code : 'DELIVERY_FAILED';
          record.state = record.error === 'REMOTE_CONFLICT' ? 'conflict' : 'pending';
          record.failures++; record.retryAt = now + Math.min(300000, 5000 * 2 ** Math.min(record.failures, 6));
          await this.write(record);
        }
      }
    });
  }
  async close(): Promise<void> {
    await this.mutex.run(async () => { if (!this.closed) { this.closed = true; await this.release(); } });
  }
}
