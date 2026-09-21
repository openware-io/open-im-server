# 平台媒体对象与私有访问工程规范

## 1. 范围与术语

本规范适用于 App、PC 管理后台上传和读取头像、聊天图片/语音/视频/附件、预约凭证及后台附件。存储提供方通过适配器支持阿里云 OSS、腾讯云 COS 和 MinIO；业务服务不得将任一提供方 SDK 类型暴露给客户端。

| 术语 | 含义 |
| --- | --- |
| `objectId` | 服务端生成、对业务和客户端稳定可见的 UUID；不是 OSS Key 或签名 URL。 |
| `objectKey` | 服务端保存的对象存储定位键；客户端不可自行构造。 |
| 上传会话 | 一次受限直传的服务端记录，由 `uploadSessionId` 标识。 |
| 上传指令 | 向一个精确临时对象写入的短期签名 `PUT` URL 或受限 `POST` Policy；由服务端适配器决定。 |
| GET URL | 由服务端鉴权后签发的短期读取 URL，不可持久化。 |
| 媒体引用 | 由业务服务以内部契约登记的 `objectId` 与业务实体关联；不是客户端可伪造的字段。 |

## 2. 强制架构边界

文件二进制必须直连受管媒体域名；控制面请求必须经 Gateway。应用服务负责身份、额度、对象元数据、状态机、授权和审计，不能代理常规媒体文件流。

```text
客户端 -- 初始化/完成/授权 --> Gateway --> Business support service（media module）
客户端 -- PUT/GET 二进制 ----------------> OSS、COS 或 MinIO
业务服务 -- 校验 mediaObjectId ---------> Business support service（media module）
```

- `API_BASE_URL` 仅用于 `/api/v1/**` 控制面接口；本地真机示例为 `http://<LAN-IP>:3002`。
- `MEDIA_BASE_URL` 是客户端允许访问的媒体域名；它必须与服务端 `MEDIA_PUBLIC_BASE_URL` 指向同一受管外部地址。
  公共读媒体走同源相对路径时（见下一条 URL 规范）**两者都不下发**：客户端拿到的是 `/api/v1/media-public/...` 相对路径，直接拼当前站点的 origin；只有配置了绝对 `public-url-prefix`（CDN/OSS 直连）时才由服务端返回绝对地址。
- `MEDIA_INTERNAL_ENDPOINT` 仅供服务端访问对象存储，可以是容器服务名；绝不能下发给客户端。
- 聊天消息引用的媒体使用「匿名可下载 + 不可猜测 UUID 对象键」的长期稳定 URL（参考 Telegram/微信）：地址随消息长期有效，仅随删除/撤回失效。上传会话的 PUT 签名仍是短期的。
- **公共读媒体的 URL 规范（唯一方案，2026-09 起）**：服务端返回**同源相对路径** `/api/v1/media-public/{bucket}/{objectKey}`，由 Gateway 的 `public-media` 路由
  （`Path=/api/v1/media-public/**` + `StripPrefix=3`）转发到对象存储（`MEDIA_INTERNAL_ENDPOINT`，如 `http://minio:9000`）。
  即 **Gateway 就是公共媒体读取的 Host**，各环境入口（nginx / Ingress）只需把 `/api` 路由到 Gateway，**不再维护任何媒体专用反代或 rewrite**；
  历史上「入口各自反代对象存储」的 `/media-public/**` 方案已废弃，存量 URL 由迁移直接改写，不做过渡兼容。
  对象存储桶只开放匿名只读；服务端上传时必须把真实 `Content-Type` 与 `Cache-Control: public, max-age=31536000, immutable`（对象键含 UUID、内容不可变）写入对象元数据。
  若某环境改走 CDN/OSS 直连，把 `media.public-url-prefix` 配成绝对地址即可，此时本网关路由不参与。
- `POST /api/v1/upload` 不属于直传协议；首次部署和后续新功能一律不得新增该路由或代理上传实现。

## 3. 对象键、用途和限额

服务端根据 `scope` 与 `mediaKind` 生成唯一对象键；任何客户端传入的路径、Bucket、对象名或 URL 均必须拒绝。

| scope | mediaKind | 最终对象键模板 |
| --- | --- | --- |
| `avatar` | `image` | `im/avatar/{ownerId}/{objectId}.{extension}` |
| `chat` | `image` | `im/chat/image/{yyyy}/{MM}/{dd}/{objectId}.{extension}` |
| `chat` | `audio` | `im/chat/audio/{yyyy}/{MM}/{dd}/{objectId}.{extension}` |
| `chat` | `video` | `im/chat/video/{yyyy}/{MM}/{dd}/{objectId}.{extension}` |
| `chat` | `attachment` | `im/chat/file/{yyyy}/{MM}/{dd}/{objectId}.{extension}` |
| `reservation` | `voucher` | `reservation/voucher/{yyyy}/{MM}/{dd}/{objectId}.{extension}` |
| `system` | `image` | `system/image/{yyyy}/{MM}/{dd}/{objectId}.{extension}` |
| `system` | `attachment` | `system/attachment/{yyyy}/{MM}/{dd}/{objectId}.{extension}` |

