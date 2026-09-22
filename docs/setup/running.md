# 运行与部署

## 目录

当前源码目录只存网页程序。服务器使用项目外的专用目录：

- queue/：原子写入的投递记录、版本元数据和单实例锁。
- vaults/Inbox/：原版编辑器使用的临时 Markdown 镜像及 .obsidian 配置。
- runtime/：Ignis 自身运行配置。
- Obsidian 解包资源另设目录，不放在 queue 或源代码内。

不要把 CAPTURE_DATA_DIR 设为 D:\AAA-Echo；后者是电脑最终接收仓库。只允许单实例，不允许两个进程共享暂存目录。

## 获取官方 Obsidian 运行资源

上游 entrypoint 固定使用 1.12.7。下面以 Windows 外部目录为例，先准备 D:\IgnisRuntime；不要在已有个人知识库中执行。

```powershell
$runtime = 'D:\IgnisRuntime'
New-Item -ItemType Directory -Force -Path $runtime
curl.exe -L --retry 3 -o "$runtime/obsidian.asar.gz" https://github.com/obsidianmd/obsidian-releases/releases/download/v1.12.7/obsidian-1.12.7.asar.gz
$source = [IO.File]::OpenRead("$runtime/obsidian.asar.gz")
$gzip = [IO.Compression.GZipStream]::new($source, [IO.Compression.CompressionMode]::Decompress)
$destination = [IO.File]::Create("$runtime/obsidian.asar")
$gzip.CopyTo($destination)
$destination.Dispose()
$gzip.Dispose()
$source.Dispose()
npm exec --yes --package=@electron/asar -- asar extract "$runtime/obsidian.asar" "$runtime/obsidian"
```

本次验证的运行资源位于 C:\Users\10159\AppData\Local\Temp\ignis-capture-runtime\obsidian，仅用于本机预览。临时目录不应当作正式服务器的可靠磁盘；正式部署请使用独立、可写且不会被系统自动清理的目录，应用在备份确认后自行回收正文。

Obsidian 本体不是 Ignis 的开源代码；不把解包文件提交到网页源码仓库，使用时遵循其许可条款。

## 配置

| 变量 | 用途 | 默认 |
|---|---|---|
| CAPTURE_DATA_DIR | 项目外应用专用临时中转目录 | 必填，缺失拒绝启动 |
| OBSIDIAN_ASSETS_PATH | 官方 Obsidian 解包资源的绝对路径 | 必填 |
| GITHUB_REPOSITORY | 目标输入仓库 | 配置为 LeeDaud/Echo |
| GITHUB_BRANCH | 已存在的分支 | main |
| GITHUB_TOKEN | 仅服务器可用的仓库 Contents 读写权限 | 无 |
| GITHUB_SYNC_ENABLED | true 时允许真实自动投递 | false |
| CAPTURE_ORIGIN | 浏览器访问的完整 origin，不含路径 | http://127.0.0.1:4319 |
| CAPTURE_HOST | 监听主机 | 127.0.0.1 |
| PORT | 服务端口 | 4319 |

程序不读取 .env。GitHub 凭据应通过部署平台 Secret 注入，不能提交到 Git、放进客户端，也不复用本机 GitHub CLI 的广泛权限 token 作为长期服务器凭据。建议单仓库 fine-grained token，仅授予 Contents 读写。

设置 GITHUB_SYNC_ENABLED=true 会产生真实提交。仓库与分支已经初始化，但仍需用户配置服务器端凭据并授权启用。更换仓库必须换新暂存目录，不能让积压笔记投递到另一仓库。

首次下载资源、安装依赖后，按 README 的启动命令运行。构建输出在 upstream/ignis/packages/*/dist；根 dist 是旧原型遗留构建，不再作为服务入口，也不应部署。

## 手机远程访问

当前 http://127.0.0.1:4319 仅供电脑本机查看。手机正式访问需要 HTTPS 域名和服务器：

- 应用仍监听 127.0.0.1；Caddy/nginx 在同机提供 HTTPS 和反向代理。
- 配置 CAPTURE_ORIGIN 为域名 origin；公网入口不设登录鉴权，仍校验 Host、写请求 Origin 和 WebSocket Origin。
- 保留 Host、Origin、Authorization，转发 /ws 的 WebSocket Upgrade。
- API、正文和 HTML 不做共享缓存；前端官方静态资源可按原版策略缓存。
- HTTP 和 WebSocket 均经过访问校验，HTTPS/localhost 提供 UUID 与 Web Locks 所需安全上下文。

尚未配置真实服务器、域名、TLS、系统服务或 CI/CD。不要直接把未经验证的原版独立启动脚本暴露到公网，项目统一入口是根目录 npm start。

## 保存与清理

编辑时同步保存设备草稿，400ms 停顿后发送版本化内容；中文组合输入结束后才更新发送内容。pagehide/visibilitychange 使用 keepalive 尽力补发，小于 60KB 才使用 keepalive；大草稿仍由设备草稿兜底。

队列先原子写入并 flush，再写原生编辑器镜像。GitHub 投递过程持久化尝试提交标识，非强制更新 ref，再按不可变提交核验内容。响应丢失时根据祖先关系确认，不能复活已被电脑移动的文件。

浏览器每 10 秒发送活动心跳；GitHub 已确认、当前版本未变且失活至少 120 秒才清理 .md。重启也先等待宽限期。先记录 retired 再删除；旧页面恢复后新建笔记或保留未发送内容为新想法。失败、冲突和未投递内容不做超时删除。

未发送的旧设备草稿由后台重试；冲突草稿可在原生命令面板“恢复未发送草稿”查看，并“另存为新想法”。冲突记录不会擅自清理，避免删除唯一副本。元数据保留用于去重，不保留 Git 对象历史。不承诺底层磁盘取证级擦除。

本次支持文字 Markdown；服务器拒绝额外库、附件、笔记重命名/移动等写入。整理在电脑完成。

## 故障处理

- 启动拒绝：检查外部路径、目录权限、资源完整性，以及是否误用了个人笔记库。
- instance.lock 遗留：先确认其中 PID 已退出，再按运维授权删除该单实例锁；不要绕过运行中的实例。
- 只保留设备草稿：网络或服务器暂存失败；保留页面或联网后重开，不清理浏览器站点数据。
- 已暂存未备份：检查投递开关、token、目标分支和权限。
- 远端冲突：另存新想法；不 force push、不恢复被电脑移动的旧文件。
- 完全离线重新加载：本次保留原版资源加载方式，没有继续沿用旧 React 页面的 Service Worker。联网后重开，localStorage 草稿仍可恢复。
- 原生文件扫描：当前空白页以虚拟元数据参与扫描，不落盘，也不被扫描移除。

GitHub 官方 API：https://docs.github.com/en/rest/git

## 当前正式环境

2026-09-21 已经按用户授权部署 https://echo.leedaud.xyz。上文部署前的准备说明仍可用于其他环境；本机预览地址不代表正式访问入口。正式服务器路径、凭据文件位置、健康验证及运维命令以 [production.md](production.md) 为准。GitHub 自动投递已启用。

