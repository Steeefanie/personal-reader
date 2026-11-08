# 云盘阅读接口契约 v1

这是 personal-reader 可选云盘客户端使用的 HTTP 契约。任何独立服务均可实现这些接口；本文不要求特定服务端仓库、数据库、物理目录、用户注册流程或部署方式。阅读器不包含服务端代码，也不依赖相邻项目即可构建、测试和离线阅读。

示例账号、域名、设备 ID 和凭证均为占位值。客户端模块与联调方法见 [drive-integration.md](drive-integration.md)。

## 1. 接口范围

用户自行填写 HTTPS 服务根地址，客户端固定使用 `/api/reader/v1` 前缀。接口提供设备绑定、授权书库查询、EPUB/PDF 下载，以及按书籍哈希读写进度和书签。基础接口不提供书籍内容修改；完整列表及上传采用可选的 [书库联动扩展](cloud-library-sync.md)；单书文件及对应 JSON 删除采用可选的 [book-delete-v1 扩展](cloud-book-deletion.md)，需要服务端配套实现和独立授权。

书籍内容标识为原始文件字节的 SHA-256，使用 64 位小写十六进制字符串。文件 ID 表示条目，哈希表示内容；重命名或移动同一文件不改变哈希。正常情况下，一份书籍文件对应一份单书 JSON；按用户和内容哈希关联，文件改名不改变关联。

`books_root_id` 是本次设备授权的书库根目录 ID，可以是服务端提供的逻辑目录。客户端不访问服务器物理路径或数据库，不要求物理目录名必须是 books。

## 2. 身份与权限

支持的角色标识为 `admin`、`user`；昵称仅用于展示。阅读器不假定固定管理员昵称、管理员数量或服务端注册方式。两种角色的设备都只能访问所属用户获授权的书库与记录。

基础设备授权必须包含以下三项权限：

```json
["books:read", "reading-state:read", "reading-state:write"]
```

基础授权不包含删除权。支持单书删除扩展的服务可在用户明确同意后另外授予 `books:delete`，并在 `/me` 公布 `capabilities: ["book-delete-v1"]`；未公布或未授权时，客户端不调用删除接口。上传扩展另可授予 `books:write` 并公布 `book-upload-v1`；其他额外 scope 当前仍拒绝。

设备凭证只能用于阅读接口。服务端自行完成用户登录与授权确认，阅读器不接收用户登录 Cookie、密码或通行密钥材料。

## 3. 设备绑定

所有接口加版本前缀，以下统一使用：

```text
/api/reader/v1
```

正式环境只使用 HTTPS。长期凭证不进入 URL、二维码、日志或错误报告。

**1. 创建绑定请求**

```http
POST /api/reader/v1/bindings
Content-Type: application/json
```

```json
{
  "client_name": "图书",
  "client_version": "1.0.0",
  "device_hint": "电子书阅读器"
}
```

返回：

```json
{
  "binding_id": "bnd_example",
  "device_code": "仅供发起绑定的阅读器保存的临时随机凭证",
  "verification_uri_complete": "https://drive.example.com/device?code=example",
  "confirmation_code": "582731",
  "expires_in": 300,
  "interval": 5
}
```

阅读器展示二维码和核对码。`device_code` 只保存在阅读器临时内存中，不编码进二维码。

授权页显示用户、阅读器名称、核对码、权限说明和设备备注输入框。服务端通过自己的身份认证与授权流程确认用户，阅读器仅根据轮询结果领取设备凭证。

设备备注是展示名称，不作为设备身份；设备身份使用服务端生成的 `device.id`。

