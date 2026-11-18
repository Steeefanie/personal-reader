# 图书 1.0.1 验证记录

验证日期为 2026-11-18。版本 1.0.1，`versionCode` 为 2，包名为 `top.steeefanie.leafreader`，最低 Android 7.0（API 24），目标 API 35。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| Release、Debug、AndroidTest 构建 | 通过 |
| JVM 单元测试 | 61 项通过，0 失败 |
| Android 回归 | 53 项通过，1 项可选外部 EPUB 测试跳过，0 失败 |
| Release Lint | 0 错误、57 警告、1 提示 |
| 正式 APK | ARM64、非调试构建、APK v2 签名通过，证书与 1.0.0 一致 |

Android 回归使用 Android 11（API 30）x86_64 模拟器，分辨率为 1264×1680，密度为 300 DPI。模拟器安装的是同版本调试包。

## 回归覆盖

- 阅读页状态栏与页码的四种开关组合，以及 EPUB 静止阅读时的渲染采样。
- EPUB 跨章跳转期间改变可用页面尺寸、同一内容文件的片段定位、长目录的当前项定位、跳转后返回原处。
- PDF 目录到页跳转与当前目录项定位，以及 EPUB/PDF 导入、翻页、搜索、恢复和书签。
- 书库、字体选择、进度保存与模拟云盘接口。

## 发布包

- `personal-reader-1.0.1-arm64.apk` 通过 GitHub Release 分发。校验值随同发布。 [SHA256SUMS.txt](https://github.com/Steeefanie/personal-reader/releases/download/v1.0.1/SHA256SUMS.txt)
- 签名证书 SHA-256 为 `3e540b07f5659b983cd8ef5a3f4a3c06cefd93df527970e3b9cb17a534671cb4`。

ARM64 真机覆盖安装、墨水屏刷新、休眠恢复、实体及蓝牙按键和长期耗电仍待设备验证。云盘测试使用模拟接口。
