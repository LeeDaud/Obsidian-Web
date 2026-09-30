# 正式部署记录：echo.leedaud.xyz

日期：2026-09-21。用户明确授权直接登录 VPS 部署。本记录不包含凭据。

## 访问

- 地址：https://echo.leedaud.xyz
- 用户于 2026-09-21 明确取消网页登录鉴权；访问域名直接进入原版编辑器。
- 电脑最终笔记仓库：D:/AAA-Echo；GitHub：私有 LeeDaud/Echo。
- GitHub 自动投递已启用，目标为私有仓库 `LeeDaud/Echo`；状态仍区分设备草稿、服务器上传和 GitHub 备份。

## 部署结构

- VPS：113.20.6.55，Ubuntu 22.04，使用已有 Docker 和 Caddy。
- 发布目录：/opt/echo/releases/20260921。
- 镜像：echo-capture:20260921；主题自适应通知上线后的 image ID 为 sha256:ae3251dba37c4b933d1f91da2a043ecbafb1d683305dd7d29fcdbeba8eef47f9。
- 基础镜像固定 node:22-bookworm-slim@sha256:48e4b67d85f87bd551df43704e24d252f56cc5f8e9718841aace50f19948f0f9。
- 容器：echo-capture，非 root、只读根文件系统，512MB 内存限制、1 CPU，日志轮转；没有发布宿主机应用端口。
- /opt/echo/data：应用专用临时队列、Inbox 和运行设置；不 clone 笔记 Git 仓库。
- /opt/echo/obsidian：官方 1.12.7 解包资源，只读挂载，未打入应用镜像。
- /opt/echo/secrets：受限密码及可选 token 文件；不进入 Git、镜像或日志。
- /opt/echo/locks：排他 flock，崩溃后安全清除应用遗留锁。另一个实例不能同时打开队列。
- 复用 vaultwarden_vaultwarden-net，由 Caddy 访问 echo-capture:4319。

Caddy 在 `/opt/vaultwarden/Caddyfile` 配置 Echo 域名块，候选配置验证后平滑 reload；旧域名永久跳转到 Echo。迁移前配置备份位于 `/opt/echo/Caddyfile.before-echo`，Vaultwarden 和 Caddy 容器没有重建或重启。

## 实际验收

- 公网 DNS 指向本 VPS。
- Let's Encrypt 正式证书已签发；最初证书下载返回临时 404，Caddy 自动重试后成功，没有绕过 TLS 校验。
- HTTPS 匿名访问返回 200，原生 Obsidian 1.12.7 Markdown 编辑器可用；Host、同源写入和 WebSocket Origin 校验继续启用。
- 390×844 真实 Chromium 浏览器：原生编辑器可见、空白停留不关闭、无横向溢出、pageErrors=0。
- WebSocket 认证成功，验证时收到 10 条消息。
- 新建笔记使用上海时间戳 `yyyyMMdd-HHmmss.md`；早期生产核验样本仍为无短横线旧格式，UUID 仅保留在内部会话元数据中。
- 取消登录后原版编辑器、WebSocket 和移动端布局重新核验通过。
- 无凭据生产浏览器验证：文件名 `20260921142257.md`、WebSocket 14 帧、页面错误 0、无横向溢出；GitHub 投递仍为关闭。
- 串行保存、中文组合输入和 Live Preview 加粗已通过真实 Obsidian 浏览器测试。导航栏保持底部，聚焦编辑器后格式工具栏移到右侧并可纵向滚动，生产布局核验通过。
- Docker 健康状态 healthy；修正了 Node fetch 不保留自定义 Host 的健康探针问题，探针使用 http.get。
- 第二实例申请相同 flock 返回 1，未获得数据访问锁。
- 模拟容器内应用进程 SIGKILL 后自动重启，restart_count=1，恢复 healthy。
- Docker 操作者手动 kill/stop 会按 unless-stopped 语义保持停机；运维需显式 start，这不等同于应用自身崩溃。
- 原密码库在部署前后均返回 HTTP 200，容器持续 healthy。
- 实际容器没有安装上游 Astro 文档站。未声称整个上游依赖审计零漏洞。

