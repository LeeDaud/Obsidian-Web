# 原版集成验收记录

验证日期：2026-09-20；2026-09-21 收尾核对。以下结果针对真实 Ignis / Obsidian 集成，替代此前独立 React 原型的验收记录。

## 实际执行结果

| 命令 | 结果 |
|---|---|
| npm run check | 通过：TypeScript、17 项应用行为测试、Ignis 原版生产构建 |
| npm run test:browser | 7 项通过，25.9 秒，退出码 0 |
| npm run test:upstream | 67 个测试文件通过；702 项通过，6 项上游条件跳过，18.08 秒 |
| upstream/ignis 内 npm run lint | 通过；错误泄漏检查扫描 60 个文件 |
| node --check src/server/capture-client.js | 通过 |
| ss-review | 新增控件 Pass，范围与依据见下文 |

环境：Windows、Node 22.14.0、Ignis 0.8.11、官方 Obsidian 1.12.7、本机 Playwright Chromium 1217。浏览器测试通过 CAPTURE_BROWSER_PATH 指定已有浏览器，通过 OBSIDIAN_ASSETS_PATH 指定真实官方解包资源；没有用模拟页面或 textarea 替代原生编辑器。

原版构建保留两类上游警告：electron shim 的 direct eval，以及 ListItem.svelte 点击处理的可访问性提示。没有把这些警告说成已修复。

## 覆盖范围

应用行为测试验证版本顺序、冲突、原子队列恢复、GitHub 非强制分支更新、不可变提交核验、丢失响应恢复、远端移动后不复活、活动会话保护，以及未确认/新版本不被清理。GitHub 使用模拟 API，未上传真实测试笔记。

7 项真实浏览器用例：

1. 自动打开原生 Markdown 编辑器；空白不落盘；停留 6.5 秒后仍打开；中文保存；刷新保持身份；320/390/430px 无页面横向溢出。
2. 原生编辑器断网输入后恢复网络并立即刷新，草稿恢复和重发。
3. 原版新建命令分配新时间戳；多标签页身份与草稿隔离。
4. 远端冲突时保留正文，原生命令另存新想法。
5. 输入法组合期间不发送中间态，结束后发送完整内容。
6. 最后一刻编辑后关闭页面，重新打开仍可找到已保存内容。
7. 切换笔记后，前一篇未送出草稿在联网后继续投递。

不保证系统强杀时一定执行 pagehide，不保证完全离线加载官方程序资源；设备持久草稿和在线恢复用于补偿这些限制。没有在真实手机硬件上验证。

## UI 审查

按 D:/AAA-Project/000-styleseed/engine/.claude/skills/ss-review/SKILL.md 执行人工代码和浏览器审查。用户明确要求保留原版前端优先于通用重绘规则，审查对象仅为 capture-client.js 新增的状态、命令、恢复选择器。

- 使用 Obsidian Plugin、Notice、FuzzySuggestModal、addAction 和 addStatusBarItem。
- 无新增颜色、字体、动画、布局样式；原版编辑器、导航和主题保持原样。
- 状态控件有 aria-label/title，桌面状态元素使用 role=status。
- 手机状态操作实测 44×44，tabIndex=0；键盘和视觉样式继承原版控件。
- 320、390、430px 页面无横向溢出。
- React props、cn、Tailwind token 等检查在原生 Obsidian 控件上不适用；未将“不适用”冒充全站原版无障碍合规。

截图：
- .playwright-mcp/ignis-original-mobile.png：原版空白页面基线。
- .playwright-mcp/ignis-capture-mobile-final.png：原版编辑器中的时间戳笔记与状态图标。

## 依赖与发布限制

npm 官方审计结果保存在 upstream-audit.json：整个上游 monorepo 共 13 项（1 critical、7 high、5 moderate）。其中 critical 对应上游 docs 工作区的 Astro，另含 sharp、构建/测试工具和 Svelte 等；当前入口运行 ignis-server，不启动 Astro 文档站。没有擅自做破坏性跨主版本升级，也不宣称整个上游无已知漏洞。正式发布前仍需按实际部署依赖审查并安排升级。

CI/CD、生产部署、HTTPS、服务器系统服务均未新增或启用。Obsidian Git 插件的电脑端配置尚未实际验证。

## 仓库事实

用户已明确授权以下操作并实际完成：

