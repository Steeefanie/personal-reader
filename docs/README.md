# 文档索引

当前版本为 **图书 1.0.0**。历史资料只说明当时实现和验证范围，不作为当前发布结论。

| 文档 | 内容 |
| --- | --- |
| [项目结构](structure.md) | 目录职能、模块边界、本机缓存与交付文件 |
| [开发说明](development.md) | 环境、构建、测试、数据升级 |
| [发布流程](releasing.md) | 私有签名、版本、校验与 GitHub Release |
| [基础云盘契约](drive-contract-v1.md) | 绑定、身份、目录、下载、单书进度 |
| [书库联动扩展](cloud-library-sync.md) | 完整列表、上传、能力与权限 |
| [单书删除](cloud-book-deletion.md) | 一个文件与一个 JSON、幂等和删除标记 |
| [客户端接入说明](drive-integration.md) | 云盘客户端模块、隔离和联调边界 |
| [进度 JSON 示例](progress-format.json) | schemaVersion 1 的单书记录 |
| [文字书签](text-bookmarks.md) | 内容范围、旧记录兼容及返回原处 |
| [连接页导航](connection-navigation.md) | 两个入口与返回栈 |
| [1.0.0 发布说明](releases/v1.0.0.md) | 功能、兼容性及下载信息 |
| [验证记录](../VALIDATION.md) | 当前构建和测试结论 |

`releases/` 保留版本记录，`history/` 存放早期界面与验证资料，`licenses/` 存放保留的第三方许可。接口不规定任何特定服务端源码文件、数据库实现或磁盘路径。
