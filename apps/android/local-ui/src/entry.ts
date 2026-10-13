import { nativeCall, nativeReady } from "./bridge";
import { localFetch } from "./connect";
import "./style.css";
import { installPlatform } from "./platform";
import { flushDraftsBeforeUpdate } from "./drafts";
import toast from "react-hot-toast";

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
void nativeCall("platform.update.auto").then(() => {
  let polls = 0;
  const timer = setInterval(() => {
    if (++polls > 30) { clearInterval(timer); return; }
    void nativeCall("platform.update.status").then((status) => {
      if (!status.busy) {
        clearInterval(timer);
        if (status.state === "available" && document.visibilityState === "visible") toast("发现新版，可在设置的应用更新中查看", { id: "echo-app-update" });
      }
    }).catch(() => clearInterval(timer));
  }, 2000);
}).catch(() => {});
(window as any).__echoPrepareUpdate = flushDraftsBeforeUpdate;
(window as any).__echoConfirmUpdateDrafts = async (nonce: string) => {
  let saved = false;
  try { await flushDraftsBeforeUpdate(); saved = true; } catch { /* The native request fails closed. */ }
  await nativeCall("platform.update.drafts-ready", { nonce, saved }).catch(() => {});
};
}
void start();
