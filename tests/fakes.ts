import { createHash } from 'node:crypto';
import type { Delivery } from '../src/server/model';
import { AppError } from '../src/server/model';
import type { Remote } from '../src/server/github';

export class MemoryRemote implements Remote {
  readonly files = new Map<string, { content: string; blob: string }>();
  async publish(filename: string, delivery: Delivery) {
    const old = this.files.get(filename);
    if (delivery.expectedBlob ? old?.blob !== delivery.expectedBlob : !!old) throw new AppError(409, 'REMOTE_CONFLICT', '冲突');
    const blob = delivery.hash;
    this.files.set(filename, { content: delivery.content, blob });
    return { blob, commit: blob, parent: 'test' };
  }
}
export class FakeGitHub {
  blobs = new Map<string, string>();
  binaryBlobs = new Map<string, Buffer>();
  trees = new Map<string, Record<string, string>>([['tree0', {}]]);
  commits = new Map<string, { tree: { sha: string }; parents: string[] }>([['head0', { tree: { sha: 'tree0' }, parents: [] }]]);
  head = 'head0';
  losePatchReply = false;
  moveBeforePatch = false;
  updates = 0;
  constructor() {}
  edit(filename: string, content?: string) {
    const entries = { ...this.trees.get(this.commits.get(this.head)!.tree.sha)! };
    if (content === undefined) delete entries[filename];
    else { const blob = 'external-' + this.commits.size; this.blobs.set(blob, content); entries[filename] = blob; }
    const tree = 'external-tree-' + this.trees.size; this.trees.set(tree, entries);
    const commit = 'external-commit-' + this.commits.size;
    this.commits.set(commit, { tree: { sha: tree }, parents: [this.head] }); this.head = commit;
  }
  fetch: typeof fetch = async (input, init) => {
    const url = new URL(String(input));
    const route = url.pathname.replace('/repos/test/inbox', '');
    const body = init?.body ? JSON.parse(String(init.body)) : {};
    const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
    if (route === '/git/ref/heads/main') return json({ object: { sha: this.head } });
    if (route.startsWith('/contents/')) {
      const ref = url.searchParams.get('ref')!;
      const commit = this.commits.get(ref);
      const blob = commit && this.trees.get(commit.tree.sha)?.[decodeURIComponent(route.slice('/contents/'.length))];
      return blob ? json({ type: 'file', encoding: 'base64', sha: blob, content: Buffer.from(this.blobs.get(blob)!).toString('base64') }) : json({}, 404);
    }
    if (route.startsWith('/compare/')) {
      const [base, head] = route.slice('/compare/'.length).split('...');
      let cursor: string | undefined = head;
      while (cursor && cursor !== base) cursor = this.commits.get(cursor)?.parents[0];
      return json({ status: cursor ? head === base ? 'identical' : 'ahead' : 'diverged' });
    }
    if (route.startsWith('/git/commits/') && init?.method === 'GET') return json(this.commits.get(route.slice('/git/commits/'.length)));
    if (route.startsWith('/git/trees/') && init?.method === 'GET') {
      const entries = this.trees.get(route.slice('/git/trees/'.length));
      return json({ truncated: false, tree: Object.entries(entries ?? {}).map(([path, sha]) => ({ path, sha, type: 'blob', mode: '100644' })) });
    }
    if (route.startsWith('/git/blobs/') && init?.method === 'GET') {
      const sha = route.slice('/git/blobs/'.length);
      const bytes = this.binaryBlobs.get(sha) ?? Buffer.from(this.blobs.get(sha) ?? '');
      return json({ sha, encoding: 'base64', size: bytes.length, content: bytes.toString('base64') });
    }
    if (route === '/git/blobs') {
      if (body.encoding === 'base64') {
        const bytes = Buffer.from(body.content, 'base64');
        const sha = createHash('sha1').update(Buffer.concat([Buffer.from(`blob ${bytes.length}\0`), bytes])).digest('hex');
        this.binaryBlobs.set(sha, bytes); return json({ sha });
      }
      const sha = createHash('sha1').update(body.content).digest('hex'); this.blobs.set(sha, body.content); return json({ sha });
    }
    if (route === '/git/trees') {
      const entries = { ...this.trees.get(body.base_tree) };
      for (const item of body.tree) entries[item.path] = item.sha;
      const sha = 'tree-' + this.trees.size; this.trees.set(sha, entries); return json({ sha });
    }
    if (route === '/git/commits') {
      const sha = 'commit-' + this.commits.size; this.commits.set(sha, { tree: { sha: body.tree }, parents: body.parents }); return json({ sha });
    }
    if (route === '/git/refs/heads/main') {
      if (this.moveBeforePatch) { this.moveBeforePatch = false; this.edit('other.md', 'concurrent'); }
      if (this.commits.get(body.sha)?.parents[0] !== this.head) return json({}, 422);
      this.head = body.sha; this.updates++;
      if (this.losePatchReply) { this.losePatchReply = false; throw new Error('Lost reply'); }
      return json({ object: { sha: this.head } });
    }
    throw new Error('Unexpected fake endpoint: ' + route);
  };
}
