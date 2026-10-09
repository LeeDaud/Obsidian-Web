# 原版集成验收记录

## 2026-10-08 原 Web 格式和全部功能核对：R0 修复

用户再次提出保留原 Web 格式、按钮、笔记区及相关功能。源码审计确认当前不是完整迁移；详细格式/功能矩阵见 [android-web-parity.md](android-web-parity.md)，完整本地原组件复用的新渲染层方案见 plan.md，尚待确认。

本轮已直接修复：quiet 按钮的 6dp 圆角、13sp 常规字重、muted/70 与整体禁用透明度；中间 44dp 带边框加号方框；原笔记显示“更新”、冻结笔记入口显示“续写”；卡片正文使用原 card-foreground。列表增加复制内容与父/子入口，详情增加关联入口，inline 编辑优先匹配实际编辑记录，避免置顶父笔记替代子卡片。

检查：本地 APK/qa APK/设备测试包构建通过；31 项本地测试全部通过，覆盖投递中和已核验两类续写不改变父正文文件、父记录及队列快照，以及置顶父笔记下子编辑位置；API 26 隔离设备 3 项交互/视觉回归通过。Lint 0 错误、11 条已有 API/版本/KTX/资源建议。中途新合成测试补齐 attempt 凭据、纯布局测试不依赖仓库启动后重跑通过，未修改业务保护以让测试通过。差异检查通过。

主 APK：`apps/android/app/build/outputs/apk/debug/app-debug.apk`，SHA-256：`a6f4f543d3f6e5dc67e84bb7e4ddedd461530adbfff33dd3257d7f453887c985`；这是 R0 修复包，不是完整 Web 迁移包。截图位于 build/android-qa/ui-reference。按 ss-review 检查 R0 控件与关系行，原版优先，保留语义按钮/关系入口/固定尺寸；整体一致性仍为 Needs Improvement，原 CodeMirror、阅读格式、画廊、任务、多引用及其他缺口未被声明完成。

Web 生产代码、原生数据格式、数据库、凭据、真实内容、CI/CD及投递协议未改，未提交、推送或部署。只有完整新方案确认后才切换渲染架构；不删除旧实现或私人内容。

## 2026-10-08 Android 图标、安全区与日历细节优化

用户已接受页面重构并提出本轮四项优化，沿用已确认的原版 UI 工作范围实施。

- 启动图标引用 `res/drawable/ic_memos.webp`，内容与网页 index.html 的 favicon `public/logo.webp` 逐字节一致，SHA-256 为 `f87a44fb961d32a1cece3d00717289b4a30f6528c61a6785d097e4feab291859`；主 APK 的各密度 application-icon 均指向该资源，roundIcon 同步。旧 Echo 图标保留，未删除。
- `NativeActivity` 开启 edge-to-edge，`MemosHeader` 使用实际 statusBars/displayCutout 留白；浅深色同步状态栏/导航栏图标。实际 API 26 窗口标志与首页截图核验通过。Robolectric API 35 的 52dp 合成顶部安全区测试通过，完整顶栏高度 100dp，未重复计算 padding；这不是 API 35 真机证据。
- 侧栏日历参考网页 `ActivityCalendar/cellStyles.ts` 与 `utils.ts`：固定 30dp 日期方块、6dp 圆角、4dp 格间距，按最大日计数的四分位使用 foreground 的 6%/12%/20%/30% 填色，不再添加圆点行。浅深色检查带/不带笔记格高一致，日期点击仍返回正确日期。
- 月份栏在紧凑模式移除“今天”按钮，避免月份被挤压；顶栏无动作的切换箭头移除；小屏/大字体的草稿状态按语义分行，保留既有 UI 结构。

检查结果：

| 检查 | 结果 |
| --- | --- |
| `assembleDebug assembleQa testDebugUnitTest assembleQaAndroidTest lintDebug` | 通过；新增测试修正导入和语义子节点定位后完成 |
| 最终 `testDebugUnitTest` | 29 项全部通过；原有 28 项与 API 35 合成高状态栏回归 |
| API 26 隔离设备 | 原有保存/菜单/双击交互、主页浅深色/状态菜单、侧栏日期行高/筛选/实际 statusBar 位置，共 3 项通过；没有配置真实目标或投递真实内容 |
| 图标与 APK | favicon 资源哈希相同；aapt 主入口 icon 正确；APK v2 调试签名通过 |
| Lint / 差异检查 | 0 错误、11 警告：原有 9 条 API/版本/KTX 建议，以及保留旧图标未使用、新 bitmap 在 drawable 中的资源位置建议；不删除或全局抑制以掩盖这些提示。`git diff --check` 通过 |

主 APK 仍为 0.2.0 / versionCode 2，路径 `apps/android/app/build/outputs/apk/debug/app-debug.apk`；SHA-256：`81bbf8dfea7e1d517becb3f5053ebe28e9b45545bab8be86a56546e9f76b343e`。本轮截图位于 build/android-qa 的 `ui-reference/ui-visual/detail-calendar-{light,dark}-390.png` 和 `logs/detail-home-390.png`。截图等待系统 UI 更新稳定，避免把主题切换瞬间的旧系统栏颜色当作最终表现。

按 ss-review 检查本轮新增/修改控件：**Pass（本轮范围）**，真实 inset、语义日期描述、固定格高、主题对比和状态换行已核验；原网页紧凑尺寸与系统字体按原版优先保留。未做整套页面重新设计。仍建议在用户的当前 Android/刘海手机、大字体和横屏下确认系统栏/键盘行为；API 26 截图和 API 35 合成测试不能代替该硬件验收。历史完整逐像素矩阵的限制继续保留。

