# Release 工作流

在 `app/build.gradle.kts` 更新版本后，手动运行 Actions → Release。填写的补充说明会进入发行日志。

流程同时构建 Debug 和正式签名 Release，执行各自的 Lint 检查。Actions 产物分别命名为 `Debug`、`Release`，保留 30 天；源码 Releases 同时发布正式签名 APK 和带 `-Debug` 后缀的调试 APK，混淆映射单独保留 90 天。官方 Debug 与正式版使用同一签名，可覆盖安装并保留配置；测试结束后建议覆盖安装正式版。旧调试签名的 Debug 仍需卸载后安装，同签名也不能绕过 Android 的版本降级限制。

本地配置正式签名时，两种构建也使用同一签名；未配置时，Debug 使用默认调试签名。官方工作流必须恢复正式签名密钥后才能构建 Debug。

已有正式发行时不覆盖已发布 APK，仍构建最新 Debug。仅在当前源码与发行标签一致且缺少 Debug 附件时补充上传；源码已变化时，Debug 仅作为 Actions 产物，需升级版本后才能发布到 Releases。独立 CI 工作流已移除，提交代码不会自动构建。

## 同步模块仓库

手动运行 Actions → Sync module repository，填写已发布的源码标签（例如 `v2.1.1`），同步到 `Xposed-Modules-Repo/io.github.kiriashi.biopay`：

- 从所选发行标签的 README 自动生成商店 README 和 SUMMARY，并将图片、许可证链接转换为源仓库发行标签的链接。
- 下载源仓库已发布的 APK，使用 `versionCode-versionName` 标签发布同一文件及更新日志。
- 仅按正式版文件名同步已发布的稳定 APK，排除 Debug，不构建 APK；可以重新运行以恢复中断的同步。
- 商店已有 APK 时比较 SHA-256，文件不同则报错，不覆盖；较旧版本的任务不会覆盖新版商店说明。

## 一次性配置

除现有签名 Secrets 外，在源仓库 Settings → Secrets and variables → Actions 新增 `MODULE_REPO_TOKEN`。

该凭据需能写入 `Xposed-Modules-Repo/io.github.kiriashi.biopay` 的仓库内容和 Releases。优先使用仅授权该仓库、具有 Contents 读写权限的 GitHub App 或细粒度 PAT；能否使用取决于组织对凭据和外部协作者的限制。若使用 classic PAT，公开仓库需要 `public_repo`。默认 `GITHUB_TOKEN` 无法跨仓库写入。

缺失或过期的凭据会让同步工作流明确失败，不影响源码 Release 构建。修复 Secret 后重新运行 Sync module repository 即可。LSPosed 官网索引在商店发行后异步更新，工作流完成不代表管理器缓存已刷新。

## 下载量徽章

`Download badge` 工作流可手动触发，也会在每周一北京时间 00:00 和 Release 流程成功结束后触发，汇总源码仓库与模块仓库所有已发布 Release 的附件下载量（含历史 Debug 附件和预发布版本）。它只查询 GitHub API，不构建 APK，使用默认 `GITHUB_TOKEN` 即可。

统计结果保存在源码仓库独立的 `badges` 分支，README 通过 Shields Endpoint 显示合计。首次运行会自动创建该分支；数值未变化时不新增提交，任一仓库查询失败时保留原结果。下载次数不代表独立用户数，也不包含 CI Artifacts 下载。
