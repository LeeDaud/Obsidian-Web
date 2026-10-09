import { resolve } from "node:path";
const source = resolve(import.meta.dirname, "src").replaceAll("\\", "/");
export function nativeAdapters() {
  const inject = (code: string, before: string, after: string) => {
    if (!code.includes(before)) throw new Error(`Original component adapter anchor changed: ${before}`);
    return code.replace(before, after);
  };
  return {
    name: "echo-native-component-adapters",
    enforce: "pre" as const,
    resolveId(id: string, importer?: string) {
      const parent = importer?.replaceAll("\\", "/") ?? "";
      if (parent.endsWith("/MemoEditor/hooks/index.ts") && id === "./useAutoSave") return `${source}/auto-save.ts`;
      if (parent.includes("/MemoEditor/") && id === "./uploadService") return `${source}/upload-service.ts`;
    },
    transform(code: string, id: string) {
      const file = id.replaceAll("\\", "/").split('?')[0];
      if (file.endsWith("/components/MemoEditor/index.tsx")) {
        code = inject(code, "ref={editorContainerRef}", "ref={editorContainerRef} data-echo-editor-key={editorCacheKey}");
        code = inject(code, "const handleDraft = useCallback(() => {", "const handleDraft = useCallback(async () => {");
        code = inject(code, "    saveDraft();", "    await saveDraft();");
      }
      if (file.endsWith("/MemoEditor/hooks/useMemoInit.ts")) {
        code = `import { cachedRelations } from '${source}/drafts';\n${code}`;
        code = inject(code, "const cachedDraft = cacheService.loadDraft(key);\n      if (cachedDraft.content)", "const cachedDraft = cacheService.loadDraft(key);\n      const nativeRelations = cachedRelations(key);\n      if (nativeRelations.length) dispatch(actions.setMetadata({ relations: nativeRelations }));\n      if (cachedDraft.content)");
        code = inject(code, "if (defaultRelations?.length)", "if (defaultRelations?.length && nativeRelations.length === 0)");
      }
      if (file.endsWith("/pages/Home.tsx")) {
        code = `import { useEditorEpoch } from '${source}/model';\n${code}`;
        code = inject(code, "const Home = () => {", "const Home = () => { const nativeEpoch = useEditorEpoch();");
        code = inject(code, "key={editorCacheKey}", "key={editorCacheKey + nativeEpoch}");
      }
      if (file.endsWith("/MemoView/MemoView.tsx")) {
        code = `import { isImmutableMemo, isRemoteMemo } from '${source}/model';\n${code}`;
        code = inject(code, "let continuation = false;", "let continuation = isImmutableMemo(memoData.name);");
        code = inject(code, "if (!memoData.parent) {", "if (!continuation && !memoData.parent) {");
        code = code.replaceAll("echoBridge?.enabled === true", "echoBridge?.enabled === true && !isRemoteMemo(memoData.name)");
      }
      if (file.endsWith("/MemoActionMenu/MemoActionMenu.tsx")) {
        code = `import { isImmutableMemo, isRemoteMemo } from '${source}/model';\nimport { NativeMemoActions } from '${source}/NativeMemoActions';\n${code}`;
        code = inject(code, '<DropdownMenuContent align="end" sideOffset={2} size="sm">', '<DropdownMenuContent align="end" sideOffset={2} size="sm"><NativeMemoActions memo={memo} onContinue={props.onEdit} />');
        code = code.replaceAll("!readonly &&", "!readonly && !isRemoteMemo(memo.name) &&");
        code = inject(code, "const canMutateTasks =", "const canMutateTasks = !isImmutableMemo(memo.name) &&");
        code = inject(code, '(props.deliveryState === "已投递" || props.deliveryState === "投递中")', '(isImmutableMemo(memo.name) || props.deliveryState === "已投递" || props.deliveryState === "投递中")');
      }
      if (file.endsWith("/MemoContent/TaskListItem.tsx")) {
        code = `import { isImmutableMemo } from '${source}/model';\n${code}`;
        code = inject(code, "const { readonly } = useMemoViewDerived();", "const { readonly: originalReadonly } = useMemoViewDerived(); const readonly = originalReadonly || isImmutableMemo(memo?.name);");
      }
      if (file.endsWith("/MemoActionMenu/hooks.ts")) {
        code = inject(code, "copy(`${host}/${memo.name}`)", "copy(`echo://memos/${memo.name.replace('memos/', '')}`)");
      }
      if (file.endsWith("/MemoActionMenu/MemoShareImageDialog.tsx")) {
        code = `import { saveBlob } from '${source}/platform';\n${code}`;
        code = inject(code, "      anchor.click();", "      await saveBlob(blob, buildMemoShareImageFileName(memo.name));");
      }
      if (file.endsWith("/lib/memo-export.ts")) code = inject(code, "  downloadFileFromUrl(url,", "  await downloadFileFromUrl(url,");
      if (file.endsWith("/MemoContent/constants.ts")) code = inject(code, 'const HANDOFF_LINK_PROTOCOLS = ["tel", "sms"];', 'const HANDOFF_LINK_PROTOCOLS = ["tel", "sms", "echo"];');
      return code;
    },
  };
}
