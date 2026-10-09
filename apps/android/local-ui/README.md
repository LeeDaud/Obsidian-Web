# APK 本地原组件

该目录只保存原 Memos 组件的构建入口和受限原生接口适配，不复制或修改 upstream/memos 的生产页面。main/router/React/CSS/CodeMirror/Markdown、表格、公式、脚注与懒加载组件直接来自固定上游。

## 当前阶段

R1 构建与浏览器原组件运行已验证，R2 本机正文保存/设备草稿/版本保护核心已实现；不是完整 R1–R4 完成。附件上传/选择、录音/位置、多引用与导出、筛选全集、账号/远端合流及 GitHub 配置/投递操作仍需适配验证。未知命令明确失败，不转发到服务器，不假装成功。完整矩阵通过前保持既有默认入口。

调试 APK 可用显式 `local-ui-preview=true` 启动候选入口。生产/release 不开放此调试入口；当前隔离 API 26 模拟器没有 WebView provider，已验证兼容性提示，而非原页面设备运行。需可用的 Chromium 111+ Android System WebView 才能加载原组件；实际最低可用版本还需设备测试。

## 构建与验证

复用 upstream/memos/web 已安装依赖及其 pnpm lock，不新增全局包或独立锁文件。运行：

```powershell
node upstream/memos/web/node_modules/vite/bin/vite.js build --config apps/android/local-ui/vite.config.mts
node upstream/memos/web/node_modules/vite/bin/vite.js preview --config apps/android/local-ui/vite.config.mts --host 127.0.0.1 --port 3016 --strictPort
node --import tsx apps/android/local-ui/verify.ts
node upstream/memos/web/node_modules/typescript/bin/tsc --project apps/android/local-ui/tsconfig.json --noEmit
```

Gradle preBuild 依赖 buildLocalUi。资源位于已忽略的 app/build/generated/assets/local-ui，截图与合成报告在 build/android-qa/local-ui。构建采用不清空输出模式，本轮不自动删除旧资源；最终发布前需完成生成资源清单/大小管理，旧 hash 残留不当成生产构建完成。

独立 tsconfig 对齐原 pnpm 已有直接/间接依赖的声明路径与原全局类型，避免误用根仓库另一套前端依赖；不修改上游源码或生成 proto。当前候选类型检查通过，原上游单独命令仍有既有声明路径解析问题，不能把候选成绩称为原上游全量检查通过。

## 安全与存储

- WebView 仅加载 APK 的 `https://echo-app.local` 资源，不访问 memos.leedaud.xyz 的页面，不启用 addJavascriptInterface。
- 主文档独占 WebMessagePort；跨源、file/content、未知命令、资源路径遍历拒绝。附件文档不能得到 port，并附 sandbox CSP，避免 SVG/HTML 以同源文档取得本机权限。
- JS 使用展示身份 users/device，不是真实鉴权凭据。Memos/GitHub token、Keystore 和任意本机文件路径不进入响应。
- 草稿只镜像到设备草稿与私有 local-ui-state/drafts 索引；清除使用停用标记，不删除源文件，不把草稿变成正式笔记或入队。保存经既有 NoteRepository/版本保护，只有显式保存调用调度。
- NativeActivity 原实现与 WebView 历史容器文件保留。无 Room 迁移、私人历史/生产操作、Git 提交推送或 CI/CD 改动。
