# Android 与原 Web 的格式及功能核对

日期：2026-10-08。基准为本项目 upstream/memos/web 的实际组件和本地独立 Android 实现。以下是源码审计，不冒充真实仓库、真实手机或所有格式的运行验收。

## 结论

当前 APK 不是原 Web 的完整迁移。此前原生界面复刻保留了部分记录流程，但使用 BasicTextField + Markwon 代替 CodeMirror + MemoMarkdownRenderer，造成格式、样式和交互缺口。此前“构建通过”“本轮 UI 检查通过”只证明当轮有限检查，不能据此说所有功能齐全。

续写底层没有被删除：NoteRepository.edit 对 frozen 记录创建新 Draft 与 parentId，禁止覆盖原记录；已有队列快照不会被当前输入改写。存在界面缺口：列表没有展示完整关联，详情只有单条文字；父笔记置顶时，旧 inlineNote 匹配可能把编辑中的子笔记放到父笔记的位置。这是展示错误，不是原快照被覆盖。

## 格式矩阵

| Web 格式/操作 | Android 当前情况 | 对照源码 |
| --- | --- | --- |
| 暂存、保存、更新 | 行为存在；原生按钮圆角、字重、禁用色与 quiet variant 不同；编辑仍显示保存而非更新 | MemoEditor/Toolbar/EditorToolbar.tsx、ui/button.tsx |
| 中间插入按钮 | 菜单存在；缺少 Web outline 边框、背景及 44px 固定方框 | MemoEditor/Toolbar/InsertMenu.tsx |
| 笔记/待办整行选择 | 已有；使用 Material TextButton，原类名/交互仍不是同一实现 | EditorToolbar.tsx |
| 编辑语法着色、选区与命令状态 | 未完整迁移；BasicTextField 没有原 CodeMirror 装饰、语法树与命令状态 | MemoEditor/Editor/* |
| 13 项原格式命令 | Android 简单插入标记仅覆盖部分；缺少有序列表、删除线、代码块、段落、H1/H3及原 toggle 行为 | MemoEditor/formatting/commands.ts |
| 标题、段落、引用、列表、行内代码 | 能显示部分 CommonMark；字号、标题分隔线、代码底色、边距与 Web 组件不同 | lib/markdownStyles.ts、MemoContent/markdown/* |
| 表格、脚注、自动链接、混合/嵌套待办 | 当前依赖没有完整复用原 GFM pipeline，不能宣称兼容 | MemoContent/pipeline.ts |
| 标签胶囊、标签点击筛选、提及 | 侧栏能搜索标签；正文标签/提及渲染与点击没有完整迁移 | MemoContent/Tag.tsx、Mention.tsx |
| 数学公式、Mermaid、代码高亮/复制 | 没有原阅读组件及插件，未迁移 | MathMarkdownRenderer.tsx、MermaidBlock.tsx、CodeBlock/* |
| HTML 与受信 iframe | 不同解析/安全策略，未对等迁移，不应绕过 Web sanitize | MemoContent/pipeline.ts、TrustedIframe.ts |
| 图片、音频、文档的展示 | 已有本地导入、图片显示、音频播放和系统分享；不是原画廊/元数据行，正文图片与附件分离显示，可能重复 | MemoView/components/MemoBody.tsx、MemoMetadata/* |
| 图片分享 | Android 分享当前 Canvas 绘制的原始 Markdown，不等同 Web 格式化图片 | MemoActionMenu/MemoShareImageDialog.tsx |

## 功能矩阵

| Web 笔记功能 | Android 当前情况 | 需要补齐/验证 |
| --- | --- | --- |
| 新建、设备暂存、保存、离线恢复 | 已有本地实现和有限回归 | 原 Web 编辑器/原按钮逐状态对照、真实中文 IME |
| 未投递编辑原记录 | 已有；已修复保存后空草稿占位 | 列表和详情入口一致、所有格式恢复 |
| 投递中/已投递续写 | 底层已保护原记录并创建新关联 Draft | 两类状态的 UI 回归、父/子链接与返回定位 |
| 续写关系、其他笔记引用 | 仅 parentId；没有多条引用数据模型/原关系行 | 原相关笔记选择器、多引用、反向关联与导航 |
| 单个待办勾选、全部完成/重置 | 待办文本可转换；阅读区勾选及菜单任务子菜单未完整迁移 | 按版本保护，不能以任务操作绕过已授权快照 |
| 置顶、归档、恢复 | 已有 | 菜单顺序/归档条件与原 Web 对齐 |
| 复制正文、链接、分享、导出 | 正文复制/分享/ZIP在详情可用；列表菜单缺入口；可用笔记链接未实现 | 原菜单层级、可用本机链接、格式化 PNG |
| 删除/移除 | 当前仅本机隐藏，队列未确认时拒绝 | 不冒充 Web 真删除；不删除 GitHub 文件 |
| 移动笔记/Space | 未迁移 | 数据模型与范围须在完整迁移方案中明确 |
| 编辑器专注模式、原附件/引用插入 | 部分附件入口存在；专注模式和引用选择器缺失 | 复用原组件，连接本机权限/文件选择 |
| 搜索、标签、日历、归档页 | 已有基本本地入口，非完整原页 | 原过滤/排序、标签元数据及逐页截图 |
| 录音、位置 | 已有原生权限/录音与最近位置/手动录入 | 原面板、地图/地址解析、取消/拒绝权限真机验证 |
| 投递状态、显式补投、重试 | 直投队列和核验实现存在 | UI/版本绑定、错误与未知、续写父等待 |
| 评论、反应、多用户、服务器账户/管理 | 未迁移；此前首版需求排除这些 | 必须向用户明确，不把未实现当作齐全；扩大范围需另案 |

## 修复路线

R0 已完成：quiet 按钮与 outline 插入方框、card-foreground、更新/续写名称、列表复制内容、父子入口和 inline 子笔记定位已修正。31 项本地测试与 3 项隔离设备测试通过，包括投递中/已核验父快照保持不变。表中的其他格式和功能缺口仍然存在，不能把 R0 当作完整迁移。

R0 在已确认原生 UI 范围内修正 quiet/outline 控件、卡片文字色、编辑/续写名称、父子关联展示与 inline 编辑位置，并增加合成冻结版本回归。不改变数据格式、数据库、凭据或真实内容。

完整一致性建议复用原 Web 的编辑器、阅读器及操作组件，打包到 APK 本地，UI 操作由受限适配层连接现有本机存储/队列/直投。不是远端网页容器。由于涉及新的本地渲染层、事件接口和多引用数据，新方案需按 CLAUDE.md 先确认；具体方案见 plan.md 的“完整原 Web 界面与功能迁移纠偏”。确认前不切换启动架构，不把 R0 APK 当作完整迁移交付。
