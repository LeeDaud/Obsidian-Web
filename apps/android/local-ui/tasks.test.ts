import { strict as assert } from "node:assert";
import { collectTasks } from "./src/tasks";

const tasks = collectTasks([
  { name: "memos/local", state: "NORMAL", createTime: "2026-10-13T01:00:00Z", content:
    "# 今日安排\n\n- [ ] 买 **牛奶**\n  - [x] 子任务\n- [X] 完成\n1. [ ] 有序任务\n\n```md\n- [ ] 围栏示例\n```\n\n    - [ ] 缩进代码示例\n\n`- [ ] 行内示例`\n\n\\- [ ] 转义示例" },
  { name: "memos/remote", state: "NORMAL", createTime: "2026-10-12T01:00:00Z", content: "> - [ ] [远端任务](https://example.test)\n\n- [ ] 第一行\n  第二行\n\n- [ ] " },
  { name: "memos/archive", state: "ARCHIVED", content: "- [ ] 已归档" },
]);
assert.deepEqual(tasks.map((task) => task.label), ["买 牛奶", "有序任务", "远端任务", "第一行 第二行", "子任务", "完成"]);
assert.equal(tasks.filter((task) => task.completed).length, 2);
assert.equal(tasks.find((task) => task.label === "买 牛奶")?.source, "今日安排");
assert.equal(tasks.find((task) => task.label === "远端任务")?.memoName, "memos/remote");
assert.equal(new Set(tasks.map((task) => task.id)).size, tasks.length);
assert.deepEqual(collectTasks([{ name: "memos/local", content: "- [x] 新内容" }]).map((task) => [task.label, task.completed]), [["新内容", true]]);
assert.deepEqual(collectTasks([]), []);
console.log("Sidebar Markdown tasks: parsing, nested/ordered/quoted tasks, code exclusion, archive, ordering and refresh passed");
