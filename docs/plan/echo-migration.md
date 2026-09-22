# Echo 全量标识迁移计划

## 目标

将即时记录系统的统一名称改为 `Echo`：GitHub 仓库使用 `LeeDaud/Echo`，正式域名使用 `echo.leedaud.xyz`，应用、包、容器、Compose 项目、服务器目录、运维脚本、截图和文档不再以 Jike 作为当前标识。

## 范围与约束

- 保留 Ignis/Obsidian 原版前端和现有手动上传行为。
- 保留当前服务器可靠队列、GitHub 已备份内容和服务器 token，不重建或丢弃数据。
- GitHub 自动投递在切换期间暂停，完成仓库、域名和队列迁移后再恢复。
- 新域名启用前核验 `echo.leedaud.xyz` 的 DNS 已指向现有 VPS；Caddy 先验证配置再平滑 reload。
- 旧域名先停止写入并转向新域名。确认新入口稳定后再决定是否永久保留跳转；不在本次未经单独确认的范围内删除旧服务器目录、容器、证书或 DNS 记录。
- 合成验收笔记 `20260921154739.md` 已确认由应用投递完成；迁移后再次从 `LeeDaud/Echo` 核验内容。删除这条 GitHub 笔记属于外部删除操作，另行确认。

## 统一命名

| 对象 | 旧值 | 新值 |
| --- | --- | --- |
| GitHub 仓库 | `LeeDaud/Jike` | `LeeDaud/Echo` |
| 正式域名 | `jike.leedaud.xyz` | `echo.leedaud.xyz` |
| 应用包名 | `thought-inbox` | `echo-capture` |
| Compose 服务/项目 | `jike` | `echo` |
| 容器/镜像 | `jike-capture` | `echo-capture` |
| 服务器根目录 | `/opt/jike` | `/opt/echo` |
| 电脑输入仓库 | `D:/AAA-Jike` | `D:/AAA-Echo`（用户已完成） |
| 页面与文档名称 | 即时想法入口/Jike | `Echo` |

当前网页源码工作区 `D:/AAA-Project/037-Obsidian-Web` 暂不移动：Codex 工作区和规则绑定该路径，目录改名会中断当前任务。代码内部项目名统一为 Echo；若需要把源码目录也改为 `037-Echo`，在本轮完成后单独迁移工作区。

## 实施顺序

1. 核验 GitHub 新仓库地址、默认分支和现有合成笔记；核验 token 对 `LeeDaud/Echo` 的 Contents 读写权限。电脑仓库与 DNS 已由用户改名，本轮只做验证。
2. 暂停生产 GitHub worker，防止目录和仓库切换时继续消费队列。
3. 修改包名、运行配置、部署脚本、生产验收脚本和用户文档中的当前标识；历史记录保留必要的“原名”说明，不伪造过去状态。
4. 在服务器创建 `/opt/echo`，复制队列、官方 Obsidian 资源、锁目录和受限 token；核对文件数量、权限和队列记录后启动 `echo-capture`。旧 `/opt/jike` 暂时保留作为只读回退来源。
5. 更新 Caddy：`echo.leedaud.xyz` 代理到 `echo-capture:4319`；旧 `jike.leedaud.xyz` 只做 308 跳转到新域名，不再接受写入。
6. 启用 `GITHUB_REPOSITORY=LeeDaud/Echo` 和 GitHub worker，验证匿名页面、同源保护、手动上传、WebSocket、容器健康及 Vaultwarden 无回归。
7. 核对用户已改名的 `D:/AAA-Echo`、新 origin，以及 Obsidian 打开的库路径和 Obsidian Git 自动拉取设置；不再次移动目录。
8. 用一条新的 Echo 合成笔记执行网页 → 服务器 → GitHub → 电脑端验证；等待活动租约失效后核验服务器正文回收。
9. 更新事实文档和清单。旧 Jike 服务器目录、旧容器资源及合成测试笔记的删除另行确认。

## 验收标准

- `echo.leedaud.xyz` 使用有效 HTTPS，加载原版 Obsidian 编辑器，页面显示 Echo，手动上传按钮工作。
- 所有运行时配置指向 `LeeDaud/Echo`；新合成笔记的 GitHub 内容和服务器版本一致。
- `jike.leedaud.xyz` 不再承接写请求，只跳转到 Echo。
- 服务器队列记录数量和版本在迁移前后可核对，没有未备份正文丢失。
- `D:/AAA-Echo` 跟踪新 origin，Obsidian Git 能拉取新验收笔记。
- `npm run check`、10 项真实浏览器测试、生产 Chromium 检查通过；Vaultwarden 保持健康。

## 回退

新容器或新域名验证失败时，停止 Echo worker，恢复 Caddy 到旧容器，并保留两边数据用于比对。不得用空目录覆盖旧队列，也不得在回退完成前删除 `/opt/jike`。
