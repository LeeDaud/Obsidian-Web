import { createHash, webcrypto } from "node:crypto";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { memoDeliveryLabel, useMemoDeliveryStatus, useSubmitMemoToEcho } from "@/hooks/useEchoBridge";

vi.mock("@/connect", () => ({ getRequestToken: async () => "user-access-token" }));
const hash = createHash("sha256").update("saved").digest("hex");
const receipt = (memo: string) => ({
  memo,
  state: "verified",
  revision: 42,
  sourceHash: hash,
  attachmentNames: [],
  path: "00_Inbox/one.md",
});
let client: QueryClient;
const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;

beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.stubGlobal("crypto", webcrypto);
  vi.stubGlobal(
    "fetch",
    vi.fn(async (_url, options) => {
      const input = JSON.parse(options.body) as { memoUIDs: string[] };
      return new Response(JSON.stringify(input.memoUIDs.map((uid) => receipt(`memos/${uid}`))), { status: 200 });
    }),
  );
});
afterEach(() => {
  client.clear();
  vi.unstubAllGlobals();
});

it("batches visible cards in one read-only request", async () => {
  const { result } = renderHook(
    () => [
      useMemoDeliveryStatus("users/1", "memos/one", "42:0", "saved", "", true),
      useMemoDeliveryStatus("users/1", "memos/two", "42:0", "saved", "", true),
    ],
    { wrapper },
  );
  await waitFor(() => expect(result.current.every((query) => query.isSuccess)).toBe(true));
  expect(fetch).toHaveBeenCalledOnce();
  expect(fetch).toHaveBeenCalledWith("/api/echo/v1/memo-statuses", expect.objectContaining({ method: "POST" }));
});

it("does not query cards outside the viewport", async () => {
  renderHook(() => useMemoDeliveryStatus("users/1", "memos/one", "42:0", "saved", "", false), { wrapper });
  expect(fetch).not.toHaveBeenCalled();
});

it.each([
  ["different body", ""],
  ["saved", "attachments/new"],
])("does not reuse success for different content or attachments", async (content, names) => {
  const { result } = renderHook(() => useMemoDeliveryStatus("users/1", "memos/one", "42:0", content, names, true), { wrapper });
  await waitFor(() => expect(result.current.isSuccess).toBe(true));
  expect(result.current.data?.state).toBe("unknown");
});

it("reports a network failure as a query error without submitting", async () => {
  vi.mocked(fetch).mockRejectedValue(new Error("offline"));
  const { result } = renderHook(() => useMemoDeliveryStatus("users/1", "memos/one", "42:0", "saved", "", true), { wrapper });
  await waitFor(() => expect(result.current.isError).toBe(true));
  expect(fetch).toHaveBeenCalledOnce();
});

it("refreshes the same saved memo immediately after explicit submission and follows verification", async () => {
  let state = "unknown";
  let submissions = 0;
  vi.mocked(fetch).mockImplementation(async (url) => {
    if (String(url).endsWith("/submissions")) {
      submissions++;
      state = "pending";
      return new Response(JSON.stringify({ state, revision: 42 }));
    }
    return new Response(JSON.stringify([{ ...receipt("memos/one"), state }]));
  });
  const { result } = renderHook(
    () => ({
      status: useMemoDeliveryStatus("users/1", "memos/one", "42:0", "saved", "", true),
      submit: useSubmitMemoToEcho(),
    }),
    { wrapper },
  );
  await waitFor(() => expect(result.current.status.isSuccess).toBe(true));
  expect(memoDeliveryLabel(result.current.status.data, 42)).toBe("已保存");
  const success = vi.fn();
  await act(() => result.current.submit.mutate("memos/one", { onSuccess: success, onError: vi.fn() }));
  await waitFor(() => expect(memoDeliveryLabel(result.current.status.data, 42)).toBe("投递中"));
  expect(success).toHaveBeenCalledOnce();
  state = "verified";
  await act(() => result.current.status.refetch());
  await waitFor(() => expect(memoDeliveryLabel(result.current.status.data, 42)).toBe("已投递"));
  expect(submissions).toBe(1);
});
