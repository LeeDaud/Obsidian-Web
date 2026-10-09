import { nativeCall } from "./bridge";
import { uploadFile } from "./drafts";
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
