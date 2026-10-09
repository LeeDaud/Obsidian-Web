import { useCallback, useEffect, useRef } from "react";
import { useEditorStore } from "@/components/MemoEditor/state";
import { persistEditorDraft, clearEditorDraft } from "./drafts";
import { editorChanged } from "./model";

export function useAutoSave(username: string, cacheKey?: string, enabled = true, baseRevision?: string) {
  const store = useEditorStore();
  const key = `${username}-${cacheKey ?? ""}`;
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const discarded = useRef(false);
  const fingerprint = useRef("");
  const persist = useCallback(async () => {
    if (enabled && !discarded.current) await persistEditorDraft(key, store.getState(), baseRevision);
  }, [enabled, key, store, baseRevision]);
  useEffect(() => {
    if (!enabled) return;
    const changed = () => {
      const state = store.getState();
      const next = JSON.stringify([state.content, state.metadata.attachments.map((item) => item.name), state.metadata.relations, state.metadata.location,
        state.localFiles.map((item) => item.previewUrl)] , (_key, value) => typeof value === "bigint" ? value.toString() : value);
      if (fingerprint.current === next) return;
      fingerprint.current = next; discarded.current = false;
      editorChanged(key);
      clearTimeout(timer.current); timer.current = setTimeout(() => { void persist().catch(() => window.dispatchEvent(new Event("echo-draft-failed"))); }, 350);
    };
    changed();
    const unsubscribe = store.subscribe(changed);
    const flush = () => { clearTimeout(timer.current); void persist().catch(() => {}); };
    window.addEventListener("pagehide", flush);
    return () => { unsubscribe(); flush(); window.removeEventListener("pagehide", flush); };
  }, [enabled, store, persist]);
  const saveDraft = useCallback(async () => { clearTimeout(timer.current); discarded.current = false; await persist(); }, [persist]);
  const discardDraft = useCallback(() => { discarded.current = true; clearTimeout(timer.current); void clearEditorDraft(key); }, [key]);
  return { saveDraft, discardDraft };
}
