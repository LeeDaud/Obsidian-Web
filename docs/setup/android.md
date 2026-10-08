# Android 独立 App：安装、配置与验证

版本：0.2.0（内部调试包） · 日期：2026-10-08。

当前启动入口为 Kotlin/Compose 的 `NativeActivity`，编辑、列表、草稿、正式 Markdown 和附件在本机运行。首次安装无需 Memos 账号、服务器地址或 GitHub 配置；核心流程不连接 Memos/Echo。现有 Web 服务继续保留，ObsidianHub 尚未接入。

UI 已按用户确认的 Memos 手机网页重构：48dp 顶栏、左侧抽屉、主页内嵌编辑器与列表、原网页主题及 Lucide 图标，保存/暂存/插入位于编辑器底部。当前是本地原生实现，不加载远端网页；完整逐像素一致性尚未验收通过，差异与设备证据见 verification。

本轮细节优化：启动图标使用原 Memos favicon；顶栏按实际状态栏和刘海安全区留白；侧栏日期使用四档浅色方块表示笔记数量，不再增加圆点行。月份栏、小屏草稿状态换行和浅深色系统栏图标同步已优化。

最新 R0 修复包进一步对齐暂存/保存/插入控件，编辑原笔记显示“更新”，列表及详情提供续写父子入口，修复置顶父笔记下子编辑位置。当前仍未完整迁移原 CodeMirror 与阅读组件，完整格式/功能缺口见 [核对表](android-web-parity.md)；不要将此 APK 当作所有 Web 功能已齐全。

当前投递仅支持 GitHub.com 的 API，不能把 GitLab/Gitee 仓库地址直接填入现有配置。国内自建 GitLab 可作为后续适配方向，但需独立的实例地址、项目身份、授权、提交和核验实现；本轮未实现或接入 GitLab。

## 安装与升级

APK 位于 `apps/android/app/build/outputs/apk/debug/app-debug.apk`。将文件传到 Android 手机后点击安装，按系统提示允许文件应用安装此包。最低 Android 8.0（API 26），目标 API 36；安装后的应用名称为 Echo，包名 `xyz.leedaud.echo`，versionCode 为 2。

本包沿用本机既有调试签名，支持覆盖同签名的 0.1.0 容器原型；不要先卸载或清除 App 数据。旧 WebView 文件仍保留，但不编入 APK，也不自动导入旧浏览器/WebView 草稿、Memos 或 GitHub 历史。原型文件不是回退网页入口。

正式发行签名、公开分发和旧数据导入尚未授权。项目 build/android-qa 内已有隔离 API 26 模拟器；本轮合成截图与交互验证不代替用户手机、当前 API、真实输入法和权限验收。

## 本机记录

1. 打开“主页”，在内嵌编辑器输入正文，底部选择“笔记 / 待办”；通过“+”菜单打开格式工具和本地预览。
2. 输入约 350ms 后保留设备草稿。“暂存”立即写本机，不创建网络投递任务。
3. “保存”生成正式 Markdown。未配置或暂停 GitHub 时仅写本机；已开启投递时授权当前版本的不可变快照。按钮沿用网页名称，具体投递由仓库开关决定。
4. 使用“+”菜单中的“附件 / 图片”系统选择器导入文件，复制到 App 私有目录后可离线查看。可插入正文、移除当前草稿引用、分享附件；照片可调用系统查看器看大图。
5. 录音在许可后开始，点击停止才加入草稿；接近容量边界时结束并尝试保留。离开录音界面或未停止就强杀不保证录音恢复。
6. 定位仅按需申请，首版读取系统最近可用位置；暂无位置时可手动输入。没有内置在线地图底图/地址解析；坐标与文字仍可离线保存。
7. 左上角打开抽屉，主页集合菜单可查看所有笔记、置顶及归档；抽屉提供日历、任务、标签、搜索与本机设置。时间戳打开详情，三点菜单提供编辑/续写及相关操作。普通未入队笔记可编辑；已入队或已核验笔记进入关联续写。

正文上限 256 KiB，每个附件 10 MiB，总附件 25 MiB、最多 100 个；超限不允许创建无效投递快照。PNG 分享包括正文和所附图片，限制文字长度/输出高度以避免内存溢出；过长内容改用完整 Markdown/附件 ZIP 导出，不静默裁切。

暂存、保存与附件导入成功后才承诺落盘。立即强杀可能早于尚未确认的异步草稿写入；应以“草稿已保留在本机”或显式暂存成功为准。后台只重试已经授权的快照，不把当前草稿混入旧任务。

## 配置用户自己的 GitHub

前提是用户已有 GitHub.com 仓库和已初始化分支。首版不替用户创建仓库/分支，不修改分支保护。

1. 自行创建 fine-grained PAT，选定专用私有输入仓库，提供 Contents 读写权限。不要复用服务器的 token，不要将 token 发到聊天或写入源码。
2. 在“设置”输入仓库所有者、仓库名、已有分支和 token。
3. “检查连接并保存配置”读取仓库身份与分支；不写测试笔记，也不宣称写入已验证。
4. 显式选择开启投递后，新保存的笔记才自动入队；旧本机笔记通过详情的“投递当前版本”显式补投。配置与联网不会自动扫描草稿或批量上传历史。
5. 同一目标替换 token，随后在失败任务详情重试。不同仓库/分支不会接收原队列；原任务绑定仓库数字 ID 与分支，恢复原目标后可继续处理。

