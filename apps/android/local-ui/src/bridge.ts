type Pending = { resolve: (value: unknown) => void; reject: (error: Error) => void; timer: number; chunks?: string[]; received?: number };
let port: MessagePort | undefined;
let sequence = 0;
const pending = new Map<string, Pending>();
let connected: () => void;
export const nativeReady = new Promise<void>((resolve) => { connected = resolve; });

window.addEventListener("message", (event) => {
  if ((event.source !== null && event.source !== window) || event.data !== "echo-local-port" || event.ports.length !== 1 || port || window !== window.top) return;
  port = event.ports[0];
  port.onmessage = (message) => {
    let response;
    try { response = JSON.parse(message.data); } catch { return; }
    const request = pending.get(response.id);
    if (!request) return;
    if (typeof response.chunk === "string") {
      if (!Number.isInteger(response.index) || !Number.isInteger(response.total) || response.total < 1 || response.total > 512 || response.index < 0 || response.index >= response.total) return;
      request.chunks ??= new Array(response.total); request.received ??= 0;
      if (request.chunks[response.index] === undefined) { request.chunks[response.index] = response.chunk; request.received++; }
      if (request.received !== response.total) return;
      try { response = JSON.parse(request.chunks.join("")); } catch { pending.delete(response.id); clearTimeout(request.timer); request.reject(new Error("本机响应不完整")); return; }
    }
    pending.delete(response.id); clearTimeout(request.timer);
    if (response.error) request.reject(new Error(response.error)); else request.resolve(response.result);
  };
  port.start(); connected();
});

export async function nativeCall(command: string, input: unknown = {}): Promise<any> {
  await nativeReady;
  if (pending.size >= 64) throw new Error("请求较多，请稍后重试");
  const id = String(++sequence);
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => { pending.delete(id); reject(new Error("本机操作尚未确认，请重试；草稿保留")); }, 60000);
    pending.set(id, { resolve, reject, timer });
    port!.postMessage(JSON.stringify({ id, command, input }));
  });
}
