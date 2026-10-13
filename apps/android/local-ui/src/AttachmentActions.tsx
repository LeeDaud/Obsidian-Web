import { ChevronDownIcon, ChevronUpIcon, ImagePlusIcon, MoreVerticalIcon, Share2Icon, XIcon } from "lucide-react";
import { toast } from "react-hot-toast";
import { useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { browserFetch, saveBlob } from "./platform";

interface Props {
  placementAction?: { label: string; onClick: () => void; disabled?: boolean };
  onRemove?: () => void;
  onMoveUp?: () => void;
  onMoveDown?: () => void;
  canMoveUp?: boolean;
  canMoveDown?: boolean;
  sourceUrl?: string;
  filename?: string;
}

export function AttachmentActions({ placementAction, onRemove, onMoveUp, onMoveDown, canMoveUp = true, canMoveDown = true, sourceUrl, filename }: Props) {
  const sharing = useRef(false);
  const [sharePending, setSharePending] = useState(false);
  const share = async () => {
    if (sharing.current || !sourceUrl) return;
    sharing.current = true;
    setSharePending(true);
    try {
      const response = await browserFetch(sourceUrl!);
      if (!response.ok) throw new Error("无法读取附件");
      await saveBlob(await response.blob(), filename || "attachment.bin", true);
    } catch (error) { toast.error(error instanceof Error ? error.message : "暂时无法分享附件"); }
    finally { sharing.current = false; setSharePending(false); }
  };
  return (
    <div className="shrink-0" onClick={(event) => event.stopPropagation()}>
      <DropdownMenu>
        <DropdownMenuTrigger render={<Button variant="quiet" className="size-11 p-0" aria-label="附件操作" title="附件操作" />}>
          <MoreVerticalIcon className="size-4" strokeWidth={1.8} />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" size="sm">
          {placementAction && <DropdownMenuItem disabled={placementAction.disabled} onClick={placementAction.onClick}><ImagePlusIcon className="size-4" />{placementAction.label}</DropdownMenuItem>}
          {sourceUrl && <DropdownMenuItem disabled={sharePending} onClick={() => { void share(); }}><Share2Icon className="size-4" />{sharePending ? "正在准备分享…" : "分享"}</DropdownMenuItem>}
          {onMoveUp && <DropdownMenuItem disabled={!canMoveUp} onClick={onMoveUp}><ChevronUpIcon className="size-4" />上移</DropdownMenuItem>}
          {onMoveDown && <DropdownMenuItem disabled={!canMoveDown} onClick={onMoveDown}><ChevronDownIcon className="size-4" />下移</DropdownMenuItem>}
          {onRemove && <DropdownMenuItem onClick={onRemove}><XIcon className="size-4" />移除</DropdownMenuItem>}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}
