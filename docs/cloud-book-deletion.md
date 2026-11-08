# 单书云端删除接口（book-delete-v1 扩展）

阅读器已实现此扩展的客户端适配。本文定义独立服务的对接约定，服务端须实现对应扩展后才能启用。旧服务继续支持原有下载和进度同步，删除项会提示接口未支持或未获得权限，不移除本机副本。

## 用户操作及删除范围

- 书库宫格长按、双栏列表更多菜单和云盘书籍长按菜单按书籍状态提供移除选项。已同步书可选择仅移除本地、仅移除云端或两端删除。
- **仅移除本地副本**：只移除当前账号空间的应用内书籍副本；外部原件、云端文件和已有阅读历史保留。未下载的书提示无需移除。
- **从本地和云端删除**：确认后，一次只删除一本书的一个云端文件及它对应的一个进度 JSON，云端 JSON 包括这本书的进度、书签及书签删除记录。云端两项均确认删除后，再移除当前空间的本机副本。其他书籍和其他账号不受影响。
- 本机操作只影响本应用私有副本，不删除 Android 文件选择器导入时的外部原件。其他设备的离线书籍不会被远程擦除，原有本机历史仍可离线使用，但不再自动上传为已删除的云端 JSON。

## 兼容性及授权

`GET /api/reader/v1/me` 增加可选能力字段：

```json
{
  "capabilities": ["book-delete-v1"],
  "scope": ["books:read", "reading-state:read", "reading-state:write", "books:delete"]
}
```

其余身份字段保持原契约。绑定领取响应也可返回 `capabilities`；阅读器执行删除前仍重新请求 `/me` 并核对用户、设备。

服务端只有完整实现本扩展，包括原子删除、幂等回执、删除标记及禁止旧进度重新创建时，才公布 `book-delete-v1`。增加接口但缺少上述任一项，不应公布能力。

`books:delete` 是可选的独立授权，必须由用户在服务端明确同意。已有三项权限的凭证继续正常下载和同步；阅读器不因菜单点击自动扩大权限。旧凭证如未获删除授权，用户需按服务提供的流程重新授权。角色 `admin` 不绕过设备 scope 或书籍归属校验。

## 删除请求

```http
DELETE /api/reader/v1/books/:bookHash
Authorization: Bearer <设备凭证>
Idempotency-Key: 0e7f9830-df7a-3f40-a30c-d8cf95896fd1
Accept: application/json
```

`bookHash` 是 64 位小写十六进制 SHA-256，标识所选文件的实际内容，不按书名、作者或扩展名匹配。正常数据模型为一个书籍文件对应一个单书 JSON。

云盘列表已有明确文件 ID 和内容版本时，阅读器同时发送：

```http
DELETE /api/reader/v1/books/:bookHash?file_id=<URL编码后的文件ID>
If-Match: "<content_version>"
Idempotency-Key: <UUID>
```

服务端必须验证 file ID、哈希、内容版本都指向当前用户获授权的同一个文件；版本变化返回 `412 book_changed`，不执行删除。书库仅有哈希时，服务端在本次设备授权的书库中解析对应文件；无法唯一确定时返回 `409 book_ambiguous`，不得扩大成批量删除。文件重命名或移动、但内容未变化时仍可定位。

`Idempotency-Key` 按用户、接口及完整目标隔离。阅读器为同一目标生成稳定 UUID，响应丢失后再次点击仍可确认同一操作；相同 key 携带不同目标或前置条件时返回 `409 idempotency_conflict`。只有用户再次确认才重试删除，刷新、启动和同步循环都不发出删除请求。

## 成功回执与服务端事务

仅在书籍文件和对应 JSON 均已删除或确认不存在后返回 `200`：

```json
{
  "book_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "file_id": "file_example",
  "book_file_deleted": true,
  "progress_file_deleted": true,
  "deleted_at": "2026-11-07T02:03:04Z"
}
```

`deleted_at` 为服务端 UTC RFC 3339 时间，至少精确到秒。重复确认同一删除操作返回原回执，包括原 file ID 和删除时间；不能只因文件已消失返回普通 404。若该书尚无进度 JSON，`progress_file_deleted: true` 表示已确认它不存在并已阻止旧设备创建。