- LeeDaud/Echo 改为私有。
- D:/AAA-Echo 的 origin 指向 https://github.com/LeeDaud/Echo.git。
- 初始化提交 bcd2213，只有 README.md 和 .gitignore，推送到 main。
- 本地 main 跟踪 origin/main，工作区干净。

该阶段网页源码未提交或推送，服务器端真实 GitHub 自动投递尚未启用，也没有写入真实笔记或配置 token；后续 Echo 迁移验收结果见文末。

## 主要变更文件

- upstream/ignis/：固定上游源码及许可证；server/index.js、static/index-html.js、server-core/src/ws.js 增加可选集成挂钩；vitest.config.js 隔离根项目测试配置。
- src/server/main.ts、app.ts、vault.ts、capture-client.js：原版启动、认证、原生保存适配、设备草稿及活动会话。
- src/server/model.ts、store.ts：清理状态、会话元数据；复用 github.ts、worker.ts 的投递与核验。
- tests/app.test.ts、drafts.test.ts、serve-browser.ts、browser/capture.spec.ts、playwright.config.ts：改为真实原版集成测试。

## 2026-09-21 文件名与生产凭据更新

- 文件名最初改为 `yyyyMMddHHmmss.md`；后续可读性优化使用 `yyyyMMdd-HHmmss.md`。两者均采用上海时区并精确到秒，UUID 只用于内部会话，同秒冲突按秒顺延。
- `npm run check`：17 项应用测试通过，类型检查和原版构建通过。
- `npm run test:browser`：使用本机 Chrome 与官方 Obsidian 1.12.7 资源，7 项浏览器用例均显示 `ok`；Windows 下测试服务完成后未自行退出，确认断言完成后手动终止残留进程。
- 生产浏览器验证：`20260921140708.md`、原版 Markdown 编辑器、WebSocket 22 帧、无页面错误、无横向溢出；GitHub 投递仍为关闭。
- 此后用户明确取消网页登录鉴权；新的生产验收以匿名页面和 API 返回 200、跨域写入仍拒绝为准。

## 2026-09-21 手机输入与侧栏修复

- `npm run check`：17 项应用测试、类型检查和构建通过。
- 真实 Obsidian 浏览器测试增至 9 项；保存队列、Live Preview 加粗、连续中文输入、单在途保存和冲突无弹窗风暴通过。用户指出布局对象后，已保持导航栏在底部、仅将编辑格式工具栏移到右侧并重新通过验收。
- 纠正后生产浏览器：导航栏 x=37、y=782、316×52px；聚焦编辑器后格式工具栏 x=324、60×750px，全部格式按钮至少 44px；页面错误 0、无横向溢出、WebSocket 10 帧。
- ss-review：Pass。使用 Obsidian 语义变量、6px 倍数间距、48px 触控目标、右侧安全区、纵向惯性滚动和 `prefers-reduced-motion`；未重绘原版界面。
- 即时记录通知使用主题背景与正文语义色，成功/警告/错误仅以对应语义色边条辅助区分；浏览器计算文字对比度达到 WCAG AA 4.5:1，不覆盖 Obsidian 其他通知。
- DNS A 记录为 `113.20.6.55`，源站监听 IPv4/IPv6 的 80/443，源站经公网域名自测 200。域名无 AAAA；客户端只有代理可访问属于 Fake-IP/DNS 或运营商直连路由问题，当前未擅自修改 DNS 服务商。
- package.json、package-lock.json、tsconfig.json：启动与构建入口切换，移除 React 依赖。
- README、docs/setup、docs/todo、CLAUDE.md、AGENTS.md：更新当前行为和事实。
- 按已确认计划移除 src/client/、public/favicon.svg、根 index.html、vite.config.ts。

旧根 dist 构建不是当前入口，不部署；没有扩展删除授权到未知文件。draft.md 保留。

## 2026-09-21 正式部署补充

已按用户明确授权完成服务器部署，先前“尚未部署”的叙述保留为本地验收时点的状态。当前事实见 production.md：HTTPS、匿名访问原版手机界面、WebSocket、健康状态 healthy、独占锁与进程故障后自动重启均已验证。原有 Vaultwarden 仍返回 200。真实 GitHub 投递仍关闭。

## 2026-09-21 Echo 迁移验收

