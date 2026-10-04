import { create } from "@bufbuild/protobuf";
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { cacheService } from "@/components/MemoEditor/services/cacheService";
import { deliveryLabel, MemoDeliveryStatus } from "@/components/MemoView/MemoDeliveryStatus";
import { MemoSchema } from "@/types/proto/api/v1/memo_service_pb";

const mocks = vi.hoisted(() => ({ query: vi.fn(), retry: vi.fn(), refetch: vi.fn() }));
vi.mock("@/hooks/useEchoBridge", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/hooks/useEchoBridge")>()),
  useMemoDeliveryStatus: mocks.query,
  useSubmitMemoToEcho: () => ({ isPending: false, mutate: mocks.retry }),
}));
const memo = create(MemoSchema, { name: "memos/one", content: "saved", updateTime: { seconds: 42n } });

beforeEach(() => {
  localStorage.clear();
  mocks.query.mockReturnValue({
    data: { memo: memo.name, state: "verified", revision: 42, path: "00_Inbox/one.md", commit: "abc" },
    isPending: false,
    isError: false,
    isFetching: false,
    refetch: mocks.refetch,
  });
});

describe("phone delivery status", () => {
  it.each([
    [undefined, "已保存"],
    [{ memo: memo.name, state: "unknown" as const }, "已保存"],
    [{ memo: memo.name, state: "verified" as const, revision: 41 }, "已保存"],
    [{ memo: memo.name, state: "pending" as const, revision: 42 }, "投递中"],
    [{ memo: memo.name, state: "waiting_parent" as const, revision: 42 }, "投递中"],
    [{ memo: memo.name, state: "pending" as const, revision: 42, error: "offline" }, "已保存"],
  ])("maps only reliable evidence to labels", (status, expected) => {
    expect(deliveryLabel(status, 42)).toBe(expected);
  });

  it("shows receipt details without claiming that the computer pulled them", () => {
    render(<MemoDeliveryStatus memo={memo} owner="users/1" detail />);
    expect(screen.getByText("已投递")).toBeInTheDocument();
    expect(screen.getByText("仓库文件：00_Inbox/one.md")).toBeInTheDocument();
    expect(screen.getByText("电脑 Obsidian 是否已拉取：尚未核验。")).toBeInTheDocument();
    expect(mocks.retry).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("刷新状态"));
    expect(mocks.refetch).toHaveBeenCalledOnce();
    expect(mocks.retry).not.toHaveBeenCalled();
  });

  it("keeps known receipts with an explicit stale label on query failure", () => {
    mocks.query.mockReturnValue({ data: { memo: memo.name, state: "verified", revision: 42 }, isError: true, refetch: mocks.refetch });
    render(<MemoDeliveryStatus memo={memo} owner="users/1" />);
    expect(screen.getByText("已投递")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "已投递" }));
    expect(screen.getByText("投递状态尚未刷新，请重试查询。")).toBeInTheDocument();
  });

  it("shows local modifications alongside the saved receipt", () => {
    cacheService.saveNow(cacheService.key("users/1", "inline-memo-editor-memos/one"), "unsaved");
    render(<MemoDeliveryStatus memo={memo} owner="users/1" />);
    fireEvent.click(screen.getByRole("button", { name: "已投递" }));
    expect(screen.getByText("有设备暂存修改 · 尚未保存到 Memos")).toBeInTheDocument();
    expect(screen.getByText("已投递")).toBeInTheDocument();
  });

  it("requires an explicit click to retry a missing historical submission", () => {
    mocks.query.mockReturnValue({ data: { memo: memo.name, state: "unknown" }, refetch: mocks.refetch });
    render(<MemoDeliveryStatus memo={memo} owner="users/1" detail />);
    expect(mocks.retry).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("重试投递已保存版本"));
    expect(mocks.retry).toHaveBeenCalledWith(memo.name, expect.any(Object));
  });
});
