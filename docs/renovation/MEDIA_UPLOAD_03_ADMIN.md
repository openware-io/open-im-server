# 媒体上传改造：PC 管理后台

> 方案集：`MEDIA_UPLOAD`；序号：03；服务端前置：[MEDIA_UPLOAD_01_SERVICE](MEDIA_UPLOAD_01_SERVICE.md)；App 并行方案：[MEDIA_UPLOAD_02_APP](MEDIA_UPLOAD_02_APP.md)。
>
> 长期规范：[平台媒体对象与私有访问工程规范](../standards/13_PLATFORM_MEDIA_CONVENTIONS.md)。本方案仅覆盖 PC 管理后台已有业务和新业务接入准则。

## 实施状态

| 项目 | 状态 | 实现位置 |
| --- | --- | --- |
| 通用单对象直传、SHA-256、进度、完成与 ACTIVE 等待 | 已实现 | `src/api/media.js` |
| 原生 Multipart、分片 ETag、取消、同页恢复、重试 | 已实现 | `src/api/media.js` |
| 小程序服务图标保存 objectId、按需取访问 URL | 已实现 | `src/views/miniapp/service-manage.vue` |
| 预约订单凭证查看/下载 | 已实现 | `src/views/reservation/orders.vue` |
| 旧 `/upload` 前端入口 | 已移除 | 不得重新创建 `src/api/upload.js` |
| 合同、报表、后台头像/Banner、贴纸、聊天审查 UI | 未建设对应领域页面/接口 | 后续业务创建时按本方案接入，不能伪称已交付 |

预约服务类型的 `icon` 当前是既有文本字段，尚无对象标识字段和领域媒体绑定接口；在 Order 服务完成该领域契约前，后台不将临时 URL 写入该字段，也不把它误改为通用上传入口。

## 已实现的通用客户端能力

页面必须使用 `uploadMedia(file, { scope, mediaKind, onProgress })` 或 `createMediaUploadTask(...)`，不能手写 `FormData` 调用 `/upload`。任务提供：

```js
const task = createMediaUploadTask(file, { scope: 'system', mediaKind: 'image', onProgress })
const { objectId } = await task.start()
await task.cancel()
await task.retry()
await task.resume()
```

上传不超过 20 MiB 时走 `POST /media/upload-sessions` → 对象存储 `PUT` → `POST complete` → 等待 `ACTIVE`。超过阈值自动改用 `/media/multipart-upload-sessions`：取得签名、逐片 PUT、从响应读取 ETag、确认每片并最终 complete。`resume()` 先读取会话状态和已确认的 `uploadedParts`（含 ETag），只传剩余分片；当前实现保证同页中断续传，浏览器刷新或关闭后新建会话重传，不能将签名 URL 或文件内容写入 localStorage。

业务表单、组件状态和后端领域表只保存 `objectId`。`getMediaAccessUrl(objectId)` 的结果只可存在当前页面内存；不能存储对象 Key、永久 URL 或签名 URL，也不能把 URL 放进导出、富文本、日志或复制链接。

## 业务交互

| 场景 | 上传参数 | 领域提交/读取 |
| --- | --- | --- |
| 小程序服务图标 | `scope=system`，`mediaKind=image` | 服务表单提交 `icon: objectId`；列表按 objectId 获取临时 URL。 |
| 预约凭证审核 | 无后台上传入口 | 管理员读取订单详情中的 `voucherUrl`，只在当前窗口查看或下载。凭证由 App 在创建预约时提交 `voucherObjectId`。 |
| 后续系统图片素材 | `scope=system`，`mediaKind=image` | 新领域 API 必须提供 `...ObjectId` 字段和服务端媒体引用绑定。 |
| 后续合同/报表附件 | `scope=system`，`mediaKind=attachment` | 仅在 `ACTIVE` 后提交 `objectId`；下载走领域授权后的短期 URL。 |
| 后续后台头像/Banner | `scope=avatar`/`system`，`mediaKind=image` | 表单只传 `objectId`，领域服务负责校验及绑定。 |

对象存储的生产 CORS 仅允许后台正式 Origin 和必要方法/请求头，并必须暴露 `ETag`，否则浏览器不能安全完成分片。直传 PUT 不得携带业务 JWT、Cookie、云存储密钥或额外的认证 Header。

## 接入与验收

新增 PC 业务页面时，先确认领域服务具备：对象 ID 字段、提交时 `ACTIVE`/scope/所有者校验、媒体引用绑定、详情中的授权访问 URL。缺少任一项不得只改前端上传控件。

- 小程序图标上传后，接口表单仅提交 objectId；刷新列表后仍由按需 URL 展示。
- 预约详情有凭证显示“查看或下载凭证”；无凭证显示 `-`；URL 不写入状态管理或本地存储。
- 单文件、超过 20 MiB 的分片文件均能完成；每个分片确认包含 ETag；取消、重试、续传行为正确。
- `rg "/upload" src` 不应存在旧上传调用；构建通过。