线上检查未输入笔记正文，仅产生测试空白会话元数据。手机硬件实际访问、真实笔记投递和电脑拉取仍需最终联调。

## 运维命令

```bash
# 状态、日志
docker compose -p echo -f /opt/echo/releases/20260921/deploy/compose.yaml ps
docker logs --tail 100 echo-capture

# 重启本应用，不影响密码库
docker compose -p echo -f /opt/echo/releases/20260921/deploy/compose.yaml restart echo

# 手动停止后恢复
docker start echo-capture
```

不要打印 secrets 文件、docker 容器进程环境或把凭据放入命令行参数。不要删除 /opt/echo/data 中尚未备份的正文。

## 继续接通自动投递

1. 用户创建仅限 LeeDaud/Echo、Contents read/write 的 fine-grained token，保存到本机项目外文件；只提供路径，不在聊天发送 token。
2. 用户明确允许开启真实自动投递。
3. 安全上传 token 到 /opt/echo/secrets/github_token，保持文件权限；修改本应用 Compose 的 GITHUB_SYNC_ENABLED 为 true，重新创建本应用容器。
4. 用一条明确标记的测试想法核验手机 → GitHub → D:/AAA-Echo；检查 GitHub 确认且活动会话失活后服务器正文回收。

用户已确认 Obsidian Git 的打开输入库、安装插件、Pull 和自动拉取设置完成，但本轮未远程控制电脑 Obsidian，也未替用户宣称完成真实笔记端到端验证。

部署使用 deploy/Dockerfile、compose.yaml、entrypoint.sh、vps.py；线上浏览器检查使用 deploy/verify.mjs。未添加 CI/CD，也未提交或推送网页源码。

## 2026-09-28 Memos 域名与接口

- 新发布目录为 `/opt/echo/releases/20260928`；镜像为 `echo-capture:20260928` 与 `echo-memos:20260928`，容器均为 healthy。
- `https://memos.leedaud.xyz` 由现有 Caddy 转发至同一 Docker 网络内的 `echo-memos:5230`，不发布宿主机应用端口；HTTP 返回 308 并跳转 HTTPS，HTTPS 首页返回 200。
- Memos 数据位于 `/opt/memos/data`；Echo 接收队列位于 `/opt/memos/echo-queue`。二者均不保存 Git 仓库。
- Bridge token 生成后拆成两份 `0400` 文件，分别归属 Echo UID 1000 与 Memos UID 10001；凭据未写入源码、镜像、日志或聊天。
- 首个管理员已在公网开放前创建，公开注册关闭，实例访问模式为 private。随机初始凭据仅保存在服务器 root 可读的 `/opt/memos/secrets/initial_admin`。
- `/api/echo/v1/status` 返回 `{"enabled":true}`；同源但未登录的提交请求返回 401。Memos 后端持服务凭据转发至 Echo 内部接口，浏览器不接触 bridge token。
- 上线后 `echo.leedaud.xyz` 与 `bitwarden.leedaud.xyz` 均返回 200；Caddy 与 Vaultwarden 未重建。
- Memos 队列到 GitHub 的生产 worker 已于同日后续启用，与 Echo worker 在同一串行 tick 中使用同一 GitHub remote，避免并发更新分支。
- 合成 Memo `memos/An746PkDDqZEevQGrFm3um` 经正式鉴权入口投递为 `Memos/20260928-173655.md`；队列状态 `verified`，GitHub commit `9779cede3e3eb49af1f697f630b9a852607e296e`，按该 commit 读取的正文和唯一标识均匹配。
- 本轮未删除合成 Memo、GitHub 文件或服务器已核验队列记录。附件、前端持久状态与活动会话保护后的条件清理仍待实现。
- 后续版本已启用保存后自动投递、设备暂存、双击编辑与图片附件快照。新正文写入 `00_Inbox/yyyyMMdd-HHmmss.md`；历史 `Memos/` 文件和队列记录保持原位。
- 图片合成验收 Memo `memos/3sYx8n8tpXbYhPUzypKWNH` 已自动投递为 `00_Inbox/20260928-231707.md`，commit `e5e9c240e4cbec90a6cf01b19fc6ce65380110e7`；Markdown 引用与附件二进制均按该 commit 核验一致。

