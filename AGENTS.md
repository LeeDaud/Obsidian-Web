# Codex 工作规则

## Memos 接入授权（2026-09-28）
- 用户已确认 docs/plan/plan.md 的 Memos 临时收件箱方案，授权本地实现与合成数据验证，保留 Echo/Ignis。
- Memos 原生手机网页增加显式投递，设备草稿不自动上传；原生手动保存仅暂存。新入口采用原生登录与私有访问，Echo 免登录策略不变。
- 固定 Memos 上游已接入默认关闭的文本 bridge：服务端核验登录用户与 memo 所有权后调用 Echo，浏览器不得持有 bridge token；附件、状态持久化和清理尚未接通。
- 清理需要正文/附件核验、原子版本检查和活动/不确定会话保护，失联不等于结束；新服务投递与清理默认关闭。
- 新部署、凭据、数据库迁移和真实内容删除仍单独授权；实际进度见 todo。

CLAUDE.md 是本项目完整规则与事实来源。执行任务前完整读取根目录 CLAUDE.md；子目录若有更具体规则则一并遵守。

- 默认中文；修改前明确目标、范围、约束、验收标准。
- 新目录先定义结构和清理规则。涉及三个及以上文件、接口、类型签名、数据结构、存储格式、新架构层或核心模块删除/重命名，先写 docs/plan/plan.md 并等待确认；确认后写 docs/todo/todo.md。
- 删除文件/目录/Git 历史，修改 .env/密钥/token/CI/CD，数据库迁移，git push/rebase/reset/强推，全局依赖、系统配置及公开发布/生产部署必须先询问。
- 不主动提交或推送；每轮改动后给 `<type>: <简短中文描述>` commit message。
- 应用代码和私人输入仓库分离；不提交私人笔记或凭据。自动 Git 同步默认关闭，真实启用前取得授权。
- 当前目录仅用于网页程序实现；真实笔记仓库必须位于外部，未配置输入路径时不得回退到项目内写入。
- 服务器只临时中转，不长期保存笔记仓库及 Git 历史；用户已确认 GitHub 长期备份；已确认核验 GitHub 投递成功后按版本清理临时正文，不传播删除，真实投递仍默认关闭。电脑通过 Obsidian Git 接收。
- 用户已明确取消网页登录鉴权；公网入口仍必须使用 HTTPS，并保留 Host、同源写入和 WebSocket Origin 校验。
- 前端遵循 D:/AAA-Project/000-styleseed/engine/DESIGN-LANGUAGE.md 和 CLAUDE.md，完成后执行 ss-review。
- 修改后执行适当测试及项目检查，如实交付文件、命令、结果、CI/CD 状态及未闭环事项。
- 规则变化先改 CLAUDE.md，同轮同步本文件；用户当前明确授权优先。

## 用户最新纠正
- 必须保留 Ignis 原有 Obsidian 前端，通过扩展增加即时记录行为；不得以独立 React 页面替代。
- 原版界面优先于通用重绘规则；旧原型测试不代表原版集成已通过。清理临时正文还须保护活动编辑会话。
- 纠偏计划见 docs/plan/plan.md，用户已确认切换与所列旧前端删除，先验收原版后移除。

## 已核验接入与授权边界
- 原版集成及已授权旧前端清理已完成；验证见 docs/setup/verification.md，来源见 docs/setup/upstream.md。
- 电脑输入仓库 D:/AAA-Echo，origin 为 https://github.com/LeeDaud/Echo.git，现为私有。用户已授权改私有及仅初始化两文件提交推送；bcd2213 已推送，main 跟踪 origin/main。
- 上述授权不扩大到源码提交、真实自动笔记投递或生产部署。GitHub 自动投递仍默认关闭；服务器凭据、Obsidian Git 配置、域名与正式部署待接入。
- 服务器 CAPTURE_DATA_DIR 与电脑输入仓库分开；官方资源通过项目外 OBSIDIAN_ASSETS_PATH 配置。
- 原生空白笔记不生成 Markdown；设备草稿使用 localStorage。清理要求已核验版本且会话失活超过 120 秒；失败和新版本保留。
- 编辑只更新设备草稿；输入、联网、隐藏、退出和心跳不得自动上传。只有独立“上传当前笔记”按钮可写入服务器队列；云图标仅显示状态。
- 新笔记使用 `yyyyMMdd-HHmmss.md`；历史文件不重命名。右侧格式工具栏默认收起为悬浮按钮，底部导航保持原位。

## 正式部署事实（2026-09-21）
- 用户已明确授权直接部署 echo.leedaud.xyz。独立容器 echo-capture 已上线，HTTPS、原版编辑器、WebSocket、健康与崩溃恢复通过；现有 Vaultwarden 未重启且正常。
- 部署代码位于 deploy/；服务器 /opt/echo；无凭据部署记录见 docs/setup/production.md。
- 用户已确认完成电脑 Obsidian Git 配置。GitHub 自动投递已启用，Echo 合成笔记已完成服务器、GitHub 与电脑端真实验证。

## Memos 正式入口（2026-09-28）
- `memos.leedaud.xyz` 已通过 Caddy HTTPS 转发到独立 `echo-memos` 容器；实例为 private，公开注册关闭，Memos、Echo、Caddy 与 Vaultwarden 健康。
- 服务端 bridge token 按容器 UID 使用独立 `0400` 文件；文本入队接口和 Memos → GitHub worker 已启用，真实合成 Markdown 已通过提交内容核验。附件、状态回显与条件清理仍关闭。

## Memos 状态回显上线（2026-10-04）
- 用户明确授权本轮源码提交、推送及部署；`76e66b3` 已上线 Echo/Memos，原版手机列表和详情支持当前版本投递状态及仓库回执显示。
- 状态查询只读且核验登录用户与 memo 所有权；10 条历史笔记的当前版本与仓库回执匹配，查询未改变队列。沿用现有凭据和投递配置，无数据库迁移或真实内容删除。
- “已投递到仓库”仅表示 GitHub 内容核验成功，电脑 Obsidian 是否拉取仍无逐条回执；手机硬件验收另待实际使用。部署与检查见 docs/setup/production.md 和 docs/setup/verification.md。

## Memos 交互调整（2026-10-05）
- 用户确认的三种主状态为“已保存 / 投递中 / 已投递”，位于时间戳右侧。投递中表示当前版本已入队且等待仓库核验完成，不宣称 worker 实时执行；错误和未知详情仍可查看。
- 已保存双击编辑原笔记，投递中或已投递双击创建关联新笔记续写；状态查询失败时先保护原笔记并提示重试。已投递笔记隐藏投递菜单，设备暂存仍不自动上传。
- 笔记/待办选择器整行等宽。沿用本功能提交推送部署授权，`7ce26b5` 已上线，仅更新 Memos；无服务器接口、数据库、凭据或真实笔记内容变更，验收见 verification。

