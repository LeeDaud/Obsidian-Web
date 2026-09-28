import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import type { Server } from 'node:http';
import { afterEach, expect, it } from 'vitest';
import { createApp, type AppConfig } from '../../src/server/app';
import { bundleFile } from '../../src/server/memos/bundle';
import { SubmissionQueue } from '../../src/server/memos/queue';
import { NoteStore } from '../../src/server/store';
import { CaptureVault } from '../../src/server/vault';

const servers: Server[] = [];
const queues: SubmissionQueue[] = [];
afterEach(async () => {
  for (const server of servers.splice(0)) await new Promise<void>(resolve => server.close(() => resolve()));
  for (const queue of queues.splice(0)) await queue.close();
});

async function setup() {
  const root = await mkdtemp(path.join(tmpdir(), 'memos-api-'));
  const store = await NoteStore.create(path.join(root, 'echo'), process.cwd());
  const vault = new CaptureVault(store, path.join(root, 'vault'));
  await vault.initialize();
  const queue = await SubmissionQueue.create(path.join(root, 'memos'), process.cwd()); queues.push(queue);
  const token = 'test-token-that-is-long-enough-for-service-use';
  const config: AppConfig = { origin: 'http://127.0.0.1', syncEnabled: false, project: process.cwd(), vault,
    memos: { queue, token } };
  const server = createApp(config).listen(0, '127.0.0.1'); servers.push(server);
  await new Promise<void>(resolve => server.once('listening', resolve));
  config.origin = 'http://127.0.0.1:' + (server.address() as { port: number }).port;
  return { config, token };
}

function submission() {
  return { instance: 'local', owner: 'users/1', memo: 'memos/1', delivery: { submissionId: 'submit-1', revision: 1,
    files: [bundleFile('20260928-153900.md', Buffer.from('中文快照'))] } };
}

it('requires the internal service credential and never returns note content', async () => {
  const { config, token } = await setup();
  const body = JSON.stringify(submission());
  const base = { method: 'POST', headers: { Host: new URL(config.origin).host, 'Content-Type': 'application/json' }, body };
  expect((await fetch(config.origin + '/api/memos/internal/submissions', base)).status).toBe(401);
  expect((await fetch(config.origin + '/api/memos/internal/submissions', { ...base,
    headers: { ...base.headers, Authorization: 'Bearer wrong' } })).status).toBe(401);
  const accepted = await fetch(config.origin + '/api/memos/internal/submissions', { ...base,
    headers: { ...base.headers, Authorization: `Bearer ${token}` } });
  expect(accepted.status).toBe(202);
  const text = await accepted.text();
  expect(text).not.toContain('中文快照');
  expect(JSON.parse(text)).toEqual({ submissionId: 'submit-1', state: 'pending', revision: 1 });
});

it('supports idempotent submit and owner-scoped status lookup', async () => {
  const { config, token } = await setup();
  const headers = { Host: new URL(config.origin).host, 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
  const url = config.origin + '/api/memos/internal/submissions';
  expect((await fetch(url, { method: 'POST', headers, body: JSON.stringify(submission()) })).status).toBe(202);
  expect((await fetch(url, { method: 'POST', headers, body: JSON.stringify(submission()) })).status).toBe(202);
  const query = '?instance=local&owner=users%2F1&memo=memos%2F1';
  const found = await fetch(url + '/submit-1' + query, { headers: { Host: new URL(config.origin).host, Authorization: `Bearer ${token}` } });
  expect(found.status).toBe(200);
  expect(await found.json()).toEqual({ state: 'pending', revision: 1, error: null });
  expect((await fetch(url + '/submit-1' + query.replace('users%2F1', 'users%2F2'), { headers })).status).toBe(404);
});
