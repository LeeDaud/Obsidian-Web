import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

process.env.CAPTURE_DATA_DIR = await mkdtemp(path.join(tmpdir(), 'capture-browser-'));
process.env.PORT = '4320';
process.env.CAPTURE_ORIGIN = 'http://127.0.0.1:4320';
process.env.GITHUB_SYNC_ENABLED = 'false';
if (!process.env.OBSIDIAN_ASSETS_PATH) throw new Error('浏览器测试需要真实 OBSIDIAN_ASSETS_PATH。');
await import('../src/server/main');
