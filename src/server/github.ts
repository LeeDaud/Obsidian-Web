import { AppError, type Attempt, type Delivery } from './model';
import { bytesHash, validateBundle, type BundleAttempt, type BundleDelivery } from './memos/bundle';

type Json = Record<string, any>;
export interface Remote {
  publish(filename: string, delivery: Delivery, checkpoint: (attempt: Attempt) => Promise<void>): Promise<Attempt>;
}
export class GitHubRemote implements Remote {
  private base: string;
  constructor(private options: { owner: string; repo: string; branch: string; token: string }, private transport: typeof fetch = fetch) {
    this.base = `https://api.github.com/repos/${encodeURIComponent(options.owner)}/${encodeURIComponent(options.repo)}`;
  }
  private async request(route: string, method = 'GET', body?: Json): Promise<Json> {
    const response = await this.transport(this.base + route, {
      method, redirect: 'error', signal: AbortSignal.timeout(15000),
      headers: { Authorization: `Bearer ${this.options.token}`, Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!response.ok) {
      // Never include upstream bodies, credentials or note text in errors/logs.
      const code = response.status === 409 || response.status === 422 ? 'REMOTE_CONFLICT' : 'GITHUB_UNAVAILABLE';
      throw new AppError(503, code, `GitHub 暂不可用 (${response.status})，内容仍保留。`);
    }
    return response.json() as Promise<Json>;
  }
  private async file(filename: string, ref: string): Promise<Json | undefined> {
    const response = await this.transport(`${this.base}/contents/${encodeURIComponent(filename)}?ref=${encodeURIComponent(ref)}`, {
      redirect: 'error', signal: AbortSignal.timeout(15000), headers: {
        Authorization: `Bearer ${this.options.token}`, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28',
      },
    });
    if (response.status === 404) return undefined;
    if (!response.ok) throw new AppError(503, 'GITHUB_UNAVAILABLE', 'GitHub 读取失败，稍后重试。');
    const file = await response.json() as Json;
    if (file.type !== 'file' || file.encoding !== 'base64' || typeof file.content !== 'string' || file.submodule_git_url) {
      throw new AppError(409, 'REMOTE_CONFLICT', '远端文件类型已改变，请另存草稿。');
    }
    return file;
  }
  private async verify(filename: string, content: string, attempt: Attempt) {
    const file = await this.file(filename, attempt.commit);
    if (!file || file.sha !== attempt.blob || Buffer.from(file.content, 'base64').toString('utf8') !== content) {
      throw new AppError(503, 'VERIFY_FAILED', '备份核验尚未通过，暂存内容继续保留。');
    }
    return attempt;
  }
  // Memos snapshots are committed as one tree: text and binary attachments cannot
  // be acknowledged independently. Existing Echo text delivery stays unchanged.
  async publishBundle(delivery: BundleDelivery, checkpoint: (attempt: BundleAttempt) => Promise<void>): Promise<BundleAttempt> {
    validateBundle(delivery);
    const refPath = `/git/refs/heads/${encodeURIComponent(this.options.branch)}`;
    const head = (await this.request(`/git/ref/heads/${encodeURIComponent(this.options.branch)}`)).object.sha as string;
    if (delivery.attempt) {
      const attempt = delivery.attempt;
      if (head === attempt.parent) {
        await this.request(refPath, 'PATCH', { sha: attempt.commit, force: false });
      } else {
        const comparison = await this.request(`/compare/${attempt.commit}...${head}`);
        if (comparison.status !== 'ahead' && comparison.status !== 'identical') {
          throw new AppError(409, 'REMOTE_CONFLICT', '远端历史已变化，快照继续保留。');
        }
      }
      return this.verifyBundle(delivery, attempt);
    }
    const base = await this.request(`/git/commits/${head}`);
    const tree = await this.request(`/git/trees/${base.tree.sha}?recursive=1`);
    if (tree.truncated || !Array.isArray(tree.tree)) throw new AppError(503, 'VERIFY_FAILED', '远端目录未能完整读取。');
    const existing = new Map<string, Json>(tree.tree.map((entry: Json) => [entry.path, entry]));
    for (const file of delivery.files) {
      const current = existing.get(file.path);
      if (file.expectedBlob ? !current || current.sha !== file.expectedBlob || current.type !== 'blob' || current.mode !== '100644' : !!current) {
        throw new AppError(409, 'REMOTE_CONFLICT', '远端文件已变化，请保留草稿并另存。');
      }
      // Never replace a directory, symlink or a differently-cased Windows path.
      const segments = file.path.split('/');
      for (let i = 1; i < segments.length; i++) {
        const ancestor = existing.get(segments.slice(0, i).join('/'));
        if (ancestor && ancestor.type !== 'tree') throw new AppError(409, 'REMOTE_CONFLICT', '远端路径不再是目录。');
      }
      for (const path of existing.keys()) {
        if (path !== file.path && path.toLowerCase() === file.path.toLowerCase()) {
          throw new AppError(409, 'REMOTE_CONFLICT', '远端存在大小写冲突路径。');
        }
      }
    }
    const blobs: Record<string, string> = {};
    for (const file of delivery.files) {
      blobs[file.path] = (await this.request('/git/blobs', 'POST', { content: file.base64, encoding: 'base64' })).sha;
    }
    const createdTree = await this.request('/git/trees', 'POST', {
      base_tree: base.tree.sha,
      tree: delivery.files.map(file => ({ path: file.path, mode: '100644', type: 'blob', sha: blobs[file.path] })),
    });
    const commit = await this.request('/git/commits', 'POST', {
      message: `capture: memos ${delivery.submissionId} v${delivery.revision}`,
      tree: createdTree.sha, parents: [head],
    });
    const attempt: BundleAttempt = { parent: head, commit: commit.sha, blobs };
    await checkpoint(attempt);
    await this.request(refPath, 'PATCH', { sha: attempt.commit, force: false });
    return this.verifyBundle(delivery, attempt);
  }
  private async verifyBundle(delivery: BundleDelivery, attempt: BundleAttempt): Promise<BundleAttempt> {
    const commit = await this.request(`/git/commits/${attempt.commit}`);
    const tree = await this.request(`/git/trees/${commit.tree.sha}?recursive=1`);
    if (tree.truncated || !Array.isArray(tree.tree)) throw new AppError(503, 'VERIFY_FAILED', '备份目录核验失败。');
    for (const file of delivery.files) {
      const entry = tree.tree.find((item: Json) => item.path === file.path);
      const sha = attempt.blobs[file.path];
      if (!sha || entry?.sha !== sha || entry.type !== 'blob' || entry.mode !== '100644') {
        throw new AppError(503, 'VERIFY_FAILED', '备份文件版本核验失败。');
      }
      // Git blobs supports binary files beyond the Contents API's 1 MiB inline limit.
      const blob = await this.request(`/git/blobs/${sha}`);
      const bytes = typeof blob.content === 'string' ? Buffer.from(blob.content, 'base64') : undefined;
      if (blob.encoding !== 'base64' || blob.sha !== sha || !bytes || blob.size !== bytes.length ||
          bytesHash(bytes) !== file.sha256 || !bytes.equals(Buffer.from(file.base64, 'base64'))) {
        throw new AppError(503, 'VERIFY_FAILED', '备份内容核验失败，快照继续保留。');
      }
    }
    return attempt;
  }
  async publish(filename: string, delivery: Delivery, checkpoint: (attempt: Attempt) => Promise<void>) {
    const refPath = `/git/refs/heads/${encodeURIComponent(this.options.branch)}`;
    const head = (await this.request(`/git/ref/heads/${encodeURIComponent(this.options.branch)}`)).object.sha as string;
    if (delivery.attempt) {
      const attempt = delivery.attempt;
      if (head !== attempt.parent) {
        const comparison = await this.request(`/compare/${attempt.commit}...${head}`);
        if (comparison.status === 'ahead' || comparison.status === 'identical') {
          return this.verify(filename, delivery.content, attempt);
        }
        throw new AppError(409, 'REMOTE_CONFLICT', '远端历史已变化，草稿保留，请另存为新想法。');
      }
      await this.request(refPath, 'PATCH', { sha: attempt.commit, force: false });
      return this.verify(filename, delivery.content, attempt);
    }
    const current = await this.file(filename, head);
    if (delivery.expectedBlob ? current?.sha !== delivery.expectedBlob : !!current) {
      throw new AppError(409, 'REMOTE_CONFLICT', '笔记已在电脑端修改或移走，请另存为新想法。');
    }
    const base = await this.request(`/git/commits/${head}`);
    const blob = await this.request('/git/blobs', 'POST', { content: delivery.content, encoding: 'utf-8' });
    const tree = await this.request('/git/trees', 'POST', {
      base_tree: base.tree.sha, tree: [{ path: filename, mode: '100644', type: 'blob', sha: blob.sha }],
    });
    const commit = await this.request('/git/commits', 'POST', {
      message: `capture: ${filename} v${delivery.revision}`, tree: tree.sha, parents: [head],
    });
    const attempt = { parent: head, blob: blob.sha as string, commit: commit.sha as string };
    // Durable checkpoint BEFORE moving the ref handles lost replies and process crashes.
    await checkpoint(attempt);
    await this.request(refPath, 'PATCH', { sha: attempt.commit, force: false });
    return this.verify(filename, delivery.content, attempt);
  }
}
