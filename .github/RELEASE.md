# Release 工作流

在 `app/build.gradle.kts` 更新版本后，手动运行 Actions → Release。填写的补充说明会进入发行日志。

流程同时构建 Debug 和正式签名 Release，执行各自的 Lint 检查。Actions 产物分别命名为 `Debug`、`Release`，保留 30 天；源码 Releases 发布正式签名 APK 和 `BioPay-v版本号-Debug.zip`，调试版需解压后安装其中同名 APK，混淆映射单独保留 90 天。官方 Debug 与正式版使用同一签名，可覆盖安装并保留配置；测试结束后建议覆盖安装正式版。旧调试签名的 Debug 仍需卸载后安装，同签名也不能绕过 Android 的版本降级限制。

本地配置正式签名时，两种构建也使用同一签名；未配置时，Debug 使用默认调试签名。官方工作流必须恢复正式签名密钥后才能构建 Debug。

已有正式发行时不覆盖已发布附件，历史 Debug APK 保持原样，仍构建最新 Debug。仅在当前源码与发行标签一致且缺少 Debug 附件时补充上传；源码已变化时，Debug 仅作为 Actions 产物，需升级版本后才能发布到 Releases。独立 CI 工作流已移除，提交代码不会自动构建。

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
