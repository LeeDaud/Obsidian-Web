/* Native Obsidian extension: no replacement editor, page layout, or stylesheet. */
(() => {
  'use strict';
  const prefix = 'ignis-capture:draft:';
  const sessionKey = 'ignis-capture:session';
  const originalFetch = window.fetch.bind(window);
  let current, plugin, statusItem, statusAction, uploadAction, notice, activeNotice, syncEnabled = false;
  let saveTask, switchTask, ready = false, changing = false, composing = false;
  let lastSaveResult = { ok: true, status: 200, body: { ok: true } };
  let releaseDraft;
  let stateText = '正在准备时间戳笔记';
  const read = id => {
    try { return JSON.parse(localStorage.getItem(prefix + id) || 'null'); } catch { return null; }
  };
  function persist() {
    try {
      localStorage.setItem(prefix + current.id, JSON.stringify(current));
      sessionStorage.setItem(sessionKey, current.id);
    } catch {
      status('设备草稿存储不可用，请保持页面打开');
      showNotice('浏览器无法保留离线草稿，请保持页面打开直到服务器确认保存。', 'error', 0);
    }
  }
  function status(text) {
    stateText = text;
    if (statusItem) statusItem.setText(text);
    if (statusAction) {
      statusAction.setAttribute('aria-label', text);
      statusAction.setAttribute('title', text);
    }
  }
  function noticeKind(text) {
    if (/失败|中断|不可用|无法/.test(text)) return 'error';
    if (/冲突|入库|另一个页面|尚未启用/.test(text)) return 'warning';
    if (/已备份/.test(text)) return 'success';
    return 'info';
  }
  function showNotice(message, kind = noticeKind(message), timeout) {
    if (!notice) return;
    activeNotice?.noticeEl?.closest?.('.notice')?.remove?.();
    activeNotice?.hide?.();
    const instance = new notice(message, timeout);
    activeNotice = instance;
    const mark = () => {
      const own = instance.noticeEl?.closest?.('.notice') ?? instance.noticeEl;
      const element = own ?? [...document.querySelectorAll('.notice')]
        .findLast(candidate => candidate.textContent?.includes(message));
      element?.classList?.add('ignis-capture-notice', 'is-' + kind);
    };
    mark();
    setTimeout(mark, 0);
    return instance;
  }
  function updateStatus(result) {
    if (result.error) status('备份失败，草稿已保留；可另存新想法');
    else if (result.state === 'backed-up' && result.revision > 0) status('已备份到 GitHub');
    else status(syncEnabled ? '已上传服务器，等待 GitHub 备份' : '已上传服务器；GitHub 尚未启用');
  }
  async function api(endpoint, data) {
    const res = await originalFetch('/api/capture/' + endpoint, data === undefined ? {} : {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
    });
    const result = await res.json();
    if (!res.ok) throw Object.assign(new Error(result.error), { code: result.code });
    return result;
  }
  function installMobileRail(setIcon) {
    const preferenceKey = 'ignis-capture:toolbar-expanded';
    const style = document.createElement('style');
    style.dataset.ignisCapture = 'mobile-rail';
    style.textContent = `
      @media (max-width: 700px) {
        body.is-phone .mobile-toolbar {
          position: fixed;
          left: auto;
          right: max(var(--safe-area-inset-right), 6px);
          top: 50%;
          bottom: auto;
          width: 52px;
          height: auto;
          max-height: min(66dvh, 528px);
          margin: 0;
          padding: 4px;
          border: var(--border-width) solid var(--background-modifier-border);
          border-radius: var(--touch-radius-l);
          background: var(--background-primary);
          box-shadow: var(--shadow-s);
          opacity: 0;
          visibility: hidden;
          pointer-events: none;
          transform: translate(12px, -50%) scale(.96);
          overflow: hidden;
          transition: opacity 120ms ease, transform 120ms ease, visibility 120ms;
        }
        body.is-phone.ignis-capture-toolbar-expanded .mobile-toolbar {
          right: calc(max(var(--safe-area-inset-right), 6px) + 56px);
          opacity: 1;
          visibility: visible;
          pointer-events: auto;
          transform: translateY(-50%);
        }
        body.is-phone .ignis-capture-toolbar-toggle {
          position: fixed;
          z-index: var(--layer-popover);
          right: max(var(--safe-area-inset-right), 6px);
          top: 50%;
          width: 44px;
          height: 44px;
          padding: 10px;
          border: var(--border-width) solid var(--background-modifier-border);
          border-radius: 50%;
          color: var(--text-muted);
          background: var(--background-primary);
          box-shadow: var(--shadow-s);
          transform: translateY(-50%);
        }
        body.is-phone .ignis-capture-toolbar-toggle:hover,
        body.is-phone .ignis-capture-toolbar-toggle:focus-visible {
          color: var(--text-normal);
          background: var(--background-modifier-hover);
          outline: 2px solid var(--interactive-accent);
          outline-offset: 2px;
        }
        body:not(.is-phone) .ignis-capture-toolbar-toggle {
          display: none;
        }
        body.is-phone .mobile-toolbar-spacer,
        body.is-phone .mobile-toolbar-options-container {
          height: auto;
          width: 44px;
          flex-direction: column;
          gap: 4px;
          margin: 0;
        }
        body.is-phone .mobile-toolbar-options-list-container {
          width: 44px;
          max-height: min(64dvh, 512px);
          overflow: hidden;
        }
        body.is-phone .mobile-toolbar-options-list {
          width: 44px;
          height: auto;
          max-height: min(62dvh, 500px);
          flex-direction: column;
          overflow-x: hidden;
          overflow-y: auto;
          padding: 0;
          -webkit-overflow-scrolling: touch;
        }
        body.is-phone .mobile-toolbar-floating-options,
        body.is-phone .mobile-toolbar-option {
          width: 44px;
          height: 44px;
          min-width: 44px;
          min-height: 44px;
          flex: 0 0 44px;
        }
      }
      @media (max-width: 700px) and (prefers-reduced-motion: reduce) {
        body.is-phone .mobile-toolbar,
        body.is-phone .ignis-capture-toolbar-toggle { transition: none; }
      }
      .notice.ignis-capture-notice {
        pointer-events: none;
        color: var(--text-normal);
        background: var(--background-primary);
        border: var(--border-width) solid var(--background-modifier-border);
        border-inline-start: 6px solid var(--text-accent);
        box-shadow: var(--shadow-s);
      }
      .notice.ignis-capture-notice.is-success { border-inline-start-color: var(--text-success); }
      .notice.ignis-capture-notice.is-warning { border-inline-start-color: var(--text-warning); }
      .notice.ignis-capture-notice.is-error { border-inline-start-color: var(--text-error); }
    `;
    document.head.appendChild(style);
    plugin.register(() => style.remove());
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'clickable-icon ignis-capture-toolbar-toggle';
    const setExpanded = expanded => {
      document.body.classList.toggle('ignis-capture-toolbar-expanded', expanded);
      toggle.setAttribute('aria-expanded', String(expanded));
      toggle.setAttribute('aria-label', expanded ? '收起格式工具栏' : '展开格式工具栏');
      toggle.setAttribute('title', expanded ? '收起格式工具栏' : '展开格式工具栏');
      setIcon(toggle, expanded ? 'chevrons-right' : 'chevrons-left');
      try { localStorage.setItem(preferenceKey, String(expanded)); } catch {}
    };
    let expanded = false;
    try { expanded = localStorage.getItem(preferenceKey) === 'true'; } catch {}
    setExpanded(expanded);
    toggle.addEventListener('pointerdown', event => event.preventDefault());
    toggle.addEventListener('click', event => {
      event.stopPropagation();
      const next = !document.body.classList.contains('ignis-capture-toolbar-expanded');
      if (next) window.app?.workspace?.activeLeaf?.view?.editor?.focus?.();
      setExpanded(next);
    });
    document.body.appendChild(toggle);
    plugin.registerDomEvent(document, 'pointerdown', event => {
      if (!document.body.classList.contains('ignis-capture-toolbar-expanded')) return;
      if (event.target.closest?.('.mobile-toolbar, .ignis-capture-toolbar-toggle')) return;
      setExpanded(false);
    }, true);
    plugin.registerDomEvent(document, 'keydown', event => {
      if (event.key === 'Escape') setExpanded(false);
    });
    plugin.register(() => {
      toggle.remove();
      document.body.classList.remove('ignis-capture-toolbar-expanded');
    });
  }
  // Add the durable client revision to Ignis's own filesystem requests.
  window.fetch = async (input, init) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, location.href);
    if (current?.filename && url.origin === location.origin) {
      const metadata = { type: 'file', size: new TextEncoder().encode(current.content).length,
        mtime: Date.now(), ctime: Date.parse(current.createdAt) };
      if (url.pathname === '/api/fs/stat' && url.searchParams.get('path')?.replace(/^\/+/, '') === current.filename) {
        return Response.json(metadata);
      }
      if (url.pathname === '/api/fs/readFile' && url.searchParams.get('path')?.replace(/^\/+/, '') === current.filename) {
        return new Response(current.content, { headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' } });
      }
      if (url.pathname === '/api/fs/tree') {
        const headers = new Headers(init?.headers);
        headers.delete('If-None-Match');
        const response = await originalFetch(input, { ...init, headers });
        if (!response.ok) return response;
        const tree = await response.json();
        // Keep the current blank document in native reconciliation without a disk file.
        tree[current.filename] = metadata;
        return Response.json(tree);
      }
    }
    if (current && url.origin === location.origin && url.pathname === '/api/fs/writeFile' && typeof init?.body === 'string') {
      const body = JSON.parse(init.body);
      if (String(body.path).replace(/^\/+/, '') === current.filename) {
        // Native autosave only acknowledges the durable device draft. The cloud action is
        // the single explicit entry point for server and GitHub uploads.
        captureEditor();
        return new Response(JSON.stringify({ ok: true, queued: true }), {
          status: 202,
          headers: { 'Content-Type': 'application/json', 'X-Capture-State': 'device-draft' },
        });
      }
    }
    return originalFetch(input, init);
  };
  function captureEditor() {
    if (!ready || changing || composing) return;
    const view = window.app.workspace.getActiveViewOfType(window.require('obsidian').MarkdownView);
    if (!view || view.file?.path !== current.filename) return;
    const content = view.editor.getValue();
    if (content === current.content) return;
    current.content = content;
    current.revision++;
    persist();
    status('设备草稿已保存，点击云朵上传');
  }
  async function drainSaveQueue() {
    while (current?.filename && current.revision && current.acked < current.revision) {
      const snapshot = { ...current };
      try {
        const body = JSON.stringify({ vault: 'Inbox', path: snapshot.filename, content: snapshot.content,
          capture: { id: snapshot.id, createdAt: snapshot.createdAt, revision: snapshot.revision, content: snapshot.content } });
        const response = await originalFetch('/api/fs/writeFile', {
          method: 'POST', headers: { 'Content-Type': 'application/json' }, body,
          keepalive: new TextEncoder().encode(body).length < 60000,
        });
        const result = await response.json();
        lastSaveResult = { ok: response.ok, status: response.status, body: result };
        if (!response.ok) throw Object.assign(new Error(result.error), { code: result.code });
        if (current.id === snapshot.id) {
          current.acked = Math.max(current.acked, snapshot.revision);
          persist();
          if (current.revision === snapshot.revision) status(syncEnabled ? '已上传服务器，等待 GitHub 备份' : '已上传服务器；GitHub 尚未启用');
        }
      } catch (error) {
        lastSaveResult = { ok: false, status: 503, body: { error: error.message, code: error.code } };
        status(['RETIRED', 'VERSION_CONFLICT', 'REMOTE_CONFLICT'].includes(error.code) ?
          '笔记已入库或有冲突，请另存新想法；草稿已保留' : '连接中断，草稿已保留在此设备');
        break;
      }
      if (current?.id !== snapshot.id) break;
    }
    return lastSaveResult;
  }
  function flush() {
    if (!current?.filename || !current.revision || current.acked >= current.revision) return Promise.resolve(lastSaveResult);
    if (!saveTask) saveTask = drainSaveQueue().finally(() => { saveTask = undefined; });
    return saveTask;
  }
  async function manualUpload() {
    if (switchTask) await switchTask;
    captureEditor();
    if (!current?.revision) return showNotice('当前笔记为空，无需上传。');
    if (current.acked >= current.revision) {
      await heartbeat();
      return showNotice(stateText);
    }
    status('正在上传笔记');
    const result = await flush();
    if (result.ok && current.acked >= current.revision) showNotice(stateText, syncEnabled ? 'info' : 'warning');
    else showNotice(stateText, 'error');
  }
  function fresh(content = '') {
    return { id: crypto.randomUUID(), createdAt: new Date().toISOString(), content, revision: content ? 1 : 0, acked: 0 };
  }
  async function claim(draft) {
    if (!navigator.locks) return true;
    return new Promise(resolve => {
      void navigator.locks.request('ignis-capture:' + draft.id, { ifAvailable: true }, lock => {
        resolve(!!lock);
        if (lock) return new Promise(release => { releaseDraft = release; });
      });
    });
  }
  async function openDraft(draft) {
    const previous = current;
    changing = true;
    current = draft;
    persist();
    try {
      const opened = await api('open', { id: current.id, createdAt: current.createdAt });
      current.filename = opened.filename;
      persist();
      let file = window.app.vault.getAbstractFileByPath(current.filename);
      if (!file) file = await window.app.vault.create(current.filename, current.content);
      const leaf = window.app.workspace.getLeaf(false);
      await leaf.openFile(file, { state: { mode: 'source' } });
      await leaf.setViewState({ type: 'markdown', state: { file: file.path, mode: 'source', source: false } });
      const view = leaf.view;
      if (current.content !== view.editor.getValue()) view.editor.setValue(current.content);
      statusAction?.remove();
      uploadAction?.remove();
      statusAction = view.addAction('cloud', stateText, () => showNotice(stateText));
      uploadAction = view.addAction('upload', '上传当前笔记', () => void manualUpload());
      uploadAction.classList.add('ignis-capture-upload');
      ready = true;
      status(current.revision ? '正在核对暂存状态' : '新想法 · 输入后自动保存');
      window.__captureReady = true;
    } catch (error) {
      if (error.code === 'RETIRED') {
        const unsent = current.revision > current.acked ? current.content : '';
        const replacement = fresh(unsent);
        releaseDraft?.(); await claim(replacement);
        return await openDraft(replacement);
      }
      if (previous?.filename) { current = previous; persist(); }
      status('笔记打开失败，设备草稿已保留');
      showNotice(error.message || '无法打开笔记，请检查网络后刷新。', 'error', 0);
      return false;
    } finally { changing = false; }
    return true;
  }
  async function switchIdea(copy) {
    captureEditor();
    if (saveTask) await saveTask;
    const draft = fresh(copy ? current.content : '');
    const previousRelease = releaseDraft;
    await claim(draft);
    const nextRelease = releaseDraft;
    if (await openDraft(draft)) previousRelease?.();
    else { nextRelease?.(); releaseDraft = previousRelease; }
  }
  function newIdea(copy = false) {
    if (!switchTask) switchTask = switchIdea(copy).finally(() => { switchTask = undefined; });
    return switchTask;
  }
  async function heartbeat() {
    if (!current?.filename || document.visibilityState === 'hidden') return;
    try {
      const result = await api('heartbeat', { id: current.id });
      if (result.retired) {
        if (current.revision > current.acked) status('笔记已入库，请另存新想法；设备草稿已保留');
        else await newIdea();
      } else if (current.revision && current.acked === current.revision) updateStatus(result);
    } catch { if (current.revision > current.acked) status('连接中断，草稿保留在此设备'); }
  }
  async function boot() {
    if (!window.app?.workspace?.layoutReady || !window.require) { setTimeout(boot, 100); return; }
    const { Plugin, Notice, FuzzySuggestModal, setIcon } = window.require('obsidian');
    if (typeof Plugin !== 'function' || typeof Notice !== 'function') { setTimeout(boot, 100); return; }
    notice = Notice;
    plugin = new Plugin(window.app, { id: 'ignis-capture', name: 'Echo', version: '1.0.0', minAppVersion: '1.0.0' });
    installMobileRail(setIcon);
    statusItem = plugin.addStatusBarItem();
    statusItem.setAttribute('role', 'status');
    plugin.addCommand({ id: 'new', name: '新建时间戳想法', callback: () => void newIdea() });
    plugin.addCommand({ id: 'save-copy', name: '将当前草稿另存为新想法', callback: () => void newIdea(true) });
    plugin.addCommand({ id: 'recover', name: '恢复未发送草稿', callback: () => {
      class Recovery extends FuzzySuggestModal {
        getItems() { return Object.keys(localStorage).filter(k => k.startsWith(prefix)).map(k => read(k.slice(prefix.length))).filter(d => d && d.revision > d.acked); }
        getItemText(draft) { return draft.createdAt + ' · ' + draft.content.slice(0, 60); }
        async onChooseItem(draft) {
          if (draft.id === current?.id) return;
          captureEditor();
          const previousRelease = releaseDraft;
          if (!await claim(draft)) { showNotice('这篇草稿正在另一个页面编辑。', 'warning'); return; }
          const nextRelease = releaseDraft;
          if (await openDraft(draft)) previousRelease?.();
          else { nextRelease?.(); releaseDraft = previousRelease; }
        }
      }
      new Recovery(window.app).open();
    } });
    plugin.addRibbonIcon('square-pen', '新建时间戳想法', () => void newIdea());
    // Keep the original new-file controls, using timestamp allocation.
    window.app.fileManager.createNewMarkdownFile = async () => { await newIdea(); return window.app.workspace.getActiveFile(); };
    plugin.registerEvent(window.app.workspace.on('editor-change', captureEditor));
    plugin.registerDomEvent(document, 'input', () => { if (!composing) queueMicrotask(captureEditor); }, true);
    plugin.registerDomEvent(document, 'compositionstart', () => { composing = true; }, true);
    plugin.registerDomEvent(document, 'compositionend', () => {
      composing = false;
      requestAnimationFrame(() => requestAnimationFrame(captureEditor));
    }, true);
    plugin.registerDomEvent(window, 'online', () => { void heartbeat(); });
    plugin.registerDomEvent(document, 'visibilitychange', () => {
      captureEditor();
      if (document.visibilityState === 'visible') void heartbeat();
    });
    plugin.registerDomEvent(window, 'pagehide', captureEditor);
    plugin.registerInterval(setInterval(() => void heartbeat(), 10000));
    let draft = read(sessionStorage.getItem(sessionKey));
    if (draft && !(await claim(draft))) draft = null;
    if (!draft) {
      for (const key of Object.keys(localStorage).filter(k => k.startsWith(prefix))) {
        const candidate = read(key.slice(prefix.length));
        if (candidate?.revision > candidate?.acked && !draft && await claim(candidate)) draft = candidate;
      }
    }
    try { syncEnabled = (await api('status')).syncEnabled; } catch { /* Draft recovery still attempts the native editor. */ }
    if (!draft) { draft = fresh(); await claim(draft); }
    await openDraft(draft);
  }
  void boot();
})();

