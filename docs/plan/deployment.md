# echo.leedaud.xyz 部署计划

2026-09-21：用户明确要求直接登录其 VPS 部署，授权范围包含本应用的容器、HTTPS 站点和必要部署配置。沿用已确认功能方案，不重复申请同一部署许可；GitHub 自动投递仍需独立凭据和明确启用。

## 已核验环境
Ubuntu 22.04，约 2GB RAM，50GB 可用磁盘；已有 Docker Compose、Caddy 与 Vaultwarden。现有 Caddy 占用 80/443，密码库站点配置为 /opt/vaultwarden/Caddyfile。

## 目录与边界
- deploy/：本应用 Dockerfile、compose、入口脚本及无凭据部署工具；只存部署代码。打包产物放系统临时目录，不提交源码。
- /opt/echo/releases/<版本>/：应用发布文件及已验证原版前端构建产物，排除 node_modules、Git 元数据、笔记和凭据。
- /opt/echo/data/：专用临时队列和 Inbox，确认备份且会话失活后应用回收正文。
- /opt/echo/obsidian/：官方运行资源，单独只读挂载。
- /opt/echo/secrets/：权限受限的可选 GitHub token，不写入镜像、Git 或日志；网页不再使用登录密码。
- /opt/echo/locks/：容器进程级排他锁，避免崩溃留下的应用锁阻止安全重启。
- 凭据回执保存到电脑项目外用户本地应用数据目录，聊天只给路径。

## 执行与验收
1. 先核对公网 DNS、现有密码库健康与网络。
2. 打包当前已验证源码及原版构建产物；Docker 仅安装运行依赖，不启动或部署上游 Astro 文档站。
3. 独立容器、独立数据、无宿主机公开应用端口。复用现有 Caddy 网络转发，保持 Vaultwarden 服务不重建。
4. Caddy 配置先备份、验证，再 reload；新增 Echo 域名块。失败时恢复配置并 reload，不删除已有服务。
5. 网页不设置登录鉴权；HTTP 写入继续校验 Host 与 Origin，WebSocket 校验 Host 与 Origin。GitHub 未获得 token 时维持关闭，不冒充同步已连通。
6. 验证 HTTPS、匿名拒绝、授权访问、原版资源、真实浏览器、容器重启和原密码库仍正常。
7. 更新部署记录；正式手机与电脑端到端同步须在用户提供专用 token 并授权启用后验证。

