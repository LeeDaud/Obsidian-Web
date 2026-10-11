# Echo

Echo 是一套面向手机快速记录的 Obsidian 输入链路：在手机记录文字与附件，将明确保存并授权投递的版本写入独立 GitHub 仓库，再由电脑上的 Obsidian Git 拉取。日常记录不必把完整知识库搬到手机上。

- **Android App 1.0**：可离线使用，默认运行打包在 APK 内的原 Memos React/CSS/CodeMirror/Markdown 组件，手机直接投递 GitHub，不经过 Echo 服务器。
- **Memos 网页**：登录后使用服务器上的轻量收件箱，通过显式投递接入 Echo 的服务器队列。
- **Echo / Ignis 网页**：保留原 Obsidian 编辑器、主题、工具栏与移动导航，通过独立上传按钮投递。

应用源码、手机本机数据、服务器临时数据、GitHub 输入仓库和电脑知识库彼此分离。Android 的本机记录不是 Memos 服务器记录；两者不会自动双向同步。

开发过程中，Android 从远端网页容器改为本地独立 App，随后从原生重绘界面改为复用原 Web 组件，尽量保留原网页的外观与操作。当前正式版本为 **1.0 / versionCode 8**，不是旧的 `0.4.0-preview` 调试包；“正式版本”不表示所有 Web 功能或全部真机验收已完成。

## Android 安装与升级

