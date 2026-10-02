import { describe, expect, test } from "vitest";
import { hasTodoBody, isTodoContent, toNoteContent, toTodoContent } from "@/components/MemoEditor/utils/todoMode";

describe("todo mode", () => {
  test("converts multiline notes without losing content", () => {
    const todo = toTodoContent("第一行\n第二行\n\n第四行");
    expect(todo).toBe("- [ ] 第一行\n  第二行\n  \n  第四行");
    expect(isTodoContent(todo)).toBe(true);
    expect(toNoteContent(todo)).toBe("第一行\n第二行\n\n第四行");
  });

  test("recognizes completed tasks and leaves regular notes unchanged", () => {
    expect(isTodoContent("- [x] 已完成")).toBe(true);
    expect(toTodoContent("- [X] 已完成")).toBe("- [X] 已完成");
    expect(toNoteContent("普通笔记")).toBe("普通笔记");
  });

  test("distinguishes an empty todo marker from a task body", () => {
    expect(hasTodoBody("- [ ] ")).toBe(false);
    expect(hasTodoBody("- [ ] 发送周报")).toBe(true);
  });
});
