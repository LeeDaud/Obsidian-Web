import { _android } from "../../../node_modules/@playwright/test/index.mjs";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
const device = (await _android.devices()).find((item) => item.serial() === "emulator-5558");
if (!device) throw new Error("Only use the isolated API35 emulator");
const pkg = "xyz.leedaud.echo";
const out = resolve("apps/android/app/build/android-qa/local-ui/recording");
await mkdir(out, { recursive: true });
let cleanup: (() => Promise<void>) | undefined;
try {
  await device.shell(`am force-stop ${pkg}`);
  await device.shell(`pm grant ${pkg} android.permission.RECORD_AUDIO`);
  await device.shell(`am start -n ${pkg}/.NativeActivity --ez local-ui-preview true`);
  const page = await (await device.webView({ pkg })).page();
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.locator(".cm-content").first().waitFor();
  await page.evaluate("globalThis.__name = (value) => value");
  const marker = `W4-QA 录音 ${Date.now()}`;
  await page.locator(".cm-content").first().fill(marker);
  await page.evaluate(() => {
    // Test-only Web Audio source: never connects to speakers or host microphones.
    const context = new AudioContext();
    const oscillator = context.createOscillator();
    const destination = context.createMediaStreamDestination();
    oscillator.connect(destination); oscillator.start();
    const original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    Object.defineProperty(navigator.mediaDevices, "getUserMedia", { configurable: true, value: async () => {
      await context.resume(); return destination.stream.clone();
    } });
    (window as any).echoSyntheticAudioCleanup = async () => {
      Object.defineProperty(navigator.mediaDevices, "getUserMedia", { configurable: true, value: original });
      oscillator.stop(); destination.stream.getTracks().forEach((track) => track.stop()); await context.close();
    };
  });
  cleanup = () => page.evaluate(async () => { await (window as any).echoSyntheticAudioCleanup(); });
  await page.getByRole("button", { name: "增加", exact: true }).click();
  await page.getByRole("menuitem", { name: "录制音频", exact: true }).click();
  const stop = page.getByRole("button", { name: "停止录音", exact: true });
  await stop.waitFor();
  await page.waitForFunction(() => { const button = document.querySelector<HTMLButtonElement>('button[aria-label="停止录音"]'); return button && !button.disabled; });
  await page.waitForTimeout(1800);
  await stop.click();
  await page.waitForFunction(() => { const button = document.querySelector<HTMLButtonElement>('button[aria-label="保存"]'); return button && !button.disabled; });
  await page.getByRole("button", { name: "暂存到当前设备", exact: true }).click();
  await page.getByText("已暂存到当前设备", { exact: true }).waitFor();
  const draft = await page.evaluate(() => JSON.parse(localStorage.getItem("users/device-home-memo-editor") ?? "{}"));
  if (draft.attachments?.length !== 1 || !draft.attachments[0].type.startsWith("audio/") || Number(draft.attachments[0].size) <= 0) throw new Error("Recorded attachment not safely stashed");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await page.locator("article").filter({ hasText: marker }).first().waitFor();
  const memo = await page.evaluate(async (marker) => {
    const response = await fetch("/memos.api.v1.MemoService/ListMemos", { method: "POST", body: JSON.stringify({ filter: `content.contains("${marker}")` }) });
    return (await response.json()).memos[0];
  }, marker);
  if (memo.attachments.length !== 1 || memo.content !== marker) throw new Error("Recording save changed source");
  const file = memo.attachments[0];
  const loaded = await page.evaluate(async (file) => {
    const response = await fetch(`/file/${file.name}/${encodeURIComponent(file.filename)}`);
    return { status: response.status, bytes: (await response.arrayBuffer()).byteLength };
  }, file);
  if (loaded.status !== 200 || loaded.bytes !== Number(file.size)) throw new Error("Saved recording bytes unavailable");
  await page.screenshot({ path: `${out}/saved-recording.png` });
  if (errors.length) throw new Error(`Page errors: ${errors.join(", ")}`);
  await writeFile(`${out}/result.json`, JSON.stringify({ marker, mime: file.type, size: file.size, loaded, errors, syntheticWebAudio: true, nativeMicrophoneCaptureVerified: false }, null, 2));
  console.log("PASS synthetic Web Audio -> original MediaRecorder -> private attachment -> stash/save/read");
} catch (error) {
  await writeFile(`${out}/result.json`, JSON.stringify({ failure: String(error) }, null, 2));
  throw error;
} finally { await cleanup?.().catch(() => {}); await device.close(); }
