import { useQuery } from "@tanstack/react-query";
import { ChevronDownIcon, SquareCheckIcon, SquareIcon } from "lucide-react";
import { type ComponentProps, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import SidebarSection from "@/components/AppSidebar/SidebarSection";
import { SIDEBAR_ROW_FOCUS_CLASSES } from "@/components/AppSidebar/SidebarRow";
import { Button } from "@/components/ui/button";
import { useAppSidebar } from "@/contexts/AppSidebarContext";
import { useAuth } from "@/contexts/AuthContext";
import useMediaQuery from "@/hooks/useMediaQuery";
import { cn } from "@/lib/utils";
import { loadSidebarTasks } from "./connect";
import type { SidebarTask } from "./tasks";

export function SidebarTasks({ className, ...props }: ComponentProps<"div">) {
  const { mobileOpen, setMobileOpen } = useAppSidebar();
  const { isInitialized } = useAuth();
  const desktop = useMediaQuery("md");
  const [showCompleted, setShowCompleted] = useState(false);
  const [pendingLimit, setPendingLimit] = useState(30);
  const [completedLimit, setCompletedLimit] = useState(30);
  // Under the list prefix so every existing memo mutation invalidates this aggregate too.
  const query = useQuery({ queryKey: ["memos", "list", "sidebar-tasks"],
    queryFn: ({ signal }) => loadSidebarTasks(signal), enabled: isInitialized && (desktop || mobileOpen), staleTime: 0,
    retry: false });
  const pending = useMemo(() => query.data?.tasks.filter((task) => !task.completed) ?? [], [query.data]);
  const completed = useMemo(() => query.data?.tasks.filter((task) => task.completed) ?? [], [query.data]);
  const row = (task: SidebarTask) => {
    const Icon = task.completed ? SquareCheckIcon : SquareIcon;
    return (
      <Link key={task.id} to={`/${task.memoName}`} onClick={() => setMobileOpen(false)} data-task-id={task.id} title={`${task.label}\n${task.source}`}
        aria-label={`${task.completed ? "已完成" : "待办"}：${task.label}，打开来源笔记`}
        className={cn("flex min-h-11 w-full items-start gap-2 rounded-md px-2 py-2 text-foreground transition-colors hover:bg-accent", SIDEBAR_ROW_FOCUS_CLASSES)}>
        <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" strokeWidth={1.8} />
        <span className="min-w-0 flex-1">
          <span className={cn("block text-sm leading-snug wrap-anywhere line-clamp-2", task.completed && "text-muted-foreground line-through")}>{task.label}</span>
          <span className="mt-1 block truncate text-xs font-medium text-muted-foreground">{task.source}</span>
        </span>
      </Link>
    );
  };
  return (
    <div {...props} className={className} data-slot="sidebar-tasks" data-echo-sidebar-tasks>
      <SidebarSection label={<span>任务 <span className="text-muted-foreground">{query.data ? pending.length : "—"}</span></span>} ariaLabel="任务">
        {query.isPending && <p role="status" className="px-2 py-2 text-sm text-muted-foreground">正在读取任务…</p>}
        {query.isError && <div role="alert" className="px-2 py-2 text-sm text-muted-foreground">
          <p>{query.data ? "任务更新未完成，以下为上次读取结果" : "任务读取未完成，请重试"}</p>
          <Button variant="quiet" className="min-h-11" disabled={query.isFetching} onClick={() => void query.refetch()}>重试</Button>
        </div>}
        {query.isFetching && query.data && <p role="status" className="px-2 text-xs font-medium text-muted-foreground">正在更新任务…</p>}
        {!query.isPending && !query.isError && pending.length === 0 && <p className="px-2 py-2 text-sm text-muted-foreground">
          {completed.length ? "暂无未完成任务" : "暂无任务，在笔记中添加待办即可"}
        </p>}
        {pending.slice(0, pendingLimit).map(row)}
        {pending.length > pendingLimit && <Button variant="quiet" className="min-h-11 justify-start" onClick={() => setPendingLimit((value) => value + 30)}>
          继续显示任务（剩余 {pending.length - pendingLimit}）
        </Button>}
        {completed.length > 0 && <>
          <button type="button" aria-expanded={showCompleted} onClick={() => setShowCompleted((value) => !value)}
            className={cn("flex min-h-11 items-center gap-1 rounded-md px-2 text-sm text-muted-foreground", SIDEBAR_ROW_FOCUS_CLASSES)}>
            <ChevronDownIcon aria-hidden="true" className={cn("size-4", !showCompleted && "-rotate-90")} />
            已完成（{completed.length}）
          </button>
          {showCompleted && completed.slice(0, completedLimit).map(row)}
          {showCompleted && completed.length > completedLimit && <Button variant="quiet" className="min-h-11 justify-start" onClick={() => setCompletedLimit((value) => value + 30)}>
            继续显示已完成（剩余 {completed.length - completedLimit}）
          </Button>}
        </>}
      </SidebarSection>
    </div>
  );
}