## 2026-09-30 续写与 Obsidian 反链部署

- 源码提交 `3710fb7` 已推送至 `LeeDaud/Obsidian-Web` 的 `main`。
- 继续使用压缩包上传至 `/opt/echo/releases/20260928` 后由服务器 Docker 多阶段构建，不在服务器 clone 或 pull Git 仓库。
- Memos 前端使用 Node 24.14 构建成功，后端使用 Go 1.27 编译成功；沿用既有 CSS `::highlight` 与 chunk size 警告。
- 运行镜像为 Echo `sha256:840d183bf44bd9726df15dedc58cad63fca7e85ac3978bc73ad918ad9c297fc5`、Memos `sha256:844faefca19b4fdd0240a606ef6d3ba1d597fba262263338daf3b67f073a1d43`，与新构建镜像一致。
- `echo-capture` 与 `echo-memos` 重建后均为 healthy；Caddy 与 Vaultwarden 未重建，Vaultwarden 继续 healthy。
- `https://echo.leedaud.xyz`、`https://memos.leedaud.xyz` 与 `https://bitwarden.leedaud.xyz` 均返回 200。旧 Jike 域名在本次检查中连接码为 `000`，不影响三个正式入口。
- 本轮未创建或删除真实笔记；续写的 GitHub WikiLink 与 Obsidian 反链仍待用户实际操作验收。

## 2026-09-21 手动上传版本

- 已部署镜像 `sha256:7797f53f1bbdb5c31830dc8f78593d8afa44c76fa14dd937d2ca0925dbdb85a9`；`echo-capture`、Caddy 与 Vaultwarden 均健康，两个公网入口返回 200。
- 生产 Chromium 验证文件名 `20260921152609.md`，独立上传按钮存在；右侧工具栏为 52×510px，全部触控目标不小于 44px；底部导航为 316×52px 且保持底部。
- 页面错误 0、无横向溢出、WebSocket 收到 16 帧。GitHub 投递继续为关闭状态。
- 编辑仅更新浏览器设备草稿，输入、联网、隐藏和退出不自动上传；用户点击独立上传按钮后才写入服务器可靠队列。

## Echo 标识迁移

- GitHub 仓库为私有 `LeeDaud/Echo`，电脑仓库为 `D:/AAA-Echo`，包名为 `echo-capture`。
- 正式入口为 https://echo.leedaud.xyz；旧域名永久跳转到 Echo，不再承接应用写入。
- 容器和镜像为 `echo-capture`，Compose 项目为 `echo`，服务器根目录为 `/opt/echo`。
- 迁移前后服务器队列均为 39 条记录，绑定从旧仓库名经精确校验后原子更新为 `{"repo":"LeeDaud/Echo","branch":"main"}`。旧服务器目录和旧容器未删除。
- `echo-capture`、Caddy 与 Vaultwarden 健康；Echo 匿名入口返回 200，旧域名返回永久跳转。
- `20260921224346.md` 已经由 Echo 正式 HTTPS API 写入，GitHub 文件 SHA 为 `033dd922ae957406cbd8172d8c308879a2aab7a8`，内容标识匹配；`D:/AAA-Echo` 已 fast-forward 拉取并再次核对标识。
- 活动保护期后，该合成记录为 `retired=true`、`contentPresent=false`、`deliveryPresent=false`；服务器正文和投递载荷已回收，GitHub 与电脑副本保留。
- 本机生产 Chromium 两次在导航阶段因跨境线路超时，未完成可视化断言；服务器健康检查、HTTP 响应、GitHub 内容和电脑落盘均通过。

