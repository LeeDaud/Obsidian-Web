# Codex 工作规则

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

