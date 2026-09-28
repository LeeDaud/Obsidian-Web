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
  delivery: { submissionId: `submit-${revision}`, revision, files: [bundleFile('Memos/20260928-120000.md', Buffer.from(content))] },
});
async function fixture() {
  const root = await mkdtemp(path.join(tmpdir(), 'memos-queue-test-'));
  const queue = await SubmissionQueue.create(root, process.cwd());
  const fake = new FakeGitHub();
  const remote = new GitHubRemote({ owner: 'test', repo: 'inbox', branch: 'main', token: 'fake' }, fake.fetch);
  return { root, queue, fake, remote };
}
describe('explicit Memos submission queue', () => {
  it('persists an immutable, idempotent snapshot and rejects reused IDs or stale revisions', async () => {
    const { root, queue } = await fixture();
    try {
      const submission = input(); await queue.enqueue(submission);
      submission.delivery.files[0] = bundleFile('Memos/20260928-120000.md', Buffer.from('later'));
      await expect(queue.enqueue(submission)).rejects.toMatchObject({ code: 'IDEMPOTENCY_CONFLICT' });
      expect((await queue.enqueue(input())).state).toBe('pending');
      expect((await readdir(root)).filter(name => name.endsWith('.json'))).toHaveLength(1);
      await queue.enqueue(input(3));
      await expect(queue.enqueue(input(2))).rejects.toMatchObject({ code: 'VERSION_CONFLICT' });
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
      fake.edit('Memos/20260928-120000.md', 'edited on desktop');
      await queue.enqueue(input(2, 'new')); await queue.enqueue(input(3, 'newest'));
      await queue.deliver(remote, { enabled: true });
      expect(await queue.status(input(), 'submit-2')).toMatchObject({ state: 'conflict' });
      expect(await queue.status(input(), 'submit-3')).toMatchObject({ state: 'pending' });
      expect(fake.updates).toBe(1);
    } finally { await queue.close(); }
  });
  it('rejects duplicate source paths, a second process and project-contained/private data directories', async () => {
    const { queue, root } = await fixture();
    try {
      await queue.enqueue(input());
      await expect(queue.enqueue({ ...input(), memo: 'memos/other' })).rejects.toMatchObject({ code: 'PATH_CONFLICT' });
      await expect(SubmissionQueue.create(root, process.cwd())).rejects.toMatchObject({ code: 'EEXIST' });
      await expect(SubmissionQueue.create(process.cwd(), process.cwd())).rejects.toThrow('源码目录');
      const foreign = await mkdtemp(path.join(tmpdir(), 'memos-foreign-test-'));
      await writeFile(path.join(foreign, 'personal.md'), 'synthetic private marker');
      await expect(SubmissionQueue.create(foreign, process.cwd())).rejects.toThrow('非应用文件');
    } finally { await queue.close(); }
  });
});
