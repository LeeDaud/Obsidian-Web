import express from 'express';
import type { IncomingMessage } from 'node:http';
import path from 'node:path';
import { AppError } from './model';
import type { CaptureVault } from './vault';

export interface AppConfig { vault: CaptureVault; syncEnabled: boolean; origin: string; project: string }
export function authorized(req: Pick<IncomingMessage, 'headers'>, config: AppConfig) {
  return req.headers.host === new URL(config.origin).host;
}
export function guard(config: AppConfig): express.RequestHandler {
  return (req, res, next) => {
    res.set({ 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'no-referrer', 'X-Frame-Options': 'DENY' });
    if (!authorized(req, config)) {
      return void res.status(421).json({ error: '访问地址不匹配。' });
    }
    if (!['GET', 'HEAD', 'OPTIONS'].includes(req.method) &&
      (req.headers.origin !== config.origin || (req.method !== 'DELETE' && !req.is('application/json')))) {
      return void res.status(403).json({ error: '只允许同源 JSON 请求。' });
    }
    next();
  };
}
export function mount(app: express.Express, config: AppConfig) {
  const handle = (fn: (req: express.Request, res: express.Response, next: express.NextFunction) => Promise<unknown>): express.RequestHandler =>
    (req, res, next) => { void fn(req, res, next).catch(next); };
  app.get('/capture.js', (_req, res) => res.sendFile(path.join(config.project, 'src/server/capture-client.js')));
  app.get('/api/capture/status', (_req, res) => res.json({ syncEnabled: config.syncEnabled }));
  app.post('/api/capture/open', handle(async (req, res) => res.json(await config.vault.open(req.body))));
  app.post('/api/capture/heartbeat', handle(async (req, res) => res.json(await config.vault.heartbeat(req.body.id))));
  app.get('/api/capture/note/:id', handle(async (req, res) => res.json(await config.vault.info(String(req.params.id)))));
  app.get('/api/capture/note/:id/content', handle(async (req, res) => res.json(await config.vault.content(String(req.params.id)))));
  app.use('/api/fs', handle(async (req, res, next) => {
    // Only original settings writes are forwarded. Every note write is versioned.
    const raw = req.body?.path ?? req.query.path ?? '';
    const name = typeof raw === 'string' ? raw.replace(/^\/+/, '') : '';
    if (name && (name.includes('\\') || name !== path.posix.normalize(name))) {
      throw new AppError(403, 'PATH_DENIED', '路径必须规范化。');
    }
    if (name === '.OBSIDIANTEST') return res.json({ ok: true, mtime: Date.now(), size: 0 });
    if (req.method === 'POST' && req.path === '/writeFile' && name.endsWith('.md') && !name.includes('/')) {
      if (req.body.vault !== 'Inbox') throw new AppError(403, 'VAULT_DENIED', '仅允许临时输入库。');
      const content = req.body.base64 ? Buffer.from(req.body.content, 'base64').toString('utf8') : req.body.content;
      if (typeof content !== 'string') throw new AppError(400, 'INVALID_NOTE', '笔记格式错误。');
      return res.json(await config.vault.nativeWrite(name, content, req.body.capture));
    }
    next();
  }));
  // The middleware above handles managed writes; all other methods need explicit forwarding.
  app.use((req, res, next) => {
    if (req.path.startsWith('/api/fs') && !['GET', 'HEAD'].includes(req.method)) {
      const name = String(req.body?.path ?? req.query.path ?? '').replace(/^\/+/, '');
      const settings = name === '.obsidian' || name.startsWith('.obsidian/');
      const read = req.path === '/api/fs/batch-read';
      if (!settings && !read) return void res.status(403).json({ error: '此库仅用于时间戳输入；整理请在电脑端进行。' });
    }
    if (req.path.startsWith('/api/vault') && !['GET', 'HEAD'].includes(req.method)) {
      return void res.status(403).json({ error: '已固定为临时输入库。' });
    }
    // File recovery would retain a second hidden copy of note text.
    if (req.path.startsWith('/api/plugins') && !['GET', 'HEAD'].includes(req.method)) {
      return void res.status(403).json({ error: '输入库不启用额外同步或备份插件。' });
    }
    next();
  });
}
export function errors(): express.ErrorRequestHandler {
  return (error: unknown, _req, res, _next) => {
    if (error instanceof AppError) return void res.status(error.status).json({ error: error.message, code: error.code });
    res.status(500).json({ error: '暂存失败，草稿保留在当前设备。', code: 'SAVE_FAILED' });
  };
}
export function createApp(config: AppConfig) {
  const app = express();
  app.use(guard(config), express.json({ limit: '2mb' }));
  mount(app, config);
  app.use(errors());
  return app;
}
