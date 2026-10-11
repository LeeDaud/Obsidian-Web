import { nativeCall } from "./bridge";
import { uploadFile } from "./drafts";
import { toast } from "react-hot-toast";
export const browserFetch = globalThis.fetch.bind(globalThis);
export async function saveBlob(blob: Blob, filename: string, share = false): Promise<void> {
  const attachment = await uploadFile(new File([blob], filename, { type: blob.type || "application/octet-stream" }), "export");
  await nativeCall(share ? "platform.share" : "platform.save", { name: attachment.name, filename });
}
export async function downloadFileFromUrl(url: string, filename: string) {
  if (!url.startsWith("blob:") && !url.startsWith(location.origin + "/file/")) throw new Error("只允许导出本机文件");
  const response = await browserFetch(url); await saveBlob(await response.blob(), filename);
}
export function installPlatform() {
  document.addEventListener("click", (event) => {
    const input = event.target;
    if (!(input instanceof HTMLInputElement) || input.type !== "file" || input.disabled || window !== window.top) return;
    event.preventDefault(); event.stopImmediatePropagation();
    void nativeCall("platform.choose", { accept: input.accept, multiple: input.multiple }, 0).then(async (result) => {
      if (!input.isConnected || !Array.isArray(result.files) || result.files.length === 0) return;
      if (result.files.length > 100 || (!input.multiple && result.files.length > 1)) throw new Error("文件选择数量无效");
      const transfer = new DataTransfer();
      for (const file of result.files) {
        if (!/^picked\/[a-f0-9-]{36}$/.test(file.name) || typeof file.filename !== "string") throw new Error("文件选择响应无效");
        const response = await browserFetch(`/file/${file.name}/${encodeURIComponent(file.filename)}`);
        if (!response.ok) throw new Error("文件暂存读取失败");
        const blob = await response.blob();
        if (blob.size !== Number(file.size)) throw new Error("文件大小核验失败");
        transfer.items.add(new File([blob], file.filename, { type: file.type }));
      }
      if (!input.isConnected) return;
      input.files = transfer.files;
      input.dispatchEvent(new Event("change", { bubbles: true }));
    }).catch((error) => toast.error(error instanceof Error ? error.message : "文件选择未完成"));
  }, true);
  window.addEventListener("echo-draft-failed", () => toast.error("设备暂存未完成，内容仍在编辑器中", { id: "echo-device-draft" }));
  window.addEventListener("echo-draft-saved", () => toast.dismiss("echo-device-draft"));
  Object.defineProperty(navigator, "share", { configurable: true, value: async (data: ShareData) => {
    if (data.files?.length === 1) await saveBlob(data.files[0], data.files[0].name, true);
    else if (data.text) await nativeCall("platform.clipboard", { text: data.text });
    else throw new Error("请选择一个文件分享");
  } });
  Object.defineProperty(navigator, "canShare", { configurable: true, value: (data: ShareData) => data.files?.length === 1 });
  if (navigator.geolocation) Object.defineProperty(navigator.geolocation, "getCurrentPosition", { configurable: true, value: (success: PositionCallback, failure?: PositionErrorCallback) => {
    void nativeCall("platform.location").then((point) => success({ coords: { ...point, altitude: null, altitudeAccuracy: null, heading: null, speed: null }, timestamp: Date.now() } as GeolocationPosition))
      .catch((error) => failure?.({ code: 2, message: error.message } as GeolocationPositionError));
  } });
  const theme = () => {
    const canvas = document.createElement("canvas"); canvas.width = canvas.height = 1;
    const context = canvas.getContext("2d")!; context.fillStyle = getComputedStyle(document.body).backgroundColor; context.fillRect(0, 0, 1, 1);
    const pixel = context.getImageData(0, 0, 1, 1).data;
    const background = "#" + [...pixel.slice(0, 3)].map((value) => value.toString(16).padStart(2, "0")).join("");
    void nativeCall("platform.theme", { dark: pixel[0] * .299 + pixel[1] * .587 + pixel[2] * .114 < 128, background }).catch(() => {});
  };
  new MutationObserver(theme).observe(document.head, { childList: true, subtree: true });
  window.addEventListener("echo-native-change", theme); theme();
}
