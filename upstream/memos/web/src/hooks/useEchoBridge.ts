import { useCallback, useEffect, useState } from "react";
import { getRequestToken } from "@/connect";

interface EchoStatus {
  enabled: boolean;
}

interface EchoSubmissionStatus {
  submissionId: string;
  state: "pending" | "verified" | "conflict";
  revision: number;
}

async function responseError(response: Response): Promise<Error> {
  const body = (await response.json().catch(() => undefined)) as { error?: string } | undefined;
  return new Error(body?.error || `Echo request failed (${response.status})`);
}

export function useEchoBridgeStatus() {
  const [data, setData] = useState<EchoStatus>();
  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/echo/v1/status", { credentials: "include", signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw await responseError(response);
        setData((await response.json()) as EchoStatus);
      })
      .catch(() => undefined);
    return () => controller.abort();
  }, []);
  return { data };
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
