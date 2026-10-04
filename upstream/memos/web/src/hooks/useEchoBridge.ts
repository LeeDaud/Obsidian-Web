import { useQuery } from "@tanstack/react-query";
import { useCallback, useState } from "react";
import { getRequestToken } from "@/connect";

interface EchoStatus {
  enabled: boolean;
}

interface EchoSubmissionStatus {
  submissionId: string;
  state: "pending" | "waiting_parent" | "verified" | "conflict";
  revision: number;
}

export interface MemoDeliveryStatus {
  memo: string;
  sourceHash?: string;
  attachmentNames?: string[];
  state: "unknown" | "pending" | "waiting_parent" | "verified" | "conflict";
  revision?: number;
  path?: string;
  commit?: string;
  error?: string | null;
  previous?: { revision: number; path: string; commit?: string };
}

type StatusRequest = { memo: string; resolve: (value: MemoDeliveryStatus) => void; reject: (error: Error) => void };
let statusRequests: StatusRequest[] = [];
let statusTimer: ReturnType<typeof setTimeout> | undefined;

async function flushStatusRequests() {
  const requests = statusRequests;
  statusRequests = [];
  statusTimer = undefined;
  // Bounded batches also bound the backend's snapshot reads.
  for (let offset = 0; offset < requests.length; offset += 10) {
    const batch = requests.slice(offset, offset + 10);
    try {
      const token = await getRequestToken();
      const headers = new Headers({ "Content-Type": "application/json" });
      if (token) headers.set("Authorization", `Bearer ${token}`);
      const response = await fetch("/api/echo/v1/memo-statuses", {
        method: "POST",
        credentials: "include",
        signal: AbortSignal.timeout(25_000),
        headers,
        body: JSON.stringify({ memoUIDs: batch.map((item) => item.memo.replace(/^memos\//, "")) }),
      });
      if (!response.ok) throw await responseError(response);
      const statuses = (await response.json()) as MemoDeliveryStatus[];
      for (const item of batch) {
        const status = statuses.find((status) => status.memo === item.memo);
        if (!status) item.reject(new Error("投递状态缺失"));
        else item.resolve(status);
      }
    } catch (error) {
      for (const item of batch) item.reject(error instanceof Error ? error : new Error("投递状态查询失败"));
    }
  }
}

export function useMemoDeliveryStatus(
  owner: string,
  memo: string,
  revision: string,
  content: string,
  attachments: string,
  enabled: boolean,
) {
  return useQuery({
    queryKey: ["echo-memo-status", owner, memo, revision, content, attachments],
    enabled,
    queryFn: async () => {
      const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(content));
      const hash = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
      const status = await new Promise<MemoDeliveryStatus>((resolve, reject) => {
        statusRequests.push({ memo, resolve, reject });
        if (statusTimer === undefined) statusTimer = setTimeout(() => void flushStatusRequests(), 40);
      });
      const names = [...(status.attachmentNames ?? [])].sort().join("\n");
      if (status.sourceHash !== hash || names !== attachments) {
        return {
          memo,
          state: "unknown" as const,
          previous:
            status.state === "verified" && status.path && status.revision
              ? { path: status.path, revision: status.revision, commit: status.commit }
              : status.previous,
        };
      }
      return status;
    },
    staleTime: 30_000,
    retry: false,
    refetchInterval: (query) => (query.state.data?.state === "verified" || query.state.data?.state === "conflict" ? false : 30_000),
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: "always",
  });
}

async function responseError(response: Response): Promise<Error> {
  const body = (await response.json().catch(() => undefined)) as { error?: string } | undefined;
  return new Error(body?.error || `Echo request failed (${response.status})`);
}

export function useEchoBridgeStatus() {
  return useQuery({
    queryKey: ["echo-bridge-status"],
    staleTime: 60_000,
    retry: false,
    queryFn: async ({ signal }) => {
      const response = await fetch("/api/echo/v1/status", { credentials: "include", signal });
      if (!response.ok) throw await responseError(response);
      return (await response.json()) as EchoStatus;
    },
  });
}

export function useSubmitMemoToEcho() {
  const [isPending, setPending] = useState(false);
  const mutate = useCallback(
    async (memoName: string, callbacks: { onSuccess: (status: EchoSubmissionStatus) => void; onError: (error: Error) => void }) => {
      if (isPending) return;
      setPending(true);
      try {
        const token = await getRequestToken();
        const headers = new Headers();
        if (token) headers.set("Authorization", `Bearer ${token}`);
        const memoUID = memoName.replace(/^memos\//, "");
        const response = await fetch(`/api/echo/v1/memos/${encodeURIComponent(memoUID)}/submissions`, {
          method: "POST",
          credentials: "include",
          headers,
        });
        if (!response.ok) throw await responseError(response);
        callbacks.onSuccess((await response.json()) as EchoSubmissionStatus);
      } catch (error) {
        callbacks.onError(error instanceof Error ? error : new Error("Echo request failed"));
      } finally {
        setPending(false);
      }
    },
    [isPending],
  );
  return { isPending, mutate };
}
