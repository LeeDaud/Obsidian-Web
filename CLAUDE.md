# Echo：项目规则与事实

## Android 方向纠正（2026-10-08）

- 用户最新要求再次核对全部原 Web 格式与笔记相关功能，不接受用有限测试成绩代表完整迁移。源码核对已确认 BasicTextField/Markwon 方案存在格式与功能缺口，见 docs/setup/android-web-parity.md。R0 控件/续写展示修复已完成；完整本地复用原 Web 组件的新渲染层计划待确认，不默认切换，也不加载远端网页。

- 用户已接受页面重构，进入细节优化：Memos favicon、实际状态栏/刘海 inset 留白、侧栏笔记日期用网页四档浅色方块，不再追加圆点行。GitLab 问题本轮仅核查可行性，当前投递仍只有 GitHub，未启用其他服务或迁移旧队列。

- 用户已确认 Android UI 必须以当前 Memos 手机网页为基准逐页复刻，计划见 docs/plan/plan.md 的 UI 重构节；继续使用本地独立 App 与 GitHub 直投。计划提交 80597b5，未推送；原生结构/主题已重构，完整逐像素一致性仍需按截图差异验收，不以构建或单项模拟器通过替代。

- 用户明确指出远端 WebView 容器与网页桌面快捷方式差别很小，不符合迁移目标。Android 新方向是本地运行的独立 App、本地草稿/Markdown/附件/投递队列、手机直接投递 GitHub；首版只迁移现有个人记录功能，ObsidianHub 接入后置。
- 用户随后回复“实施”，已确认 docs/plan/android-requirements.md、android-architecture.md 及 plan.md 的独立 Android App 方案，授权本地原生实现、项目级依赖、本地新建存储与合成验证；进度见 todo。
- 使用 apps/android/ 实现原生入口，保留旧容器文件作为未启用历史代码，本轮不删除/改名。旧容器构建不代表独立 App 验收。现有 Web 服务、生产数据库、服务器凭据、私人数据与 CI/CD不变；真实 GitHub 联调、源码提交推送和公开发布另行授权。
- 下文原版前端、免登录、bridge 凭据和服务器临时存储规则继续适用于既有 Web 服务；不要求 Android 加载其远端界面。新方案建议用户在原生设备内配置独立 GitHub 授权，不复用服务器凭据，具体实现待计划确认。

## Memos 接入授权（2026-09-28）
- 用户已确认 docs/plan/plan.md 中 Memos 临时收件箱方案，授权本地实现与合成数据验证，保留原 Echo/Ignis 入口。
- Memos 使用原生手机网页和显式投递按钮；编辑只存设备草稿，原生手动保存仅暂存，投递按钮才授权 GitHub 投递。采用原生登录与私有访问，不改变 Echo 现有免登录策略。
- Memos 文本 bridge 已在固定上游中实现：仅配置服务端 Echo bridge 后显示投递菜单，由 Memos 登录身份核验 memo 所有权，浏览器不接触 bridge 凭据；附件、状态持久化及清理仍未启用。
- 清理须核验正文及全部附件、原子比较源版本并保护活动/不确定编辑会话；心跳失联不是安全结束。新服务真实投递和清理默认关闭。
- 新服务部署、凭据、数据库迁移及真实内容删除按专项授权执行；实现状态以 todo 和验证记录为准。

## 当前状态
- 计划已于 2026-09-20 获用户确认。原版 Ignis 前端集成已实现；17 项应用测试、10 项真实浏览器测试、702 项上游测试通过（6 项跳过）。独立输入仓库已初始化并关联；真实 GitHub 投递已启用，CI/CD 尚未启用；用户于 2026-09-21 明确授权后已部署 https://echo.leedaud.xyz。
- 用户纠正：必须保留 Ignis 原有 Obsidian 前端；此前独立 React 输入页不符合预期，不再作为交付方向。修订计划位于 docs/plan/plan.md，用户已确认执行切换和计划列出的旧前端删除。
- 输入数据使用独立文件夹和独立 Git 仓库；用户手动转入个人知识库。
- 应用代码与笔记数据分离；不得将私人笔记或凭据纳入应用源码仓库。
- 当前目录用于网页程序及已确认的 Android 客户端实现，真实笔记仓库不得放在当前目录或其子目录；输入路径必须显式配置，未配置时不得回退到项目内写入笔记。
- 用户新增约束：服务器仅临时中转笔记，最终文件保存在电脑本地。服务器不长期 clone 笔记仓库或保存 Git 对象历史；用户已确认 GitHub 可以长期保存笔记作为备份。

