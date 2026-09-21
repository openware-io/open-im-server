# 媒体上传改造：App

> 方案集：`MEDIA_UPLOAD`；序号：02；服务端前置：[MEDIA_UPLOAD_01_SERVICE](MEDIA_UPLOAD_01_SERVICE.md)；PC 后台并行方案：[MEDIA_UPLOAD_03_ADMIN](MEDIA_UPLOAD_03_ADMIN.md)。
>
> 长期规范：[平台媒体对象与私有访问工程规范](../standards/13_PLATFORM_MEDIA_CONVENTIONS.md)。本方案只说明 Flutter App 的改造与验收。

## 目标与边界

App 必须迁移至“申请上传会话 → 直传私有对象存储 → 完成确认 → 等待 `ACTIVE` → 业务只提交 `objectId`”。不得调用已移除的 `/upload`，不得把对象存储 Key、永久 URL 或签名 URL 写入消息、资料、预约单或本地业务草稿。

签名 URL 只用于当前内存中的上传或展示；过期后重新请求访问 URL。业务数据只保存媒体对象标识 `objectId`，由领域服务绑定引用并做授权。

## 业务接入矩阵

| 业务 | 上传参数 | 业务提交字段 | 展示方式 |
| --- | --- | --- | --- |
| 用户头像 | `scope=avatar`，`mediaKind=image` | 资料更新请求的 `avatar` = `objectId` | 用户资料/会话资料接口返回短期 `avatarUrl` |
| 聊天图片 | `scope=chat`，`mediaKind=image` | 消息协议 `mediaObjectIds` | 消息响应中的媒体 URL，过期后访问接口续签 |
| 语音 | `scope=chat`，`mediaKind=audio`，带 `durationMs` | 消息协议 `mediaObjectIds` | 播放器只使用临时 URL |
| 视频 | `scope=chat`，`mediaKind=video`，带 `durationMs` | 消息协议 `mediaObjectIds` | 视频/封面对象均以 objectId 引用 |
| 聊天附件 | `scope=chat`，`mediaKind=attachment` | 消息协议 `mediaObjectIds` | 文件卡片使用临时 URL 下载 |
| 预约凭证 | `scope=reservation`，`mediaKind=voucher` | 创建预约请求的 `voucherObjectId` | 预约详情接口返回 `voucherUrl` |

`avatar` 是对象标识而不是 URL。聊天消息的文本 `content` 不再拼接媒体 JSON 或 URL；消息类型与 `mediaObjectIds` 分别发送。预约没有凭证时不传 `voucherObjectId`。

## 上传接口与交互

### 单对象（不超过服务端分片阈值）

1. 客户端预检 MIME、大小、SHA-256；音视频同时取时长 `durationMs`。预检只改善体验，服务端校验为准。
2. `POST /media/upload-sessions`，请求头 `Idempotency-Key: UUID`，请求体：

```json
{
  "scope": "chat",
  "mediaKind": "audio",
  "fileName": "voice.m4a",
  "contentType": "audio/mp4",
  "size": 46284,
  "sha256": "64 位小写十六进制",
  "durationMs": 3200
}
```

3. 响应包含 `uploadSessionId`、`objectId`、`uploadUrl`、`requiredHeaders`、`expiresAt`。按原样以 `PUT` 上传；对象存储请求不得添加 App 的 `Authorization`、Cookie 或自定义密钥。
4. `POST /media/upload-sessions/{uploadSessionId}/complete`，同样带新的 `Idempotency-Key`，请求体 `{ "size": 46284, "sha256": "..." }`。
5. 轮询 `GET /media/{objectId}`，仅在 `status=active` 时允许提交业务；`rejected`、`deleted` 或超时均显示失败。

### 分片（超过服务端阈值）

1. 以同一请求体调用 `POST /media/multipart-upload-sessions`，响应返回 `uploadSessionId`、`objectId`、`partSize`、`partCount`、`expiresAt`。
2. 按批请求 `POST /media/multipart-upload-sessions/{sessionId}/parts/signatures`，请求体 `{ "partNumbers": [1,2] }`；逐片 `PUT` 返回的 `partUrls`。
3. 每个成功 PUT 必须读取对象存储响应 `ETag`，调用 `POST /media/multipart-upload-sessions/{sessionId}/parts/{partNumber}/complete`，请求体 `{ "etag": "..." }`。对象存储 CORS 必须暴露 `ETag`。
4. 调用 `POST /media/multipart-upload-sessions/{sessionId}/complete`：

```json
{
  "parts": [{ "partNumber": 1, "etag": "..." }],
  "size": 31457280,
  "sha256": "64 位小写十六进制"
}
```

5. 网络中断后先 `GET /media/multipart-upload-sessions/{sessionId}`，服务端返回已确认的 `uploadedParts`（含 ETag），只续传未完成分片。取消使用 `DELETE /media/multipart-upload-sessions/{sessionId}`；会话过期则新建会话后重传。

## Flutter 改造范围

| 位置 | 必须改造 |
| --- | --- |
| `lib/im-services/im_api.dart` | 删除旧 `POST /upload` 代码；增加上传会话、单 PUT、Multipart、complete、状态/访问 URL 查询 DTO。 |
| 媒体上传仓库 | 统一任务状态：`preparing`、`uploading`、`completing`、`processing`、`ready`、`failed`、`cancelled`；保存本地文件安全引用、会话 ID、SHA-256、已确认分片，绝不保存签名 URL。 |
| `profile_edit_screen.dart` | 使用 `avatar/image` 上传并等待 `ACTIVE`；资料更新只传 `avatar: objectId`。 |
| `chat_room_screen.dart` 与录音/选取器 | 图片、音频、视频、附件改用统一仓库；发送协议传 `mediaObjectIds`，不把 URL 组装到 `content`。 |
| 预约创建/详情页面 | 增加凭证选择、`reservation/voucher` 上传、创建时提交 `voucherObjectId`；详情展示服务端返回的 `voucherUrl`，过期后重新取详情。 |

媒体预处理规则：修正图片方向、移除 GPS EXIF、生成缩略图；语音过滤空录音并优先 `audio/mp4`；视频统一 MP4/H.264/AAC 并生成封面。大文件不能整体读入内存，Hash、分片和预处理应在 isolate/后台任务中执行；移动端被系统终止后只能在下次打开界面恢复，不承诺后台持续上传。

## 验收

- 头像更新后资料接口保存且仅保存 `objectId`，展示 URL 过期后仍能重新获取。
- 图片、语音、视频、附件均经会话直传成功；服务端接收的消息包含 `mediaObjectIds`，`content` 不含 URL。
- 预约凭证创建、详情展示、无权限访问与 URL 过期重新获取均正确。
- 单对象可取消、重试；大文件可中断后续传，分片 ETag 缺失时明确失败而不提交 complete。
- 抓包确认对象存储 PUT 不携带业务 JWT，App 与业务数据库均无签名 URL 持久化。
