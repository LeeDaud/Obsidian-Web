import { test, expect, type Page } from '@playwright/test';

async function boot(page: Page) {
  await page.goto('/');
  await page.waitForFunction(() => (window as any).__captureReady === true);
  await expect(page.locator('.cm-editor')).toBeVisible();
}
async function current(page: Page) {
  return page.evaluate(() => {
    const id = sessionStorage.getItem('ignis-capture:session');
    return JSON.parse(localStorage.getItem('ignis-capture:draft:' + id)!);
  });
}
async function write(page: Page, text: string) {
  await page.locator('.cm-content').click();
  await page.keyboard.insertText(text);
}
async function saved(page: Page) {
  await page.locator('.ignis-capture-upload').click();
  await expect.poll(async () => { const d = await current(page); return d.revision > 0 && d.acked === d.revision; }).toBe(true);
}
test('opens a real native editor, keeps blank files virtual, saves Chinese and restores on refresh', async ({ page }) => {
  await boot(page);
  expect(await page.title()).toContain('Obsidian 1.12.7');
  const original = await current(page);
  expect(original.filename).toMatch(/^\d{8}-\d{6}\.md$/);
  const tree = await (await page.request.get('/api/fs/tree?vault=Inbox')).text();
  expect(tree).not.toContain(original.filename);
  await page.waitForTimeout(6500);
  await expect(page.locator('.cm-editor')).toBeVisible();
  await write(page, '即时想法：先留下，再整理。');
  await saved(page);
  await expect.poll(async () => (await (await page.request.get('/api/capture/note/' + original.id + '/content')).json()).content).toBe('即时想法：先留下，再整理。');
  await page.reload();
  await page.waitForFunction(() => (window as any).__captureReady === true);
  expect((await current(page)).id).toBe(original.id);
  await expect(page.locator('.cm-content')).toContainText('即时想法：先留下，再整理。');
  for (const width of [320, 390, 430]) {
    await page.setViewportSize({ width, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
});
test('retains offline native edits across an abrupt reload and reconnects', async ({ page, context }) => {
  await boot(page);
  await context.setOffline(true);
  await write(page, '断网时输入也不能丢失');
  await expect.poll(async () => (await current(page)).content).toBe('断网时输入也不能丢失');
  const id = (await current(page)).id;
  await context.setOffline(false);
  await page.reload();
  await page.waitForFunction(() => (window as any).__captureReady === true);
  await saved(page);
  expect((await current(page)).id).toBe(id);
  await expect(page.locator('.cm-content')).toContainText('断网时输入也不能丢失');
});
test('creates a separate timestamp with the original new-file command and new tab', async ({ page, context }) => {
  await boot(page); await write(page, '第一条'); await saved(page);
  const first = (await current(page)).id;
  await page.evaluate(() => (window as any).app.commands.executeCommandById('file-explorer:new-file'));
  await expect.poll(async () => (await current(page)).id).not.toBe(first);
  await expect(page.locator('.workspace-leaf.mod-active .cm-content')).toHaveText('');
  const second = await context.newPage();
  await boot(second);
  expect((await current(second)).id).not.toBe((await current(page)).id);
  await second.close();
});
test('preserves rejected drafts and lets the native command save a separate copy', async ({ page }) => {
  await boot(page);
  const original = (await current(page)).id;
  await page.route('**/api/fs/writeFile', async route => {
    const body = route.request().postDataJSON();
    if (body.capture?.id === original && body.content) {
      await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ code: 'REMOTE_CONFLICT', error: '远端已移动' }) });
    } else await route.continue();
  });
  await write(page, '冲突中保留的草稿');
  await expect.poll(async () => (await current(page)).content).toBe('冲突中保留的草稿');
  await page.locator('.ignis-capture-upload').click();
  expect((await current(page)).acked).toBeLessThan((await current(page)).revision);
  await page.evaluate(() => (window as any).app.commands.executeCommandById('ignis-capture:save-copy'));
  await expect.poll(async () => (await current(page)).id).not.toBe(original);
  await saved(page);
  await expect(page.locator('.cm-content')).toContainText('冲突中保留的草稿');
});

