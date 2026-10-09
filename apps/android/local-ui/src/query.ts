import { Environment, type ASTNode } from "@marcbachmann/cel-js";

export type LocalMemo = Record<string, any>;
const environment = new Environment({ unlistedVariablesAreDyn: true, homogeneousAggregateLiterals: false });
function adaptTagMembership(expression: string): string {
  if (!expression.trim()) return expression;
  const tree = environment.parse(expression).ast;
  const replacements: { start: number; end: number; value: string }[] = [];
  const visit = (value: unknown) => {
    if (Array.isArray(value)) { value.forEach(visit); return; }
    if (!value || typeof value !== "object" || !("op" in value)) return;
    const node = value as ASTNode;
    if (node.op === "in" && node.args[0].op === "id" && node.args[0].args === "tag") {
      const right = node.args[1];
      replacements.push({ start: node.range.start, end: node.range.end, value: `tags.exists(_echo_tag, _echo_tag in ${expression.slice(right.range.start, right.range.end)})` });
      return;
    }
    visit(node.args);
  };
  visit(tree);
  for (const item of replacements.sort((a, b) => b.start - a.start)) expression = expression.slice(0, item.start) + item.value + expression.slice(item.end);
  return expression;
}

export function filterMemos(memos: LocalMemo[], filter: string, state: string, orderBy: string): LocalMemo[] {
  if (filter.length > 4096) throw new Error("筛选表达式过长");
  const predicate = filter.trim() ? environment.parse(adaptTagMembership(filter)) : undefined;
  const result = memos.filter((memo) => {
    if (state && memo.state !== state) return false;
    if (!predicate) return true;
    const tags: string[] = memo.tags ?? [];
    const body = memo.content ?? "";
    const fields = {
      ...memo, tags, tag: tags, now: new Date(), created_ts: new Date(memo.createTime), updated_ts: new Date(memo.updateTime ?? memo.createTime),
      has_link: memo.property?.hasLink ?? /https?:\/\//.test(body),
      has_code: memo.property?.hasCode ?? /`/.test(body), has_location: Boolean(memo.location),
      has_task_list: memo.property?.hasTaskList ?? /(?:^|\n)\s*[-*+] \[[ xX]\] /.test(body),
      has_incomplete_tasks: memo.property?.hasIncompleteTasks ?? /(?:^|\n)\s*[-*+] \[ \] /.test(body),
    };
    const value = predicate(fields);
    if (typeof value !== "boolean") throw new Error("筛选必须返回布尔值");
    return value;
  });
  const sorting = (orderBy || "pinned desc, create_time desc").split(",").map((part) => {
    const [field, direction = "asc", extra] = part.trim().split(/\s+/);
    if (!new Set(["pinned", "create_time", "update_time", "name"]).has(field) || !["asc", "desc"].includes(direction) || extra) throw new Error("排序字段不支持");
    return { field, sign: direction === "desc" ? -1 : 1 };
  });
  return result.sort((a, b) => {
    for (const { field, sign } of sorting) {
      const value = (memo: LocalMemo) => field === "create_time" ? Date.parse(memo.createTime) : field === "update_time" ? Date.parse(memo.updateTime) : field === "pinned" ? Number(Boolean(memo.pinned)) : memo.name;
      const av = value(a), bv = value(b);
      if (av !== bv) return (av < bv ? -1 : 1) * sign;
    }
    return a.name.localeCompare(b.name);
  });
}