凭据以 Android Keystore 密钥 + AES-GCM 加密文件保存，不直接放入 Room/日志/APK；Android 系统权限及 Keystore 的真实行为仍待设备验证。笔记/附件默认由 App 私有目录隔离，未声称全库加密或端到端加密。卸载/清除 App 数据会丢失尚未导出的本地内容和授权；系统不会自动备份这些私人数据。

## 投递、回执与故障

- 手机直接访问 `api.github.com`。正文及全部附件通过 Git Data API 组成一个 commit，以 `force:false` 发布到既有分支。
- 新笔记为 `00_Inbox/yyyyMMdd-HHmmss.md`，附件为 `attachments/<最终笔记名>/yyyyMMdd-HHmmss[-NN].ext`，使用上海时区和相对链接。
- 创建提交后先保存检查点，再更新分支；丢回执时先查候选提交是否进入分支，不直接重发。任何内容/附件未核验均不能显示已投递。
- “已保存”证明本机正式版本存在；“投递中”包含排队、离线、父任务等待与核验过程；错误/权限/冲突详情说明具体原因，失败版本仍冻结。
- “已投递”仅证明该次 GitHub 发布与内容核验，不代表电脑 Obsidian 已拉取，也不表示远端以后永不改变。
- 父笔记未核验时，子笔记继续留在队列，核验后追加 `续写自：[[父文件名]]`。未核验父路径的本地 ZIP 导出不编造 WikiLink，关联仍留在 App 中。
- WorkManager 调度受系统限制，强制停止/省电时不承诺立即执行；重开 App 后恢复持久任务。活动租约可能暂时等待到期再核查，失联不视作未发布。
- 路径已被占用且无发布尝试时按秒顺延；已有提交尝试与外部历史分歧时保守转为冲突，不强推、不覆盖、不自动复活被电脑移走的文件。冲突可先保留并续写，必要时人工核查。
- “暂停投递”阻止后续授权任务执行；已经发出的请求不能保证撤回，需要继续核查其结果。本机笔记不会在投递后自动删除。

电脑继续使用既有 Obsidian Git 拉取，首版没有桌面代理或 ObsidianHub 协议。

## 构建与检查

工程位于 `apps/android/`，使用 AGP 8.12.0、Gradle 8.13、Kotlin/Compose compiler 2.1.20、Compose BOM 2025.06.01、Room 2.7.2、WorkManager 2.10.1 和 Markwon 4.6.2。项目外 JDK 21、SDK 36、Build Tools 35.0.0 是本机已验证工具链；其他机器替换路径即可。

```powershell
$env:JAVA_HOME = 'D:/AAA-Project/000-dependencies/jdk21/jdk-21.0.10+7'
$env:ANDROID_HOME = 'C:/Users/10159/AppData/Local/Android/Sdk'
Set-Location apps/android
./gradlew.bat --no-daemon assembleDebug testDebugUnitTest assembleDebugAndroidTest lintDebug
```

依赖缓存齐备后可加 `--offline`。Room schema 位于 `app/schemas/`，新数据库为 `echo-native.db`，无 destructive fallback；正式内容/附件在设备私有文件区，构建目录不存真实笔记。`app/src/main/assets/` 仅存随 APK 分发的开源声明/许可，不存私人内容，不自动清理源码资源；构建及测试产物仍在忽略的 build 目录中。

隔离模拟器验收环境约定为 `app/build/android-qa/`：`downloads/` 存官方镜像与校验记录，`sdk/` 仅存测试用系统镜像和平台工具副本，`avd/` 存临时模拟设备及合成记录，`logs/` 存运行证据。全目录忽略，不接入私人账号，不更改用户全局 SDK/AVD 或 Windows 功能；验收后停止本次模拟器，目录删除另按清理授权处理。

在**空白且未配置仓库的专用测试设备**运行：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class xyz.leedaud.echo.NativeOfflineTest xyz.leedaud.echo.test/androidx.test.runner.AndroidJUnitRunner
```

设备测试检测到已配置仓库、正式笔记或未保存草稿时会拒绝输入合成内容；不要在日常使用的数据区运行。当前仅构建测试包，未执行上述设备命令。Robolectric 模拟环境的界面测试不等同于真机中文 IME、文件选择、录音、定位或后台系统验收。

## 当前验证范围

纯逻辑、mock GitHub、Room 内存/磁盘、文件恢复与 Robolectric 原生输入/暂存/保存/列表已有回归；构建、Lint 和最终签名/包内容核验见 [verification.md](verification.md)。

尚未执行真实 token、专用 GitHub 仓库发布、电脑接收、最低/当前 API 模拟器与真实手机、1000 条记录性能、横竖屏/字体缩放及正式签名升级验证。本地界面已实现，不等于这些端到端门槛已通过。
