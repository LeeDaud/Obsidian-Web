# Echo

保留 Ignis 的原生 Obsidian 编辑器、主题、工具栏和手机导航，仅添加自动创建时间戳笔记、设备草稿、版本化暂存和 GitHub 投递。此前自制 React 页面已移除。

固定上游：Ignis 0.8.11，提交 6ce5bcb184d428b7809240b36cd4fe1b5159d2cd；Obsidian 运行资源 1.12.7。原版源码及许可证位于 upstream/ignis，Obsidian 本体从官方单独获取，不随本项目分发。

## 启动

需要 Node.js 22.14+。安装两套本地依赖并构建：

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

打开 http://127.0.0.1:4319 。两个运行目录必须位于项目外；CAPTURE_DATA_DIR 使用应用专用空目录，不能指向 D:\AAA-Echo。资源获取步骤见 [运行指南](docs/setup/running.md)。

## 记录行为

- 每个新会话打开一篇上海时间戳命名的 Markdown，例如 `20260920-222234.md`；日期与时间之间使用短横线，内部 UUID 不显示。刷新、后台返回继续当前笔记，同一秒新建多篇时文件名按秒顺延避免覆盖。
- 空白笔记只保留身份元数据和原生虚拟文件，首次非空输入才生成服务器 Markdown。
- 编辑即时写入本设备 localStorage 草稿，不因停顿、联网、切换页面或退出而自动上传。完全离线首次打开/刷新原版程序资源不保证可用；恢复网络后再打开可恢复设备草稿。
- 原版新文件控件改用时间戳分配。命令面板提供“新建时间戳想法”“将当前草稿另存为新想法”“恢复未发送草稿”。
- 手机端底部导航保持原位；编辑格式工具栏默认收起为 44px 悬浮按钮，按需展开为 52px 宽的右侧竖向栏，格式按钮保持至少 44px 触控区域。
- 中文组合输入结束后只更新设备草稿，Live Preview 支持 Markdown 加粗显示；编辑期间不触发服务器文件监听，因此不会因远端回写造成字符消失、复制或光标跳动。
- 顶部新增“上传当前笔记”按钮，只有手动点击才通过串行队列上传。原生云图标只查看状态；“已上传服务器”和“已备份到 GitHub”区分显示。
- 服务器只加载临时 Inbox；不 clone GitHub 仓库，不加载个人知识库。该入口仅支持 Markdown 记录，重命名、移动、附件入库和额外同步插件不在本次范围。

## 同步和清理

手机设备草稿 → 手动点上传按钮 → 服务器可靠队列 → 私有 GitHub → 电脑 Obsidian Git 自动拉取。

GitHub 投递默认关闭。开启后每 10 秒检查队列，使用非强制分支更新并核验不可变提交内容。已备份版本的队列正文被清除；原生编辑器的 Markdown 镜像还须等待最后一次活动至少 120 秒才清理。新版本、失败记录和冲突不会自动删除；清理不向 GitHub 传播删除。仅保留去重、版本及清理状态等元数据。

浏览器草稿与服务器临时正文不同；清除网站数据会丢失尚未送出的设备草稿。长期关闭或故障期间，尚未成功备份的服务器正文继续保留以避免丢失。

## 已连接的笔记仓库

- 电脑：D:\AAA-Echo，独立于网页源码。
- GitHub：https://github.com/LeeDaud/Echo ，已确认为私有。
- 首次初始化提交：bcd2213，仅 README.md 和 .gitignore；main 已跟踪 origin/main。
- 网页已部署至 https://echo.leedaud.xyz，GitHub 自动投递已启用，电脑端 `D:/AAA-Echo` 已完成真实拉取验证。详情见 [正式部署记录](docs/setup/production.md)。

[电脑端接入](docs/setup/repositories.md) · [运行与部署](docs/setup/running.md) · [验证结果和限制](docs/setup/verification.md)

## 检查命令

```powershell
npm run check
npm run test:upstream
Push-Location upstream/ignis
npm run lint
Pop-Location
# 设置真实 OBSIDIAN_ASSETS_PATH；必要时设置 CAPTURE_BROWSER_PATH
npm run test:browser
```

浏览器测试实际运行官方 Obsidian 资源，GitHub 安全行为测试使用模拟远端。未配置 CI/CD。



