import { nativeCall, nativeReady } from "./bridge";
import { localFetch } from "./connect";
import "./style.css";
import { installPlatform } from "./platform";

async function start() {
await nativeReady;
const initial = await nativeCall("local.bootstrap");
for (const [key, value] of Object.entries(initial.consumed ?? {})) {
  if (localStorage.getItem(key) === value) localStorage.removeItem(key);
}
for (const [key, value] of Object.entries(initial.drafts as Record<string, string>)) localStorage.setItem(key, value);
window.fetch = localFetch;
installPlatform();
await import("../../../../upstream/memos/web/src/main");
}
void start();
