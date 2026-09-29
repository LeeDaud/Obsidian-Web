# Echo

Echo 是一套面向手机快速记录的 Obsidian 输入链路。项目保留两种入口：

- **Echo / Ignis**：保留原生 Obsidian 编辑器、主题、工具栏和移动端导航，用于接近桌面 Obsidian 的网页编辑体验。
- **Memos**：使用原生 Memos 手机网页作为轻量收件箱，保存后自动把 Markdown 和图片投递到 Obsidian 输入仓库。

两条入口共用 Echo 的 GitHub 投递链路，但应用源码、服务器临时数据、GitHub 输入仓库和电脑知识库彼此分离。

## 当前状态

| 入口 | 地址 | 状态 |
| --- | --- | --- |
| Echo | <https://echo.leedaud.xyz> | 已上线，HTTPS 与 GitHub 投递已启用 |
| Memos | <https://memos.leedaud.xyz> | 已上线，私有实例，关闭公开注册 |

生产环境由独立的 `echo-capture` 与 `echo-memos` 容器运行，通过现有 Caddy 提供 HTTPS。Memos、Echo、Caddy 和 Vaultwarden 已通过健康检查。

固定上游：

- Ignis 0.8.11，提交 `6ce5bcb184d428b7809240b36cd4fe1b5159d2cd`
- Obsidian 运行资源 1.12.7
- Memos v0.31.0，提交 `2b2192d4e153bd04f1d325b60fd880cf00d68b01`

上游源码、许可证和本项目补丁分别位于 `upstream/ignis` 与 `upstream/memos`。Obsidian 本体从官方单独获取，不随本项目分发。

## Memos 手机记录

Memos 是目前推荐的手机快速输入入口。

1. 在手机浏览器打开 <https://memos.leedaud.xyz> 并登录。
2. 输入文字或添加图片。
3. 点击“暂存”只保存设备草稿，不上传服务器。
4. 点击“保存”创建或更新 Memo，并自动投递当前版本。
5. 投递成功后，Markdown 写入 GitHub 输入仓库的 `00_Inbox/`，电脑端通过 Obsidian Git 拉取。

新笔记使用 `yyyyMMdd-HHmmss.md` 命名，例如：

```text
00_Inbox/20260928-231707.md
```

图片使用稳定摘要路径，并在 Markdown 中写入相对引用：

```text
attachments/memos/<memo-id>/<content-hash>.<ext>
```

正文和附件在同一个 Git commit 中写入并逐项核验。保存失败不会伪装为投递成功；幂等重试不会创建重复笔记。历史 `Memos/` 路径不自动迁移。

编辑器保留设备草稿、双击正文编辑、独立暂存与保存操作。左侧活动日历用四级黑白灰表示每天的记录数量；页面跨过本地午夜或从后台恢复时会重新计算“今天”。默认浅色和深色主题使用简约黑白强调色，成功、警告和危险状态仍保留语义色。

## Echo / Ignis 记录

- 每个新会话打开一篇上海时间戳命名的 Markdown；刷新或后台返回继续当前笔记，同一秒创建多篇时按秒顺延避免覆盖。
- 空白笔记只保留身份元数据和原生虚拟文件，首次非空输入才生成服务器 Markdown。
- 编辑即时写入设备 `localStorage`，输入、停顿、联网、切换页面和退出均不会自动上传。
- 只有“上传当前笔记”按钮会把当前版本写入服务器队列；原生云图标只显示状态。
- “已上传服务器”和“已备份到 GitHub”分别显示，不把 GitHub 成功描述为电脑已经接收。
- 手机端底部导航保持原位，右侧格式工具栏默认折叠，交互控件保持至少 44px 触控区域。

## 数据流与存储边界

```text
手机设备草稿
    ↓ 显式保存或上传
服务器临时队列
    ↓ GitHub commit + 内容核验
私有 GitHub 输入仓库
    ↓ Obsidian Git
电脑 D:\AAA-Echo
```

- GitHub 输入仓库：<https://github.com/LeeDaud/Echo>，私有。
- 电脑输入目录：`D:\AAA-Echo`，独立于本项目源码。
- 服务器不 clone 输入仓库，也不保存 Git 历史。
- 凭据只存于服务器 secret 文件，不写入源码、镜像、浏览器或日志。
- Echo 已核验版本会按版本和活动状态清理临时正文；失败、新版本和冲突内容继续保留。
- Memos 的条件清理仍需完成活动会话与版本保护；未经专项授权不会删除真实 Memo、附件或历史合成数据。
- 已进入 GitHub 的内容不会因服务器清理而删除，最终整理在电脑端完成。

## 本地启动 Echo

需要 Node.js 22.14+。运行目录必须位于项目外，`CAPTURE_DATA_DIR` 不能指向 `D:\AAA-Echo`。

```powershell
npm ci --legacy-peer-deps
Push-Location upstream/ignis
npm ci
Pop-Location
npm run build

$env:CAPTURE_DATA_DIR = 'D:\IgnisRuntime\capture'
$env:OBSIDIAN_ASSETS_PATH = 'D:\IgnisRuntime\obsidian'
$env:GITHUB_REPOSITORY = 'LeeDaud/Echo'
$env:GITHUB_SYNC_ENABLED = 'false'
npm start
```

打开 <http://127.0.0.1:4319>。Memos 固定源码的本地构建需要 Node.js 24、pnpm 11 和 Go 1.27，详细步骤见 [Memos 接入记录](docs/setup/memos.md)。

## 检查命令

```powershell
npm run check
npm run test:upstream

Push-Location upstream/ignis
npm run lint
Pop-Location

Push-Location upstream/memos/web
pnpm lint
pnpm test
pnpm build
Pop-Location

# 设置真实 OBSIDIAN_ASSETS_PATH；必要时设置 CAPTURE_BROWSER_PATH
npm run test:browser
```

浏览器测试使用项目外的官方 Obsidian 资源；GitHub 安全行为测试默认使用模拟远端。当前未配置 CI/CD。

## 文档

- [运行指南](docs/setup/running.md)
- [电脑与 GitHub 仓库接入](docs/setup/repositories.md)
- [Memos 接入与验证](docs/setup/memos.md)
- [生产部署记录](docs/setup/production.md)
- [验证结果和限制](docs/setup/verification.md)
