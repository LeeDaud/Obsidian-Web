import { strict as assert } from "node:assert";
import { filterMemos } from "./src/query";
const memos = [
  { name: "memos/a", creator: "users/device", content: "中文 #记录\n- [ ] 待办", tags: ["记录", "work"], state: "NORMAL", createTime: "2026-10-09T00:00:00Z" },
  { name: "memos/b", creator: "users/device", content: "- [x] 完成", tags: [], pinned: true, state: "NORMAL", createTime: "2026-10-08T00:00:00Z" },
  { name: "memos/c", creator: "users/device", content: "archive", tags: ["work"], state: "ARCHIVED", createTime: "2026-10-07T00:00:00Z" },
];
assert.deepEqual(filterMemos(memos, 'creator == "users/device" && content.contains("中文")', "NORMAL", "create_time desc").map((m) => m.name), ["memos/a"]);
assert.deepEqual(filterMemos(memos, 'tag in ["记录"] && tag in ["work"]', "NORMAL", "").map((m) => m.name), ["memos/a"]);
assert.deepEqual(filterMemos(memos, "has_task_list && has_incomplete_tasks", "NORMAL", "").map((m) => m.name), ["memos/a"]);
assert.deepEqual(filterMemos(memos, "created_ts >= timestamp(1791504000) && created_ts < timestamp(1791590400)", "NORMAL", "").map((m) => m.name), ["memos/a"]);
assert.deepEqual(filterMemos(memos, "", "NORMAL", "pinned desc, create_time desc").map((m) => m.name), ["memos/b", "memos/a"]);
assert.deepEqual(filterMemos(memos, 'tags.exists(t, t == "work")', "ARCHIVED", "").map((m) => m.name), ["memos/c"]);
assert.throws(() => filterMemos(memos, "1 + 1", "NORMAL", ""));
assert.throws(() => filterMemos(memos, "", "NORMAL", "content desc"));
console.log("CEL filter tests passed (8 assertions)");