未完成上传仅允许写入 `temp/upload/{uploadSessionId}/{objectId}`。完成校验后由服务端复制到最终 Key 并删除临时对象；不得把临时对象直接作为业务媒体使用。

服务端是大小和 MIME 的最终裁决者。当前默认上限为图片 10 MiB、音频 20 MiB、附件 50 MiB、视频 200 MiB；时长、分辨率、像素和用户配额也必须受服务端配置限制。任何限额调整必须同步更新应用配置、OpenAPI、客户端预检和本规范。允许的 `scope/mediaKind/MIME` 组合由受管配置白名单维护，最低要求如下：

| 类别 | 允许 MIME |
| --- | --- |
| 图片 | `image/jpeg`、`image/png`、`image/webp` |
| 音频 | `audio/mpeg`、`audio/ogg`、`audio/wav`、`audio/mp4`、`audio/aac` |
| 视频 | `video/mp4` |
| 附件 | `application/pdf`、`text/plain`、`application/zip` |
| 凭证 | `image/jpeg`、`image/png`、`application/pdf` |

`avatar` 仅允许 `image`；`chat` 允许 `image`、`audio`、`video`、`attachment`；`reservation` 仅允许 `voucher`；`system` 允许 `image`、`attachment`。未列出的组合必须拒绝。

原始文件名仅可作为经长度、控制字符和 Unicode 规范化校验后的展示元数据，不能参与 Key 构造。扩展名由服务端根据声明 MIME 和允许列表确定。

## 4. 媒体对象与会话状态机

```text
对象：PENDING -> UPLOADED -> SCANNING -> ACTIVE -> DELETED
                    |             |
                    +-----------> REJECTED

会话：UPLOADING -> COMPLETED
          |             |
          +-> CANCELLED +-> EXPIRED
```

| 状态 | 含义与允许操作 |
| --- | --- |
| `PENDING` | 媒体对象已分配稳定 `objectId`，但临时对象尚未校验。 |
| `UPLOADED` | 对象存储已确认存在，等待校验任务。 |
| `SCANNING` | 正在做魔数、图片尺寸、音视频容器、Hash 与病毒检查。 |
| `ACTIVE` | 可被头像、消息、预约等业务引用，也可按授权签发 GET URL。 |
| `REJECTED` | 校验或扫描失败；拒绝业务引用并删除或隔离对象。 |
| `DELETED` | 对象被逻辑删除；不再签发 URL，异步删除存储对象。 |

会话的 `COMPLETED` 只表示客户端已完成直传，不表示媒体可用。客户端绝不能发送非 `ACTIVE` 的 `objectId`；`complete` 通常返回 `processing`，客户端必须轮询对象状态或订阅已冻结的通知契约，不能以“上传 HTTP 成功”替代激活成功。

## 5. 控制面 REST v1 契约

以下路径均以 `{API_BASE_URL}/api/v1` 为根，并携带 `Authorization: Bearer <token>`、`X-Api-Contract: v1`。所有写操作都必须携带 UUID 格式的 `Idempotency-Key`；同一个键只能用于语义完全一致的请求。

### 5.1 初始化单对象上传

```http
POST /media/upload-sessions
Idempotency-Key: 20e9f2a2-332a-4aa0-984d-61d8d9c3ac18
Content-Type: application/json
```

```json
{
  "scope": "chat",
  "mediaKind": "audio",
  "fileName": "voice.m4a",
  "contentType": "audio/mp4",
  "size": 46284,
  "sha256": "lowercase-hex-64-characters",
  "durationMs": 3200
}
```

字段规则：

| 字段 | 规则 |
| --- | --- |
| `scope` | 枚举：`avatar`、`chat`、`reservation`、`system`；由当前角色和业务上下文授权。 |
| `mediaKind` | 枚举：`image`、`audio`、`video`、`attachment`、`voucher`；必须与 scope 和 MIME 匹配。 |
| `fileName` | 可选展示信息，最长 255 字符；服务端不信任扩展名。 |
| `contentType` | 必填，必须在该类白名单中；其值必须与 PUT 签名绑定。 |
| `size` | 必填正整数，必须不超过服务端上限和用户配额。 |
| `sha256` | 必填 64 位小写十六进制；所有接入端必须在初始化前完成流式计算。 |
| `durationMs` | 音频/视频必填；图片/附件不得传。 |

