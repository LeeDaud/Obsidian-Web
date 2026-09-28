# Memos 接入与验证记录

## 固定来源

- 上游：`https://github.com/usememos/memos`
- 版本：`v0.31.0`
- commit：`2b2192d4e153bd04f1d325b60fd880cf00d68b01`
- 下载归档 SHA-256：`75FFEB73F58AAC5C21209D62E9D88E17B65CDD66E6986FA84443E7777FA29F88`
- 本地源码：`upstream/memos/`，包含上游许可证，不含嵌套 Git 元数据、凭据或运行数据。

## 目录和清理规则

- `upstream/memos/` 仅存固定源码、本地补丁和构建输入；`node_modules`、`web/dist` 等仍按项目忽略规则处理。
- `src/server/memos/` 存 Echo 侧的显式提交队列和快照校验，不直接读写 Memos 数据库。
- `tests/memos/` 只使用系统临时目录与模拟 GitHub；不触碰真实笔记或真实仓库。
- 运行时必须显式提供项目外的独占队列目录；目录含未知文件、符号链接或被另一进程占用时拒绝启动。当前模块没有接入生产启动入口。

## 已验证基线（2026-09-28）

- 官方发布清单核验后的便携 Go 1.27.0 可运行；Node 24.14.0 与 pnpm 11.0.1 安装在系统临时工具目录，未做全局安装。
- Memos 原生前端 `pnpm build` 成功；存在上游 CSS `::highlight` 和大 chunk 警告，未误报为零警告。
- `go test ./server/api/v1/test -run TestMemo -count=1` 通过，使用上游 SQLite 测试夹具。
- 将 `web/dist` 复制到上游约定的 `server/frontend/dist` 后，便携 Go 1.27.0 成功构建含原版网页的 92,087,296 字节 Windows 可执行文件。产物位于系统临时目录，未纳入源码。
- 隔离实例绑定 `127.0.0.1:5231`，数据目录为系统临时目录；`/healthz` 返回 200 和 `Service ready.`，根页面返回 200，包含 React 根节点和模块脚本，不再出现 `No embeddable frontend found.`。当前本机进程仅供后续合成验收，不构成生产部署。
- 根项目 `npm run typecheck`、31 项应用测试及 `npm run build` 通过。
- Memos mock/dry-run 覆盖：正文和附件单次 Git 提交、二进制逐字节核验、提交前持久检查点、丢回执恢复、并发分支保护、远端移动/改写保护、显式幂等提交、版本顺序、队列重启和进程排他。没有调用真实 GitHub。
- Echo 已增加 `/api/memos/internal/submissions` 提交及状态查询端点，仅在 `MEMOS_QUEUE_DIR` 与 `MEMOS_BRIDGE_TOKEN` 同时配置时启用。服务端 bearer 凭据至少 32 字符，队列必须是项目外独占目录；该开关与 Echo 的 `GITHUB_SYNC_ENABLED` 分离，当前没有生产配置。
- 内部端点接受受限的正文/附件快照，成功响应只包含 submission ID、状态和修订号，不回传正文。无凭据和错误凭据、幂等重试、按 owner 查询隔离已有自动测试；根项目最新 33 项测试、类型检查和构建通过。
- 原 Echo 浏览器回归已尝试，但所选旧 Obsidian 资源目录无法完成 `__captureReady`，10 项均在启动等待处超时；这不是功能断言失败，仍须找到正确资源路径后重跑。

## 源码审计结论

- 新 memo 草稿会写 localStorage；现有 memo 的编辑模式默认清理并禁用该草稿缓存。
- 普通附件在保存时上传，但内联图片插入会立即上传服务器；要满足“显式操作才上传”，必须修改前端文件生命周期。
- 上游 Memo/附件更新已有事务内的预期正文比较，适合扩展条件写入。
- 上游删除事务验证身份和所有权，但请求没有预期版本和编辑会话条件；自动清理必须扩展服务端事务，外层延时后调用普通删除不合格。

## 当前未接通部分

原生登录身份到 Echo 队列的受保护提交接口、显式投递按钮、编辑会话 fencing、条件清理、隔离实例浏览器验收、生产配置和真实投递均未完成。真实投递和清理保持关闭。
