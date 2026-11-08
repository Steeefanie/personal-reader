# 可选云盘书库联动接口

本文是独立 HTTP 扩展契约，不要求特定网盘项目、框架、数据库表或磁盘布局。客户端已包含适配，实际服务需分别实现并联调。基础协议见 [drive-contract-v1.md](drive-contract-v1.md)。

## 能力与授权

`GET /api/reader/v1/me` 及绑定成功响应可增加：

```json
{
  "scope": ["books:read", "reading-state:read", "reading-state:write", "books:write", "books:delete"],
  "capabilities": ["book-list-v1", "book-upload-v1", "book-delete-v1"]
}
```

三项基础权限必须存在，`books:write`、`books:delete` 为可选授权；不能因阅读器升级自动扩大原设备权限。scope 当前只接受上述五个名称。完整列表需 `book-list-v1`；上传需 `book-upload-v1` 和 `books:write`；删除需 `book-delete-v1` 和 `books:delete`。公布能力前须完整实现对应契约。

## 完整列表：book-list-v1

```http
GET /api/reader/v1/books?limit=200&cursor=<可选游标>
Authorization: Bearer <设备凭证>
```

返回设备授权书库根下所有层级书籍的扁平列表：

```json
{
  "items": [{
    "id": "file_example", "parent_id": "folder_example", "kind": "file", "name": "示例.epub",
    "size": 1234567, "format": "epub",
    "content_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "content_version": "content_example_1", "modified_at": "2026-11-08T01:02:03Z"
  }],
  "next_cursor": null
}
```

元数据与 `/files/:id` 一致，哈希按实际文件字节计算，不能以文件名或修改时间代替。每页不超过 200 项，必须有 `next_cursor`，末页为 null；游标绑定用户、授权根和目录版本。列表变化时可返回 `409 cursor_expired`，阅读器重读一次；中途失败不替换旧缓存。排除 JSON、内部进度目录和已删文件。

完整列表用于确定已同步／待上传状态。正文进度仍按一个书籍文件对应一份 JSON，不把全书库合并为一个文档。

## 上传：book-upload-v1

```http
POST /api/reader/v1/books
Authorization: Bearer <设备凭证>
Content-Type: application/epub+zip
Content-Length: <实际字节数>
X-Content-SHA256: <64位小写SHA-256>
X-File-Name: <UTF-8文件名按百分号编码>
Idempotency-Key: <UUID>

<原始书籍字节，不是 multipart>
```

PDF 使用 `application/pdf`。客户端发送“书名.epub/pdf”，不保留导入前的完整源路径；由传输层设置固定 Content-Length。服务端验证授权、格式、限额、实际长度和完整哈希，先写临时文件，成功后原子登记并公布元数据。失败清除临时文件，不留下半本书。

服务端按用户授权书库中的内容哈希去重：已有同内容书返回 `200` 和已有文件元数据，新书返回 `201` 和新元数据。重命名不生成重复书；同名不同内容应按服务策略避免覆盖。客户端要求回执为 file、哈希与本机相同，其他状态不算成功。

幂等键按用户、内容和上传目标隔离，重试不能重复落盘或影响其他用户。服务端自行保证并发与崩溃恢复；数据库及文件系统实现不属于本契约。

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | `invalid_upload` | 实际长度或请求无效 |
| 403 | `upload_forbidden` | 设备未获上传授权 |
| 411 | `length_required` | 缺少合法长度 |
| 413 | `quota_exceeded` | 文件或可用容量限制 |
| 415 | `unsupported_media_type` | 格式不支持 |
| 422 | `invalid_hash` / `hash_mismatch` / `invalid_book` | 哈希或书籍校验失败 |
| 429 | `rate_limited` | 遵守 Retry-After |
| 503 | `temporarily_unavailable` | 暂时不可用，稍后重试 |

## 删除及重新上传

删除请求与完整回执见 [cloud-book-deletion.md](cloud-book-deletion.md)。一次只删除选定的一个书籍文件及其一个 JSON；文件缺失、版本变化或回执不完整时不继续移除本机副本。

删除标记必须保存在被删除的进度 JSON 之外；旧 GET/PUT 进度返回 `410 book_deleted`，索引保留删除版本，以阻止离线设备重建。用户再次明确选择上传并在云盘页刷新，可作为恢复操作；服务端应给恢复的文件分配新的对象身份和进度版本，恢复事务成功后才能解除该内容的删除限制。已完成 DELETE 的回执及目标代次需独立保存，旧幂等请求不得再次删除恢复后的新对象。恢复机制未经实际服务端联调，不能以清除一个标记代替全部并发保护。

## 客户端操作

- 书库已同步书可仅移除本地、仅移除云端，或两端删除；仅移除云端后保留本机文件，并设置为仅本地。
- 待上传书可选择仅本地；仅本地书可改为待上传。上传只由云盘页“同步并刷新”触发，不因打开正文自动上传书籍文件。
- 已下载云盘书直接离线阅读；未下载书由用户发起下载。删除操作有逐书确认。
- 进度 JSON 的实际同步还受授权、书籍存在状态、仅本地设置和删除标记约束，不能仅凭本机有记录就重建已删云端状态。

## 服务联调清单

检查能力与权限独立生效；完整列表分页和游标失效；新文件上传、同内容重试与同名不同内容；哈希／容量／中断错误清理；文件和 JSON 的原子删除、幂等回执；两台设备离线再上线；删除与上传／进度 PUT 并发；恢复后旧请求隔离；实际 HTTPS、账号隔离与设备撤销。源码中模拟测试不替代具体服务端验证。
