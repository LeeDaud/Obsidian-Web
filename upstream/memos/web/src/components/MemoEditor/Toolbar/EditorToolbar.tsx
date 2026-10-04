import { ArchiveIcon, CheckIcon, CornerDownLeftIcon, LoaderIcon, SaveIcon } from "lucide-react";
import type { FC } from "react";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import type { Location } from "@/types/proto/api/v1/memo_service_pb";
import { useTranslate } from "@/utils/i18n";
import { primaryModifierGlyph } from "@/utils/platform";
import { validationService } from "../services";
import { useEditorContext, useEditorSelector } from "../state";
import type { EditorToolbarProps } from "../types";
import { isTodoContent, toNoteContent, toTodoContent } from "../utils/todoMode";
import InsertMenu from "./InsertMenu";

const ACTION_BUTTON_CLASSES = "min-h-11 w-full min-w-0 gap-1.5 border border-border/70 sm:w-auto sm:min-w-24";

/**
 * Shortcut chip inside the commit button. While saving, a spinner takes the
 * chip's place; both layers share one grid cell so the button keeps its width
 * across the swap. Hidden on coarse pointers, where there is no keyboard to hint.
 */
const ShortcutChip: FC<{ busy: boolean }> = ({ busy }) => (
  <kbd
    aria-hidden
    className="grid place-items-center rounded-[4px] bg-primary-foreground/20 px-1 py-0.5 font-sans text-2xs leading-none font-medium pointer-coarse:hidden"
  >
    <span className={cn("col-start-1 row-start-1 inline-flex items-center gap-px", busy && "invisible")}>
      {primaryModifierGlyph()}
      <CornerDownLeftIcon className="size-2.5" strokeWidth={2.5} />
    </span>
    <LoaderIcon className={cn("col-start-1 row-start-1 size-2.5 animate-spin", !busy && "invisible")} strokeWidth={3} />
  </kbd>
);

export const EditorToolbar: FC<EditorToolbarProps> = ({
  onSave,
  onDraft,
  onCancel,
  memoName,
  parentMemoName,
  onAudioRecorderClick,
  viewToggles,
  onInsertImages,
  showKindToggle,
}) => {
  const t = useTranslate();
  const { actions, dispatch, getState } = useEditorContext();
  // Subscribe to narrow/derived slices so typing (which only changes content)
  // doesn't re-render the toolbar or the heavy InsertMenu it hosts. `valid`
  // flips only on empty↔non-empty / loading transitions, not per keystroke.
  const valid = useEditorSelector((s) => validationService.canSave(s).valid);
  const blockedReason = useEditorSelector((s) => validationService.canSave(s).reason);
  const blockedReasonDetail = useEditorSelector((s) => validationService.canSave(s).detail);
  const isSaving = useEditorSelector((s) => s.ui.isLoading.saving);
  const justSaved = useEditorSelector((s) => s.ui.justSaved);
  const isUploading = useEditorSelector((s) => s.ui.isLoading.uploading);
  const location = useEditorSelector((s) => s.metadata.location);
  const isTodo = useEditorSelector((s) => isTodoContent(s.content));
  // The save transaction is in flight or its confirmation is holding the
  // editor open; either way the toolbar is frozen.
  const committing = isSaving || justSaved;
  const blockedMessage =
    valid || committing
      ? undefined
      : blockedReason
        ? t(blockedReason, blockedReasonDetail ? { url: blockedReasonDetail } : undefined)
        : t("editor.validation.cannot-save");
  // The verb names what the host does with the memo: an existing memo is
  // updated, a reply becomes a comment, and a new memo is simply saved. A memo
  // is stored with a visibility, not posted, so messaging verbs stay out.
  const commitLabel = memoName ? t("common.update") : parentMemoName ? t("editor.comment") : t("editor.save");

  const handleLocationChange = (next?: Location) => {
    dispatch(actions.setMetadata({ location: next }));
  };

  const handleKindChange = (todo: boolean) => {
    const content = getState().content;
    dispatch(actions.setContent(todo ? toTodoContent(content) : toNoteContent(content)));
  };

  const commitButton = justSaved ? (
    <Button variant="quiet" size="sm" className={ACTION_BUTTON_CLASSES} disabled>
      {t("editor.saved")}
      <CheckIcon className="size-3.5" strokeWidth={2.5} />
    </Button>
  ) : (
    <Button
      variant="quiet"
      size="sm"
      className={ACTION_BUTTON_CLASSES}
      onClick={onSave}
      disabled={isSaving || !valid}
      aria-label={commitLabel}
    >
      <SaveIcon className="size-4" />
      {commitLabel}
      <ShortcutChip busy={isSaving} />
    </Button>
  );

  return (
    <div className="flex w-full min-w-0 flex-col gap-2">
      {showKindToggle && (
        <div className="grid w-full grid-cols-2 rounded-lg border border-border/70 p-0.5" role="group" aria-label="记录类型">
          <Button
            variant="ghost"
            size="sm"
            className={cn("min-h-11 w-full px-3", !isTodo && "bg-muted")}
            aria-pressed={!isTodo}
            onClick={() => handleKindChange(false)}
          >
            笔记
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className={cn("min-h-11 w-full px-3", isTodo && "bg-muted")}
            aria-pressed={isTodo}
            onClick={() => handleKindChange(true)}
          >
            待办
          </Button>
        </div>
      )}
      {/* Every control on this rail is 28px, the same box as the sidebar's compose control and nav pills. */}
      <div className="grid w-full min-w-0 grid-cols-[minmax(0,1fr)_2.75rem_minmax(0,1fr)] gap-2 sm:flex sm:items-center sm:justify-end sm:gap-1">
        <Button
          variant="quiet"
          size="sm"
          className={ACTION_BUTTON_CLASSES}
          onClick={onDraft}
          disabled={committing}
          aria-label="暂存到当前设备"
        >
          <ArchiveIcon className="size-4" />
          暂存
        </Button>
        <InsertMenu
          isUploading={isUploading}
          isSaving={committing}
          location={location}
          onLocationChange={handleLocationChange}
          memoName={memoName}
          onAudioRecorderClick={onAudioRecorderClick}
          viewToggles={viewToggles}
          onInsertImages={onInsertImages}
        />
        {onCancel && (
          <Button
            variant="quiet"
            size="sm"
            className="order-first col-span-3 min-h-11 w-full border border-border/70 sm:order-none sm:min-h-0 sm:w-auto"
            onClick={onCancel}
            disabled={committing}
          >
            {t("common.cancel")}
          </Button>
        )}

        {blockedMessage ? (
          <Tooltip>
            <TooltipTrigger render={<span className="inline-flex w-full min-w-0 sm:w-auto" tabIndex={0} aria-label={blockedMessage} />}>
              {commitButton}
            </TooltipTrigger>
            <TooltipContent side="top">{blockedMessage}</TooltipContent>
          </Tooltip>
        ) : (
          commitButton
        )}
      </div>
    </div>
  );
};
