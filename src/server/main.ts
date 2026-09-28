import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { mkdir, open, unlink, readFile, writeFile, readdir, realpath } from 'node:fs/promises';
import { guard, mount, errors, authorized, type AppConfig } from './app';
import { NoteStore } from './store';
import { CaptureVault } from './vault';
import { GitHubRemote } from './github';
import { DeliveryWorker } from './worker';
import { SubmissionQueue } from './memos/queue';
import type { IncomingMessage } from 'node:http';
import type express from 'express';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const host = process.env.CAPTURE_HOST ?? '127.0.0.1';
const port = Number(process.env.PORT ?? 4319);
const origin = process.env.CAPTURE_ORIGIN ?? 'http://127.0.0.1:' + port;
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('PORT 无效。');
if (new URL(origin).origin !== origin) throw new Error('CAPTURE_ORIGIN 只能包含协议、主机和端口。');
if ((!['127.0.0.1', 'localhost', '::1'].includes(host) || !['127.0.0.1', 'localhost', '[::1]'].includes(new URL(origin).hostname)) &&
    new URL(origin).protocol !== 'https:') throw new Error('远程访问必须使用 HTTPS 域名和 TLS 反向代理。');
const root = process.env.CAPTURE_DATA_DIR;
const assets = process.env.OBSIDIAN_ASSETS_PATH;
if (!root || !path.isAbsolute(root) || !assets || !path.isAbsolute(assets)) throw new Error('必须指定项目外绝对路径 CAPTURE_DATA_DIR 和 OBSIDIAN_ASSETS_PATH。');
const outside = (base: string, target: string) => {
  const rel = path.relative(base, target);
  return rel === '..' || rel.startsWith('..' + path.sep) || path.isAbsolute(rel);
};
if (!outside(project, path.resolve(root))) throw new Error('暂存目录不能位于源码目录内。');
await mkdir(root, { recursive: true });
const runtime = await realpath(root);
if (!outside(await realpath(project), runtime)) throw new Error('暂存目录解析后位于源码目录内。');
if ((await readdir(runtime)).some(n => !['queue', 'vaults', 'runtime'].includes(n))) throw new Error('请使用应用专用空目录，不能使用个人知识库。');
const store = await NoteStore.create(path.join(runtime, 'queue'), project);
const vaultRoot = path.join(runtime, 'vaults');
await mkdir(vaultRoot, { recursive: true });
if ((await readdir(vaultRoot)).some(n => n !== 'Inbox')) throw new Error('临时目录仅允许 Inbox，不能加载其他库。');
const vault = new CaptureVault(store, path.join(vaultRoot, 'Inbox'));
const memosQueueDir = process.env.MEMOS_QUEUE_DIR;
const memosToken = process.env.MEMOS_BRIDGE_TOKEN;
if (!!memosQueueDir !== !!memosToken) throw new Error('MEMOS_QUEUE_DIR 与 MEMOS_BRIDGE_TOKEN 必须同时配置。');
if (memosToken && memosToken.length < 32) throw new Error('MEMOS_BRIDGE_TOKEN 至少需要 32 个字符。');
const memosQueue = memosQueueDir ? await SubmissionQueue.create(memosQueueDir, project) : undefined;
const enabled = process.env.GITHUB_SYNC_ENABLED === 'true';
const repository = process.env.GITHUB_REPOSITORY ?? '';
const branch = process.env.GITHUB_BRANCH ?? 'main';
if (enabled && (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository) || !process.env.GITHUB_TOKEN)) throw new Error('请配置 GitHub 仓库与服务器端 token。');
const ownerPath = path.join(store.root, 'owner.json');
const binding = JSON.stringify({ repo: repository, branch });
try {
  if (await readFile(ownerPath, 'utf8') !== binding) throw new Error('队列已绑定其他仓库；请勿重用。');
} catch (error) {
  if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
  if (repository) await writeFile(ownerPath, binding, { flag: 'wx', mode: 0o600 });
}
const lock = await open(path.join(store.root, 'instance.lock'), 'wx', 0o600);
await lock.writeFile(String(process.pid));
await store.recoverTemps();
await vault.initialize();
const settingsDir = path.join(vault.root, '.obsidian');
await mkdir(settingsDir, { recursive: true });
// Native recovery snapshots must not retain delivered note bodies.
await writeFile(path.join(settingsDir, 'core-plugins.json'), JSON.stringify(['file-explorer', 'global-search', 'switcher', 'backlink', 'outgoing-link', 'tag-pane', 'page-preview', 'command-palette', 'editor-status', 'word-count']));
await writeFile(path.join(settingsDir, 'community-plugins.json'), '[]');
const [owner, repo] = repository.split('/');
const worker = enabled ? new DeliveryWorker(store, new GitHubRemote({ owner, repo, branch, token: process.env.GITHUB_TOKEN! })) : undefined;
const config: AppConfig = { vault, syncEnabled: enabled, origin, project,
  memos: memosQueue ? { queue: memosQueue, token: memosToken! } : undefined };
let running: Promise<void> | undefined;
const tick = () => {
  if (running) return;
  running = (async () => { await worker?.tick(); await vault.clean(); })()
    .catch(() => console.error('投递或清理失败；未确认正文继续保留。')).finally(() => { running = undefined; });
};
const interval = setInterval(tick, 10000);
Object.assign(process.env, { VAULT_ROOT: vaultRoot, DATA_ROOT: path.join(runtime, 'runtime'),
  OBSIDIAN_ASSETS_PATH: assets, PORT: String(port), CAPTURE_HOST: host, WS_ORIGINS: origin, AUTO_CREATE_DEFAULT: 'false' });
Object.assign(globalThis, { __ignisCapture: {
  guard: guard(config), mount: (app: express.Express) => mount(app, config), errors: errors(),
  verifyClient: ({ req, origin: wsOrigin }: { req: IncomingMessage; origin: string }) => authorized(req, config) && wsOrigin === origin,
  stop: async () => { clearInterval(interval); await running; await worker?.waitForIdle(); await memosQueue?.close(); await lock.close(); await unlink(path.join(store.root, 'instance.lock')); }
} });
console.info('Ignis 原版输入入口：' + origin + '；GitHub 投递' + (enabled ? '已启用' : '未启用'));
createRequire(import.meta.url)(path.join(project, 'upstream/ignis/apps/ignis-server/server/index.js'));
