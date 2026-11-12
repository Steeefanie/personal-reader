# 开发与验证

## 环境

JDK 17、Gradle 8.14.3（Wrapper 已包含）、Android Gradle Plugin 8.10.1、Kotlin 2.1.21、Android SDK 35 与 Build Tools 35.0.0。最低运行版本 Android 7.0 / API 24。依赖由 Google Maven、Maven Central 和 JitPack 获取，版本固定在 Gradle 文件中。

## 调试构建

```powershell
$env:JAVA_HOME = '<JDK 17 目录>'
$env:ANDROID_HOME = '<Android SDK 目录>'
.\tools\build.ps1
# 本机依赖已齐全时才使用 -Offline。
.\tools\build.ps1 -Offline -WithAndroidTests
```

默认构建调试 APK、运行 JVM 测试和 Lint；`-WithAndroidTests` 另外编译仪器测试包，不自动连接设备。首次开发构建会生成标准开发测试签名；维护者本机有固定签名时复用同一签名。首次独立克隆不能覆盖其他签名安装的应用。

调试 APK 位于 `app/build/outputs/apk/debug/`；仪器测试包位于 `app/build/outputs/apk/androidTest/debug/`。发布签名需按 [releasing.md](releasing.md) 单独配置。

## 模拟器测试

先启动测试模拟器，再明确指定序列号，避免在非测试设备上运行：

```powershell
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w top.steeefanie.leafreader.test/androidx.test.runner.AndroidJUnitRunner
```

仪器测试会在目标模拟器内导入自建书籍、打开页面、修改测试设置并清理夹具。JVM 测试覆盖进度交换、接口校验、三方合并、续传、字体元数据和 EPUB 内容解析；Android 回归覆盖实际阅读、文字选择、恢复、界面、数据库持久化及模拟云盘。需要用户书籍的可选测试默认跳过，书籍不会打包进仓库。

## 数据存储

本地书库、阅读记录、云端状态、删除标记及逐书仅本地设置保存在 Room 数据库（v1）中；手动进度 JSON 为 schemaVersion 1。设备凭证由 Android Keystore 加密保存。

## 原生 EPUB 的边界

正文通过 `EpubChapterParser` 提取内容，`EpubTypesetter` 分页，`NativeEpubView` 绘制，`NativeEpubNavigatorFragment` 提供兼容的内容定位和翻页接口。常用结构受支持，完整 CSS、复杂文字塑形及出版者版式不等同于浏览器渲染。

静止渲染采样只能说明该短时窗口的帧变化，不能推导真实墨水屏耗电或续航；实际设备刷新与休眠需单独验收。
