import { fromMarkdown } from "../../../../upstream/memos/web/node_modules/mdast-util-from-markdown/index.js";
import { gfm } from "../../../../upstream/memos/web/node_modules/micromark-extension-gfm/index.js";
import { gfmFromMarkdown } from "../../../../upstream/memos/web/node_modules/mdast-util-gfm/index.js";

export interface SidebarTask {
  id: string;
  memoName: string;
  label: string;
  completed: boolean;
  source: string;
  created: string;
}
interface TaskMemo { name: string; content: string; state?: string; createTime?: string }

// Use the reader's CommonMark/GFM grammar: code examples are never tasks.
export function collectTasks(memos: TaskMemo[]): SidebarTask[] {
  const tasks: SidebarTask[] = [];
  for (const memo of memos) {
    if (memo.state === "ARCHIVED") continue;
    const tree = fromMarkdown(memo.content, { extensions: [gfm()], mdastExtensions: [gfmFromMarkdown()] });
    const text = (node: any): string => {
      if (node.type === "code" || node.type === "html" || node.type === "list") return "";
      if (node.type === "image") return node.alt ?? "";
      if (typeof node.value === "string") return node.value;
      return node.children?.map(text).join(node.type === "listItem" ? " " : "") ?? "";
    };
    const sourceNode = tree.children.find((node) => node.type === "heading" || node.type === "paragraph");
    const source = (sourceNode ? text(sourceNode) : "").replace(/\s+/g, " ").trim().slice(0, 80) || "任务笔记";
    const visit = (node: any) => {
      if (node.type === "listItem" && typeof node.checked === "boolean") {
        const label = text(node).replace(/\s+/g, " ").trim() || "未命名任务";
        tasks.push({ id: `${memo.name}:${node.position?.start.offset}`, memoName: memo.name, label,
          completed: node.checked, source, created: memo.createTime ?? "" });
      }
      node.children?.forEach(visit);
    };
    visit(tree);
  }
  return tasks.sort((a, b) => Number(a.completed) - Number(b.completed) || b.created.localeCompare(a.created));
}
