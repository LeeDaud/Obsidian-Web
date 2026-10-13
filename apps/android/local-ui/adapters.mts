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
      if (file.endsWith("/AppSidebar/AppSidebar.tsx")) {
        code = `import { SidebarTasks } from '${source}/SidebarTasks';\n${code}`;
        code = inject(code, '{currentUser && <ViewsSection />}', '{currentUser && <SidebarTasks />}\n      {currentUser && <ViewsSection />}');
      }
      if (file.endsWith("/AppSidebar/ViewsSection.tsx")) {
        const start = code.indexOf('      <SidebarRow\n        state={!manageActive && selectedMemoView === BUILTIN_TASKS_VIEW_ID');
        const end = code.indexOf('      {memoViews.map', start);
        if (start < 0 || end < 0) throw new Error("Original tasks view adapter anchor changed");
        code = code.slice(0, start) + code.slice(end);
      }
      if (file.endsWith("/MemoReactionListView/hooks.ts")) {
        code = `import toast from 'react-hot-toast';\nimport { isRemoteMemo } from '${source}/model';\nimport { useRef, useState } from 'react';\n${code}`;
        code = inject(code, '  const hasReacted =', '  const [busy, setBusy] = useState(false);\n  const pending = useRef(false);\n  const hasReacted =');
        code = inject(code, '    if (!currentUser) return;', '    if (!currentUser || isRemoteMemo(memo.name) || pending.current) return;\n    pending.current = true; setBusy(true);');
        code = inject(code, '    } catch {\n      // skip error\n    }\n    onComplete?.();', '      onComplete?.();\n    } catch (error) {\n      toast.error(error instanceof Error ? error.message : "心情保存未完成，请重试");\n    } finally {\n      pending.current = false; setBusy(false);\n    }');
        code = inject(code, 'return { hasReacted, handleReactionClick };', 'return { hasReacted, handleReactionClick, busy };');
      }
      if (file.endsWith("/MemoReactionListView/ReactionSelector.tsx")) {
        code = `import { isRemoteMemo } from '${source}/model';\n${code}`;
        code = inject(code, '{ hasReacted, handleReactionClick }', '{ hasReacted, handleReactionClick, busy }');
        code = inject(code, '  return (', '  if (isRemoteMemo(memo.name)) return null;\n  return (');
        code = inject(code, '              key={reactionType}', '              key={reactionType}\n              disabled={busy}\n              aria-pressed={hasReacted(reactionType)}\n              aria-label={`心情 ${reactionType}`}');
        code = inject(code, 'inline-flex w-auto text-base', 'inline-flex min-h-11 min-w-11 items-center justify-center w-auto text-xl');
        code = inject(code, 'transition-colors",', 'transition-colors focus-visible:outline-2 focus-visible:outline-ring disabled:opacity-50",');
        code = inject(code, '      <PopoverContent align="center"', '      <PopoverContent data-echo-reaction-picker align="center"');
      }
      if (file.endsWith("/MemoReactionListView/ReactionView.tsx")) {
        code = `import { isRemoteMemo } from '${source}/model';\n${code}`;
        code = inject(code, 'const { handleReactionClick }', 'const { handleReactionClick, busy }');
        code = inject(code, 'Boolean(currentUser) && !readonly', 'Boolean(currentUser) && !readonly && !isRemoteMemo(memo.name)');
        code = inject(code, '                aria-pressed={hasReaction}', '                aria-pressed={hasReaction}\n                disabled={busy}\n                aria-label={`取消心情 ${reactionType}`}');
        code = inject(code, 'inline-flex h-7 shrink-0', 'inline-flex h-7 pointer-coarse:min-h-11 pointer-coarse:min-w-11 shrink-0');
      }
      if (file.endsWith("/utils/managed-attachment.ts")) {
        code = inject(code, "const UID_PATTERN = /^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,34}[a-zA-Z0-9])?$/;",
          "const UID_PATTERN = /^(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,34}[a-zA-Z0-9])?|r-[a-f0-9]{64})$/;");
      }
      if (file.endsWith("/MemoMetadata/Attachment/AttachmentListEditor.tsx")) {
        const start = code.indexOf("const AttachmentItemActions:");
        const end = code.indexOf("const AttachmentItemCard:", start);
        if (start < 0 || end < 0) throw new Error("Original attachment action adapter anchor changed");
        code = code.slice(0, start) + "const AttachmentItemActions = AttachmentActions;\n\n" + code.slice(end);
        code = `import { AttachmentActions } from '${source}/AttachmentActions';\n${code}`;
        code = inject(code, "      <AttachmentItemActions", "      <AttachmentItemActions sourceUrl={sourceUrl} filename={filename}");
      }
      if (file.endsWith("/components/MemoEditor/index.tsx")) {
        code = inject(code, "ref={editorContainerRef}", "ref={editorContainerRef} data-echo-editor-key={editorCacheKey}");
        code = inject(code, "const handleDraft = useCallback(() => {", "const handleDraft = useCallback(async () => {");
        code = inject(code, '    saveDraft();\n    toast.success("已暂存到当前设备");', '    try {\n      await saveDraft();\n      toast.success("已暂存到当前设备");\n    } catch (error) {\n      toast.error(error instanceof Error ? error.message : "设备暂存未完成，内容保留");\n    }');
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
      if (file.endsWith("/MemoContent/markdown/Image.tsx")) code = inject(code, 'referrerPolicy="no-referrer"',
        'referrerPolicy={typeof props.src === "string" && (props.src.startsWith("/file/attachments/") || props.src.startsWith(window.location.origin + "/file/attachments/")) ? "same-origin" : "no-referrer"}');
      if (file.endsWith("/MemoContent/constants.ts")) code = inject(code, 'const HANDOFF_LINK_PROTOCOLS = ["tel", "sms"];', 'const HANDOFF_LINK_PROTOCOLS = ["tel", "sms", "echo"];');
      return code;
    },
  };
}
