import { unified } from "../../../../upstream/memos/web/node_modules/unified/index.js";
import remarkParse from "../../../../upstream/memos/web/node_modules/remark-parse/index.js";
import { buildRemarkPlugins } from "@/components/MemoContent/pipeline";
const processor = unified().use(remarkParse).use(buildRemarkPlugins());
const cache = new Map<string, { body: string; tags: string[]; property: Record<string, boolean> }>();
export function profileMemo(memo: any): any {
  if (memo.localMetadata?.remote) return memo;
  let parsed = cache.get(memo.name);
  if (!parsed || parsed.body !== memo.content) {
    const tags = new Set<string>();
    const property = { hasLink: false, hasCode: false, hasTaskList: false, hasIncompleteTasks: false };
    const tree = processor.runSync(processor.parse(memo.content), { value: memo.content });
    const visit = (node: any) => {
      const tag = node.data?.hProperties?.["data-tag"];
      if (typeof tag === "string") tags.add(tag);
      if (node.type === "link" || node.type === "linkReference") property.hasLink = true;
      if (node.type === "code" || node.type === "inlineCode") property.hasCode = true;
      if (node.type === "listItem" && typeof node.checked === "boolean") { property.hasTaskList = true; if (!node.checked) property.hasIncompleteTasks = true; }
      node.children?.forEach(visit);
    };
    visit(tree); parsed = { body: memo.content, tags: [...tags], property }; cache.set(memo.name, parsed);
    if (cache.size > 2000) cache.delete(cache.keys().next().value!);
  }
  return { ...memo, tags: parsed.tags, property: parsed.property };
}
