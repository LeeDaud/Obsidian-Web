import { parse } from "../../../../upstream/memos/web/node_modules/.pnpm/node_modules/micromark/lib/parse.js";
import { preprocess } from "../../../../upstream/memos/web/node_modules/.pnpm/node_modules/micromark/lib/preprocess.js";
import { postprocess } from "../../../../upstream/memos/web/node_modules/.pnpm/node_modules/micromark/lib/postprocess.js";
import { decodeString } from "../../../../upstream/memos/web/node_modules/micromark-util-decode-string/index.js";

/** Edit only parsed Markdown destinations: code, alt text and titles retain their original bytes. */
export function internalizeManagedImages(source: string, attachments: { name: string; filename: string }[], origin: string): string {
  return rewriteManagedDestinations(source, attachments.map((item) => ({ ...item, replacement: `echo-attachment:${item.name.slice("attachments/".length)}` })), origin, false);
}

export function projectManagedImages(source: string, attachments: { name: string; filename: string; replacement: string }[], origin: string): string {
  return rewriteManagedDestinations(source, attachments, origin, true);
}

function rewriteManagedDestinations(source: string, attachments: { name: string; filename: string; replacement: string }[], origin: string, allowSuffix: boolean): string {
  const events = postprocess(parse().document().write(preprocess()(source, undefined, true)));
  const replacements: { start: number; end: number; value: string }[] = [];
  for (const [kind, token] of events) {
    if (kind !== "enter" || !["resourceDestinationString", "definitionDestinationString"].includes(token.type)) continue;
    const start = token.start.offset, end = token.end.offset;
    if (start == null || end == null) continue;
    let url: URL;
    try { url = new URL(decodeString(source.slice(start, end)), origin); } catch { continue; }
    if (url.origin !== new URL(origin).origin || (!allowSuffix && (url.search || url.hash)) || url.username || url.password) continue;
    const attachment = attachments.find((item) => {
      if (!/^attachments\/[a-zA-Z0-9][a-zA-Z0-9_-]{0,119}$/.test(item.name)) return false;
      const path = `/file/${item.name}`;
      if (url.pathname === path) return true;
      if (!url.pathname.startsWith(path + "/")) return false;
      try { return decodeURIComponent(url.pathname.slice(path.length + 1)) === item.filename; } catch { return false; }
    });
    if (attachment) replacements.push({ start, end, value: attachment.replacement });
  }
  for (const item of replacements.sort((a, b) => b.start - a.start)) source = source.slice(0, item.start) + item.value + source.slice(item.end);
  return source;
}
