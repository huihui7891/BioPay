# Release 工作流

在 `app/build.gradle.kts` 更新版本后，手动运行 Actions → Release。填写的补充说明会进入发行日志。

流程先构建并校验正式签名 APK、发布源仓库 Release，再同步到 `Xposed-Modules-Repo/io.github.kiriashi.biopay`：

- 从源码 README 自动生成商店 README 和 SUMMARY，并将图片、许可证链接转换为源仓库发行标签的链接。
- 下载源仓库已发布的 APK，使用 `versionCode-versionName` 标签发布同一文件及更新日志。
- 已有源仓库发行时跳过构建，继续检查商店同步；可以重新运行以恢复中断的同步。
- 商店已有 APK 时比较 SHA-256，文件不同则报错，不覆盖；较旧版本的任务不会覆盖新版商店说明。

## 一次性配置

除现有签名 Secrets 外，在源仓库 Settings → Secrets and variables → Actions 新增 `MODULE_REPO_TOKEN`。

该凭据需能写入 `Xposed-Modules-Repo/io.github.kiriashi.biopay` 的仓库内容和 Releases。优先使用仅授权该仓库、具有 Contents 读写权限的 GitHub App 或细粒度 PAT；能否使用取决于组织对凭据和外部协作者的限制。若使用 classic PAT，公开仓库需要 `public_repo`。默认 `GITHUB_TOKEN` 无法跨仓库写入。

缺失或过期的凭据会让同步 job 明确失败，源仓库已发布的 Release 保留。修复 Secret 后重新运行 Release 即可。LSPosed 官网索引在商店发行后异步更新，工作流完成不代表管理器缓存已刷新。
