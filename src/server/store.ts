import { mkdir, open, readFile, readdir, realpath, rename, unlink, lstat } from 'node:fs/promises';
import path from 'node:path';
import { AppError, digest, filename, ID_PATTERN, type NoteRecord, type SaveInput, validate } from './model';

export class Mutex {
  private tail: Promise<unknown> = Promise.resolve();
  run<T>(fn: () => Promise<T>): Promise<T> {
    const result = this.tail.then(fn);
    this.tail = result.catch(() => undefined);
    return result;
  }
}
export class NoteStore {
  private mutex = new Mutex();
  private constructor(readonly root: string) {}
  static async create(root: string, projectRoot: string) {
    if (!root || !path.isAbsolute(root)) throw new Error('CAPTURE_DATA_DIR 必须是项目外的绝对路径。');
    const project = await realpath(projectRoot);
    const relative = path.relative(project, path.resolve(root));
    if (!relative || (!relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative))) {
      throw new Error('临时数据目录不能位于源码目录内。');
    }
    await mkdir(root, { recursive: true, mode: 0o700 });
    const resolved = await realpath(root);
    const rel = path.relative(project, resolved);
    if (!rel || (!rel.startsWith('..' + path.sep) && rel !== '..' && !path.isAbsolute(rel))) throw new Error('临时数据目录解析后位于源码目录内。');
    // This is an exclusive application-owned directory, never a user's vault.
    const existing = await readdir(resolved);
    if (existing.some(name => name !== 'owner.json' && name !== 'instance.lock' &&
      !(name.endsWith('.json') && ID_PATTERN.test(name.slice(0, -5))) &&
      !(name.endsWith('.tmp') && ID_PATTERN.test(name.slice(0, -4))))) throw new Error('临时目录含非应用文件，请使用独立空目录。');
    return new NoteStore(resolved);
  }
  private file(id: string) {
    if (!ID_PATTERN.test(id)) throw new AppError(400, 'INVALID_ID', '笔记标识无效。');
    return path.join(this.root, id + '.json');
  }
  async read(id: string): Promise<NoteRecord | undefined> {
    try {
      const file = this.file(id);
      if (!(await lstat(file)).isFile()) throw new Error('Unsafe record');
      return JSON.parse(await readFile(file, 'utf8')) as NoteRecord;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return undefined;
      throw error;
    }
  }
  private async write(record: NoteRecord) {
    const temp = path.join(this.root, record.id + '.tmp');
    const handle = await open(temp, 'wx', 0o600);
    try {
      try { await handle.writeFile(JSON.stringify(record)); await handle.sync(); }
      finally { await handle.close(); }
      await rename(temp, this.file(record.id));
    } finally {
      await unlink(temp).catch(error => { if (error.code !== 'ENOENT') throw error; });
    }
    if (process.platform !== 'win32') {
      const directory = await open(this.root, 'r');
      try { await directory.sync(); } finally { await directory.close(); }
    }
  }
  async recoverTemps() {
    // A .tmp write was never acknowledged. The durable .json remains authoritative.
    for (const name of await readdir(this.root)) {
      if (name.endsWith('.tmp') && ID_PATTERN.test(name.slice(0, -4))) await unlink(path.join(this.root, name));
    }
  }
  async mutate<T>(id: string, fn: (note: NoteRecord | undefined) => { note?: NoteRecord; result: T }) {
    return this.mutex.run(async () => {
      const change = fn(await this.read(id));
      if (change.note) await this.write(change.note);
      return change.result;
    });
  }
  async save(input: SaveInput) {
    validate(input);
    return this.mutex.run(async () => {
      const old = await this.read(input.id);
      const hash = digest(input.content);
      if (old && (old.createdAt !== input.createdAt || input.revision < old.revision ||
          (input.revision === old.revision && hash !== old.hash))) throw new AppError(409, 'VERSION_CONFLICT', '这篇笔记已有另一个版本，请将草稿另存为新想法。');
      if (old?.revision === input.revision) return old;
      if (!old && !input.content.trim()) return undefined;
      let allocated = old?.filename ?? filename(input.createdAt);
      if (!old) {
        const used = new Set((await this.all()).map(note => note.filename));
        for (let offset = 1; used.has(allocated); offset++) allocated = filename(input.createdAt, undefined, offset);
      }
      const note: NoteRecord = { ...old, id: input.id, filename: allocated,
        createdAt: input.createdAt, revision: input.revision, hash, content: input.content,
        publishedRevision: old?.publishedRevision ?? 0 };
      await this.write(note);
      return note;
    });
  }
  async pending() {
    return (await this.all()).filter(r => r.content !== undefined || !!r.delivery);
  }
  async all() {
    const ids = (await readdir(this.root)).filter(name => name.endsWith('.json') && ID_PATTERN.test(name.slice(0, -5)));
    const records = await Promise.all(ids.map(name => this.read(name.slice(0, -5))));
    return records.filter((r): r is NoteRecord => !!r);
  }
}
