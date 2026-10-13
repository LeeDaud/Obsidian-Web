import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import SettingSection from "@/components/Settings/SettingSection";
import ConfirmDialog from "@/components/ConfirmDialog";
import { nativeCall } from "./bridge";
import { flushDraftsBeforeUpdate } from "./drafts";

export function AppUpdateSection() {
  const [status, setStatus] = useState<any>();
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  useEffect(() => {
    let alive = true;
    const refresh = () => { if (document.visibilityState !== "visible") return; void nativeCall("platform.update.status").then((value) => { if (alive) setStatus(value); }).catch((error) => { if (alive) setError(error.message); }); };
    refresh(); const timer = setInterval(refresh, 1000);
    return () => { alive = false; clearInterval(timer); };
  }, []);
  const act = async (command: string) => {
    if (pending) return;
    setPending(true); setError("");
    try {
      if (command === "install" || command === "open") await flushDraftsBeforeUpdate();
      const value = await nativeCall(`platform.update.${command}`);
      if (command !== "open" && command !== "releases") setStatus(value);
    } catch (error) { setError(error instanceof Error ? error.message : "更新未完成，请重试"); }
    finally { setPending(false); }
  };
  const busy = pending || status?.busy;
  const candidate = status?.candidate;
  return <SettingSection title="应用更新">
    <div data-slot="app-update" className="flex flex-col items-start gap-3">
      <p className="text-sm text-muted-foreground">{status ? `当前 ${status.currentVersion} · 构建 ${status.currentCode}` : "正在读取版本…"}</p>
      <p role="status" className="text-sm">{pending ? "正在确认设备暂存…" : status?.message}</p>
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      {candidate && <>
        <p className="text-sm font-medium">新版 {candidate.versionName} · 构建 {candidate.versionCode}</p>
        <p className="text-sm whitespace-pre-wrap wrap-anywhere">{candidate.releaseNotes}</p>
        {status.state === "downloading" && <progress className="w-full" aria-label="更新下载进度" max={candidate.size} value={status.downloadedBytes} />}
      </>}
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" className="min-h-11" disabled={busy || status?.state === "installing"} onClick={() => void act("check")}>检查更新</Button>
        {status?.canDownload && !status?.canInstall && <Button variant="outline" className="min-h-11" disabled={busy} onClick={() => void act("download")}>下载更新</Button>}
        {status?.canInstall && <Button className="min-h-11" disabled={busy} onClick={() => void act("install")}>{status.state === "permission_required" ? "允许安装并返回" : "安装更新"}</Button>}
        {status?.busy && ["checking", "downloading"].includes(status.state) && <Button variant="outline" className="min-h-11" disabled={pending} onClick={() => void act("cancel")}>取消</Button>}
      </div>
      <p className="text-xs font-medium text-muted-foreground">确认后由系统覆盖安装，保留本机笔记、草稿与附件。</p>
      <Button variant="quiet" className="min-h-11" disabled={pending} onClick={() => void act("open")}>打开原生更新页面</Button>
      <Button variant="quiet" className="min-h-11" disabled={pending} onClick={() => void act("releases")}>查看正式发布页</Button>
      {status?.state === "installing" && !busy && <Button variant="outline" className="min-h-11" onClick={() => setConfirmCancel(true)}>安装已取消，重新确认</Button>}
      <ConfirmDialog open={confirmCancel} onOpenChange={setConfirmCancel} title="确认系统安装已取消" description="请先在系统安装界面完成取消，再重新校验。正在安装时不要继续。" confirmLabel="已取消" cancelLabel="返回"
        onConfirm={() => { setConfirmCancel(false); void act("confirm-cancel"); }} />
    </div>
  </SettingSection>;
}
