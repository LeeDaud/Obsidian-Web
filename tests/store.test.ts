import { mkdtemp, readFile, readdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { NoteStore } from '../src/server/store';
import { filename, type SaveInput } from '../src/server/model';
import { DeliveryWorker } from '../src/server/worker';
import { GitHubRemote } from '../src/server/github';
import { FakeGitHub, MemoryRemote } from './fakes';

async function fixture() {
  const dir = await mkdtemp(path.join(tmpdir(), 'capture-test-'));
  return NoteStore.create(dir, process.cwd());
}
function input(content = '即时想法'): SaveInput { return { id: randomUUID(), createdAt: '2026-09-20T06:30:52.381Z', revision: 1, content }; }
describe('durable capture', () => {
  it('does not create files for blank input, but can clear an existing note', async () => {
    const store = await fixture(); const note = input('  \n');
    expect(await store.save(note)).toBeUndefined(); expect(await readdir(store.root)).toEqual([]);
    await store.save({ ...note, content: 'hello', revision: 2 });
    expect((await store.save({ ...note, content: '', revision: 3 }))?.content).toBe('');
  });
  it('names notes to the second in Shanghai time and advances simultaneous notes', async () => {
    const store = await fixture();
    const a = input(); const b = input();
    expect(filename(a.createdAt)).toBe('20260920-143052.md');
    expect((await store.save(a))?.filename).toBe('20260920-143052.md');
    expect((await store.save(b))?.filename).toBe('20260920-143053.md');
    expect(a.id).not.toBe(b.id);
  });
  it('rejects traversal, bad dates, oversized input and stale versions', async () => {
    const store = await fixture(); const note = input(); await store.save(note);
    await expect(store.save({ ...note, id: '../escape' })).rejects.toMatchObject({ code: 'INVALID_NOTE' });
    await expect(store.save({ ...note, createdAt: 'not a date' })).rejects.toMatchObject({ code: 'INVALID_NOTE' });
    await expect(store.save({ ...note, content: '字'.repeat(100000) })).rejects.toMatchObject({ code: 'TOO_LARGE' });
    await store.save({ ...note, revision: 3, content: '新内容' });
    await expect(store.save({ ...note, revision: 2 })).rejects.toMatchObject({ code: 'VERSION_CONFLICT' });
    await expect(store.save({ ...note, revision: 3 })).rejects.toMatchObject({ code: 'VERSION_CONFLICT' });
    expect((await store.read(note.id))?.content).toBe('新内容');
  });
  it('replays identical saves without recreating cleaned content, survives reopening', async () => {
    const store = await fixture(); const note = input(); await store.save(note);
    await new DeliveryWorker(store, new MemoryRemote()).tick();
    const opened = await NoteStore.create(store.root, process.cwd());
    expect((await opened.save(note))?.content).toBeUndefined();
    expect(await readFile(path.join(store.root, note.id + '.json'), 'utf8')).not.toContain(note.content);
  });
  it('retains newer text when an older upload completes', async () => {
    const store = await fixture(); const note = input(); await store.save(note);
    let release!: () => void; let started!: () => void;
    const ready = new Promise<void>(resolve => { started = resolve; });
    const waiting = new Promise<void>(resolve => { release = resolve; });
    const remote = new MemoryRemote();
    const worker = new DeliveryWorker(store, { publish: async (file, delivery) => { started(); await waiting; return remote.publish(file, delivery); } });
    const job = worker.tick(); await ready;
    await store.save({ ...note, revision: 2, content: '后来的想法' }); release(); await job;
    expect((await store.read(note.id))?.content).toBe('后来的想法');
    expect((await store.read(note.id))?.publishedRevision).toBe(1);
    await worker.tick(); expect((await store.read(note.id))?.content).toBeUndefined();
    expect([...remote.files.values()][0].content).toBe('后来的想法');
  });
  it('retains queue on network failure and does not revive moved notes', async () => {
    const store = await fixture(); const note = input(); await store.save(note);
    await new DeliveryWorker(store, { publish: async () => { throw new Error('offline'); } }).tick();
    expect((await store.read(note.id))?.content).toBe(note.content);
    await store.mutate(note.id, record => ({ note: { ...record!, retryAt: 0 }, result: undefined }));
    const remote = new MemoryRemote(); const worker = new DeliveryWorker(store, remote); await worker.tick();
    remote.files.clear(); await store.save({ ...note, revision: 2, content: '旧页面编辑' }); await worker.tick();
    expect(remote.files.size).toBe(0); expect((await store.read(note.id))?.error).toBe('REMOTE_CONFLICT');
    expect((await store.read(note.id))?.content).toBe('旧页面编辑');
  });
  it('refuses project-contained data and non-application directories', async () => {
    await expect(NoteStore.create(process.cwd(), process.cwd())).rejects.toThrow('源码目录');
    const dir = await mkdtemp(path.join(tmpdir(), 'capture-unsafe-')); await writeFile(path.join(dir, 'personal.md'), 'private');
    await expect(NoteStore.create(dir, process.cwd())).rejects.toThrow('非应用文件');
  });
});
describe('GitHub transaction', () => {
  const adapter = (fake: FakeGitHub) => new GitHubRemote({ owner: 'test', repo: 'inbox', branch: 'main', token: 'fake-token' }, fake.fetch);
  it('verifies immutable commit content before cleaning server text', async () => {
    const store = await fixture(); const note = input(); await store.save(note);
    const fake = new FakeGitHub(); await new DeliveryWorker(store, adapter(fake)).tick();
    const record = (await store.read(note.id))!;
    expect(record.publishedRevision).toBe(1); expect(record.content).toBeUndefined(); expect(record.delivery).toBeUndefined();
    expect(fake.updates).toBe(1); expect([...fake.blobs.values()]).toContain(note.content);
  });
  it('recovers a lost ref-update reply after restart, even if desktop already removed the note', async () => {
    const store = await fixture(); const note = input(); await store.save(note);
    const fake = new FakeGitHub(); fake.losePatchReply = true;
    await new DeliveryWorker(store, adapter(fake)).tick();
    const record = (await store.read(note.id))!; expect(record.delivery?.attempt).toBeDefined();
    fake.edit(record.filename);
    await store.mutate(note.id, r => ({ note: { ...r!, retryAt: 0 }, result: undefined }));
    const reopened = await NoteStore.create(store.root, process.cwd());
    await new DeliveryWorker(reopened, adapter(fake)).tick();
    expect((await reopened.read(note.id))?.publishedRevision).toBe(1);
    expect(fake.updates).toBe(1);
    expect(fake.trees.get(fake.commits.get(fake.head)!.tree.sha)?.[record.filename]).toBeUndefined();
  });
  it('does not overwrite a concurrent branch update', async () => {
    const store = await fixture(); const note = input(); await store.save(note);
    const fake = new FakeGitHub(); fake.moveBeforePatch = true;
    await new DeliveryWorker(store, adapter(fake)).tick();
    expect((await store.read(note.id))?.content).toBe(note.content);
    expect((await store.read(note.id))?.error).toBe('REMOTE_CONFLICT'); expect(fake.updates).toBe(0);
  });
  it('does not clean text if the immutable content check fails', async () => {
    const store = await fixture(); const note = input(); await store.save(note);
    const fake = new FakeGitHub();
    const transport: typeof fetch = async (url, init) => {
      const response = await fake.fetch(url, init);
      if (String(url).includes('/contents/') && response.ok) {
        const file = await response.json(); file.content = Buffer.from('wrong response').toString('base64');
        return new Response(JSON.stringify(file), { status: 200 });
      }
      return response;
    };
    const remote = new GitHubRemote({ owner: 'test', repo: 'inbox', branch: 'main', token: 'fake' }, transport);
    await new DeliveryWorker(store, remote).tick();
    expect((await store.read(note.id))?.error).toBe('VERIFY_FAILED');
    expect((await store.read(note.id))?.content).toBe(note.content);
    expect((await store.read(note.id))?.publishedRevision).toBe(0);
  });
});
