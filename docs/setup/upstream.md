# 上游来源与本地适配

- 来源：https://github.com/Nystik-gh/ignis.git
- 固定提交：6ce5bcb184d428b7809240b36cd4fe1b5159d2cd
- 上游版本：0.8.11
- 路径：upstream/ignis
- 上游 LICENSE 原样保留，声明 AGPL-3.0-or-later。
- 官方 Obsidian 运行资源：1.12.7，位于项目外，未随源码打包或分发。

本地修改只为集成服务：默认监听本机；在 Express 解析前认证，解析后挂载输入接口；原版 HTML 加载 capture.js；WebSocket 使用相同认证；关闭时收尾队列。原版编辑器、CSS、移动导航及 Obsidian 本体文件未重写。

新增 vitest.config.js 避免嵌套上游继承根应用仅匹配 TypeScript 测试的配置；同时包含上游 .test.js 和 .test.mjs。package-lock 的 npm 安装变动已保留，不通过 reset 丢弃。

upstream/ignis 已移除嵌套 Git 元数据，并以 vendored source 方式纳入当前源码仓库；固定版本、许可证和本地集成补丁均随根仓库保存。上游来源仍通过版本号与提交哈希追溯，官方 Obsidian 解包资源不加入 Git。
