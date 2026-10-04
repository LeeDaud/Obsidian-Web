import { create } from "@bufbuild/protobuf";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import MemoView from "@/components/MemoView/MemoView";
import { memoDeliveryLabel } from "@/hooks/useEchoBridge";
import { MemoSchema } from "@/types/proto/api/v1/memo_service_pb";

const mocks = vi.hoisted(() => ({ refetch: vi.fn(), error: vi.fn() }));
vi.mock("react-hot-toast", () => ({ toast: { error: mocks.error } }));
vi.mock("@/hooks/useEchoBridge", async (original) => ({
  ...(await original<typeof import("@/hooks/useEchoBridge")>()),
  useEchoBridgeStatus: () => ({ data: { enabled: true } }),
  useSavedMemoDeliveryStatus: () => ({ data: undefined, refetch: mocks.refetch }),
}));
vi.mock("@/contexts/AuthContext", () => ({ useAuth: () => ({ userTagsSetting: undefined }) }));
vi.mock("@/hooks/useCurrentUser", () => ({ default: () => ({ name: "users/1" }) }));
vi.mock("@/components/ColumnGrid/ColumnGridContext", () => ({
  useColumnGridUntrapped: () => ({ setUntrappedKey: vi.fn(), clearUntrappedKey: vi.fn() }),
}));
vi.mock("@/components/MemoContent/MentionResolutionContext", () => ({ useResolvedUser: () => undefined }));
vi.mock("@/lib/tag", () => ({ isMemoBlurred: () => false }));
vi.mock("@/components/MemoView/hooks", () => ({ useImagePreview: () => ({ previewState: { items: [] } }) }));
vi.mock("@/components/MemoView/components", async () => {
  const { useMemoViewContext } = await import("@/components/MemoView/MemoViewContext");
  return {
    MemoHeader: () => null,
    MemoCommentListView: () => null,
    MemoBody: () => {
      const { openEditor } = useMemoViewContext();
      return (
        <button type="button" onDoubleClick={openEditor}>
          正文
        </button>
      );
    },
  };
});
vi.mock("@/components/MemoEditor/loader", () => ({
  loadMemoEditor: async () => ({
    default: ({
      memo,
      defaultRelations,
      cacheKey,
    }: {
      memo?: { name: string };
      defaultRelations?: { relatedMemo?: { name: string } }[];
      cacheKey: string;
    }) => <div data-testid="editor" data-memo={memo?.name} data-parent={defaultRelations?.[0]?.relatedMemo?.name} data-cache={cacheKey} />,
  }),
}));
const memo = create(MemoSchema, { name: "memos/one", creator: "users/1", content: "saved", updateTime: { seconds: 42n } });
const mount = () =>
  render(
    <MemoryRouter>
      <MemoView memo={memo} />
    </MemoryRouter>,
  );

beforeEach(() => vi.clearAllMocks());
describe("double click follows current delivery evidence", () => {
  it.each(["unknown", "conflict"])("edits the original %s memo", async (state) => {
    mocks.refetch.mockResolvedValue({ data: { memo: memo.name, state, revision: 42 } });
    mount();
    fireEvent.doubleClick(screen.getByText("正文"));
    const editor = await screen.findByTestId("editor");
    expect(editor).toHaveAttribute("data-memo", memo.name);
    expect(editor).not.toHaveAttribute("data-parent");
    expect(editor).toHaveAttribute("data-cache", `inline-memo-editor-${memo.name}`);
  });
  it.each(["verified", "pending", "waiting_parent"])("creates a linked continuation for %s", async (state) => {
    mocks.refetch.mockResolvedValue({ data: { memo: memo.name, state, revision: 42 } });
    mount();
    fireEvent.doubleClick(screen.getByText("正文"));
    const editor = await screen.findByTestId("editor");
    expect(editor).not.toHaveAttribute("data-memo");
    expect(editor).toHaveAttribute("data-parent", memo.name);
    expect(editor).toHaveAttribute("data-cache", `continuation-${memo.name}`);
  });
  it("does not open or duplicate an editor before a query completes", async () => {
    let resolve!: (value: unknown) => void;
    mocks.refetch.mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    mount();
    fireEvent.doubleClick(screen.getByText("正文"));
    fireEvent.doubleClick(screen.getByText("正文"));
    expect(screen.queryByTestId("editor")).not.toBeInTheDocument();
    expect(mocks.refetch).toHaveBeenCalledOnce();
    resolve({ data: { state: "verified", revision: 42 } });
    expect(await screen.findByTestId("editor")).not.toHaveAttribute("data-memo");
  });
  it("keeps the original intact when status cannot be refreshed", async () => {
    mocks.refetch.mockResolvedValue({ isError: true, data: { state: "verified", revision: 42 } });
    mount();
    fireEvent.doubleClick(screen.getByText("正文"));
    await waitFor(() => expect(mocks.error).toHaveBeenCalledOnce());
    expect(screen.queryByTestId("editor")).not.toBeInTheDocument();
  });
  it("does not reuse an old verified version for the current edit", async () => {
    mocks.refetch.mockResolvedValue({ data: { state: "verified", revision: 41 } });
    mount();
    fireEvent.doubleClick(screen.getByText("正文"));
    expect(await screen.findByTestId("editor")).toHaveAttribute("data-memo", memo.name);
    expect(memoDeliveryLabel({ memo: memo.name, state: "pending", revision: 42, error: "offline" }, 42)).toBe("已保存");
  });
});
