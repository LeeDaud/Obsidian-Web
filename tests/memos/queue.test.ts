import { mkdtemp, readdir, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { SubmissionQueue, type Submission } from '../../src/server/memos/queue';
import { bundleFile } from '../../src/server/memos/bundle';
import { GitHubRemote } from '../../src/server/github';
import { FakeGitHub } from '../fakes';

const input = (revision = 1, content = '合成笔记'): Submission => ({
  instance: 'local', owner: 'users/test', memo: 'memos/test',
  delivery: { submissionId: `submit-${revision}`, revision, files: [bundleFile('00_Inbox/20260928-120000.md', Buffer.from(content))] },
});
const inputWithAttachment = (memo = 'memos/test', revision = 1): Submission => {
  const attachment = 'attachments/memos/memo-uid/20260928-115959.png';
  return {
    instance: 'local', owner: 'users/test', memo,
    delivery: { submissionId: `attachment-${revision}`, revision, files: [
      bundleFile('00_Inbox/20260928-120000.md', Buffer.from(`图片：![](${attachment})`)),
      bundleFile(attachment, Buffer.from(`image-${revision}`)),
    ] },
  };
};
async function fixture() {
  const root = await mkdtemp(path.join(tmpdir(), 'memos-queue-test-'));
  const queue = await SubmissionQueue.create(root, process.cwd());
  const fake = new FakeGitHub();
  const remote = new GitHubRemote({ owner: 'test', repo: 'inbox', branch: 'main', token: 'fake' }, fake.fetch);
  return { root, queue, fake, remote };
}
describe('explicit Memos submission queue', () => {
  it('reports parent waiting and rejects a changed continuation relationship', async () => {
    const { queue } = await fixture();
    const child = input(); child.parent = { memo: 'memos/parent' };
    try {
      await queue.enqueue(child);
      const lookup = { memo: child.memo, submissionId: child.delivery.submissionId, attachmentHashes: [], parentMemo: child.parent.memo };
      expect((await queue.statuses('local', 'users/test', [lookup]))[0].state).toBe('waiting_parent');
      expect((await queue.statuses('local', 'users/test', [{ ...lookup, parentMemo: 'memos/other' }]))[0].state).toBe('unknown');
    } finally { await queue.close(); }
  });
  it('queries current receipts without enqueueing, scopes owners and preserves older verified versions', async () => {
    const { queue, root, remote } = await fixture();
    const first = input();
    const query = (submissionId: string) => [{ memo: first.memo, submissionId, attachmentHashes: [] }];
    try {
      expect(await queue.statuses(first.instance, first.owner, query('missing'))).toEqual([{ memo: first.memo, state: 'unknown' }]);
      expect(await readdir(root)).not.toContain('missing.json');
      await queue.enqueue(first);
      expect((await queue.statuses(first.instance, first.owner, query('submit-1')))[0]).toMatchObject({ state: 'pending', revision: 1 });
      await queue.deliver(remote, { enabled: true });
      const result = (await queue.statuses(first.instance, first.owner, query('submit-1')))[0];
      expect(result).toMatchObject({ state: 'verified', path: '00_Inbox/20260928-120000.md' });
      expect(result).toHaveProperty('commit');
      expect(JSON.stringify(result)).not.toContain('合成笔记');
      const before = await readdir(root);
      expect((await queue.statuses(first.instance, 'users/other', query('submit-1')))[0].state).toBe('unknown');
      const newer = (await queue.statuses(first.instance, first.owner, query('submit-2')))[0];
      expect(newer).toMatchObject({ state: 'unknown', previous: { revision: 1 } });
      expect(await readdir(root)).toEqual(before);
      await queue.enqueue(input(2, '新版本'));
      expect((await queue.statuses(first.instance, first.owner, query('submit-2')))[0]).toMatchObject({ state: 'pending', previous: { revision: 1 } });
    } finally { await queue.close(); }
  });

  it('does not report a receipt for different attachment bytes or malformed batches', async () => {
    const { queue, remote } = await fixture();
    const snapshot = inputWithAttachment();
    try {
      await queue.enqueue(snapshot);
      await queue.deliver(remote, { enabled: true });
      const lookup = { memo: snapshot.memo, submissionId: snapshot.delivery.submissionId,
        attachmentHashes: [snapshot.delivery.files[1].sha256] };
      expect((await queue.statuses('local', 'users/test', [lookup]))[0].state).toBe('verified');
      expect((await queue.statuses('local', 'users/test', [{ ...lookup, attachmentHashes: [] }]))[0].state).toBe('unknown');
      await expect(queue.statuses('local', 'users/test', Array(11).fill(lookup))).rejects.toMatchObject({ code: 'INVALID_STATUS_QUERY' });
      await expect(queue.statuses('local', 'users/test', [{ ...lookup, attachmentHashes: ['invalid'] }])).rejects.toMatchObject({ code: 'INVALID_STATUS_QUERY' });
    } finally { await queue.close(); }
  });
  it('normalizes the rolling-upgrade Memos prefix to 00_Inbox', async () => {
    const { queue } = await fixture();
    const submission = input();
    submission.delivery.files[0] = bundleFile('Memos/20260928-120000.md', Buffer.from('legacy prefix'));
    try {
      const record = await queue.enqueue(submission);
      expect(record.delivery.files[0].path).toBe('00_Inbox/20260928-120000.md');
    } finally { await queue.close(); }
  });

  it('persists an immutable, idempotent snapshot and rejects reused IDs or stale revisions', async () => {
    const { root, queue } = await fixture();
    try {
      const submission = input(); await queue.enqueue(submission);
      submission.delivery.files[0] = bundleFile('00_Inbox/20260928-120000.md', Buffer.from('later'));
      await expect(queue.enqueue(submission)).rejects.toMatchObject({ code: 'IDEMPOTENCY_CONFLICT' });
      expect((await queue.enqueue(input())).state).toBe('pending');
      expect((await readdir(root)).filter(name => name.endsWith('.json'))).toHaveLength(1);
      await queue.enqueue(input(3));
      await expect(queue.enqueue(input(2))).rejects.toMatchObject({ code: 'VERSION_CONFLICT' });
    } finally { await queue.close(); }
  });

  it('allocates adjacent timestamp paths for memos created in the same second and keeps them idempotent', async () => {
    const { queue } = await fixture();
    const first = input();
    const second = input();
    second.memo = 'memos/second';
    second.delivery.submissionId = 'submit-second';
    try {
      const storedFirst = await queue.enqueue(first);
      const storedSecond = await queue.enqueue(second);
      expect(storedFirst.delivery.files[0].path).toBe('00_Inbox/20260928-120000.md');
      expect(storedSecond.delivery.files[0].path).toBe('00_Inbox/20260928-120001.md');
      expect((await queue.enqueue(second)).delivery.files[0].path).toBe('00_Inbox/20260928-120001.md');
    } finally { await queue.close(); }
  });
  it('groups attachments under the final note name and rewrites Markdown to a vault-relative path', async () => {
    const { queue } = await fixture();
    const first = inputWithAttachment();
    const second = inputWithAttachment('memos/second');
    second.delivery.submissionId = 'attachment-second';
    try {
      const storedFirst = await queue.enqueue(first);
      const storedSecond = await queue.enqueue(second);
      expect(storedFirst.delivery.files.map(file => file.path)).toEqual([
        '00_Inbox/20260928-120000.md',
        'attachments/20260928-120000/20260928-115959.png',
      ]);
      expect(Buffer.from(storedFirst.delivery.files[0].base64, 'base64').toString()).toContain(
        '![](../attachments/20260928-120000/20260928-115959.png)',
      );
      expect(storedSecond.delivery.files.map(file => file.path)).toEqual([
        '00_Inbox/20260928-120001.md',
        'attachments/20260928-120001/20260928-115959.png',
      ]);
      expect((await queue.enqueue(second)).fingerprint).toBe(storedSecond.fingerprint);
    } finally { await queue.close(); }
  });
  it('keeps later revisions in the directory allocated to the original note', async () => {
    const { queue } = await fixture();
    try {
      await queue.enqueue(inputWithAttachment());
      const update = inputWithAttachment('memos/test', 2);
      const stored = await queue.enqueue(update);
      expect(stored.delivery.files[1].path).toBe('attachments/20260928-120000/20260928-115959.png');
    } finally { await queue.close(); }
  });
  it('waits for the verified parent and publishes a continuation link without changing the parent', async () => {
    const { queue, fake, remote } = await fixture();
    const parent = input();
    const child = input();
    child.memo = 'memos/child';
    child.parent = { memo: parent.memo };
    child.delivery.submissionId = 'submit-child';
    child.delivery.revision = 2;
    child.delivery.files[0] = bundleFile('00_Inbox/20260928-120001.md', Buffer.from('续写内容'));
    try {
      await queue.enqueue(child);
      expect(await queue.status(child, 'submit-child')).toMatchObject({ state: 'waiting_parent' });
      await queue.deliver(remote, { enabled: true });
      expect(fake.updates).toBe(0);

      await queue.enqueue(parent);
      await queue.deliver(remote, { enabled: true });
      expect(await queue.status(child, 'submit-child')).toMatchObject({ state: 'verified' });
      const tree = fake.trees.get(fake.commits.get(fake.head)!.tree.sha)!;
      const read = (name: string) => {
        const blob = tree[name];
        return fake.blobs.get(blob) ?? fake.binaryBlobs.get(blob)?.toString('utf8');
      };
      expect(read('00_Inbox/20260928-120000.md')).toBe('合成笔记');
      expect(read('00_Inbox/20260928-120001.md')).toBe('续写内容\n\n---\n续写自：[[20260928-120000]]');
    } finally { await queue.close(); }
  });
  it('rejects forged or self-referencing parent identities', async () => {
    const { queue } = await fixture();
    try {
      const forged = input(); forged.parent = { memo: '../outside' };
      await expect(queue.enqueue(forged)).rejects.toMatchObject({ code: 'INVALID_SUBMISSION' });
      const self = input(); self.parent = { memo: self.memo };
      await expect(queue.enqueue(self)).rejects.toMatchObject({ code: 'INVALID_SUBMISSION' });
    } finally { await queue.close(); }
  });
  it('does no delivery while disabled and never exposes another owner’s status', async () => {
    const { queue, fake, remote } = await fixture();
    try {
      await queue.enqueue(input()); await queue.deliver(remote, { enabled: false });
      expect(fake.updates).toBe(0);
      expect(await queue.status({ ...input(), owner: 'users/other' }, 'submit-1')).toBeUndefined();
    } finally { await queue.close(); }
  });
  it('recovers queued versions after reopening and verifies them in order', async () => {
    const { queue, root, fake, remote } = await fixture();
    await queue.enqueue(input()); await queue.enqueue(input(2, '第二版'));
    await queue.close();
    const reopened = await SubmissionQueue.create(root, process.cwd());
    try {
      await reopened.deliver(remote, { enabled: true });
      expect(fake.updates).toBe(2);
      expect(await reopened.status(input(), 'submit-2')).toMatchObject({ state: 'verified', revision: 2 });
      // Queue verification alone never deletes source content or claims desktop acknowledgement.
      const files = (await readdir(root)).filter(name => name.endsWith('.json'));
      expect(files).toHaveLength(2);
      for (const file of files) expect(JSON.parse(await readFile(path.join(root, file), 'utf8')).delivery.files).toHaveLength(1);
    } finally { await reopened.close(); }
  });
  it('persists the attempt before a lost ref response and resumes without duplicate commit', async () => {
    const { queue, root, fake, remote } = await fixture(); fake.losePatchReply = true;
    await queue.enqueue(input()); await queue.deliver(remote, { enabled: true, now: 0 }); await queue.close();
    const reopened = await SubmissionQueue.create(root, process.cwd());
    try {
      await reopened.deliver(remote, { enabled: true, now: 300001 });
      expect(await reopened.status(input(), 'submit-1')).toMatchObject({ state: 'verified' });
      expect(fake.updates).toBe(1);
    } finally { await reopened.close(); }
  });
  it('blocks subsequent versions when an earlier version conflicts with desktop edits', async () => {
    const { queue, fake, remote } = await fixture();
    try {
      await queue.enqueue(input()); await queue.deliver(remote, { enabled: true });
      fake.edit('00_Inbox/20260928-120000.md', 'edited on desktop');
      await queue.enqueue(input(2, 'new')); await queue.enqueue(input(3, 'newest'));
      await queue.deliver(remote, { enabled: true });
      expect(await queue.status(input(), 'submit-2')).toMatchObject({ state: 'conflict' });
      expect(await queue.status(input(), 'submit-3')).toMatchObject({ state: 'pending' });
      expect(fake.updates).toBe(1);
    } finally { await queue.close(); }
  });
  it('rejects a second process and project-contained/private data directories', async () => {
    const { queue, root } = await fixture();
    try {
      await queue.enqueue(input());
      await expect(SubmissionQueue.create(root, process.cwd())).rejects.toMatchObject({ code: 'EEXIST' });
      await expect(SubmissionQueue.create(process.cwd(), process.cwd())).rejects.toThrow('源码目录');
      const foreign = await mkdtemp(path.join(tmpdir(), 'memos-foreign-test-'));
      await writeFile(path.join(foreign, 'personal.md'), 'synthetic private marker');
      await expect(SubmissionQueue.create(foreign, process.cwd())).rejects.toThrow('非应用文件');
    } finally { await queue.close(); }
  });
});
