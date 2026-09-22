import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import type { Server } from 'node:http';
import { afterEach, expect, it } from 'vitest';
import { createApp, type AppConfig, authorized } from '../src/server/app';
import { NoteStore } from '../src/server/store';
import { CaptureVault } from '../src/server/vault';

const servers: Server[] = [];
afterEach(async () => { for (const server of servers.splice(0)) await new Promise<void>(resolve => server.close(() => resolve())); });
async function setup() {
  const root = await mkdtemp(path.join(tmpdir(), 'capture-api-'));
  const store = await NoteStore.create(path.join(root, 'queue'), process.cwd());
  const vault = new CaptureVault(store, path.join(root, 'vault'));
  await vault.initialize();
  const config: AppConfig = { origin: 'http://127.0.0.1', syncEnabled: false, project: process.cwd(), vault };
  const server = createApp(config).listen(0, '127.0.0.1'); servers.push(server);
  await new Promise<void>(resolve => server.once('listening', resolve));
  config.origin = 'http://127.0.0.1:' + (server.address() as { port: number }).port;
  return config;
}
it('serves the original UI without login and protects note writes with host and same-origin checks', async () => {
  const config = await setup();
  expect((await fetch(config.origin + '/api/capture/status')).status).toBe(200);
  const headers = { 'Content-Type': 'application/json', Origin: config.origin };
  const input = { id: randomUUID(), createdAt: new Date().toISOString() };
  expect((await fetch(config.origin + '/api/capture/open', { method: 'POST', headers: { ...headers, Origin: 'https://evil.invalid' }, body: JSON.stringify(input) })).status).toBe(403);
  const opened = await fetch(config.origin + '/api/capture/open', { method: 'POST', headers, body: JSON.stringify(input) });
  expect(opened.status).toBe(200);
  const { filename } = await opened.json();
  const body = { vault: 'Inbox', path: filename, content: 'private text', capture: { ...input, revision: 1, content: 'private text' } };
  const saved = await fetch(config.origin + '/api/fs/writeFile', { method: 'POST', headers, body: JSON.stringify(body) });
  expect(saved.status).toBe(200); expect(await saved.text()).not.toContain(body.content);
  expect((await fetch(config.origin + '/api/capture/status', { headers })).headers.get('cache-control')).toBe('no-store');
  expect(authorized({ headers: { host: 'wrong.invalid' } }, config)).toBe(false);
  expect(authorized({ headers: { host: new URL(config.origin).host } }, config)).toBe(true);
});
it('rejects unversioned note overwrites and vault mutation', async () => {
  const config = await setup();
  const headers = { 'Content-Type': 'application/json', Origin: config.origin };
  const input = { id: randomUUID(), createdAt: new Date().toISOString() };
  const opened = await fetch(config.origin + '/api/capture/open', { method: 'POST', headers, body: JSON.stringify(input) });
  const { filename } = await opened.json();
  const response = await fetch(config.origin + '/api/fs/writeFile', { method: 'POST', headers, body: JSON.stringify({ vault: 'Inbox', path: filename, content: 'overwrite' }) });
  expect(response.status).toBe(409);
  expect((await fetch(config.origin + '/api/vault/create', { method: 'POST', headers, body: '{}' })).status).toBe(403);
});
