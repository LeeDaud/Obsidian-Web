import { describe, expect, it } from 'vitest';
import { GitHubRemote } from '../../src/server/github';
import { bundleFile, bytesHash, type BundleAttempt, type BundleDelivery, validateBundle } from '../../src/server/memos/bundle';
import { FakeGitHub } from '../fakes';

const filename = '00_Inbox/20260928-120000.md';
function fixture() {
  const fake = new FakeGitHub();
  const image = Buffer.from([0, 255, 128, 13, 10, 0, 37]);
  const attachment = `attachments/memos/note-1/${bytesHash(image)}.png`;
  const delivery: BundleDelivery = { submissionId: 'submit-1', revision: 1, files: [
    bundleFile(filename, Buffer.from(`中文想法\n![](../${attachment})`)), bundleFile(attachment, image),
  ] };
  const remote = new GitHubRemote({ owner: 'test', repo: 'inbox', branch: 'main', token: 'synthetic' }, fake.fetch);
  const checkpoint = async (attempt: BundleAttempt) => { delivery.attempt = structuredClone(attempt); };
  return { fake, remote, delivery, attachment, image, checkpoint };
}
describe('Memos multi-file GitHub delivery', () => {
  it('accepts a legacy Memos record without rewriting its persisted identity', () => {
    const delivery: BundleDelivery = {
      submissionId: 'legacy-prefix', revision: 1,
      files: [bundleFile('Memos/20260928-173655.md', Buffer.from('legacy'))],
    };
    validateBundle(delivery);
    expect(delivery.files[0].path).toBe('Memos/20260928-173655.md');
  });
  it('commits Chinese text and exact binary bytes together after durable checkpoint', async () => {
    const f = fixture();
    const result = await f.remote.publishBundle(f.delivery, async attempt => {
      expect(f.fake.updates).toBe(0); await f.checkpoint(attempt);
    });
    expect(f.fake.updates).toBe(1);
    expect(f.fake.binaryBlobs.get(result.blobs[f.attachment])).toEqual(f.image);
    expect(Object.keys(result.blobs)).toEqual([filename, f.attachment]);
  });
  it('does not advance the branch if checkpoint persistence fails', async () => {
    const f = fixture();
    await expect(f.remote.publishBundle(f.delivery, async () => { throw new Error('disk failure'); })).rejects.toThrow('disk failure');
    expect(f.fake.updates).toBe(0);
  });
  it('recovers a lost response against immutable commit even after the desktop moves the file', async () => {
    const f = fixture(); f.fake.losePatchReply = true;
    await expect(f.remote.publishBundle(f.delivery, f.checkpoint)).rejects.toThrow('Lost reply');
    f.fake.edit(filename);
    const result = await f.remote.publishBundle(f.delivery, f.checkpoint);
    expect(result).toEqual(f.delivery.attempt); expect(f.fake.updates).toBe(1);
    expect(f.fake.trees.get(f.fake.commits.get(f.fake.head)!.tree.sha)?.[filename]).toBeUndefined();
  });
  it('blocks overwrites and moved-note resurrection on later submissions', async () => {
    const f = fixture(); const prior = await f.remote.publishBundle(f.delivery, f.checkpoint);
    const next: BundleDelivery = { ...f.delivery, submissionId: 'submit-2', revision: 2, attempt: undefined,
      files: f.delivery.files.map(file => ({ ...file, expectedBlob: prior.blobs[file.path] })) };
    f.fake.edit(filename, '电脑整理后的内容');
    await expect(f.remote.publishBundle(next, async () => {})).rejects.toMatchObject({ code: 'REMOTE_CONFLICT' });
    f.fake.edit(filename);
    await expect(f.remote.publishBundle(next, async () => {})).rejects.toMatchObject({ code: 'REMOTE_CONFLICT' });
  });
  it('fails verification if an attachment is corrupt or the tree omits it', async () => {
    const f = fixture();
    const transport: typeof fetch = async (url, init) => {
      const response = await f.fake.fetch(url, init);
      if (String(url).includes('/git/blobs/') && init?.method === 'GET') {
        const blob = await response.json(); blob.content = Buffer.from('corrupt').toString('base64');
        return Response.json(blob);
      }
      return response;
    };
    const remote = new GitHubRemote({ owner: 'test', repo: 'inbox', branch: 'main', token: 'synthetic' }, transport);
    await expect(remote.publishBundle(f.delivery, f.checkpoint)).rejects.toMatchObject({ code: 'VERIFY_FAILED' });
    expect(f.delivery.attempt).toBeDefined();
    const committedTree = f.fake.trees.get(f.fake.commits.get(f.fake.head)!.tree.sha)!;
    delete committedTree[f.attachment];
    await expect(f.remote.publishBundle(f.delivery, f.checkpoint)).rejects.toMatchObject({ code: 'VERIFY_FAILED' });
  });
  it('does not force a concurrent Echo commit out of the branch', async () => {
    const f = fixture(); f.fake.moveBeforePatch = true;
    await expect(f.remote.publishBundle(f.delivery, f.checkpoint)).rejects.toMatchObject({ code: 'REMOTE_CONFLICT' });
    expect(f.fake.updates).toBe(0);
  });
  it('rejects traversal, ambiguous paths, oversized text and altered snapshots before network I/O', async () => {
    const f = fixture(); let calls = 0;
    const remote = new GitHubRemote({ owner: 'test', repo: 'inbox', branch: 'main', token: 'synthetic' }, async () => { calls++; throw new Error(); });
    for (const path of ['../private.md', 'Memos/../private.md', '/00_Inbox/20260928-120000.md', 'Memos\\00_Inbox/20260928-120000.md']) {
      const files = [bundleFile(path, Buffer.from('x'))];
      await expect(remote.publishBundle({ ...f.delivery, files }, f.checkpoint)).rejects.toMatchObject({ code: 'INVALID_BUNDLE' });
    }
    await expect(remote.publishBundle({ ...f.delivery, files: [bundleFile(filename, Buffer.alloc(256 * 1024 + 1))] }, f.checkpoint))
      .rejects.toMatchObject({ code: 'TOO_LARGE' });
    f.delivery.files[0].sha256 = 'invalid';
    await expect(remote.publishBundle(f.delivery, f.checkpoint)).rejects.toMatchObject({ code: 'INVALID_BUNDLE' });
    expect(calls).toBe(0);
  });
  it('keeps existing files untouched if a recursive tree is truncated', async () => {
    const f = fixture();
    const remote = new GitHubRemote({ owner: 'test', repo: 'inbox', branch: 'main', token: 'synthetic' }, async (url, init) => {
      if (String(url).includes('/git/trees/')) return Response.json({ truncated: true, tree: [] });
      return f.fake.fetch(url, init);
    });
    await expect(remote.publishBundle(f.delivery, f.checkpoint)).rejects.toMatchObject({ code: 'VERIFY_FAILED' });
    expect(f.fake.updates).toBe(0);
  });
});
