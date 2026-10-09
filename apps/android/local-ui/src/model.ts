import { useSyncExternalStore } from "react";
const metadata = new Map<string, { remote?: boolean; frozen?: boolean; offline?: boolean }>();
let epoch = 0;
const listeners = new Set<() => void>();
export const useEditorEpoch = () => useSyncExternalStore((callback) => { listeners.add(callback); return () => listeners.delete(callback); }, () => epoch);
export function installDraft(key: string, value: string) {
  localStorage.setItem(key, value); epoch++; listeners.forEach((callback) => callback());
}
export function registerMetadata(value: any) {
  if (value && typeof value === "object") {
    if (typeof value.name === "string" && value.localMetadata) { metadata.set(value.name, value.localMetadata); delete value.localMetadata; }
    for (const item of Object.values(value)) if (item && typeof item === "object") registerMetadata(item);
  }
}
export const isRemoteMemo = (name?: string) => Boolean(name && metadata.get(name)?.remote);
export const isImmutableMemo = (name?: string) => Boolean(name && (metadata.get(name)?.remote || metadata.get(name)?.frozen));
export let activeEditorKey = "users/device-home-memo-editor";
const changes = new Map<string, string>();
export const editorChangeToken = (key: string) => changes.get(key) ?? "";
export function editorChanged(key: string) { changes.set(key, crypto.randomUUID()); }
document.addEventListener("pointerdown", (event) => {
  const key = (event.target as HTMLElement)?.closest<HTMLElement>("[data-echo-editor-key]")?.dataset.echoEditorKey;
  if (key) activeEditorKey = key;
}, true);
