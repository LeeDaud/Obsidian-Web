import { Download, Copy, FileText, SquarePen } from "lucide-react";
import toast from "react-hot-toast";
import { DropdownMenuItem, DropdownMenuSeparator } from "@/components/ui/dropdown-menu";
import { nativeCall } from "./bridge";
import { installDraft, isRemoteMemo } from "./model";
export function NativeMemoActions({ memo, onContinue }: { memo: { name: string }; onContinue?: () => void }) {
  const run = async (command: string) => {
    try {
      const result = await nativeCall(command, { name: memo.name });
      if (result.key) { installDraft(result.key, result.value); history.pushState({}, "", "/"); window.dispatchEvent(new PopStateEvent("popstate")); }
      toast.success(command === "local.memos-cache" ? "正文和附件已核验并离线保存" : "已另存设备草稿，没有上传");
    } catch (error) { toast.error(error instanceof Error ? error.message : "操作未完成，原内容保留"); }
  };
  if (!isRemoteMemo(memo.name)) return <DropdownMenuItem onClick={() => { void nativeCall("local.export-note", { name: memo.name }).then((result) => nativeCall("platform.save", result)).catch((error) => toast.error(error.message)); }}><FileText />导出 Markdown 和附件</DropdownMenuItem>;
  return <>
    <DropdownMenuItem onClick={onContinue}><SquarePen />本机续写</DropdownMenuItem>
    <DropdownMenuItem onClick={() => { void run("local.memos-cache"); }}><Download />离线保存</DropdownMenuItem>
    <DropdownMenuItem onClick={() => { void run("local.memos-copy"); }}><Copy />另存设备草稿</DropdownMenuItem>
    <DropdownMenuSeparator />
  </>;
}