## 工作规则
- 默认中文、结论先行；修改前明确目标、范围、约束和验收标准。
- 新目录先规定结构、命名与清理方式。三个及以上文件、接口或类型签名、数据结构或存储格式、新架构层、核心模块删除或重命名，先写计划并等待确认。
- 计划写入 docs/plan/plan.md；确认后创建 docs/todo/todo.md，逐项记录验收与完成情况。
- 删除文件或目录、修改 Git 历史、.env/密钥/token/CI/CD、数据库迁移、git push/rebase/reset/强推、全局安装、系统配置、公开发布或生产部署，必须先询问。
- 不主动提交或推送；每轮改动后提供符合 `<type>: <简短中文描述>` 的 commit message。
- 规则变更同步 AGENTS.md；用户当前明确授权优先于通用规则。

## 目录与管理
- upstream/ignis/：固定版本原项目源码、许可证及本项目集成补丁，已作为 vendored source 纳入当前仓库，不保留嵌套 Git 元数据；src/client/ 等已授权的旧独立前端已经移除。
- src/server/：当前笔记 API、受限临时存储、GitHub 投递与版本核验。
- tests/：保存、恢复、并发及同步行为测试；只使用临时测试数据。
- docs/plan/：实施计划；docs/todo/：确认后的执行清单；docs/setup/：仓库与部署指南。
- apps/android/：2026-10-08 已实现 NativeActivity / Kotlin / Compose / Room / WorkManager 的独立 Android App，本机记录与 GitHub 直投为新入口；旧容器文件保留但从构建排除。设备及真实仓库验收待执行，构建不冒充硬件验收；build 只存忽略产物，签名密钥与私人输入不纳入源码。
- 原版静态资源及构建产物位于 upstream/ignis；官方 Obsidian 解包资源必须在项目外。构建产物、测试产物不纳入版本管理；旧根 dist 不再使用。
- 外部输入目录由配置指定，不放入源码目录；持久笔记为纯 Markdown。
- 文件采用语义化名称；不随意新增顶层目录。临时产物集中管理，删除前遵循询问规则。

## 实施约束与完成标准
- 当前入口采用 Ignis 原有运行时与编辑器，通过 src/server/capture-client.js 原生扩展接入，复用 GitHub 投递代码。不得把旧原型测试结果作为新集成方案的验收结论。
- 服务默认仅本机访问；远程部署必须有 HTTPS，不将服务器端凭据下发到浏览器。用户于 2026-09-21 明确取消网页登录鉴权；公网入口继续保留 Host、同源写入和 WebSocket Origin 校验。
- 临时保存和 GitHub 投递分别显示真实状态，不将 GitHub 成功冒充电脑已接收。已实现核验 GitHub 对应提交与内容后清理相同版本暂存，不等待电脑回执；新版本不得被旧确认清理，未投递内容不得超时删除。电脑优先使用 Obsidian Git 自动拉取，需打开对应库；清理策略已获计划确认，仅在真实同步明确开启后执行。
- Git 自动提交/推送功能默认关闭，接入真实笔记仓库并获得启用授权后才开启。
- 必须验证空白不落盘、时间戳重名、中文输入、断网恢复、写入顺序、电脑转移后旧页面不复活文件。
- 验证命令：npm run typecheck、npm test、npm run build、npm run test:browser；npm run check 汇总前三项。依赖安装使用 npm ci --legacy-peer-deps，绕过 npm 10 的可选 peer 解析问题。实际结果见 docs/setup/verification.md。
- 前端遵循 D:/AAA-Project/000-styleseed/engine/DESIGN-LANGUAGE.md 和 CLAUDE.md；实施前完整分段读取，完成后执行 ss-review 工作流；当前审查记录见 docs/setup/verification.md。单一输入页面遵循用户范围，不填充无关仪表盘模块。
- 交付如实记录文件、验证命令与结果、CI/CD 状态和未闭环项；无效代码及旧实现按授权范围清理。

## 原版前端优先
- 保留原版 Obsidian 编辑器、主题、工具栏及移动界面，通过 bridge/插件增加快速记录，不做视觉重写。
- 用户明确保留原界面的要求优先于通用 StyleSeed 重绘规则；ss-review 只审查新增界面。
- 临时正文清理还须满足不再活动编辑，不能在 GitHub 回执到达时直接删掉编辑器正在使用的文件；具体策略见纠偏计划。

