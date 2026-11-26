# 图书 1.0.2 验证记录

验证日期为 2026-11-26。版本 1.0.2，`versionCode` 为 16，包名为 `top.steeefanie.leafreader`，最低 Android 7.0（API 24），目标 API 35。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| Release、Debug、AndroidTest 构建 | 通过 |
| JVM 单元测试 | 61 项通过，0 失败 |
| Android 回归 | 55 项通过，1 项可选外部 EPUB 测试跳过，0 失败 |
| Release Lint | 0 错误、57 警告、1 提示 |
| 调试包覆盖安装 | 1.0.1 覆盖到 1.0.2，安装前后数据库及 WAL 文件的 SHA-256 一致 |
| 正式 APK | ARM64、非调试构建、APK v2 签名验证通过，签名证书与 1.0.1 一致，构建号由 15 递增至 16 |

Android 回归使用 Android 11（API 30）x86_64 模拟器，分辨率为 1264×1680，密度为 300 DPI，安装同版本调试包。新增测试核验 EPUB 双字破折号的连续绘制、两端对齐、换行和原文偏移。

## 发布包

- `personal-reader-1.0.2-arm64.apk` 通过 GitHub Release 分发。[SHA256SUMS.txt](https://github.com/Steeefanie/personal-reader/releases/download/v1.0.2/SHA256SUMS.txt)
- 签名证书 SHA-256 为 `3e540b07f5659b983cd8ef5a3f4a3c06cefd93df527970e3b9cb17a534671cb4`。

ARM64 正式包覆盖安装、原 EPUB 和墨水屏显示效果尚未在真机上验证。
