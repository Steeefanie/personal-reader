# 构建与发布 APK

源码通过 Git 提交，APK 通过 GitHub Release 分发。所有发布说明使用简体中文；私有签名、凭证、用户书籍、原始设备数据及本机缓存不上传。

## 版本和签名

1. 修改 `app/build.gradle.kts` 的 versionName，并递增 versionCode。当前 1.0.0 / 14。
2. 维护者沿用原固定签名；本机保存在 Git 忽略的 `work/signing/personal-reader.keystore`。不要重新生成密钥来替换已有发布签名。
3. 通过环境变量向 Gradle 提供签名，不写入代码、项目属性或命令行明文口令：

```powershell
$env:READER_KEYSTORE = '<固定签名文件绝对路径>'
$env:READER_KEY_ALIAS = '<签名别名>'
$env:READER_STORE_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host '密钥库口令' -AsSecureString)).Password
$env:READER_KEY_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host '签名密钥口令' -AsSecureString)).Password
.\tools\build.ps1 -Release -WithAndroidTests
```

构建脚本缺少签名配置时会停止，执行 Release 构建、JVM 测试和 Release Lint。依赖已缓存时可加 `-Offline`。首次自行分发者须创建并备份自己的固定密钥；自行签名的 APK 不能覆盖维护者签名版本。

## 核验与交付

- 检查 `app/build/outputs/apk/release/app-release.apk` 的包名、版本、minSdk、ARM64 架构和非 debuggable 标记。
- 用 Android SDK 的 `apksigner verify --verbose --print-certs` 验证签名，证书指纹应与既有发布一致。
- 在测试模拟器验证待发布版本的源码。ARM64 Release 包应在 ARM64 设备实测；x86 模拟器测试不能替代该验证。
- 将 APK 命名为 `personal-reader-1.0.0-arm64.apk`，与 `SHA256SUMS.txt` 放在 `outputs/1.0.0/`。R8 mapping 只保存在本机验证目录。
- 将实际测试数量、失败／跳过项目、环境及未测范围写入根目录 `VALIDATION.md`，发布摘要写入 `docs/releases/v1.0.0.md`。不引用未经测量的性能、内存或耗电比例。

## GitHub 发布

确认暂存区没有密钥、凭证、缓存、用户电子书和旧 APK，提交中文说明，将 main 推送至远端；在同一提交创建并推送 `v1.0.0` 标签。Release 使用中文标题与正文，上传 APK 和 SHA-256 文件。

先创建草稿，检查标签提交与构建源码一致，核对附件后发布正式版本。验证发布后返回的附件校验值；需要时再下载附件复算。GitHub 自带 Source code ZIP/TAR 来自对应标签，不额外上传包含本机目录的源码压缩包。