绑定参考设备授权模式，使用专用接口，不宣称完整实现 OAuth。[RFC 8628](https://www.rfc-editor.org/rfc/rfc8628.html)

**2. 轮询绑定结果**

```http
POST /api/reader/v1/bindings/poll
Content-Type: application/json
```

```json
{
  "binding_id": "bnd_example",
  "device_code": "创建绑定时返回的临时凭证"
}
```

未批准：

```json
{
  "status": "pending",
  "interval": 5
}
```

成功：

```json
{
  "status": "approved",
  "token_type": "Bearer",
  "access_token": "drv_example",
  "scope": [
    "books:read",
    "reading-state:read",
    "reading-state:write"
  ],
  "user": {
    "id": "usr_example",
    "name": "示例用户",
    "role": "admin"
  },
  "device": {
    "id": "dev_example",
    "name": "我的墨水屏"
  },
  "books_root_id": "dir_books_example"
}
```

其他状态：

| `status` | 阅读器处理 |
|---|---|
| `denied` | 停止轮询，提示授权被拒绝 |
| `expired` | 停止轮询，允许重新生成二维码 |
| `consumed` | 结果已领取，停止轮询 |
| `slow_down` | 按新的 `interval` 降低频率 |

设备密钥使用至少 32 字节安全随机数生成，长期有效直至撤销。服务端保存哈希，不长期保存密钥明文。

领取操作只能成功一次。如果领取响应丢失或本地保存失败，第一版重新绑定，并允许用户在安全页清理旧设备。

---

## 4. 设备校验与撤销

后续请求统一携带：

```http
Authorization: Bearer drv_example
```

Bearer 凭证必须通过 TLS 传输，并避免放入 URL。[RFC 6750](https://www.rfc-editor.org/rfc/rfc6750.html)

**校验当前连接：**

```http
GET /api/reader/v1/me
Authorization: Bearer drv_example
```

返回：

```json
{
  "user": {
    "id": "usr_example",
    "name": "示例用户",
    "role": "admin"
  },
  "device": {
    "id": "dev_example",
    "name": "我的墨水屏"
  },
  "scope": [
    "books:read",
    "reading-state:read",
    "reading-state:write"
  ],
  "books_root_id": "dir_books_example",
  "api_version": 1,
  "progress_schema_versions": [1],
  "server_time": "2026-11-03T08:00:00Z"
}
```

阅读器启动或恢复连接时调用 `/me`，不能仅凭本地存有密钥就认为仍有权限。

**阅读器主动解除自身绑定：**

```http
DELETE /api/reader/v1/me/device
Authorization: Bearer drv_example
```

成功返回 `204`。阅读器清除本地凭证并停止同步。

用户从网盘安全页删除设备时，后续请求立即拒绝，正在进行的下载也应中止。

已下载到本地的文件无法由服务端保证收回。设备撤销不等于远程删除离线书籍。

---

## 5. 书库目录与元数据

**1. 获取目录内容**

```http
GET /api/reader/v1/entries?parent_id=dir_example&limit=100&cursor=opaque
Authorization: Bearer drv_example
```

不传 `parent_id`，默认读取当前设备获授权的书库根目录。

返回：

```json
{
  "parent": {
    "id": "dir_books_example",
    "name": "books",
    "parent_id": null
  },
  "entries": [
    {
      "id": "dir_literature",
      "kind": "directory",
      "name": "文学",
      "parent_id": "dir_books_example",
      "modified_at": "2026-11-03T08:00:00Z"
    },
    {
      "id": "file_example",
      "kind": "file",
      "name": "示例.epub",
      "parent_id": "dir_books_example",
      "size": 2381092,
      "format": "epub",
      "media_type": "application/epub+zip",
      "content_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "content_version": "content_example",
      "modified_at": "2026-11-03T08:00:00Z"
    }
  ],
  "next_cursor": null
}
```

规则：

- 时间字段使用 UTC、ISO 8601；只有现有 JSON 内的 `updatedAt` 继续用 Unix 毫秒。
- 游标是不透明字符串，阅读器不能解析、修改或跨目录复用。
- 分页期间目录变化导致游标失效时，返回 `409 cursor_expired`，阅读器重新拉取该目录。
- 完整分页成功后，阅读器才替换该目录的本地列表；中途失败不能把未拉到的文件当作已删除。
- 不返回服务器物理路径、用户目录编码或其他用户的条目。
- 第一版不提供数据库镜像或全库增量事件流，通过目录分页刷新即可。

**2. 查询单个文件**

```http
GET /api/reader/v1/files/:fileId
```

返回该文件的同类元数据。无权限和文件不存在都返回 `404`，避免暴露其他用户的文件。

标题、作者、封面等 EPUB/PDF 内部信息，第一版由阅读器解析并保存在本地，不要求网盘先建立书籍内容索引。

---

## 6. 下载、校验与本地缓存

下载接口：

```http
HEAD /api/reader/v1/files/:fileId/content
GET /api/reader/v1/files/:fileId/content
Authorization: Bearer drv_example
```

支持：

```http
Range: bytes=0-1048575
If-Range: "content_example"
```

响应包含：

```http
Content-Type: application/epub+zip
Content-Length: ...
ETag: "content_example"
Accept-Ranges: bytes
```

阅读器需要：

1. 读取文件元数据，取得大小、内容版本和 SHA-256。
2. 下载到临时文件，按需断点续传。
3. 根据实际响应处理 `200`、`206` 和 `416`。
4. 下载完成后计算 SHA-256，与服务端元数据核对。
5. 校验成功后原子移入本地缓存，关联 `bookHash`。
6. 校验失败时不交给阅读模块，重新获取元数据并重试或提示失败。

续传时如果服务器返回完整的 `200`，必须重新写入文件，不能追加到旧分段末尾。

书籍缓存可以按内容哈希复用，但账号与文件归属映射要分开保存。删除网盘条目不会自动证明本地缓存损坏；阅读器可将其标记为“云端已删除”。

下载按设备授权鉴权。服务端可实施自己的容量、流量、带宽和并发限制，并使用约定状态码及 Retry-After 告知客户端。

---

## 7. 单书进度与书签 JSON

每个用户、每个 `bookHash` 对应一份 JSON，继续使用现有格式：

```json
{
  "schemaVersion": 1,
  "progress": [],
  "bookmarks": []
}
```

具体内容沿用现有 [progress-format.json](progress-format.json)。

约束：

- `progress` 为零条或一条记录。
- 所有记录的 `bookHash` 必须等于接口路径中的哈希。
- 书签 ID 不能在同一文档内重复。
- 不包含完整电子书或全文、凭证和字体设置；文字书签仅保留选文与有限前后文。
- 书签删除保留 `deleted: true` 和更新时间。
- EPUB `position` 仍是定位序号，不解释成固定屏幕页码。
- 没有对应书籍时，也允许同步该哈希的进度。
- 仅移除本机副本保留阅读状态，便于重新导入后恢复；用户确认“从本机和云端删除”则通过可选扩展同时删除云端文件和对应 JSON。
- 第一版不自动清理书签删除标记。
- 服务端保留合法 Locator 的扩展字段，包括 `text.highlight/before/after`、`locations.cssSelector` 和 `locations.readerTextRange`，不按本机显示页码重写锚点。范围字段说明见 [text-bookmarks.md](text-bookmarks.md)。

书籍文件和阅读记录分别管理；文件生命周期由服务端自行确定，客户端不因文件不可见而自动删除阅读记录。

---

## 8. 进度读取与条件上传

**读取：**

```http
GET /api/reader/v1/progress/:bookHash
Authorization: Bearer drv_example
```

成功返回 JSON，并包含：

```http
ETag: "state_example_17"
Cache-Control: no-store
```

尚不存在时：

```http
HTTP/1.1 404 Not Found
```

```json
{
  "error": {
    "code": "progress_not_found",
    "message": "尚未保存该书阅读状态"
  }
}
```

只有这个明确错误才能视为远端无记录。网络故障或 JSON 解析失败不能当作空数据。

**首次创建：**

```http
PUT /api/reader/v1/progress/:bookHash
Authorization: Bearer drv_example
Content-Type: application/json
If-None-Match: *

{ ...完整的单书JSON... }
```

成功返回 `201` 和新 ETag。

**更新：**

```http
PUT /api/reader/v1/progress/:bookHash
Authorization: Bearer drv_example
Content-Type: application/json
If-Match: "state_example_17"

{ ...完整的单书JSON... }
```

成功返回 `200`、保存后的 JSON 和新 ETag。

- 版本已变化：`412`，不修改远端。
- 没带必要的条件头：`428`。
- JSON 校验失败：`422`，整份不写入。
- 超过公布的请求大小限制：`413`。

条件检查和保存必须原子完成，避免两个设备都通过旧版本检查后互相覆盖。ETag 用于并发控制，`updatedAt` 用于记录合并，两者分工不同。[RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.1)

**为支持新设备恢复全部已有进度，再提供索引：**

```http
GET /api/reader/v1/progress?limit=100&cursor=opaque
```

```json
{
  "items": [
    {
      "book_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "etag": "\"state_example_17\"",
      "modified_at": "2026-11-03T08:00:00Z"
    }
  ],
  "next_cursor": null
}
```

索引包括尚未导入书籍的进度。阅读器分页读取索引后，按需拉取新增或版本变化的单书 JSON，不能仅对当前已下载的书同步。

基础接口不提供单独重置云端进度的操作。可选 [单书删除扩展](cloud-book-deletion.md) 同时删除一个书籍文件及对应 JSON，并以持久删除标记和 `410 book_deleted` 阻止旧设备重新上传；删除标记需进入进度索引。

---

## 9. 多设备合并与本地状态

阅读器继续以本地数据库为即时保存位置。每本书额外保存：

```text
连接配置ID
服务端用户ID
bookHash
上次同步成功的云端JSON
上次同步的ETag
本地修改序号
待同步标记
最近同步状态和错误
```

连接配置至少关联网盘服务地址。同步数据按：

```text
连接配置ID + 用户ID + bookHash
```

隔离，禁止换账号后把上个账号的待同步记录上传给新账号。

合并使用三份数据：上次成功同步的基准、本地当前记录、最新远端记录。

| 情况 | 处理 |
|---|---|
| 只有本地变化 | 保留本地变化 |
| 只有远端变化 | 接收远端变化 |
| 两端结果相同 | 确认一致 |
| 两端修改了同一条记录 | 执行冲突规则 |

冲突规则固定为：

- 阅读进度取 `updatedAt` 较新的整条记录，不拆分拼接定位与缩放字段。
- 书签按 `(bookHash, id)` 合并，不同 ID 全部保留。
- 相同书签 ID 取较新的记录。
- 相同书签时间相同时，删除标记优先。
- 自动同步中，进度时间相同且内容不同，采用远端记录，并保留本地冲突副本。
- 手动导入保持原规则：时间相同保留本地。
- 只因同步或重试，不更新 `updatedAt`。
- 不按进度百分比大小判定新旧，用户可能主动回读。

设备时钟偏差仍可能影响同时修改的先后判断，不能宣称时间戳合并绝对准确。三方比较和冲突副本用于减少误覆盖。

同步流程：

```text
本地事务保存阅读变化、标记待同步
→ 读取远端JSON与ETag
→ 整份校验
→ 三方合并
→ 条件上传
→ 成功后更新基准与ETag
```

每本书只运行一个同步任务。上传开始时记录本地修改序号；如果上传期间又产生新修改，成功后仍保留待同步状态，不能用旧快照覆盖用户刚保存的内容。

收到 `412` 后重新读取、合并、重试；连续冲突时退避。上传超时后重新读取确认结果，不直接假定上传失败。

---

## 10. 客户端交互与同步时机

阅读器增加一个“连接网盘”功能，包含：

- 网盘地址。
- 扫码绑定。
- 已绑定用户名和设备备注。
- 书库分类浏览。
- 下载状态、离线缓存。
- 最近同步时间、待同步数量。
- 手动同步。
- 解除绑定。

同步建议：

| 时机 | 行为 |
|---|---|
| 首次绑定 | 拉取书库目录和全部进度索引 |
| 启动、恢复前台 | 校验设备、刷新必要数据 |
| 打开书籍 | 尝试同步该书状态 |
| 阅读中 | 本地及时保存，暂停周期云端待办处理 |
| 修改书签 | 本地保存，退出阅读后按同步策略处理 |
| 关闭书籍 | 尝试立即同步 |
| 网络恢复 | 重试持久化队列 |
| 手动同步 | 刷新进度索引并处理待同步项 |

打开书籍时，网络超时后可直接使用本地位置。用户已经开始阅读后，迟到的云端进度不能自动跳页，改为提示是否跳转。

当前客户端在绑定成功后，将未归属账号的本地书库与记录复制到当前账号空间，并保留原本地数据；其他账号空间不自动合并。书籍文件上传仍由手动刷新触发，阅读记录同步受仅本地设置、删除标记和服务能力约束。

如果阅读器是浏览器应用或桌面 Web 容器，还需要在实际平台上验证密钥安全存储和带鉴权头的文件读取方式。不要依赖普通 `<a>`、`<iframe>` 自动携带 `Authorization`；下载层应主动发起鉴权请求，再将本地内容交给解析器。

---

## 11. 错误与安全约定

统一错误体：

```json
{
  "error": {
    "code": "device_revoked",
    "message": "设备授权已撤销，请重新绑定",
    "request_id": "req_example"
  }
}
```

| 状态 | 处理 |
|---|---|
| `401` | 凭证无效或撤销，停止同步并提示重新绑定 |
| `403` | 权限不足，或预算等业务限制；按错误码区分 |
| `404` | 资源不可访问或不存在 |
| `409` | 游标等状态失效，按错误码重新获取 |
| `412` | 进度版本冲突，重新拉取并合并 |
| `413` | 请求过大 |
| `422` | 数据校验失败，保留本地并提示 |
| `429` | 按 `Retry-After` 延迟 |
| `503`、网络错误 | 保留凭证和队列，退避重试 |

阅读接口独立校验 Bearer 设备凭证；服务端管理接口的登录和访问控制由其自行实现，不与阅读器共享管理权限。

若阅读器通过浏览器发起跨域请求，网盘应配置明确的允许来源，并支持：

```text
请求头：
Authorization
Content-Type
If-Match
If-None-Match
Range
If-Range

暴露的响应头：
ETag
Content-Range
Accept-Ranges
Retry-After
```

跨域预检不依赖设备凭证，但实际业务请求必须鉴权。不能为了阅读器接入而放宽全部管理接口的跨域策略。

---

## 12. 兼容性验收

阅读器与网盘共同验证：

- 用户扫码后绑定到正确账号，其他用户不能领取结果。
- 设备密钥只能访问自己的书库与进度。
- `admin` 的阅读器也不能读取其他用户书籍。
- 修改书名、移动分类后仍匹配原进度。
- 两个相同内容副本共享进度，内容变化后视为另一本书。
- 下载中断可续传，版本变化后不会拼接出损坏文件。
- 两设备同时上传能触发版本冲突并正确合并。
- 离线删除书签后，旧设备上线不会恢复书签。
- 上传过程中继续阅读，不会丢失新位置。
- 非法 JSON 整批拒绝，不破坏本地或远端原数据。
- 新设备可恢复尚未下载书籍的进度与书签。
- 换账号不会上传前一个账号的数据。
- 撤销设备后拒绝新请求，并中止正在下载的内容。
- 网络故障不会误删凭证、清空进度或清除待同步队列。

阅读器端可以先围绕统一的 `DriveClient` 接口开发，将**绑定、书库查询、下载、进度同步**分成独立模块，并用上述响应做模拟测试。待网盘接口完成后替换服务地址联调，阅读解析和本地数据库的既有逻辑可以继续沿用。