执行顺序必须在逻辑上不可分割：校验身份、权限、归属和前置条件；登记持久删除标记；移除该文件和 JSON；提交可重复读取的回执。删除与进度 PUT、文件更新必须协调，不能在两次独立删除之间允许旧进度重新写入。物理文件与数据库无法使用同一事务时，由服务端实现可恢复的删除事务或等价机制，恢复完成前不返回成功，不暴露半完成状态。

阅读器不接受 202、204、错误哈希、错误 file ID、缺少字段、无效时间或任一 false 字段作为成功。超时或回执校验失败保留本机文件；服务端可能已经完成删除，用户可再次确认以查询幂等结果。

## 防止旧设备重新上传已删 JSON

删除标记是服务端内部元数据，保存在被删除的书籍 JSON 之外；不是为该书保留一份原进度 JSON。其范围为当前服务、用户、文件内容和原 file ID，包含删除时间、稳定版本及幂等回执。删除标记不能在离线设备仍可能回传旧数据时直接清除。

- 删除后的 `GET /progress/:bookHash` 和 `PUT /progress/:bookHash` 返回 `410 book_deleted`。特别是 `If-None-Match: *` 也不能重新创建；不得返回 `404 progress_not_found`。
- 删除标记应进入同一个进度索引，携带稳定且区别于删除前的 ETag、删除时间和可选的 `deleted: true`。读取该哈希时返回上述 410。新设备和原来已同步的设备都能发现删除。
- 目录、文件元数据及下载不得继续暴露已删除文件；已经进行的进度 PUT 必须与删除串行化，或在提交时再检查删除标记。旧客户端不认识 410 也必须在服务端被阻止重新上传。
- 阅读器记录账号隔离的 `deleted_cloud_books` 标记，停止该书自动同步及待同步队列。原有本机历史可继续离线保存；后续退出阅读、字号变化、书签修改、手动导入备份不会清除标记。该标记保存在 Room v5，重启和重新绑定同一账号后仍有效。
- 删除扩展不自动恢复。重新上传采用 [book-upload-v1](cloud-library-sync.md) 的明确操作，需隔离旧删除请求与恢复后的对象身份；单纯导入相同文件不会清除云端删除记录。

索引示例：

```json
{
  "items": [{
    "book_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "etag": "\"deleted_example_18\"",
    "modified_at": "2026-11-07T02:03:04Z",
    "deleted": true
  }],
  "next_cursor": null
}
```

## 失败响应

沿用 `{ "error": { "code": "..." } }`，不要在错误正文返回凭证、磁盘路径或文件内容。

| HTTP | code | 行为 |
| --- | --- | --- |
| 401 | `device_revoked` 等 | 撤销凭证处理，保留本机数据 |
| 403 | `delete_forbidden` | 未获得删除授权，不执行删除 |
| 404 / 501 | `delete_not_supported` | 未实现扩展，不移除本机副本 |
| 404 | `book_not_found` | 没有文件且没有已提交删除回执，不执行本机删除 |
| 409 | `book_ambiguous` / `idempotency_conflict` | 目标或请求不明确，不扩大删除范围 |
| 410 | `book_deleted` | 仅用于进度等读取/写入；DELETE 重试应返回原 200 回执 |
| 412 | `book_changed` | 文件已变化，刷新并重新确认 |
| 429 | `rate_limited` | 遵守 Retry-After |
| 503 | `temporarily_unavailable` | 不假定删除成功，保留本机文件 |

## 验证与服务端联调

客户端模拟验证覆盖：未实现能力、未授权、请求失败、本机文件保留、单书删除、其他书籍和账号不受影响、共享缓存保留、部分回执、响应丢失后幂等确认、410 阻止旧进度和新书签回传、同步并发、删除提交后账号切换、v1/v2/v3 数据库升级及删除标记持久化。

服务端接入还必须实测：真实文件与对应 JSON 同时消失、崩溃恢复、重复请求原回执、未授权请求零变更、前置版本冲突、删除与 PUT 并发、旧客户端无法重建 JSON，以及实际 HTTPS 和两台设备离线后上线。客户端模拟测试不覆盖上述服务端验证。