test('does not send partial IME composition and flushes the final text', async ({ page }) => {
  await boot(page);
  const editor = page.locator('.cm-content');
  await editor.dispatchEvent('compositionstart');
  await page.evaluate(() => (window as any).app.workspace.activeLeaf.view.editor.setValue('中文组合输入'));
  await page.waitForTimeout(1000);
  expect((await current(page)).revision).toBe(0);
  await editor.dispatchEvent('compositionend');
  await saved(page);
  expect((await current(page)).content).toBe('中文组合输入');
});
test('keeps navigation at the bottom, moves the editing toolbar aside and renders bold text', async ({ page }) => {
  await boot(page);
  const navbar = await page.locator('.mobile-navbar').boundingBox();
  expect(navbar).not.toBeNull();
  expect(navbar!.y).toBeGreaterThan(740);
  expect(navbar!.width).toBeGreaterThan(navbar!.height);
  await page.locator('.cm-content').click();
  const toolbar = page.locator('.mobile-toolbar');
  const toggle = page.getByRole('button', { name: '展开格式工具栏' });
  await expect(toggle).toBeVisible();
  await expect(toolbar).toBeHidden();
  const beforeToggle = await page.evaluate(() => ({
    text: (window as any).app.workspace.activeLeaf.view.editor.getValue(),
    cursor: (window as any).app.workspace.activeLeaf.view.editor.getCursor(),
  }));
  await toggle.click();
  await expect(toolbar).toBeVisible();
  const rail = await toolbar.boundingBox();
  expect(rail).not.toBeNull();
  expect(rail!.x).toBeGreaterThan(260);
  expect(rail!.height).toBeGreaterThan(rail!.width);
  expect(rail!.width).toBeLessThanOrEqual(52);
  expect(rail!.height).toBeLessThanOrEqual(528);
  expect(rail!.y + rail!.height).toBeLessThan(830);
  const targets = await page.locator('.mobile-toolbar-option').evaluateAll(nodes =>
    nodes.map(node => ({ width: node.getBoundingClientRect().width, height: node.getBoundingClientRect().height })));
  expect(targets.every(target => target.width >= 44 && target.height >= 44)).toBe(true);
  await page.locator('.workspace').dispatchEvent('pointerdown');
  await expect(toolbar).toBeHidden();
  expect(await page.evaluate(() => ({
    text: (window as any).app.workspace.activeLeaf.view.editor.getValue(),
    cursor: (window as any).app.workspace.activeLeaf.view.editor.getCursor(),
  }))).toEqual(beforeToggle);
  await page.getByRole('button', { name: '展开格式工具栏' }).click();
  await page.evaluate(() => {
    const editor = (window as any).app.workspace.activeLeaf.view.editor;
    editor.setValue('**加粗内容**\n\n继续输入');
    editor.setCursor({ line: 2, ch: 4 });
  });
  await saved(page);
  await expect(page.locator('.cm-strong').filter({ hasText: '加粗内容' })).toBeVisible();
  expect((await current(page)).content).toBe('**加粗内容**\n\n继续输入');
  await page.getByLabel('已上传服务器；GitHub 尚未启用').click();
  const message = page.locator('.notice.ignis-capture-notice.is-warning');
  await expect(message).toBeVisible();
  expect(await message.evaluate(node => {
    const parse = (value: string) => value.match(/[\d.]+/g)!.slice(0, 3).map(Number);
    const luminance = (rgb: number[]) => rgb.map(value => {
      const channel = value / 255; return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
    }).reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0);
    const style = getComputedStyle(node); const foreground = luminance(parse(style.color));
    const background = luminance(parse(style.backgroundColor));
    return (Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05);
  })).toBeGreaterThanOrEqual(4.5);
});
test('serializes rapid saves and does not create a notice storm on conflicts', async ({ page }) => {
  let active = 0; let maximum = 0; let requests = 0;
  await page.route('**/api/fs/writeFile', async route => {
    if (!route.request().postDataJSON().capture) return route.continue();
    requests++; active++; maximum = Math.max(maximum, active);
    await new Promise(resolve => setTimeout(resolve, 120));
    active--;
    if (requests <= 2) await route.fulfill({ status: 409, contentType: 'application/json',
      body: JSON.stringify({ code: 'VERSION_CONFLICT', error: '合成冲突' }) });
    else await route.continue();
  });
  await boot(page);
  await page.evaluate(() => {
    const editor = (window as any).app.workspace.activeLeaf.view.editor;
    for (const text of ['中', '中文', '中文连续', '中文连续输入']) editor.setValue(text);
  });
  await expect.poll(async () => (await current(page)).content).toBe('中文连续输入');
  await page.locator('.ignis-capture-upload').click();
  await page.waitForTimeout(1200);
  expect(maximum).toBe(1);
  expect(await page.locator('.notice').count()).toBeLessThanOrEqual(1);
});
test('keeps text and cursor stable until one explicit upload', async ({ page }) => {
  let writes = 0;
  page.on('request', request => { if (request.url().includes('/api/fs/writeFile') && request.postDataJSON()?.capture) writes++; });
  await boot(page);
  const editor = page.locator('.cm-content');
  await editor.click();
  for (const chunk of ['输入', '不会', '消失', '，也不会', '复制多份']) await page.keyboard.insertText(chunk);
  const before = await page.evaluate(() => {
    const view = (window as any).app.workspace.activeLeaf.view;
    return { text: view.editor.getValue(), cursor: view.editor.getCursor() };
  });
  await page.waitForTimeout(1800);
  const idle = await page.evaluate(() => {
    const view = (window as any).app.workspace.activeLeaf.view;
    return { text: view.editor.getValue(), cursor: view.editor.getCursor() };
  });
  expect(idle).toEqual(before);
  expect(writes).toBe(0);
  await saved(page);
  await page.waitForTimeout(1200);
  expect(writes).toBe(1);
  expect(await page.evaluate(() => (window as any).app.workspace.activeLeaf.view.editor.getValue())).toBe(before.text);
});
test('keeps a last-moment edit locally after closing and uploads only on command', async ({ page, context }) => {
  await boot(page);
  await write(page, '关闭前一瞬的输入');
  const draft = await current(page);
  expect(draft.content).toBe('关闭前一瞬的输入');
  await page.close();
  const next = await context.newPage();
  await boot(next);
  expect((await current(next)).id).toBe(draft.id);
  await expect(next.locator('.cm-content')).toContainText(draft.content);
  expect((await current(next)).acked).toBeLessThan((await current(next)).revision);
  await saved(next);
  await expect.poll(async () => (await (await next.request.get('/api/capture/note/' + draft.id + '/content')).json()).content).toBe(draft.content);
});
test('does not upload an earlier draft after switching or reconnecting', async ({ page }) => {
  await boot(page);
  const first = (await current(page)).id;
  await page.route('**/api/fs/writeFile', async route => {
    if (route.request().postDataJSON().capture?.id === first) await route.abort();
    else await route.continue();
  });
  await write(page, '前一篇尚未送出的想法');
  const old = await current(page);
  await page.evaluate(() => (window as any).app.commands.executeCommandById('ignis-capture:new'));
  await expect.poll(async () => (await current(page)).id).not.toBe(first);
  await page.unroute('**/api/fs/writeFile');
  await page.evaluate(() => window.dispatchEvent(new Event('online')));
  await page.waitForTimeout(1200);
  const stored = await (await page.request.get('/api/capture/note/' + old.id + '/content')).json();
  expect(stored.revision).toBe(0);
  expect(stored.content).toBe('');
  expect(JSON.parse(await page.evaluate(id => localStorage.getItem('ignis-capture:draft:' + id)!, old.id)).content).toBe(old.content);
});
