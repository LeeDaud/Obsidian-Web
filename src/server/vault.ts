import { mkdir, readFile, rename, unlink, stat } from 'node:fs/promises';
import path from 'node:path';
import { AppError, digest, filename, publicStatus, validate, type SaveInput } from './model';
import { Mutex, type NoteStore } from './store';

// The filesystem mirror is disposable. The atomic queue is authoritative until delivery.
export class CaptureVault {
  private mutex = new Mutex();
  private startedAt: number;
  constructor(readonly store: NoteStore, readonly root: string, readonly graceMs = 120000,
    private now = () => Date.now()) { this.startedAt = now(); }

  async initialize() {
    await mkdir(this.root, { recursive: true });
    for (const note of await this.store.all()) {
      const file = path.join(this.root, note.filename);
      const temp = file + '.capture-tmp';
      if (note.retired) {
        for (const target of [file, temp]) await unlink(target).catch(e => { if (e.code !== 'ENOENT') throw e; });
      }
      else if (note.content === undefined) {
        const interrupted = await readFile(temp, 'utf8').catch(e => { if (e.code !== 'ENOENT') throw e; return undefined; });
        if (interrupted !== undefined) {
          if (digest(interrupted) !== note.hash) throw new Error('暂存镜像版本不一致，请保留文件并检查。');
          await rename(temp, file);
        }
      }
    }
  }
  async open(input: { id: string; createdAt: string }) {
    validate({ ...input, revision: 1, content: '' });
    return this.mutex.run(async () => {
      const existing = await this.store.read(input.id);
      const used = new Set((await this.store.all()).filter(note => note.id !== input.id).map(note => note.filename));
      let allocated = filename(input.createdAt, input.id);
      for (let offset = 1; used.has(allocated); offset++) allocated = filename(input.createdAt, input.id, offset);
      return this.store.mutate(input.id, old => {
        if (old?.retired) throw new AppError(410, 'RETIRED', '这篇笔记已入库，请新建想法。');
        if (old && old.createdAt !== input.createdAt) throw new AppError(409, 'VERSION_CONFLICT', '笔记标识已使用。');
        const note = old ?? { ...input, filename: existing?.filename ?? allocated, revision: 0,
          hash: digest(''), publishedRevision: 0 };
        note.lastActive = this.now();
        return { note, result: { ...publicStatus(note), createdAt: note.createdAt } };
      });
    });
  }
  async save(input: SaveInput) {
    validate(input);
    return this.mutex.run(async () => {
      const old = await this.store.read(input.id);
      if (!old) throw new AppError(404, 'NOT_FOUND', '请先创建会话。');
      if (old.retired) throw new AppError(410, 'RETIRED', '笔记已入库，请将新内容另存为新想法。');
      if (old.error === 'REMOTE_CONFLICT') throw new AppError(409, 'REMOTE_CONFLICT', '远端笔记已移动或修改，请另存为新想法。');
      if (old.revision === 0 && !input.content.trim()) return publicStatus(old);
      const saved = await this.store.save(input);
      if (!saved) return publicStatus(old);
      await this.store.mutate(input.id, note => {
        if (note) note.lastActive = this.now();
        return { note, result: undefined };
      });
      return publicStatus(saved);
    });
  }
  async heartbeat(id: string) {
    return this.mutex.run(async () => this.store.mutate(id, note => {
      if (!note) throw new AppError(404, 'NOT_FOUND', '会话不存在。');
      if (!note.retired) note.lastActive = this.now();
      return { note, result: { ...publicStatus(note), retired: !!note.retired } };
    }));
  }
  async clean() {
    // A restart gives live browsers time to renew their leases.
    if (this.now() - this.startedAt < this.graceMs) return;
    await this.mutex.run(async () => {
      for (const note of await this.store.all()) {
        if (note.retired || this.now() - (note.lastActive ?? this.startedAt) < this.graceMs ||
          note.revision === 0 || note.content !== undefined || note.delivery || note.error ||
          note.publishedRevision !== note.revision || !note.commit) continue;
        const file = path.join(this.root, note.filename);
        // Retire before unlink: interrupted cleanup cannot resurrect a desktop-moved note.
        await this.store.mutate(note.id, current => {
          if (current) current.retired = true;
          return { note: current, result: undefined };
        });
        await unlink(file).catch(e => { if (e.code !== 'ENOENT') throw e; });
      }
    });
  }
  async info(id: string) {
    const note = await this.store.read(id);
    if (!note) throw new AppError(404, 'NOT_FOUND', '笔记不存在。');
    return { ...publicStatus(note), retired: !!note.retired };
  }
  async content(id: string) {
    const note = await this.store.read(id);
    if (!note || note.retired) throw new AppError(404, 'NOT_FOUND', '笔记不存在。');
    return { content: note.content ?? '', revision: note.revision, hash: note.hash };
  }
  async nativeWrite(name: string, content: string, capture?: SaveInput) {
    const record = (await this.store.all()).find(n => n.filename === name);
    if (!record || record.retired) throw new AppError(410, 'RETIRED', '请使用新的时间戳笔记。');
    if (capture && capture.id === record.id && capture.content === content) await this.save(capture);
    else if (content !== '' || record.revision !== 0) {
      throw new AppError(409, 'VERSION_REQUIRED', '请从即时记录会话保存，避免覆盖其他版本。');
    }
    const meta = await stat(path.join(this.root, name)).catch(() => undefined);
    return { ok: true, mtime: meta?.mtimeMs ?? this.now(), size: Buffer.byteLength(content) };
  }
}
