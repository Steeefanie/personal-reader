# 第三方依赖

下表列出应用使用的第三方组件及用途。再分发时应保留各组件的版权与许可说明。

| 组件 | 用途 | 上游许可与出处 |
| --- | --- | --- |
| Readium Kotlin 3.1.2 | EPUB/PDF 解析、定位及阅读导航 | BSD-3-Clause，https://github.com/readium/kotlin-toolkit |
| AndroidPdfViewer / PdfiumAndroid（marain87 分支） | PDF 显示及原生渲染 | Apache-2.0 / PDFium 上游许可，https://github.com/marain87/AndroidPdfViewer 和 https://github.com/marain87/PdfiumAndroid |
| PDFBox Android 2.0.27.0 | 用户触发的 PDF 文本搜索 | Apache-2.0，https://github.com/TomRoush/PdfBox-Android |
| AndroidX / Room / DataStore | 原生界面、数据库与设置 | Apache-2.0，https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin / kotlinx.coroutines | 语言与协程 | Apache-2.0，https://github.com/JetBrains/kotlin |
| Gradle Wrapper | 构建入口 | Apache-2.0，https://github.com/gradle/gradle |
| Project Nayuki QR Code generator（Java） | 在本机生成设备绑定二维码 | MIT，https://github.com/nayuki/QR-Code-generator ，源码固定于 `bcfe1b018cec5f1f91b5705eca5bb4cdd34e9f8d` |

再分发时应保留本说明以及相应上游许可和原生库第三方声明。测试书籍为项目自建材料，不使用用户文件或商业电子书。

二维码编码器仅纳入 `BitBuffer.java`、`DataTooLongException.java`、`QrCode.java`、`QrSegment.java`，每个文件保留完整 MIT 版权与许可头，不增加 Maven 依赖。未修改上游源码。

## 本机字体（v0.5.2）

应用不再分发思源黑体、思源宋体字体文件。EPUB 排版可以跟随系统，或选择应用可读取的本机字体；字体只读加载，未覆盖字符由系统字体回退。读取系统字体不代表取得其再次分发许可，应用不导出这些字库。

Readium、PDFBox 依赖自带的辅助字体资源随原库保留，包括 OpenDyslexic、AccessibleDfA、iA Writer Duospace 和 Liberation Sans；它们不作为本机中文字体选项。相应版权及许可遵循上游分发内容。

`font-index.json` 是字体名称、别名、类别和文字体系名称的对照表，不包含字库文件、预览图片或字体下载服务。列入名称索引不代表该字体免费、开源或取得分发许可。实际可用字体由设备可读文件决定。