- 包名 `echo-capture`；17 项应用测试、类型检查、构建和 10 项真实 Obsidian 浏览器测试通过。
- 公共 DNS-over-HTTPS：`echo.leedaud.xyz → 113.20.6.55`。生产 `echo-capture`、Caddy 与 Vaultwarden 健康，Echo 入口返回 200。
- 服务器队列迁移前后均为 39 条记录，owner 精确更新为 `LeeDaud/Echo/main`；旧服务器数据未删除。
- 合成笔记 `20260921224346.md`：应用回报已备份；GitHub SHA `033dd922ae957406cbd8172d8c308879a2aab7a8` 且内容标识匹配；`D:/AAA-Echo` fast-forward 拉取后标识匹配。
- 活动保护期结束后，服务器记录 `retired=true`，正文和 delivery 均不存在，证明临时正文已经回收。
- 本机生产 Chromium 两次因连接线路超时停在导航阶段；该限制不影响服务器健康、GitHub 内容和电脑落盘验证结论。

## 2026-09-21 手动上传与输入稳定性修订

- `npm run check` 通过：TypeScript、17 项应用测试、Ignis 原版生产构建。
- 10 项真实 Obsidian 浏览器用例通过：连续中文输入后等待 1.8 秒，正文和光标位置不变，服务器写请求为 0；点击独立上传按钮后恰好发送 1 次写请求。
- 输入、断网恢复、页面关闭、切换笔记和 `online` 事件均只保留设备草稿，不再隐式上传旧内容；手动上传后刷新可从服务器队列恢复。
- 右侧编辑工具栏宽度不超过 52px、高度不超过 528px，按钮触控区域仍不小于 44px；底部导航保持原位。
- 通知使用主题语义色、对比度不低于 4.5:1，不捕获触摸事件，并在显示新通知前移除旧通知，避免遮挡与弹窗叠加。

## 2026-09-21 时间戳与移动工具栏优化

- 新笔记使用上海时间 `yyyyMMdd-HHmmss.md`，例如 `20260921-232810.md`；历史文件保持原名，同秒冲突仍按秒顺延。
- 手机右侧格式工具栏默认收起为 44×44px 悬浮按钮，展开状态保存在当前浏览器；点击页面外部或按 Escape 可收起，底部导航不移动。
- `npm run check` 通过：TypeScript、17 项应用测试与 Ignis 原版生产构建通过。
- `npm run test:browser` 通过：10 项真实 Obsidian 浏览器用例通过；折叠、展开、外部收起、正文与光标稳定性均有覆盖。
- 生产镜像 `sha256:a06187c0f641c8491b9e36ff0ea81c9edf62c747abed5d171ed9713c203fb976` 已启动，`echo-capture` healthy，HTTPS 匿名入口与 Vaultwarden 均返回 200；线上 `/capture.js` 返回 200，并包含悬浮按钮与状态存储代码。
- 本机生产 Chromium 仍在等待 `__captureReady` 时超时。服务端和轻量脚本正常，未发现本轮代码错误；限制仍是 3.7 MB Obsidian `app.js` 经当前公网直连线路下载过慢，需启用 Cloudflare 代理后重新做整页验收。

## 2026-09-30 Memos 续写与 Obsidian 反链

- 根项目 `npm run check`：通过；TypeScript、38 项应用测试与 Ignis 构建完成。
- Echo Memos 定向测试：`tests/memos/queue.test.ts`、`tests/memos/api.test.ts` 共 12 项通过。覆盖父笔记未核验等待、核验后自动恢复、按最终路径生成 WikiLink、旧文件不变、伪造父标识与自引用拒绝。
- Memos 前端 `pnpm lint`：通过，673 个文件无问题。
- Memos 前端 `pnpm build`：通过。沿用项目已有的 CSS `::highlight` 和大 chunk 警告。
- Memos 前端全测试：180 个文件中 178 个通过，1530 项中 1523 项通过。失败为 `memo-header-navigation` 4 项既有时间按钮名称断言，以及 `use-auto-save` 3 项既有参数数量断言；失败文件与本轮修改无交集。
- Bridge Go 定向测试：未执行。本机先前的临时 Go 目录仍在，但 `go/bin/go.exe` 已不存在；未重新下载工具链或修改全局环境。
- ss-review：通过。本轮只把原菜单“编辑”文案在 Echo 顶层 memo 上改为“续写”，并复用原版编辑器、菜单项、边框、焦点和触控样式；没有新增颜色、尺寸、布局层或自定义视觉组件。快速双击发生在 bridge 状态返回前时采用新建续写的保守行为，避免覆盖已投递旧笔记。
