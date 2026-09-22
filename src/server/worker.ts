import { AppError } from './model';
import type { Remote } from './github';
import type { NoteStore } from './store';

export class DeliveryWorker {
  private running = false;
  private idle: (() => void)[] = [];
  constructor(private store: NoteStore, private remote: Remote) {}
  async tick() {
    if (this.running) return;
    this.running = true;
    try {
      for (const entry of await this.store.pending()) {
        if (entry.error === 'REMOTE_CONFLICT' || (entry.retryAt ?? 0) > Date.now()) continue;
        const delivery = await this.store.mutate(entry.id, note => {
          if (!note) return { result: undefined };
          if (!note.delivery && note.content !== undefined) {
            note.delivery = { revision: note.revision, content: note.content, hash: note.hash, expectedBlob: note.remoteBlob };
          }
          return { note, result: note.delivery };
        });
        if (!delivery) continue;
        try {
          const result = await this.remote.publish(entry.filename, delivery, async attempt => {
            await this.store.mutate(entry.id, note => {
              if (!note?.delivery || note.delivery.revision !== delivery.revision) throw new Error('Delivery changed');
              note.delivery.attempt = attempt;
              return { note, result: undefined };
            });
          });
          await this.store.mutate(entry.id, note => {
            if (!note?.delivery || note.delivery.revision !== delivery.revision) throw new Error('Delivery changed');
            note.publishedRevision = delivery.revision;
            note.remoteBlob = result.blob;
            note.commit = result.commit;
            if (note.revision === delivery.revision && note.hash === delivery.hash) delete note.content;
            delete note.delivery;
            delete note.error;
            delete note.retryAt;
            delete note.failures;
            return { note, result: undefined };
          });
        } catch (error) {
          await this.store.mutate(entry.id, note => {
            if (!note) return { result: undefined };
            note.error = error instanceof AppError ? error.code : 'GITHUB_UNAVAILABLE';
            note.failures = (note.failures ?? 0) + 1;
            note.retryAt = Date.now() + Math.min(300000, 5000 * 2 ** Math.min(note.failures, 6));
            return { note, result: undefined };
          });
        }
      }
    } finally { this.running = false; this.idle.splice(0).forEach(resolve => resolve()); }
  }
  async waitForIdle() {
    if (this.running) await new Promise<void>(resolve => this.idle.push(resolve));
  }
}
