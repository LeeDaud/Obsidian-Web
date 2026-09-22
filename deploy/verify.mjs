import path from 'node:path';
import { chromium } from '@playwright/test';

const browser = await chromium.launch({ executablePath: process.env.CAPTURE_BROWSER_PATH });
try {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  const failures = [];
  let websocketFrames = 0;
  page.on('websocket', socket => {
    socket.on('framereceived', () => { websocketFrames++; });
    socket.on('socketerror', () => { failures.push('WebSocket connection failed'); });
  });
  page.on('pageerror', error => failures.push(error.message));
  await page.goto('https://echo.leedaud.xyz', { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForFunction(() => window.__captureReady === true, null, { timeout: 45000 });
  const navbar = await page.locator('.mobile-navbar').boundingBox();
  await page.locator('.cm-content').click();
  await page.waitForTimeout(7000);
  const toolbar = page.locator('.mobile-toolbar');
  const toolbarToggle = page.locator('.ignis-capture-toolbar-toggle');
  const toolbarCollapsed = await toolbar.isHidden();
  await toolbarToggle.click();
  await toolbar.waitFor({ state: 'visible' });
  const state = { ...(await page.evaluate(async () => ({
    title: document.title,
    nativeEditor: !!document.querySelector('.cm-editor'),
    activeType: window.app.workspace.activeLeaf.view.getViewType(),
    filename: JSON.parse(localStorage.getItem('ignis-capture:draft:' + sessionStorage.getItem('ignis-capture:session'))).filename,
    toolbarToggle: !!document.querySelector('.ignis-capture-toolbar-toggle'),
    overflow: document.documentElement.scrollWidth > innerWidth,
    uploadAction: !!document.querySelector('.ignis-capture-upload'),
    toolbar: (() => {
      const box = document.querySelector('.mobile-toolbar')?.getBoundingClientRect();
      return box ? { x: box.x, y: box.y, width: box.width, height: box.height,
        targets: [...document.querySelectorAll('.mobile-toolbar-option')].every(node => {
          const target = node.getBoundingClientRect(); return target.width >= 44 && target.height >= 44;
        }) } : null;
    })(),
    sync: await (await fetch('/api/capture/status')).json(),
  }))), navbar, toolbarCollapsed };
  if (!state.nativeEditor || state.activeType !== 'markdown' || !/^\d{8}-\d{6}\.md$/.test(state.filename) ||
      !state.navbar || state.navbar.y < 740 || state.navbar.width <= state.navbar.height ||
      !state.uploadAction || !state.toolbarToggle || !state.toolbarCollapsed || !state.toolbar || state.toolbar.width > 52 ||
      state.toolbar.height > 528 || state.toolbar.height <= state.toolbar.width || !state.toolbar.targets ||
      state.overflow || failures.length || !websocketFrames) {
    throw new Error('UI verification failed: ' + JSON.stringify({ state, failures }));
  }
  await page.screenshot({ path: path.resolve('.playwright-mcp/echo-production-mobile.png'), fullPage: true });
  console.log(JSON.stringify({ ...state, pageErrors: failures.length, websocketFrames }));
  await context.close();
} finally { await browser.close(); }


