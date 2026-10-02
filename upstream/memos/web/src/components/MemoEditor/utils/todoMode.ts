const OPEN_TODO_PREFIX = "- [ ] ";
const TODO_LINE = /^\s*- \[([ xX])\](?:\s(.*))?$/;

export const isTodoContent = (content: string): boolean => TODO_LINE.test(content.split("\n", 1)[0] ?? "");

export const hasTodoBody = (content: string): boolean => {
  const match = content.split("\n", 1)[0]?.match(TODO_LINE);
  return Boolean(match?.[2]?.trim());
};

export const toTodoContent = (content: string): string => {
  if (isTodoContent(content)) return content;
  const normalized = content.replace(/^\s+|\s+$/g, "");
  if (!normalized) return OPEN_TODO_PREFIX;
  const [first, ...rest] = normalized.split("\n");
  return `${OPEN_TODO_PREFIX}${first}${rest.map((line) => `\n  ${line}`).join("")}`;
};

export const toNoteContent = (content: string): string => {
  const lines = content.split("\n");
  const match = lines[0]?.match(TODO_LINE);
  if (!match) return content;
  const first = match[2] ?? "";
  const rest = lines.slice(1).map((line) => (line.startsWith("  ") ? line.slice(2) : line));
  return [first, ...rest].join("\n").replace(/\s+$/g, "");
};