成功响应：

```json
{
  "data": {
    "uploadSessionId": "c9c0a2c0-a8b2-4e56-a3b3-a339f8a2dd77",
    "objectId": "91d77a9d-472f-4257-9932-6df377651f34",
    "method": "PUT",
    "uploadUrl": "https://media.example.com/temp/upload/...?...",
    "requiredHeaders": {
      "Content-Type": "audio/mp4",
      "x-oss-meta-sha256": "lowercase-hex-64-characters"
    },
    "expiresAt": "2026-08-04T10:20:00Z",
    "maxRetries": 3
  },
  "requestId": "uuid"
}
```

上传指令必须固定临时 Key、过期时间、Content-Type 和必要元数据。能通过 POST Policy 约束内容长度的提供方必须同时下发最小/最大长度条件；仅支持预签名 PUT 的提供方，必须将期望 `Content-Length` 写入签名约束（若提供方支持），并在 `complete` 阶段以 HEAD 和流式校验作为最终裁决。服务端访问密钥、Bucket 列表和永久凭据绝不能返回。若使用 POST Policy，响应额外返回 `formFields`，客户端以 `multipart/form-data` 提交且不得擅自增加字段。

### 5.2 客户端直传

客户端只允许请求响应给出的上传指令；`method=PUT` 时按 `requiredHeaders` 原样发送：

```http
PUT {uploadUrl}
Content-Type: audio/mp4
x-oss-meta-sha256: <sha256>
Content-Length: 46284
```

上传 URL、查询签名和响应体不得写入普通日志、埋点、崩溃报告或聊天内容。客户端必须报告进度，但不得因为网络重试重新调用初始化接口；应先复用未过期会话。

### 5.3 完成上传

```http
POST /media/upload-sessions/{uploadSessionId}/complete
Idempotency-Key: 3f224b92-451d-4587-8c66-4ab95ecf5b43
Content-Type: application/json
```

```json
{
  "size": 46284,
  "sha256": "lowercase-hex-64-characters"
}
```

服务端必须对临时对象执行 `HEAD`，比较大小、声明 Content-Type 和受限元数据，将会话标记为 `COMPLETED`、对象标记为 `UPLOADED` 后投递校验任务。校验任务必须流式重算 SHA-256 并校验魔数；仅依赖客户端提交的 Hash 或 HEAD 元数据不构成完整性校验。校验通过后复制到最终 Key、删除临时对象并激活。成功响应：

```json
{
  "data": {
    "objectId": "91d77a9d-472f-4257-9932-6df377651f34",
    "status": "processing",
    "contentType": "audio/mp4",
    "size": 46284,
    "durationMs": 3200
  },
  "requestId": "uuid"
}
```

扫描未完成时 `status` 为 `processing`，并返回 `retryAfterSeconds`。完成接口是状态收口的权威来源；对象存储回调只允许补偿 `UPLOADING` 会话，不能直接创建业务引用或发送消息。

### 5.4 取消、状态和读取

| 操作 | 请求 | 成功响应与规则 |
| --- | --- | --- |
| 取消上传 | `DELETE /media/upload-sessions/{uploadSessionId}` | `204`；只允许所有者取消未激活会话。 |
| 查询状态 | `GET /media/upload-sessions/{uploadSessionId}` | `{objectId,status,expiresAt,retryAfterSeconds?}`；仅会话所有者。 |
| 查询媒体状态 | `GET /media/{objectId}` | 返回经授权的 `{objectId,status,contentType,size,durationMs?,width?,height?}`；用于等待激活。 |
| 获取单个读取 URL | `GET /media/{objectId}/access` | 返回 `{objectId,url,expiresAt,contentType,size}`；仅 `ACTIVE`。 |
| 批量获取读取 URL | `POST /media/access-urls` | body `{objectIds:[...]} `，最多 100 个；逐项返回成功或授权错误。 |
| 删除媒体 | `DELETE /media/{objectId}` | `204`；执行引用检查和延迟物理删除。 |

聊天消息引用的媒体读取 URL 是稳定长期地址（匿名可下载 + 不可猜测对象键），随消息长期有效，仅随删除/撤回失效；客户端不得依赖会过期的签名 URL 作为消息媒体的永久地址，也不需要到期续签。批量 access 仍是聊天历史渲染的默认方式，避免 N+1 请求。非聊天场景（如预约凭证）如需访问控制，可保留短期签名 URL。

### 5.5 大文件分片上传

