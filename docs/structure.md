# 项目目录与模块边界

项目根目录放置构建入口、说明文件和一级职能目录。应用采用单 Android 模块，代码按职能组织。

## 应用代码

应用包名保持 `top.steeefanie.leafreader`，避免项目改名导致安装和数据空间变化。

| 路径（`app/src/main/java/top/steeefanie/leafreader/` 下） | 职能 |
| --- | --- |
| `library/` | 文件导入、书库视图、元数据与封面缓存 |
| `reader/` | 阅读页、Publication 打开、搜索、书签、跳转与翻页 |
| `reader/epub/` | XHTML 内容解析、原生分页布局、Canvas 绘制与文字选择 |
| `reader/fonts/` | 可读系统字体发现、名称索引、字体元数据和预览 |
| `storage/` | Room、DataStore、JSON 交换与校验 |
| `drive/` | 可选 HTTP 客户端、授权凭证、上传下载、同步与删除 |
| `ui/` | 共用控件、底栏图标、面板及设置页 |

`io/nayuki/qrcodegen/` 为保留原许可头的二维码第三方源码。`assets/font-index.json` 仅含名称信息；`res/` 为应用原生资源。测试 EPUB/PDF 均为自建材料，不含用户出版物。

EPUB 继续使用 Readium 解析与 Locator 数据模型，正文显示采用原生 Canvas；PDF 使用 Readium 的 PDFium 适配器。云盘只依赖接口契约，不能引用相邻服务端目录、配置或私有数据库。

## 本机内容

- `work/toolchain/`：本机 JDK 与 Gradle；`work/android-sdk/`、`work/android-user/`：SDK 和模拟器。
- `work/gradle-home/`：依赖与 Wrapper 缓存，便于离线构建。
- `work/signing/personal-reader.keystore`：维护者本机固定签名，严格排除 Git 和 Release。
- `work/validation/v1.0.0/`：按版本保存的原始日志、报告和模拟器截图；公开验证摘要另存 `VALIDATION.md`。
- `outputs/1.0.0/`：保存对应版本的 APK、SHA-256 和发布说明副本。历史交付和临时资料可通过清理脚本管理。
- `app/build/`、`build/`、`.gradle/`、`.kotlin/`：自动生成内容，不作为源码提交。

## 清理规则

```powershell
.\tools\clean.ps1 -Build              # 预览构建缓存目标
.\tools\clean.ps1 -History            # 预览旧交付与临时资料
.\tools\clean.ps1 -Build -History -Apply
```

脚本逐个校验绝对路径处于本项目内，拒绝链接目录。`-Build` 不删除固定签名，`-History` 保留按版本分类的当前验证目录和 `outputs/1.0.0/`。构建运行期间应先结束 Gradle、模拟器日志写入后清理。目录名中的版本只用于本机交付分类，不影响协议版本。