## 已核验接入与授权边界
- 电脑输入仓库 D:/AAA-Echo；origin 为 https://github.com/LeeDaud/Echo.git，现为私有。
- 用户明确授权改私有，以及只提交并推送 README.md、.gitignore；初始化提交 bcd2213 已完成，main 跟踪 origin/main。此授权不自动扩大到网页源码提交、真实笔记投递或生产部署。
- CAPTURE_DATA_DIR 是独立的服务器中转目录，不能使用电脑输入仓库；须同时配置项目外 OBSIDIAN_ASSETS_PATH。
- 原生空白笔记以虚拟元数据参与扫描，首次非空内容才生成 Markdown。设备草稿保存在 localStorage；没有继续使用旧原型的离线 Service Worker。
- 编辑只更新设备草稿；输入、联网、隐藏、退出及定时心跳不得自动上传。只有顶部独立“上传当前笔记”按钮可将当前版本写入服务器队列；原云图标仅展示状态。
- 新笔记文件名使用上海时区 `yyyyMMdd-HHmmss.md`；历史文件不重命名。手机右侧格式工具栏默认收起为悬浮按钮，底部导航保持原位。
- 已核验 GitHub 版本且最后活动超过 120 秒，才回收服务器 Markdown；失败和新版本继续保留，不传播删除。
- docs/setup/verification.md 是实际验证记录，docs/setup/upstream.md 记录来源与本地补丁。上游依赖审计仍有已知问题，不宣称整体无漏洞。

## 正式部署事实（2026-09-21）
- 用户明确授权使用 D:/AAA-Project/000-VPS-CC 中的接入信息直接部署 echo.leedaud.xyz；此授权覆盖本应用的 Docker、必要目录与 Caddy 站点配置。网页登录鉴权已按用户要求取消。
- 已部署独立 echo-capture 容器，/opt/echo/releases/20260921；复用现有 Caddy，仅新增域名块，原有 Vaultwarden 健康检查通过。
- deploy/ 存放无凭据部署代码；临时打包产物在系统临时目录，凭据在项目外本地应用数据目录和服务器 secrets。
- HTTPS、匿名拒绝、真实原版编辑器、WebSocket、健康检查、排他锁和进程崩溃恢复验证通过；详情 docs/setup/production.md。
- 用户已确认完成电脑 Obsidian Git 配置。专用 token 已安装，真实 GitHub 投递已启用；`20260921224346.md` 已完成网页入口、服务器、GitHub 与 `D:/AAA-Echo` 的内容标识核验。

## Memos 正式入口（2026-09-28）
- 用户已授权并完成 `memos.leedaud.xyz` 部署；Caddy HTTPS 转发至独立 `echo-memos` 容器，实例为 private 且关闭公开注册，Echo、Caddy 与 Vaultwarden 验收正常。
- Memos bridge token 只以按容器 UID 隔离的服务器 `0400` 文件提供；Memos 文本提交接口及独立 GitHub worker 已启用。合成 Memo 已真实写入 `LeeDaud/Echo` 并按提交核验 Markdown 内容；附件、状态回显和条件清理仍未启用。

## Memos 状态回显上线（2026-10-04）
- 用户明确授权本轮源码提交、推送及部署；`76e66b3` 已上线 Echo/Memos，原版手机列表和详情支持当前版本投递状态及仓库回执显示。
- 状态查询只读且核验登录用户与 memo 所有权；10 条历史笔记的当前版本与仓库回执匹配，查询未改变队列。沿用现有凭据和投递配置，无数据库迁移或真实内容删除。
- “已投递到仓库”仅表示 GitHub 内容核验成功，电脑 Obsidian 是否拉取仍无逐条回执；手机硬件验收另待实际使用。部署与检查见 docs/setup/production.md 和 docs/setup/verification.md。

## Memos 交互调整（2026-10-05）
- 用户确认的三种主状态为“已保存 / 投递中 / 已投递”，位于时间戳右侧。投递中表示当前版本已入队且等待仓库核验完成，不宣称 worker 实时执行；错误和未知详情仍可查看。
- 已保存双击编辑原笔记，投递中或已投递双击创建关联新笔记续写；状态查询失败时先保护原笔记并提示重试。已投递笔记隐藏投递菜单，设备暂存仍不自动上传。
- 笔记/待办选择器整行等宽。沿用本功能提交推送部署授权，`7ce26b5` 已上线，仅更新 Memos；无服务器接口、数据库、凭据或真实笔记内容变更，验收见 verification。


