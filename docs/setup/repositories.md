# 电脑端输入仓库

## 已完成

2026-09-20 已核验：

- 本地路径 D:\AAA-Echo。
- origin 为 https://github.com/LeeDaud/Echo.git。
- 经用户明确授权，GitHub 仓库已从公开改为私有。
- 经用户明确授权，初始化提交 bcd2213 已推送，main 跟踪 origin/main。
- 提交只有 README.md、.gitignore；忽略 .obsidian/、.trash/，不含测试笔记或真实笔记。

不需要再次创建或初始化这个仓库。网页代码仍位于 D:\AAA-Project\037-Obsidian-Web。

## Obsidian Git 自动接收

1. 在电脑 Obsidian 中“打开文件夹作为仓库”，选择 D:\AAA-Echo。
2. 安装社区插件 Git（Vinzent03/obsidian-git）。
3. 手动执行一次 Pull，核实 Git Credential Manager/SSH 认证可用。
4. 开启启动时拉取，自动拉取间隔设置为 5 分钟，具体名称以插件版本为准。
5. 输入库保持一个独立 Obsidian 窗口打开。关闭输入库窗口后，插件不再后台运行。
6. 整理时先确认个人知识库已收到文件，再从输入库移走。如果希望 GitHub 当前文件树也移除这些文件，再通过插件提交并同步删除；历史中仍保留旧版本。

网页不访问你的个人知识库，不参与整理。不要再并行配置另一个自动 Git 任务写入同一本地目录。

## 后续仍需配置

- 服务器的专用临时目录、域名与 HTTPS；公网入口按用户要求不设登录密码。
- 仅目标仓库的服务器端 GitHub token。
- 明确开启真实 GitHub 自动投递。
- 在 Obsidian 安装并启用 Git 插件后，验证一条手机 → GitHub → D:\AAA-Echo 的真实笔记。

网页显示“已备份到 GitHub”只代表 GitHub 已核验，不能代表电脑已经拉取。

官方文档：
- https://github.com/Vinzent03/obsidian-git
- https://publish.obsidian.md/git-doc/Features
- https://publish.obsidian.md/git-doc/Authentication