大于受管阈值（初始建议 20 MiB）的文件使用以下接口：

```text
POST /media/multipart-upload-sessions
POST /media/multipart-upload-sessions/{id}/parts/signatures
POST /media/multipart-upload-sessions/{id}/complete
DELETE /media/multipart-upload-sessions/{id}
```

初始化参数与单对象上传一致，响应增加 `partSize`、`partCount`；存储提供方 `uploadId` 仅由媒体服务保存，客户端只能通过受签 Part URL 使用它。客户端每次申请一组有限的 Part URL，上传后从响应头读取 `ETag`，调用 `POST /media/multipart-upload-sessions/{id}/parts/{partNumber}/complete` 并提交 `{"etag":"..."}`。最终 `complete` 必须提交完整且有序的 `[{"partNumber":1,"etag":"..."}]`，服务端将其与已确认 ETag、预期 Part 数和总大小逐项比对后才调用对象存储完成请求。客户端不可调用 OSS/COS/MinIO 的 complete multipart API，也不可伪造 ETag。未完成分片会话必须由存储生命周期和服务端清理任务双重回收。

## 6. 授权、业务引用和数据模型

媒体对象表必须保存稳定映射，签名 URL 不得入库。表结构必须至少包含下列字段集合：

| 字段 | 说明 |
| --- | --- |
| `object_id` | UUID 业务标识，唯一。 |
| `provider`、`bucket_name`、`object_key` | 服务端定位对象存储的完整映射。 |
| `owner_id`、`scope`、`media_kind` | 上传者与业务用途。 |
| `content_type`、`original_file_name`、`size_bytes`、`checksum_sha256` | 经校验的对象元数据。 |
| `duration_ms`、`width`、`height` | 仅音视频或图像适用的经校验元数据。 |
| `status`、`upload_session_id`、`expires_at` | 上传状态、幂等和清理依据。 |
| `created_at`、`activated_at`、`deleted_at` | 审计与生命周期字段。 |

上传会话表必须另外保存 `owner_id`、`idempotency_key`、`request_digest`、临时 Key、声明元数据、会话状态和过期时间，并对 `(owner_id, idempotency_key)` 建立唯一约束。重复键只有在请求摘要相同时才能返回原会话；摘要不同时必须返回冲突。

必须新增受权威业务事务维护的媒体引用关系，至少包含 `object_id`、`business_type`、`business_id`、`reference_role`、`created_at`，并对业务实体与对象建立幂等唯一约束。客户端不能直接创建、修改或删除引用。聊天消息创建时，消息服务调用业务支撑服务的媒体内部授权接口，校验对象为 `ACTIVE`、所有者为发件人、类型匹配且尚未被不兼容业务绑定；校验成功后以可靠 Outbox 写入消息和引用关系。预约、用户和后台领域同样通过内部绑定契约完成引用登记。

聊天消息媒体使用稳定长期 URL，访问控制由「不可猜测 UUID 对象键」承担，不再依赖私有 GET 签名或成员续签。对象所有者、管理员或消息有效成员的授权校验仍用于控制面接口（状态查询、删除、引用绑定等）；二进制下载走匿名可下载的稳定地址。

## 7. 安全、扫描与存储配置

- 聊天媒体 Bucket 使用「匿名可下载」读策略（写入仍仅限服务端凭据）；对象键含不可猜测 UUID，作为下载访问控制。上传 PUT 签名仍是短期且绑定 Content-Type。预约凭证等敏感场景可保留私有 Bucket + 短期签名 GET。
- CORS 仅为浏览器/PC Web 直传而设，生产必须列出明确 Origin、Method 和 Header；原生 App 不依赖 CORS。
- Referer 防盗链只能作为浏览器辅助策略，不能替代签名 URL 或成员授权；不要要求原生 App 提供 Referer。
- `complete` 仅负责对象存在性与会话收口；异步校验任务必须流式复算 SHA-256、校验文件魔数。图片还要校验可解码性、像素上限和 EXIF 清洗，音视频校验容器、时长、编解码白名单。可执行、HTML、SVG、脚本和非白名单内容必须拒绝。
- 病毒扫描、视频转码、封面和缩略图由异步任务处理。衍生对象必须有自己的 `objectId` 并以引用关系关联原对象。
- 任何文件访问、初始化、完成、删除和拒绝事件都记录 `requestId`、`objectId`、操作用户、大小、耗时、结果；日志不得包含签名 URL、访问密钥或原始二进制。
- 存储生命周期负责回收临时目录；服务端定时任务补偿会话超时、OSS 回调失败及孤儿对象。删除先逻辑删除，再在无引用和保留期届满后物理删除。
