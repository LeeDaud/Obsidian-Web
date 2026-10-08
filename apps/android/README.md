# Echo Android

## 当前入口：原生独立 App 0.2.0

用户已确认独立 App 方案并实施。启动入口为 NativeActivity + Compose，Room/私有 Markdown/附件承接本机记录，WorkManager 负责已授权快照，手机直接访问 GitHub；旧容器类及测试已从构建中排除，保留源码文件但不启用。当前安装/配置指南以 [docs/setup/android.md](../../docs/setup/android.md) 为准；设备/真实仓库验收状态见 verification。

`app/src/main/assets/` 仅保存随 APK 分发的开源许可和组件声明，无私人内容；属于源码资源，不自动清理。旧记录如下，仅解释 0.1.0 原型的历史边界。

<details>
<summary>0.1.0 WebView 容器原型历史说明</summary>

> 本目录当前是远端 WebView 容器原型。用户已明确其不满足独立 App 迁移目标；下面保留的是原型的构建/安装事实，不能当作离线记录或手机直投 GitHub 已实现。新需求见 [android-requirements.md](../../docs/plan/android-requirements.md)，技术规划见 [android-architecture.md](../../docs/plan/android-architecture.md)，待确认后实施；本轮没有替换或删除原型。

首版为现有 Memos 手机入口的 Android 客户端，包名 `xyz.leedaud.echo`，版本 `0.1.0`，支持 Android 8.0（API 26）及以上。主界面来自 `https://memos.leedaud.xyz`；当前仍需要 Memos/Echo 服务器，不包含 ObsidianHub 接入或手机直投 GitHub。

## 工程与产物

- `app/src/main/`：原生容器、导航策略和资源。
- `app/src/test/`：导航与下载来源边界测试。
- `app/src/androidTest/`：设备合成烟雾测试，无登录或笔记投递。
- `gradle/wrapper/`：固定 Gradle 8.13 的项目构建入口，包含官方分发 SHA-256。
- `app/build/outputs/apk/debug/app-debug.apk`：本地调试 APK。
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`：设备测试 APK。
- `.gradle/`、`build/`、`app/build/`：缓存与生成产物，已忽略；不存真实笔记，清理按项目授权规则执行。

构建使用 AGP 8.12.0、Gradle 8.13、JDK 21、Android SDK 36 和 Build Tools 35.0.0，无原生二进制架构依赖。`local.properties` 可配置 `sdk.dir`，但不能提交；签名密钥位于源码外。此版使用本机已有的 Android 调试签名，不作为正式公开发布版本。

## 构建

在 PowerShell 中进入本目录，将两个变量设为本机已有工具链路径，仅影响当前进程：

```powershell
$env:JAVA_HOME = 'D:/AAA-Project/000-dependencies/jdk21/jdk-21.0.10+7'
$env:ANDROID_HOME = 'C:/Users/10159/AppData/Local/Android/Sdk'
./gradlew.bat --no-daemon assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
```

其他电脑需替换路径。首次构建需要网络下载项目级依赖；缓存完整后可加 `--offline`。不需要全局 Gradle，不需要 Node 或启动本地 Memos 才能编译 APK。Android Studio 也可直接打开本目录。

## 安装与使用

1. 将 `app-debug.apk` 传到 Android 手机，点击文件，按系统提示允许该文件应用安装此 APK。
2. 打开 **Echo**，使用当前 Memos 账号登录；首版固定连接现有私有实例，不提供注册或服务器配置入口。
3. 继续使用已有笔记/待办、暂存/保存、图片、状态和续写交互。“已投递”仅表示 GitHub 仓库核验成功，仍无电脑逐条回执。
4. 录音与定位仅在调用对应功能时请求系统权限；文件上传使用系统文件选择器，不申请整个存储空间访问权限。

也可在已授权的测试设备上通过 SDK 的 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 安装。安装升级需保持同包名、兼容版本号与同一签名；不要先卸载以免丢失设备草稿。

## 数据与行为边界

- App 的登录与 `localStorage` 使用独立私有 WebView 数据区；不会读取或导入手机浏览器的旧草稿。
- 暂存保留在设备，保存沿用 Memos → Echo → GitHub。App 不持有 GitHub 或 bridge token，不增加自动保存、自动补投或后台 worker。
- 暂存后重启可由现有编辑器恢复草稿；切入后台会调用现有页面生命周期处理器，尽力刷新本地草稿。进程被立即强杀、尚未执行的 JavaScript 或未暂存的本地附件不保证恢复；重要输入请点击“暂存”。首次离线启动不能读取完整 Memos 界面。
- 加载失败显示可关闭的连接提示，不清除草稿；重试重新加载当前受信任页面。当前已打开编辑器仍由现有 Web 逻辑处理离线保存失败。
- Android 13+ 使用系统返回回调，旧版本使用兼容返回处理；有页面历史时返回上一页，根页面返回桌面并保留任务。键盘和网页弹窗行为仍须实机验收。
- 文件下载/导出采用系统“保存到”选择器，支持受信任站点的文件与 Blob，首版上限 20 MB；不直接写任意本地路径。选择器取消不显示成功。
- 卸载、清除 App 数据会删除未投递的设备草稿和登录；已在服务器或 GitHub 的笔记不受影响。App 禁止系统备份/设备迁移其私有数据。
- 外部 HTTPS、邮件、电话及 Obsidian 链接交由系统应用打开；无对应应用时提示。拒绝明文 HTTP、任意 intent 链接和证书错误，无原生 JavaScript 凭据桥。

## 验证

本地构建及单元检查见 `../../docs/setup/verification.md`。设备烟雾测试仅验证原生 WebView 设置、合成草稿在暂停时刷新及 Activity 重建后保留，不代表正式 Memos 或 GitHub 的端到端验收。

在明确授权的空白测试设备或模拟器上：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w xyz.leedaud.echo.test/xyz.leedaud.echo.AndroidSmokeInstrumentation
```

脚本不登录或保存 Memo；在 App 私有 WebView 的 Memos origin 下使用独立的合成草稿键，不清除其他草稿。真实手机验收还需确认中文键盘、返回手势、图片选择/取消、录音/定位授权、文件保存，以及暂存后强关重开。真实保存、续写和 GitHub 投递需由用户操作或另行授权。

</details>
