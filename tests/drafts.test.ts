import { mkdtemp, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { expect, it } from 'vitest';
import { NoteStore } from '../src/server/store';
import { CaptureVault } from '../src/server/vault';
import { DeliveryWorker } from '../src/server/worker';
import { MemoryRemote } from './fakes';

async function setup() {
  let clock = 10000;
  const root = await mkdtemp(path.join(tmpdir(), 'capture-native-'));
  const store = await NoteStore.create(path.join(root, 'queue'), process.cwd());
  const vault = new CaptureVault(store, path.join(root, 'vault'), 100, () => clock);
  await vault.initialize();
  const remote = new MemoryRemote();
  const worker = new DeliveryWorker(store, remote);
  const input = { id: randomUUID(), createdAt: new Date().toISOString(), content: '即时想法', revision: 1 };
  const opened = await vault.open(input);
  return { store, vault, remote, worker, input, opened, advance: (n = 101) => { clock += n; } };
}
it('uses a native virtual blank file without persisting empty Markdown', async () => {
  const { vault, opened } = await setup();
  await vault.nativeWrite(opened.filename, '');
  expect(await readdir(vault.root)).toEqual([]);
});
it('keeps active files after backup and retires only verified inactive versions', async () => {
  const { vault, worker, input, advance } = await setup();
  await vault.save(input);
  await worker.tick();
  advance(50); await vault.heartbeat(input.id); advance(51);
  await vault.clean();
  expect(await readdir(vault.root)).toEqual([]);
  expect((await vault.info(input.id)).retired).toBe(false);
  advance(); await vault.clean();
  expect(await readdir(vault.root)).toEqual([]);
  expect((await vault.info(input.id)).retired).toBe(true);
  await expect(vault.save({ ...input, content: '旧页面不能复活文件', revision: 2 })).rejects.toMatchObject({ code: 'RETIRED' });
});
it('never clears failed delivery or newer text, and preserves native save order', async () => {
  const { vault, worker, input, advance, store } = await setup();
  await vault.save(input); await worker.tick();
  await vault.save({ ...input, revision: 2, content: '新的正文' });
  advance(); await vault.clean();
  expect(await readdir(vault.root)).toEqual([]);
  await expect(vault.save(input)).rejects.toMatchObject({ code: 'VERSION_CONFLICT' });
  expect((await store.read(input.id))?.content).toBe('新的正文');
});
it('restarts from the durable queue without creating a filesystem mirror', async () => {
  const { vault, store, input } = await setup();
  await store.save(input);
  await vault.initialize();
  expect(await readdir(vault.root)).toEqual([]);
  expect((await store.read(input.id))?.content).toBe(input.content);
});
