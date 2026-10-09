import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
export type SSEConnectionStatus = "connected" | "disconnected" | "connecting";
export const useSSEConnectionStatus = (): SSEConnectionStatus => "connected";
export function useLiveMemoRefresh() {
  const client = useQueryClient();
  useEffect(() => {
    const refresh = () => { void client.invalidateQueries(); };
    const timer = setInterval(() => { if (document.visibilityState === "visible") void client.invalidateQueries({ queryKey: ["echo-memo-status"] }); }, 5000);
    window.addEventListener("echo-native-change", refresh);
    return () => { clearInterval(timer); window.removeEventListener("echo-native-change", refresh); };
  }, [client]);
}
