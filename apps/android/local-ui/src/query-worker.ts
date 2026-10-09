import { filterMemos } from "./query";
self.onmessage = (event) => {
  try { self.postMessage({ id: event.data.id, names: filterMemos(event.data.memos, event.data.filter ?? "", event.data.state ?? "", event.data.orderBy ?? "").map((memo) => memo.name) }); }
  catch (error) { self.postMessage({ id: event.data.id, error: error instanceof Error ? error.message : "筛选表达式无效" }); }
};