1. 从 [1.0 正式发布页](https://github.com/LeeDaud/Obsidian-Web/releases/tag/v1.0) 下载 [echo-1.0.apk](https://github.com/LeeDaud/Obsidian-Web/releases/download/v1.0/echo-1.0.apk)。
2. 在手机打开 APK，按系统提示允许当前文件应用安装此包。
3. 打开 **Echo**，无需登录即可开始本机记录；查看原 Memos 历史与 GitHub 投递分别配置。

最低 Android 8.0（API 26），包名 `xyz.leedaud.echo`。原 Web 界面要求 Chromium 111+ 的 Android System WebView；当前设备验证使用 API 35 / WebView 124。WebView 不可用或版本过低时可进入保留的原生界面，建议先更新系统 WebView。

同包名、同正式签名的版本可覆盖安装。若出现签名冲突，通常是旧调试预发布与正式版的签名不同：**不要通过卸载或清除数据解决**，先导出并核对正文与附件。旧浏览器草稿、早期容器数据不自动迁入；完整旧版数据迁移仍未闭环。App 禁止系统自动备份私有数据，卸载或清除数据会丢失未导出的本机笔记、草稿、缓存和授权。

## Android 快速开始

### 记录、暂存与保存

1. 在首页编辑器输入正文，选择笔记或待办，使用格式工具输入 Markdown。
2. 点击 **暂存**，确认成功后保留设备草稿；暂存不创建正式笔记，也不授权网络投递。
3. 点击 **保存**，生成本机正式 Markdown。未配置或暂停 GitHub 投递时，只保存本机；已配置并开启投递时，保存同时授权当前版本入队。
4. 在列表查看状态，通过时间戳进入详情，通过三点菜单操作当前笔记。

输入会尝试异步保留草稿，但立即强杀可能早于写入完成。重要内容以显式暂存成功或保存成功为准。断网可以继续记录；后台只重试已经授权的版本，不把正在编辑的草稿自动上传。

| 状态 | 含义 |
| --- | --- |
| 已保存 | 本机正式版本已保存，不等于已进入 GitHub |
| 投递中 | 当前快照已授权，可能正在排队、等待联网、等待父笔记或核验 |
| 已投递 | GitHub 提交及正文/附件核验成功，不等于电脑已拉取 |

失败、权限问题或冲突应查看任务详情，不要仅凭主状态判断成功。

### 附件与图片

- 从编辑器的附件/图片入口调用系统选择器，导入后复制到 App 私有目录，可离线查看。
- 新导入附件采用上海时区时间戳命名 `yyyyMMdd-HHmmss[-NN].ext`，而不是沿用手机原文件名；历史文件不批量重命名。
- 附件右侧使用三点菜单，展开插入正文、分享等适用操作。图片可在原画廊查看，正文与附件引用会进行去重处理。
- 录音仅在调用时申请麦克风权限，停止后才加入草稿；未停止就离开或强杀不保证恢复。
- 定位按需申请权限，目前读取最近可用位置，不保证实时定位，也没有在线地图底图或地址解析。

正文上限 256 KiB；单附件 10 MiB；全部附件总计 25 MiB、最多 100 个。取消选择或权限拒绝不应当作导入成功。

### 编辑、续写与整理

双击不是固定的“编辑”或“续写”，而是取决于来源与投递状态：

| 笔记类型 | 双击正文 |
| --- | --- |
| 未入队且可编辑的本机笔记 | 编辑当前记录 |
| 已入队或已核验的本机笔记 | 创建关联续写，保护原快照 |
| 原 Memos 的远端记录 | 创建附来源链接的本机续写，不修改服务器原文 |

父笔记投递尚未核验时，关联子笔记等待父任务；核验后投递内容追加 `续写自：[[父文件名]]`。搜索、标签、任务、日历及置顶/归档入口用于整理记录；本机归档不等于删除 GitHub 文件或远端 Memos。

可使用分享、格式化图片和 Markdown/附件 ZIP 导出。长内容优先 ZIP，不依赖图片完整表达。导出需要在系统选择器完成保存，并核对实际文件；导入不会自动投递，完整分组及媒体元数据往返仍有限制。

## 配置 GitHub 与电脑接收

### 手机绑定投递仓库

准备一个独立的 GitHub.com 输入仓库和已经初始化的分支，建议使用私有仓库，不要把笔记投递到本项目源码仓库。

1. 在 GitHub 创建仅授权该仓库的 fine-grained PAT，授予仓库 **Contents 读写**权限，并设置合理有效期。授权入口与最新说明见 [GitHub 官方 PAT 文档](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)。
2. 在 App 设置填写仓库所有者、仓库名、已有分支与 token。不要把仓库完整 URL 当作仓库名。
3. 点击“检查连接并保存配置”。此步骤只核对仓库与分支，**不证明实际写入已成功**。
4. 显式开启投递，保存一条不含私人内容的测试笔记，等待核验，再到 GitHub 检查正文与附件。
5. 旧本机记录需要从详情显式“投递当前版本”；绑定配置或恢复网络不会扫描并上传全部历史草稿。

token 只在 App 内填写，不发送到聊天、不写入源码，不复用服务器凭据。手机凭据采用 Android Keystore 与 AES-GCM 加密保存，笔记文件本身不宣称全库或端到端加密。

新内容的仓库布局：

```text
00_Inbox/20261011-193000.md
attachments/20261011-193000/20261011-193001.png
```

正文与全部附件组成一个 Git commit，并核验内容后显示已投递。队列绑定仓库身份与分支：切换目标不会把原任务悄悄发到新仓库。遇到冲突保留内容，不强推或自动覆盖远端历史。暂停投递不能保证撤回已经发出的请求。

**目前仅支持 GitHub.com。** GitLab 可以提供基于 PAT 的仓库 API，但 App 尚未实现 GitLab/Gitee 适配，不能把其地址或 token 填进 GitHub 配置。GitHub 直投需要手机能够访问 `api.github.com`；更换 Git 仓库服务不是当前版本的网络故障解决开关。

### 电脑上的 Obsidian

1. 将同一个输入仓库 clone 到项目外的独立目录，并在 Obsidian 中打开。
2. 配置 Obsidian Git 拉取，或先手动执行 Git pull。
3. 手机显示已投递后，在电脑检查 `00_Inbox/` 和相对附件引用。
4. 在电脑整理进入自己的知识库；App 没有电脑逐条接收回执，也不会读取电脑整理后的完整知识库。

仓库保留 Git 历史，整理或删除文件不等于清除历史。电脑移走的文件遇到分歧时不会被 App 无条件复活。电脑接入细节见 [仓库指南](docs/setup/repositories.md)。

## 在首页读取原 Memos

在设置打开“连接原 Memos”，填写 HTTPS 实例地址与原账号密码，默认实例为 `https://memos.leedaud.xyz`。账号只在 App 内使用；此入口可选，不影响离线本机记录。

- 登录后，本人有权读取的原笔记直接与本机记录显示在首页；归档视图可读取远端归档，不需要单独的“Memos 历史”页面。
- 设置提供刷新和退出。远端记录保持只读，不因登录而自动保存、编辑或投递到 GitHub。
- “离线保存”显式下载并核验正文、元数据与附件；成功副本可离线查看，但不代表服务器最新版。外链资源不携带会话任意下载。
- “另存设备草稿”创建本机副本并附来源链接，不自动入队；之后保存仍遵循 GitHub 开关。
- 退出隐藏账号历史并停用会话，缓存按实例和账号隔离；本机独立笔记不受影响。

只能读取服务器仍保留且账号有权访问的记录。GitHub/电脑独有、服务器已删除的笔记，不会通过 Memos 登录自动找回。首阶段不支持 SSO，真实私人账号与历史需由用户自行验收。

## 常见问题与数据保护

| 问题 | 处理方式 |
| --- | --- |
| 仍显示 preview 或旧界面 | 核对安装包是否为 1.0，检查安装是否成功及系统 WebView 版本；不要卸载排障 |
| 登录后没有旧笔记 | 确认实例、账号与刷新结果；登录读取 Memos，不读取 GitHub 全库或浏览器草稿 |
| 保存后没有到 GitHub | 检查投递开关、当前任务、联网与 PAT；“已保存”仅证明本机保存 |
| 一直投递中 | 查看任务详情及父笔记状态；系统省电或强制停止可能延迟 WorkManager，重开 App 后核查 |
| PAT 失效或权限不足 | 为同一目标更新 token，再从失败任务重试；连接检查不等于写权限测试 |
| 手机已投递，电脑没有 | 确认电脑打开了正确仓库、分支，并执行拉取；手机没有电脑接收回执 |
| 附件或导出失败 | 检查容量、授权、系统选择器及实际文件；取消不能当作成功 |
| 出现冲突或签名不兼容 | 先保留和导出内容，人工核对，不强推、不清数据、不先卸载 |

重要记录建议同时保留已核验的 GitHub 副本和核对过的 ZIP 导出。草稿、未投递记录与 Memos 离线缓存不是云端备份。PAT、签名密钥及密码不进入公开文档。自行构建时必须独立备份签名密钥；遗失原密钥会影响同包名覆盖升级。

## 1.0 验证范围与限制

已完成 Debug/Release 各 92 项本地测试、Release Lint 零错误、签名与 APK 内容核验，以及 API35/WebView124 的 11 组生命周期/权限/离线/布局验证；设备验证使用 Debug 变体。图片选择、正文嵌入、画廊与附件字节核验已有合成设备证据。

仍待完善或验收：完整分组/媒体元数据归档往返、实时 GPS、受信 iframe、全部页面逐像素对比、最低 WebView、真实手机中文输入/录音/定位、真实账号与 GitHub 到电脑的端到端流程，以及完整旧调试版数据迁移。Memos 远端写入、GitLab、ObsidianHub 和完整管理/协作能力不在当前已实现范围内。详见 [功能核对表](docs/setup/android-web-parity.md)、[验证记录](docs/setup/verification.md)和[待办](docs/todo/todo.md)。

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

以下为 Memos **网页**入口，与 Android 本机保存及直投分开理解。

1. 在手机浏览器打开 <https://memos.leedaud.xyz> 并登录。
2. 输入文字或添加图片。
3. 点击“暂存”只保存设备草稿，不上传服务器。
4. 点击“保存”创建或更新服务器 Memo；使用明确的投递操作将当前版本加入仓库队列，不把暂存、编辑或登录视作投递授权。
5. 文本投递核验成功后，Markdown 写入 GitHub 输入仓库，电脑端通过 Obsidian Git 拉取。服务器 bridge 的附件投递与条件清理仍未启用，不要把 Android 附件直投能力套用到网页服务。

新笔记使用 `yyyyMMdd-HHmmss.md` 命名，例如：

```text
00_Inbox/20260928-231707.md
```

Memos 网页可添加图片，但当前服务器投递边界是已接通的文本 bridge，不能承诺附件已进入 GitHub。历史仓库路径不自动迁移。

编辑器保留设备草稿、独立暂存与保存操作；已保存记录双击编辑，投递中/已投递记录双击续写，状态未知时先保护原记录。左侧活动日历以四档浅色方块表示记录数量，不用日期下方圆点增加行高。

## Echo / Ignis 记录

- 每个新会话打开一篇上海时间戳命名的 Markdown；刷新或后台返回继续当前笔记，同一秒创建多篇时按秒顺延避免覆盖。
- 空白笔记只保留身份元数据和原生虚拟文件，首次非空输入才生成服务器 Markdown。
- 编辑即时写入设备 `localStorage`，输入、停顿、联网、切换页面和退出均不会自动上传。
- 只有“上传当前笔记”按钮会把当前版本写入服务器队列；原生云图标只显示状态。
- “已上传服务器”和“已备份到 GitHub”分别显示，不把 GitHub 成功描述为电脑已经接收。
- 手机端底部导航保持原位，右侧格式工具栏默认折叠，交互控件保持至少 44px 触控区域。

## 数据流与存储边界

Android 的日常链路无需 Echo/Memos 中转服务器（可选历史登录仍访问 Memos）：

```text
Android 本机草稿与正式 Markdown/附件
    ↓ 保存且开启投递，或显式补投
手机队列 → GitHub API → 私有输入仓库
    ↓ Obsidian Git 拉取
电脑 Obsidian 输入库
```

既有网页入口使用服务器链路：

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
- 网页 bridge 凭据只存于服务器 secret 文件，不写入源码、镜像、浏览器或日志；Android 独立账号/PAT 使用本机加密存储，不复用服务器凭据。
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

## 开源许可与二次开发声明

Echo 是基于 Memos 等开源组件进行适配与二次开发的独立项目，不是 Memos、Ignis 或 Obsidian 的官方产品，也不表示获得其官方背书。感谢上游作者与贡献者。

本仓库包含不同许可证的组件，**不将整个仓库统一声明为 MIT**。目录归属用于说明来源，不替代对实际代码、修改和组合关系的许可判断。

| 组件与范围 | 许可证与声明 |
| --- | --- |
| `upstream/memos/` 中的 Memos 原始代码，以及 Android 复用的 Memos Web 组件 | MIT；保留 `Copyright (c) 2025 Memos` 与[完整许可文本](upstream/memos/LICENSE) |
| `upstream/ignis/` 中的 Ignis 及受其许可约束的修改/衍生部分 | AGPL-3.0-or-later；保留[原始许可文本](upstream/ignis/LICENSE)，不得以 MIT 声明替代 |
| Android 原生与 Web 运行时的其他第三方依赖 | 分别遵循 MIT、ISC、Apache-2.0、BSD 等原有许可证，见[随包开源声明](apps/android/app/src/main/assets/open-source-notices.txt)和各依赖的许可文件 |
| 官方 Obsidian 运行资源 | 不适用上述 MIT 声明；从官方独立获取，遵循其自身条款，不随本仓库分发 |

Memos 的 MIT 许可允许使用、复制、修改、合并、发布、分发、再许可及销售软件；分发其副本或实质性部分时，必须保留原版权声明与许可声明，包括免责声明。原始许可全文不应仅以本 README 的摘要代替。Android 保留了 [MEMOS-LICENSE.txt](apps/android/app/src/main/assets/MEMOS-LICENSE.txt)，本地 Web 依赖的许可与声明随构建生成到 APK 的 `local-ui/THIRD-PARTY-NOTICES.txt`。

涉及 Ignis 的修改版分发和网络服务时，应按 AGPL 的适用条款保留声明、标明修改并提供对应源码；尤其是支持远程网络交互的修改版，应向交互用户显著提供获取对应源码的机会。仅公开本 README 或上游原版源码，不替代提供实际修改版本对应源码的义务。Android APK 不打包 Ignis/Obsidian 运行时，但这不改变仓库中相关组件的许可证。

本声明没有为 Echo 独立原创代码新增统一的 MIT 授权；尚未明确授权的原创部分不能仅因仓库公开就视为可任意再许可。受上游许可证约束的部分继续遵循其许可证。若后续为独立原创代码选择许可证，应明确权利人及适用范围，不覆盖第三方版权或既有许可义务。

软件许可证不应被视为品牌、商标、名称或图标的独立使用授权；相关权利归各自权利人所有。重新分发或商业使用时，应另行核查品牌及素材的适用条件。第三方代码的保证与责任限制以各自许可证为准；本节是来源与许可范围说明，不是完整依赖审计或法律合规保证。

## 文档

- 本 README 是 1.0 的日常使用入口；下列部分早期 Android 指南仍记录 preview 阶段行为，版本/默认入口以本 README、实际 APK 和最新验证记录为准。
- [Android 安装与技术说明](docs/setup/android.md)
- [Android 功能核对表](docs/setup/android-web-parity.md)
- [运行指南](docs/setup/running.md)
- [电脑与 GitHub 仓库接入](docs/setup/repositories.md)
- [Memos 接入与验证](docs/setup/memos.md)
- [生产部署记录](docs/setup/production.md)
- [验证结果和限制](docs/setup/verification.md)
