import { useEffect, useRef, useState } from "react";
import { cacheService } from "@/components/MemoEditor/services/cacheService";
import { Button } from "@/components/ui/button";
import { type MemoDeliveryStatus as DeliveryStatus, useMemoDeliveryStatus, useSubmitMemoToEcho } from "@/hooks/useEchoBridge";
import { cn } from "@/lib/utils";
import type { Memo } from "@/types/proto/api/v1/memo_service_pb";

export function deliveryLabel(status: DeliveryStatus | undefined, revision: number): string {
  if (!status || status.state === "unknown") return "已保存 · 投递状态未确认";
  if (status.revision !== revision) return "已保存 · 版本已变化，需刷新笔记";
  if (status.state === "verified") return "已投递到仓库";
  if (status.state === "conflict") return "投递冲突 · 需处理";
  if (status.error) return "投递失败 · 等待重试";
  if (status.state === "waiting_parent") return "等待前一条笔记投递";
  return "已保存 · 等待投递";
}

type Props = { memo: Memo; owner: string; detail?: boolean; className?: string };

export function MemoDeliveryStatus({ memo, owner, detail = false, className }: Props) {
  const host = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);
  const [expanded, setExpanded] = useState(detail);
  const [retryError, setRetryError] = useState("");
  const [draftChanged, setDraftChanged] = useState(false);
  const revision = Number(memo.updateTime?.seconds || memo.createTime?.seconds || 0);
  const versionKey = `${revision}:${memo.updateTime?.nanos ?? 0}`;
  const query = useMemoDeliveryStatus(
    owner,
    memo.name,
    versionKey,
    memo.content,
    memo.attachments
      .map((item) => item.name)
      .sort()
      .join("\n"),
    visible,
  );
  const retry = useSubmitMemoToEcho();

  useEffect(() => {
    const element = host.current;
    if (!element || typeof IntersectionObserver === "undefined") {
      setVisible(true);
      return;
    }
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const check = () => {
      try {
        const draft = cacheService.loadDraft(cacheService.key(owner, `inline-memo-editor-${memo.name}`));
        setDraftChanged(
          Boolean(draft.content || draft.attachments.length) &&
            (draft.content !== memo.content ||
              draft.attachments
                .map((item) => item.name)
                .sort()
                .join("\n") !==
                memo.attachments
                  .map((item) => item.name)
                  .sort()
                  .join("\n")),
        );
      } catch {
        // Storage availability is handled by the editor; do not claim a draft was saved.
      }
    };
    check();
    window.addEventListener("storage", check);
    window.addEventListener("focus", check);
    return () => {
      window.removeEventListener("storage", check);
      window.removeEventListener("focus", check);
    };
  }, [owner, memo.name, memo.content, memo.attachments]);

  const status = query.data;
  const label = query.isError
    ? status
      ? `${deliveryLabel(status, revision)} · 尚未刷新`
      : "已保存 · 状态暂不可查询"
    : query.isPending
      ? "已保存 · 正在查询投递状态"
      : deliveryLabel(status, revision);
  const matchingVersion = status?.revision === revision;

  return (
    <div ref={host} data-slot="memo-delivery-status" className={cn("w-full min-w-0 text-[13px] leading-normal", className)}>
      <div className="flex flex-wrap items-center gap-1.5">
        <Button
          variant="ghost"
          className="min-h-11 min-w-11 max-w-full whitespace-normal px-3 text-[13px] text-muted-foreground"
          aria-expanded={expanded}
          onClick={() => setExpanded((value) => !value)}
        >
          <span aria-live="polite">{label}</span>
        </Button>
        {draftChanged && <span className="text-muted-foreground">有设备暂存修改 · 尚未保存</span>}
      </div>
      {expanded && (
        <div className="space-y-1.5 px-3 pb-3 text-muted-foreground break-words">
          <p>已保存到 Memos；设备暂存修改需保存后才会投递。</p>
          {matchingVersion && status?.path && <p>仓库文件：{status.path}</p>}
          {matchingVersion && status?.commit && <p className="break-all">核验提交：{status.commit}</p>}
          {status?.previous && <p>其他版本已入库：{status.previous.path}；不代表当前修改已投递。</p>}
          {matchingVersion && status?.error && <p className="text-destructive">{status.error}</p>}
          <p>电脑 Obsidian 是否已拉取：尚未核验。</p>
          {status?.state === "unknown" && <p>缺少当前版本的投递凭证，查询不会自动补投。</p>}
          {retryError && <p className="text-destructive">{retryError}</p>}
          <div className="flex flex-wrap gap-3">
            <Button variant="outline" className="min-h-11 min-w-11" disabled={query.isFetching} onClick={() => void query.refetch()}>
              刷新状态
            </Button>
            {(status?.state === "unknown" || (matchingVersion && (status?.error || status?.state === "conflict"))) && (
              <Button
                variant="outline"
                className="min-h-11 min-w-11"
                disabled={retry.isPending || query.isFetching}
                onClick={() => {
                  setRetryError("");
                  retry.mutate(memo.name, {
                    onSuccess: () => void query.refetch(),
                    onError: (error) => setRetryError(error.message),
                  });
                }}
              >
                {retry.isPending ? "正在提交…" : "重试投递已保存版本"}
              </Button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