关于 GitLab：当前 `NetworkTransport` 仍只访问 `https://api.github.com`，本轮未新增提供者。GitLab 官方 [Commits API](https://docs.gitlab.com/api/commits/) 支持多文件操作提交，[Repository Files API](https://docs.gitlab.com/api/repository_files/) 可用于按提交读取核验，未来可单独适配国内部署的实例；实际网络可达性需针对实例测试，不能仅替换现有 GitHub 配置地址。存储/队列/凭据、Web 服务和 CI/CD未改，未提交、推送、部署或公开发布。

## 2026-10-08 Android 原网页 UI 重构验证

用户确认以当前 Memos 手机网页为基准重构，保持本地原生运行、离线存储及 GitHub 直投。计划提交为 `80597b5`，未推送。本节是新的验收记录；下文旧容器或早期原生构建记录不代表本节已逐像素通过。

### 实现与实际检查

- `EchoApp.kt` 改为原网页的 48dp 顶栏与抽屉导航，主页内嵌编辑器/列表；`MemosTheme.kt` 直接转换原网页 OKLCH 主题，`MemosNavigation.kt`、`MemosFeed.kt` 负责原版导航与卡片。使用原网页 Lucide 节点与 Memos 图片，APK 内附原许可证。
- 编辑器改为等宽笔记/待办选择和“暂存 / + / 保存”；三种状态位于时间戳旁，三点菜单提供编辑/续写、置顶、归档及详情。设备设置保留 GitHub 配置；输入、暂存及导航不新增草稿投递。
- 修复已保存笔记重新编辑为空：通过已有完成日志识别保存后的空草稿占位，仍保留用户主动清空的后续草稿。没有 Room schema/文件格式变更。光标/选区变化不再创建草稿版本；正文双击保护链接、代码、图片及待办勾选区域。

| 检查 | 结果 |
| --- | --- |
| `gradlew.bat --offline --no-daemon assembleDebug assembleQa testDebugUnitTest assembleQaAndroidTest lintDebug` | 通过；最终本地构建与测试包已生成 |
| Android 本地测试 | 28 项全部通过；含保存后重新编辑、主动清空、菜单编辑、待办暂存、不投递与既有 mock GitHub/存储回归 |
| API 26 隔离 qa 包 `MemosInteractionTest,MemosVisualTest` | 390px 最终包 2 项通过；实际菜单编辑、取消、正文双击、三种状态、已投递隐藏投递入口及浅/深色截图 |
| 最终 API 26 实际屏幕尺寸 | 320/390/430px `MemosVisualTest` 各 1 项通过，均使用实际 AVD 屏幕尺寸；最终浅/深色、日历与菜单截图已收集。结束后停止本次模拟器，恢复测试配置为 390px |
| `node --import tsx apps/android/web-reference.ts` | 真实原版 React 页面配合合成 Connect 响应，320/390/430px 无横向溢出，0 pageerror；首页、详情、日历、抽屉与深色截图已保存 |
| Android Lint / APK 签名 | 0 错误、9 条版本/KTX 写法建议；可访问性与 Compose modifier 新警告已修正。APK v2 调试签名核验通过，1 个签名者 |

最终主 APK：`apps/android/app/build/outputs/apk/debug/app-debug.apk`；`xyz.leedaud.echo` / 0.2.0 / versionCode 2，最低 API 26、目标 API 36。SHA-256：`bc25ca29c466d7e40e3512a42497e081e2b525461b3862e242afe32cc3953253`。合成截图与日志位于已忽略的 `apps/android/app/build/android-qa/ui-reference/`、`logs/`；没有私人账号或真实仓库写入。

### ss-review 与一致性结论

按 `D:/AAA-Project/000-styleseed/engine/.claude/skills/ss-review/SKILL.md` 完成原生等效审查，结果为 **Needs Improvement**。颜色已集中为原网页语义主题；新 GitHub 设置有标签、开关、密码遮罩和触控目标；系统安全区、滚动与原生焦点行为保留。原版优先，源网页的 24/28/32px 紧凑控件按原网页复制，不以通用 StyleSeed 重绘；React/Tailwind 专属条目不适用于 Compose。

**本轮不宣称 UI 已“一模一样”。** 尚需闭环：

- `EchoApp.kt:230` 的 BasicTextField 未复制 CodeMirror 语法装饰；`EchoApp.kt:469` 的 Markwon 阅读与 `MemosFeed.kt:66` 的卡片仍缺少网页标签胶囊/图片画廊等细节，需要按原网页逐项补齐。字体跨 Windows Chromium 与 Android 也不相同，尚未做同一手机上的逐像素比较。
- 详情、搜索/标签、归档、附件及设备设置已有入口和本地行为，但所有状态、320/430px 长内容、图片与字体缩放的逐页截图矩阵未完成。设备设置保留直投所需字段，不伪造网页服务器账号/管理后台。
- 早期 `wm size` 虚拟缩放截图有黑边，430px 截图曾返回 null；这些失败不算通过。随后使用隔离 AVD 的真实屏幕尺寸复测；实际原生截图不代替真实中文输入法、录音/位置权限、系统分享和当前 Android API 验收。

本轮未修改 Web 生产代码、服务、数据库 schema、凭据或 CI/CD；未部署、推送或公开发布。只提交过 UI 计划，其余本地 Android 实现和文档保留在工作区；原本已有的未提交改动没有回退或混入计划提交。

## 2026-10-08 Android WebView 容器原型验证

后续用户明确此容器不满足独立 App 目标。以下仅是历史原型构建和回归证据，不作为本地独立运行、离线存储或手机直投 GitHub 的验收结论。新方案目前只完成需求与规划文档，尚未实施；本轮文档差异与链接检查通过，无新增功能测试成绩。

用户确认实施 `docs/plan/plan.md` 的 Android 客户端方案：原生 Android Activity/WebView 承载现有 HTTPS Memos 界面，沿用 Memos → Echo → GitHub；不接入 ObsidianHub、不引入手机 GitHub 凭据、不修改服务端或正式数据。

### 本地交付与检查

| 检查 | 结果 |
| --- | --- |
| `apps/android/gradlew.bat --offline --no-daemon assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` | 通过；主 APK 与设备测试 APK 已构建 |
| Android 单元测试 | 4 项通过；受信任路由、伪造域名/协议/端口、外部协议、受信任文件/Blob 来源 |
| Android Lint | 0 错误、2 警告：API 33 返回属性在旧版本忽略、存在更高 Gradle 版本；固定版本复用已有工具链 |
| `apksigner.bat verify --verbose …/app-debug.apk` | 通过，APK v2 调试签名，1 个签名者 |
| `aapt.exe dump badging …/app-debug.apk` | Echo / `xyz.leedaud.echo` / 0.1.0，最低 API 26，目标/编译 API 36；无 ABI 专属原生库 |
| `npm run typecheck` | 通过 |
| `npm test` | 6 文件、45 项通过；沙箱 realpath EPERM 后在受审查的本地环境重跑通过 |
| `npm run build` | 通过；Ignis 既有 eval 和 Svelte 可访问性警告保留 |
| Memos `node node_modules/vitest/vitest.mjs run tests/memo-editor-cache.test.ts tests/memo-editor-todo-mode.test.ts tests/memo-delivery-status.test.tsx tests/memo-delivery-edit.test.tsx tests/memo-action-menu.test.tsx tests/echo-delivery-query.test.tsx` | 6 文件、47 项通过，合成数据回归；不代表 Android 实机通过 |
| `git diff --check` | 通过；Windows CRLF 转换提示不影响检查 |

工具链使用项目外已有 JDK 21.0.10、Android SDK 36 / Build Tools 35.0.0、AGP 8.12.0 和 Gradle 8.13。缺少的 Maven 构建依赖仅下载到现有工具缓存，没有全局安装或修改环境变量持久配置。Wrapper 源于缓存的官方 Gradle 8.13，分发校验固定为官方 `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`。

交付主 APK：`apps/android/app/build/outputs/apk/debug/app-debug.apk`，SHA-256：`c259c7773529fd9727712d488b8ceded33e9fb4668d9509e35336fee55b7d8bb`。安装说明位于 `apps/android/README.md`；APK 与构建缓存已忽略，既有本机调试密钥不复制进源码。

### 原生行为与代码审查

- 保留 Cookie 与 DOM storage；不清空 WebView 数据。暂停/恢复仅通过已核对的 Memos pagehide/pageshow 处理器刷新本地草稿并暂停/恢复 SSE，不调用保存或投递接口。系统直接杀进程仍可能早于异步刷新，不承诺恢复尚未暂存的最后输入或本地附件。
- 文件选择使用 ACTION_OPEN_DOCUMENT，保留页面声明的类型/多选；只接受系统返回的 content URI，取消回调为空，不申请整个存储空间权限。
- 录音仅授权已有 AUDIO_CAPTURE，定位只允许固定 HTTPS origin，系统权限按需申请，拒绝/取消不默许授权。其他 WebView 权限仍拒绝；没有新增相机或后台权限。
- 系统返回在 API 33+ 使用 OnBackInvokedCallback，API 26–32 使用旧回调；Lint 的 GestureBackNavigation 仅在已实现新回调的旧版兼容方法上局部说明抑制，未全局关闭检查。根页返回桌面保留任务；软键盘和安全区通过系统 Insets/adjustResize 处理。
- 下载/图片导出仅处理受信任文件/Blob，以一次性 evaluateJavascript 读取结果并由系统保存选择器确定目标；无 addJavascriptInterface、无任意路径写入。最大 20 MB，取消不报成功，失败保留页面。
- App 不处理外部输入 URL；仅固定 Memos origin 内部导航，外部受限协议使用系统应用。拒绝明文 HTTP、混合内容、证书错误及 file/content 顶层访问。禁止系统备份和设备迁移私有 WebView 数据。

按 `D:/AAA-Project/000-styleseed/engine/.claude/skills/ss-review/SKILL.md` 审查新增原生连接提示：**Needs Improvement（设备视觉验证缺失；代码检查通过）**。原 Memos 界面不重绘；原生提示使用集中颜色资源和夜间变体、18sp 标题/14sp 正文、24dp 间距、12dp 操作间距、48dp 触控目标、原生焦点/ripple、polite 状态反馈与可关闭提示；没有新增动画。React/Tailwind 专属项不适用于原生 View；Android 系统字体与原生控制保持平台一致。320/390/430 等效宽度的键盘、字体缩放、安全区及提示截图尚未实测，不能宣称完整视觉通过。

### 未闭环与发布边界

- `emulator -list-avds` 为空，SDK 无 system-images；`adb devices -l` 无已连接设备。未安装模拟器镜像或操作用户手机。
- `AndroidSmokeInstrumentation` 已编译入设备测试 APK，尚未运行；测试用于合成草稿暂停刷新及 Activity 重建保留，不是强杀、完整 Memos 登录或投递验证。
- 中文键盘、文件选择/取消、返回手势、录音/定位授权、文件下载/Blob 导出、断网和暂存后杀进程恢复仍待实机验收。正式账号和保存/续写/GitHub 端到端须由用户使用或另行授权；本轮没有创建生产 Memo 或 GitHub 笔记提交。
- 本轮未改 Web 前端、服务端、数据库、生产凭据或 CI/CD；未执行与 Android 无关的 Ignis 官方运行资源浏览器回归，也未重复 Memos 全量已有失败测试。没有提交、推送、生产部署或公开发布；现有线上服务没有改变。

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
- 生产镜像构建补充验证：Docker 内 Go 1.27 完成 Memos 后端编译，Node 24.14 完成前端构建；新 Echo/Memos 容器均 healthy，三个正式 HTTPS 入口返回 200。
## 2026-10-02 Memos 待办入口

- 新建和编辑顶层 Memo 显示“笔记 / 待办”分段入口；待办使用标准 `- [ ]`，多行正文可逆转换，空任务标记不能保存。续写评论不显示类型入口。
- bridge 将待办导出为独立的 `00_Inbox/yyyyMMdd-HHmmss.md`，增加 `type: todo`、`status`、`created`、`source` YAML；普通笔记内容保持原样，附件仍走现有最终笔记名目录。
- Memos 前端 lint 通过（675 个文件）；待办转换、保存校验与工具栏相关 10 项测试通过；生产构建通过，只有既有 `::highlight` 和大 chunk 警告。
- Go 1.27 容器内 `go test ./server` 通过，覆盖普通笔记不变、待办 open/done 元数据和既有附件 bridge 回归。
- ss-review：Pass。新增入口复用原版 Button、语义颜色、边框、焦点环和 `aria-pressed`；粗指针触控目标为 44px，紧凑分段不影响下方三列手机操作栏，无硬编码颜色或新增动画。


## 2026-10-04 手机笔记流转状态（本地完成，随后已部署）

### 行为与文件

- Echo：`src/server/memos/queue.ts` 增加受限批量只读查询，`src/server/app.ts` 增加 `/api/memos/internal/statuses`，使用现有 bridge 凭据。查询按 instance/owner/memo 隔离，读取一次队列，不入队、不补投、不写正文。
- Memos：`server/echo_bridge.go` 增加 `/api/echo/v1/memo-statuses`。核验登录和整批 memo 所有权，再使用与提交相同的快照组装，核对当前 submission、附件摘要和续写父关系；源正文摘要和附件名供前端与正在显示的版本比较。浏览器不获取 bridge token，不返回正文或附件字节。历史无法匹配时显示未确认，并保留其他已核验版本的凭证。
- 手机：`MemoView/MemoDeliveryStatus.tsx`、`MemoView.tsx`、`hooks/useEchoBridge.ts`、`MemoEditor/index.tsx` 显示已保存/等待/已入库/冲突/失败/未知，提供展开详情、只读刷新和显式重试。设备暂存修改与已保存版本分开；编辑器提示暂存仅在设备。列表查询合并为每批最多 10 条，仅可见卡片自动刷新，后台暂停，查询有 25 秒超时。成功/冲突不持续轮询，回到前台或手动刷新可重新核对。
- 当前队列没有持久的实时执行中标记和可靠核验时间，因此显示“等待投递”，不虚构“正在投递”或时间。GitHub 成功仍不代表电脑 Obsidian 已拉取。
- 本地实施阶段未修改数据库格式、凭据、生产开关或 CI/CD，也未删除真实内容；随后经用户明确授权完成提交、推送和部署，见下方生产验收。

### 命令与结果

- `npm run check`：通过；类型检查、45 项 Echo 测试及 Ignis 构建通过。新增测试覆盖批量查询只读、账户隔离、新旧版本、附件字节不一致、续写父关系与等待；原有构建警告保留。
- Memos `node node_modules/typescript/bin/tsc --noEmit --skipLibCheck`、`node node_modules/@biomejs/biome/bin/biome check src tests`：通过，678 个文件检查完成。系统 pnpm 启动报 `unable to open database file`，采用现有 node_modules 中相同入口执行，无依赖版本变更。
- Memos `node node_modules/vitest/vitest.mjs run --maxWorkers=4 --reporter=dot`：1541/1548 通过；7 项既有失败是 `memo-header-navigation.test.tsx` 的 4 项旧时间标签断言与 `use-auto-save.test.tsx` 的 3 项旧参数断言，相关实现和测试本轮未改。菜单测试按新 React Query hook 隔离 bridge 能力，9 项通过。
- 最终 Memos 定向测试（`memo-delivery-status`、`echo-delivery-query`、`memo-action-menu`、`memo-editor-cache`）：32 项通过，包含断网保留回执、旧版本不冒充当前成功、设备草稿并列、可见列表批量及显式重试。
- Memos `node node_modules/vite/bin/vite.js build`：通过；保留既有 CSS `::highlight`、大 chunk 和插件耗时警告。
- 官方 Go 1.27.0 临时工具链通过官方清单 SHA-256 核对后使用，不做全局安装。`go test -v ./server`：通过，含新增所有权、认证、跨源拒绝与只读路由测试；后续 `go test ./server/... ./core/...` 中 root server、auth、mcp、server/test 和所有有测试的 core 包通过。api/v1 1 项及 frontend 7 项仍因 Windows 临时 SQLite 文件/目录句柄无法清理失败；api/v1/test 和 fileserver 因 gofakes3 既有依赖下载连接超时未运行完整。
- `go test -race ./server/... ./core/...` 未完成：本机 C 编译器报 `64-bit mode not compiled in`；未修改系统编译器。广域竞态验收仍应在具备 64 位 C 工具链的环境补跑。
- `npm run test:browser` 未启动：未配置 `OBSIDIAN_ASSETS_PATH`；本次未改 Echo/Ignis 前端，不能将下面的 Memos 控件验证冒充原版完整入口回归。
- `git diff --check`：通过。

### ss-review 与视觉验证

按 `D:/AAA-Project/000-styleseed/engine/.claude/skills/ss-review/SKILL.md` 完成新增控件代码和浏览器审查：Pass。保留 Memos 原版界面优先，状态位于现有卡片内；新增组件使用语义颜色、13px 中文、data-slot、cn、原版 Button、焦点样式、aria-expanded 和 polite 状态播报。按钮最小 44px，操作间距 12px，长路径和提交可换行，不增加动画或全屏浮层。

使用临时 Vite 页面加载真实 `MemoDeliveryStatus` 源组件与 Memos 样式，Playwright 用合成回执拦截状态 API。在 320/390/430px 宽度下均无横向溢出，全部按钮宽高至少 44px，查看状态产生 0 次提交请求。该验证覆盖新增控件，不是完整原版 Memos 登录/保存端到端或真机验收。截图位于 `C:/Users/10159/.codex/visualizations/2026/10/04/01a106fd-68c4-7282-8b69-306777720036/memos-status-{320,390,430}.png`，临时工具、合成测试库、预览脚本与日志位于系统临时目录，没有私人笔记。

### 尚未闭环

- Echo 查询端和 Memos 后端/前端已同时部署，现有投递开关和凭据保持不变；历史笔记只读查询验收通过。
- 手机硬件实际操作和电脑 Obsidian 逐条接收仍待验收；没有逐条电脑回执时继续显示未核验。
- 补跑上文广域 Go 依赖/竞态与 Echo 官方资源浏览器回归；既有前端断言失败未在本任务扩大修复。

### 用户授权后的生产验收

- `76e66b3` 已提交、推送至 `origin/main`，服务器重新构建 Echo/Memos 镜像并更新两个应用容器。运行镜像、回滚方案和部署目录详见 docs/setup/production.md。
- Echo 与 Memos healthy，Memos、Echo、Vaultwarden 的 HTTPS 正式入口均为 200。Caddy 与 Vaultwarden 镜像及启动时间不变。
- 新查询端点：匿名同源 401，缺少 Origin 403，登录查询不存在的笔记 404。使用服务器既有登录身份查询 10 条历史笔记，10 条均 `verified`，源正文 SHA-256 全部匹配，路径及提交凭证存在，查询前后队列文件摘要一致。没有新建笔记、重投或 GitHub 内容提交。
- 公网 HTML 引用 `/assets/index-BSGOe1Rg.js`，实际懒加载 `/assets/MemoView-CnvCjECo.js` 包含 `memo-delivery-status` 控件及 `/api/echo/v1/memo-statuses` 查询逻辑；bridge 能力接口返回 enabled=true。
- 构建期间 SSH 连接曾中断；重连检查确认两个最终镜像均带 `76e66b3` 标签并构建完成后才切换服务。旧 Echo 镜像底层文件缺失，已通过旧容器源码重建回滚版本，未将数据或 secrets 纳入镜像。
- 默认 Playwright 浏览器版本未安装；改用本机已安装 Chrome 进行公网原版页面检查，不安装全局工具。页面登录初始化、用户设置和列表 API 曾返回 200，页面异常和创建/更新请求均为 0；但跨境导航、资源加载与状态标签等待发生超时，未完成原版页面状态显示断言，不能宣称完整手机端验收通过。保留本地控件的 320/390/430px 合成验证，真机使用仍待验收。

## 2026-10-05 状态位置、编辑方式与模式选择

- 用户确认实施后补充“投递中”。主标签为已保存 / 投递中 / 已投递，状态移至时间戳右侧，详情复用原生 Popover；队列 pending/waiting_parent 且无错误时表示投递过程尚未完成，非 worker 实时执行标记。失败、断网、旧版本等说明保留在详情。正文下方不再重复展示状态。
- MemoView 与 Header/三点菜单共享同一版本查询；查询尚未完成时不提供投递菜单，已投递隐藏“投递到 Obsidian”。显式提交成功立即刷新对应 memo，正常投递中每 5 秒查询、错误/未知每 30 秒，成功/冲突停止持续轮询；仍仅可见卡片自动查询，隐藏页面停止后台轮询。
- 双击已保存笔记使用原 memo 和 inline 草稿键；投递中及已投递使用新 memo 和 continuation 草稿键，REFERENCE 关联原笔记。打开前重新只读核验，失败时不打开；并发双击合并为一次核验，不触发提交或上传。原版评论编辑保留。
- 笔记 / 待办选择器为整行两列等宽，按钮至少 44px；时间戳过长可截断，状态和右侧操作保留。
- `node node_modules/typescript/bin/tsc --noEmit --skipLibCheck`、`node node_modules/@biomejs/biome/bin/biome check src tests` 通过（679 文件）；Vite 生产构建通过，既有 CSS、chunk 和插件耗时警告保留。
- 最终四组前端测试 `memo-delivery-status`、`memo-action-menu`、`echo-delivery-query`、`memo-delivery-edit`：36 项通过，覆盖菜单、当前/旧版本、原 memo 编辑和关联续写、并发双击、错误保护，以及显式提交后的已保存→投递中→已投递和只提交一次。
- 全量 Vitest 1552/1559 通过；仍是此前的 7 项旧断言失败（memo-header-navigation 4 项，use-auto-save 3 项）。未改服务器接口、数据库、凭据、CI/CD 或真实笔记内容，本轮未使用 Go/Echo 服务端检查代替前端验证。
- ss-review：Pass（仅新增控件）。复用原生 Button/Popover、语义颜色、13px 中文、焦点和 aria-pressed；状态触控区域与模式按钮至少 44px。保留原生三点/反应图标样式，没有将新增控件规范扩展为全站重绘。
- 临时预览加载真实 MemoDeliveryStatus 组件和 Memos CSS，模式选择部分直接取本轮 EditorToolbar 的 JSX，时间戳布局采用相同 flex 类。320/390/430px 均无横向溢出，模式等宽占满一行，切换 aria-pressed 正常，状态详情展开无溢出，查看状态产生 0 次提交。该预览不是完整原版登录/编辑端到端或真机验收。
- 合成截图位于 `C:/Users/10159/.codex/visualizations/2026/10/04/01a106fd-68c4-7282-8b69-306777720036/memos-status-adjustment-{320,390,430}.png`；临时 harness 与日志在系统临时目录，预览服务已停止。

### 本轮生产原版手机页面验收

- `7ce26b5` 已提交、推送并部署，仅替换 Memos。镜像及回滚记录见 production；Memos/Echo/Vaultwarden healthy，Caddy 与其他服务未重建，三个 HTTPS 正式入口 200。服务器 10 条历史笔记状态均 verified，源摘要匹配且查询前后队列文件未变化。
- 使用本机 Chrome 访问正式 HTTPS 原版 Memos，390×844px 移动视口，登录 token 只在进程内存中传递。页面实际出现“已投递”，所属 DOM 位于 `memo-header-meta` 时间戳区域；可见笔记查询合并为 1 次请求。
- 等待三点菜单挂载后核验：已投递笔记有“续写”菜单项，没有“投递到 Obsidian”。真实编辑器的记录类型为 2 个等宽按钮，整行占满，触控高度至少 44px。页面无横向溢出，pageerror=0，提交/创建/更新请求=0。
- 本轮原版页面的状态/菜单/布局验收成功，不再沿用上一轮因公网超时未完成的结论。原笔记更新和关联续写仍由本地行为测试核验；生产只读检查没有实际保存、创建或重投内容，手机硬件与电脑逐条接收仍待真实使用。
# 2026-10-09 草稿恢复、损坏缓存与附件 Range 修复

- 已消费草稿在原生落盘前按编辑 changeToken 拦截迟到回放，保留精确 localStorage 消费值；新 token 的相同正文仍允许暂存。保存前写完整 Draft 检查点和待消费值，恢复时核对正文、附件、父引用、多引用、位置、分组和时间，不能仅凭正文相同消除草稿。无 Room schema 迁移，旧无检查点条目保留而非猜测已保存。
- 离线列表逐条核验，损坏索引/附件不阻断其他独立已核验记录；损坏文件不删除，直接 load 仍失败。合成回归保留两个账号隔离及坏索引文件证据；未读取真实历史。
- 附件增加单范围206/范围边界检查和终点受限流，保留 sandbox/nosniff/no-store。真实设备初次测试暴露双重偏移（请求2–4但正文只剩4），因此改为由 Chromium 进行初始 seek，流在初始available报告原文件大小、读取只到所请求终点。依据 [Chromium InputStreamReader](https://raw.githubusercontent.com/chromium/chromium/main/components/embedder_support/android/util/input_stream_reader.cc) 与实际124行为；不把普通HTTP切片实现直接当成 WebView 流契约。
- 最终 `testDebugUnitTest lintDebug assembleDebug` 成功：62 项测试零失败；TypeScript通过，Lint0错误/18警告。新增草稿迟到/新输入、完整保存检查点、损坏缓存隔离及range边界测试；既有58项保留。API35真实 WebView 用原附件输入导入合成10字节文件、暂存/保存后读取2–4、后3字节、8起、首字节、超出终点范围，全部206且正文/Content-Range准确，pageerror=0。编辑、任务、双击、表格/公式/Mermaid再次通过。
- 最终主APK 0.4.0-preview2 / versionCode6，16,566,145 bytes，SHA-256 `b9e3ea22c16b2c2038412e27f8d52e626069402e89bd0c982a649c39a2820865`，签名核验通过；346项本地资产与构建清单逐项一致，增量许可证存在。默认入口仍未切换，显式Debug预览可用。后续构建改变hash时以新记录为准。
- ss-review仍为Needs Improvement（整体）：本次不重绘原组件；改用普通viewport截图，目视表格/公式/代码/Mermaid/脚注不重叠，原操作保持布局。深色、完整逐页、权限、真实音视频播放/拖动与所有格式仍待验收；启动立即附加Playwright时的页面关闭仍偶发，稍后完整回归通过，不宣称生命周期压力已完成。
- 审批服务曾短暂额度耗尽，首次清理未执行；随后恢复并正常获批测试。没有绕过审批、提交推送、部署、删除文件、真实账号/仓库写入或CI/CD改动。

# 2026-10-09 原组件候选继续迁移与设备验证

- 当前候选已接入原工具栏/编辑器/阅读器、附件私有分块上传、元数据、全部本机 CEL 筛选排序、多引用/反向关系、本机分组、个人偏好、原生保存/分享/权限、账号管理及首页远端合流。完整能力与未闭环范围见 android-web-parity；不以接口已接入等同设备验收完成。
- 修复：原 Connect Request 请求体适配；保存后草稿消费回执与幂等重试；原动态照片和媒体元数据传递；同秒多笔记 Markdown 导出采用不占用其他原时间戳的唯一文件名，不修改原记录时间或投递路径。旧 Compose 回归测试明确挂载旧界面，不冒充新入口验证。
- 安全：远端 Memo 投影改为已知展示字段白名单，合成未知字段/token 不进入页面；身份与附件资源按账号隔离，冻结记录拒绝正文更新。APK 按当前资产清单与源 hash 命名空间打包，旧资源留在忽略 build 但不混入包；构建生成实际依赖许可证。Room schema、生产源码、凭据与 CI/CD 未变。
- 最终 `testDebugUnitTest lintDebug assembleDebug assembleQa` 成功，58 项测试零失败；Lint 0 错误/18 警告。候选 TypeScript 无输出通过，CEL 8 项断言通过。原组件浏览器320/390/430：短卡102px、正文24px，无横向溢出、pageerror=0，输入不创建记录，显式保存才调用 CreateMemo。Vite 保留既有 highlight/CSS 与大 chunk 警告。
- API35 / WebView124 已实际运行原组件，不再沿用 API26 无 provider 的限制作为现代设备未测试结论。隔离 emulator-5558，仅合成数据、无账号/投递配置，实际通过输入/暂存/保存/任务勾选/双击编辑，以及表格、KaTeX、代码块、Mermaid SVG。原画廊图片加载也已实测。最终版本再次安装、显式预览启动并重跑通过，报告 `app/build/android-qa/local-ui/android35/result.json`。首次启动立即附加 Playwright 曾遇页面关闭，App PID 仍在，稍后重跑通过；启动重载/生命周期压力矩阵尚未完成，不宣称此竞态已彻底修复。
- APK 为 0.4.0-preview2 / versionCode6，15,963,859 bytes，SHA-256 `da9fdf35630fb2d3809e5910d7301ac39b25d370653cfe56087c3af841518b31`，apksigner verify 通过。默认入口仍为保留原生界面；Debug 的 `local-ui-preview=true` 开启本地原组件。曾临时启用默认本地入口验证通过，但完整验收门槛未满足，因此最终包恢复显式预览，不提前发布正式0.4.0。
- ss-review 为 Needs Improvement：新增适配没有重绘原组件，三宽度短卡与实际设备格式目视检查通过。设备完整截图可能受 Playwright fullPage 滚动合成影响，不拿合成图重复区域当作最终逐像素结果；完整系统栏/IME/深色/权限与逐页比对仍待完成。
- 未闭环：最低WebView111、录音/定位/系统保存取消、音视频seek、受信iframe（CSP仍禁用）、大消息/磁盘失败/强杀恢复、缓存损坏回退、完整导入元数据往返及所有格式逐页矩阵。评论/反应/服务器管理等仍为已确认个人范围之外。真实 Memos 登录、真实 GitHub 写入及手机硬件未执行，无提交推送或生产部署。

# 2026-10-09 原 Web 组件本地复用第一阶段

- 授权：用户“采用 / 开始实施”确认 R1–R4。新增 apps/android/local-ui/ 构建及 native LocalUiBackend/LocalWebView，复用固定原 main/router/React/CSS/CodeMirror/MemoMarkdownRenderer 及其懒加载模块，没有修改 upstream/memos 生产源码或 generated proto。旧原生/容器文件保留；默认入口尚未切换。
- 构建：Gradle preBuild 已依赖 buildLocalUi；生成资源位于忽略的 app/build/generated/assets/local-ui，APK 已核验存在 assets/local-ui/index.html。使用原已安装依赖和 pnpm lock，不新增全局包。Vite 原组件构建通过；保留 ::highlight 解析和大 chunk 警告。emptyOutDir=false，不自动删除旧 hash；最终资源清单与大小控制尚待处理。
- 类型检查：`node upstream/memos/web/node_modules/typescript/bin/tsc --project apps/android/local-ui/tsconfig.json --noEmit` 最终通过。开始检查暴露 pnpm 间接声明回落到根仓库不同版本的问题，独立候选配置对齐 router/query-core/lezer/leaflet/lodash/sanitize 的原已有声明路径及原全局类型，不修改上游代码。另行运行原上游 `tsc --noEmit --skipLibCheck` 仍失败（既有 router/testing-library 等声明解析），不把候选结果称为上游全量检查成功。
- 桥接口：APK 私有 HTTPS origin `https://echo-app.local`，无 file/content 页面访问、无 addJavascriptInterface、无真实 token 响应。只交付主文档 WebMessagePort；跨源/目录遍历/未知命令拒绝。附件 URI 以资源 ID 查询核验，本机文件路径不进入 JS；附件不能获得 port，文件响应增加 sandbox CSP，防止同源 SVG/HTML 取得原生权限。原生侧后台串行调用；路径/命令/版本保护有合成测试，真实端口及大消息/导航生命周期仍待设备验证。设计依据为 [Android 主框架消息说明](https://developer.android.com/reference/android/webkit/WebView#postWebMessage(android.webkit.WebMessage,%20android.net.Uri))，不能仅凭 API 选择称为已完成安全验收。
- 本机核心适配：原 Connect JSON 请求映射原生本机列表/正文保存/更新、单父引用和真实队列状态；更新携带本机 revision，冻结/旧版本拒绝覆盖。原 localStorage 结构化草稿镜像已有设备草稿及私有 local-ui-state 索引，清除写停用标记而非删除文件；输入不生成正式笔记或调度投递。原服务协议、Room schema、GitHub 队列格式不变。草稿与正式保存间的失败原子性、权限与附件完整生命周期尚待补齐，不能宣称 W2 全部完成。
- 浏览器实际运行：`node --import tsx apps/android/local-ui/verify.ts` 通过。原构建页面 + 合成 MessageChannel/native response，未使用真实账号/笔记。320/390/430 均无横向溢出；原组件短卡片 102px/正文24px，与 Web 基准坐标一致。表格、KaTeX 公式、脚注真实 DOM 通过；输入后没有 CreateMemo，点击保存后才创建，并观测 local.draft/local.clear-draft。页面错误 0。测试中首次暴露 Connect fetch 使用 Request 而非仅 init 的真实接口差异，已修复后重跑。
- 合成截图/报告：build/android-qa/local-ui/original-local-home-{320,390,430}.png、browser-verification.json。已等待 KaTeX 加载后截图，不把懒加载占位当最终格式。测试拦截了本机安全软件注入的外部脚本；没有关闭或修改安全软件/系统配置。其注入引起的 CSP 诊断与 App 自身策略区分记录，不开放外部脚本绕过。
- 原生检查：`./gradlew.bat --offline --no-daemon assembleDebug assembleQa testDebugUnitTest lintDebug` 最终成功，50 项本地测试 0 失败，新增5项核心适配测试验证无凭据 bootstrap/无队列、原格式保存与乐观更新、草稿不入队及停用后不复活、冻结保护、未知命令及文件/文档路径隔离。Lint 0 错误/18 项警告（包括新 JavaScript/自定义 View 审查项），未宣称全部既有或无风险。
- 设备限制：隔离 API26 `dumpsys webviewupdate` 实测 Current WebView package=null、Any WebView package installed=false。显式 local-ui-preview 启动已验证显示兼容性提示和返回本机按钮，而非崩溃/空白；compat.xml 留存在合成目录。没有安装系统 WebView、改系统 provider 或访问真机私人数据。候选设置 Chromium111+ 下限来自 [原 Tailwind v4 兼容性要求](https://tailwindcss.com/docs/compatibility)，但 App 的实际最低内核及原组件功能仍需现代 WebView 设备测试。本机提示不等于新页面已在设备运行通过。
- 本轮 ss-review：Needs Improvement。可视页面直接复用原组件、主题、语义控件和响应式布局，三宽度短卡片已实测；原版优先于通用风格改写。Android 安全区与不支持内核保护已实现，但深色/IME/键盘/权限/完整功能及逐页截图仍未闭环。
- 阶段 APK：0.4.0-preview / versionCode5，app/build/outputs/apk/debug/app-debug.apk，17,367,235 bytes，SHA-256 `14794e2a184689481c4c3d47bd9cf20ec32e1b957c733eae4ce5d5cc94426b23`；签名验证通过，沿用 `58e970ab2519ab99db7fd717fb70d0eba1215c7e834d932860b0d452e7eee174`。仅 Debug 的显式 local-ui-preview 参数可打开候选，默认仍旧已验收入口；不是完整迁移交付包，避免让未适配接口替代已有可用功能。
- 未完成：附件选择/上传、录音/位置、筛选全集、多引用/导出、原生设备配置与 Memos 合流/离线、完整投递操作、WebView 主框架/导航/大消息/故障恢复、现代设备实际运行、资源/许可证清单和完整视觉矩阵。未知调用显式失败，不伪造原服务成功。W2–W5 保留待办；无生产改动、实际账号/仓库联调、数据库迁移、源码提交推送或 CI/CD 修改。

# 2026-10-09 Android 首页合流与短卡片修复

- 用户确认“首页统一显示原 Memos 与本机笔记，保留远端只读边界，修正短卡片留白，本机双击沿用投递状态规则”，实施中再次要求原 Web 1:1。取消独立历史导航，账号连接/刷新/退出在设置，登录后返回首页；首页/所有笔记/归档合流显示，两种源以各自身份去重并按置顶/时间排序。不按正文合并、不导入 Room、不修改服务器原笔记或原投递队列。
- 远端时间戳/三点打开只读详情，离线保存与另存草稿保留；双击正文创建仅附原 URL 的本机新续写草稿，不复制原全文，不伪造本机父投递回执。本机原编辑/续写规则不变。退出隐藏远端记录与详情，不隐藏已独立另存的本机草稿。分类切换等待正在进行的读取结束后按需刷新，防止读取归档后首页未切回；读取失败不循环发请求。
- 已实际运行原 Web 固定上游及 apps/android/web-reference.ts，用原 React/CodeMirror/MemoView 组件和合成 Connect 响应截取基准。仅 localhost，外部请求拦截；无生产账号、私人历史、服务器写入或部署。使用已有系统 Chrome，不安装新浏览器。原页面 pageerror 数为 0。
- 实测 `web-short-measurements.json`：320/390/430 均无横向溢出；单行卡片高 102px，正文 24px；Web 内边距上下 12px/左右 16px，加 1px 边框，正文顶部相对卡片 65px、底部 13px。原源码为 MemoView/constants.ts、MemoContent/index.tsx 的 leading-6。原生正文改为完整 24sp 行盒（TextView lineSpacing 原来不为末行保留同等空间），段落分隔仍为 8px；空正文不生成额外占位，移除无内容的首页来源提示占位。正文颜色按原 MemoContent 的 foreground 显式覆写而非继承 card-foreground。
- 最终执行 `./gradlew.bat --offline --no-daemon assembleDebug assembleQa testDebugUnitTest assembleQaAndroidTest lintDebug` 成功，45 项本地测试 0 失败；新增 4 项合流测试覆盖来源身份不混淆、重复远端 UID、非本人拒绝、置顶排序、退出隐藏缓存、离线完成快照、归档隔离与账号 key。Lint 0 错误/11 项既有警告。
- 最终隔离 API 26 QA 包运行 UnifiedHomeTest、MemosAccountTest、MemosInteractionTest、MemosVisualTest 共 5 项通过。验证首页同时出现两种来源、远端单行正文 24dp/底部 13dp、详情入口、双击空白来源续写、原正式笔记/队列不变、独立历史导航不存在、设置登录入口，以及既有编辑/菜单/日历/浅深色回归。首轮失败为设备截图 API 在逻辑尺寸覆写时返回 null，以及测试等待条件误匹配旧合成草稿；截图加入系统 screencap 回退，测试等待新 ID，最终全量重跑通过。
- 最终使用项目忽略 build/android-qa 内 AVD 的 432x932 物理画面（density 160，无 wm size 覆写），320/390/430 内容宽度分别截图，避免物理390/逻辑432导致画面缩放。原 Web 六张 `ui-reference/web-short-{light,dark}-{width}.png`，App 六张 `unified-home-{light,dark}-{width}.png`。目视核查标准浅色和小屏深色的卡片/正文/边框及换行；其余宽度由设备尺寸断言与截图留存。系统栏不计入 Web 内容区域比较。
- 本轮 ss-review：Needs Improvement（整体 1:1 未完成）。新增账号/来源/返回入口使用既有语义主题和 Lucide，主操作至少44dp；原 quiet 24dp 三点沿用 Web 特例。单行卡片尺寸通过，但原组件/字体栅格化、完整 Markdown、附件与多段/菜单逐像素矩阵仍未闭环。远端来源标为 Memos，不假装有本机 GitHub 回执；本机草稿状态文案按本机数据语义保留。这些不能称为与 Web 所有细节完全一致。
- APK 0.3.1 / versionCode 4，路径 `apps/android/app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `75f9ab09d5eddc5ab48d8bacd293a64dbf0077ea8ca024a19a531e68c663bebf`，签名验证通过，沿用调试证书 `58e970ab2519ab99db7fd717fb70d0eba1215c7e834d932860b0d452e7eee174`。不卸载、不清数据，可同签名覆盖。真实账号需用户在 App 内验收。CI/CD、生产服务、Room schema、真实笔记、提交推送不变。
- 完整 R1–R4 本地原 Web 组件复用仍等待独立确认；已向用户询问是否将再次提出的 1:1 要求同时确认成此渲染层切换。当前 APK 为首页/短卡片修复包，不作为全功能/完整 1:1 最终验收。

# 2026-10-09 Android Memos 只读历史接入

- 授权：用户确认 plan 的可选账号/只读历史/显式离线方案并回复“实施”。本轮没有请求真实 Memos 服务、读取私人历史、生产写入/部署、Room 迁移、Git 提交推送或 CI/CD 修改。
- 实现：独立 memos/ 客户端、Android Keystore + AES-GCM 会话；仅认证三个 POST，其余限定 GET。HTTPS 单实例、不跟随重定向；读取核验登录身份和 memo/附件归属。正常/归档分页；缓存按实例/账号/UID/规范化源版本分区，原子索引仅在全部附件与最终源版本核验完成后推进。
- 入口：侧栏“Memos 历史”、设置“连接原 Memos”；离线保存不入队。另存创建新设备草稿，保留原 Markdown、复制附件、附来源 URL；不修改原 Memos，不冒充已投递父笔记，不调用 enqueue 或 worker。
- 构建命令：在 apps/android 使用现有 JDK 21/SDK 执行 `./gradlew.bat --offline --no-daemon assembleDebug assembleQa testDebugUnitTest assembleQaAndroidTest lintDebug`，最终成功。41 项测试通过，0 失败；Lint 0 错误、11 项既有警告。首轮暴露新增图标不存在，次轮暴露 JSONObject.quote 的斜线转义不适合 CEL；两项已修复后全量重跑通过。
- 新增 10 项 Robolectric 合成测试：HTTPS/资源标识与账号范围、跨账号拒绝、登录后身份核验与密码不持久化、正常/归档分页参数、完整附件缓存与摘要损坏检测、下载中断不生成回执、源版本变化不推进、禁止写接口、刷新轮换与身份核验、失败保留已核验旧版本。传输为注入式 mock，不代表真实网络/实际 Memos 版本兼容已验证。
- 隔离 API 26 模拟器 emulator-5556、QA 包：MemosAccountTest + MemosInteractionTest + MemosVisualTest 共 4 项通过；增加另存草稿/截图断言后 MemosAccountTest 再次通过。验证真实 Keystore 密文不含合成 access/refresh 明文、断开后不恢复、无需登录入口可达、复制后的草稿新 ID/来源 URL/无伪造父关系、正式笔记及任务列表不变。既有交互/浅深色与侧栏回归通过。未清除设备或用户数据，仅安装 QA 包并使用合成内容。
- 截图位于忽略的 `apps/android/app/build/android-qa/memos-login-320.png` 与 `memos-login-390.png`。已目视检查状态栏留白、表单、按钮和中文无横向溢出。截图底部包含操作 Toast；不将 Toast 或系统栏算作表单区域差异。
- 本轮 ss-review：新增登录表单及账号控件为 Pass（局部审查），采用既有 Memos 语义主题与 Lucide 资源，输入有标签、密码隐藏、44dp 操作区、选中 Tab 语义、附件名称省略并独立图标操作、详情操作垂直排列防小屏溢出，图片延迟降采样。React/Tailwind 项不适用，原 Web 风格优先。完整登录后详情、430px/大字号/当前 API 与真机视觉仍未闭环，不将局部 Pass 扩大为整个 App 与 Web 一模一样。
- APK：`apps/android/app/build/outputs/apk/debug/app-debug.apk`，0.3.0 / versionCode 3；SHA-256 `1308145172d5e375ddd3cb5a5f054cfc406618b06d460610879539ab521dabf4`。apksigner verify 成功，沿用既有 Android Debug 证书，SHA-256 `58e970ab2519ab99db7fd717fb70d0eba1215c7e834d932860b0d452e7eee174`。同签名覆盖安装，不建议卸载。
- 限制：仅用户名/密码；SSO 未适配。外链附件不下载，超限/归属不明不会标记完整离线。离线缓存并非全库加密；断开隐藏但不删除。远端内嵌图片链接在原 Markdown 中保留，复制附件不代表内嵌引用已完整重写。此前已被清理或仅在 GitHub/电脑的历史不会自动恢复。完整原 Web 渲染 R1–R4 仍待单独确认；真实账号由用户在 App 内登录验收。
