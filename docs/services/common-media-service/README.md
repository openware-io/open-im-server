# common-media-service

## 职责

- 提供通用媒体上传会话、分片上传和完成确认
- 维护媒体对象元数据、状态机和对象存储适配
- 为经领域授权的调用方签发私有媒体短期访问地址
- 提供业务无关的公共图片能力（内部接口 `POST /internal/media/images`）：校验类型/大小后写入匿名只读公开桶并返回可直接引用的公开 URL
- 登记并校验媒体对象与头像、消息、预约凭证、后台附件等业务实体的引用关系

## 当前接口

- 网关公开控制面：`/api/media/**`
- 服务内部调用：`/internal/media/**`
- 业务无关的内部图片上传：`POST /internal/media/images`（平台内部服务 HMAC 鉴权，不要求用户态）；
  图片字节走请求体、`Content-Type` 为图片 MIME、`biz`/`scope` 走查询参数；响应 `{url, objectKey, bucket, contentType, size}`，
  `url` 形态 `{media.public-url-prefix}/{media.public-bucket}/{objectKey}`（默认 `/api/v1/media-public/gv-media-public/...`，
  走网关 `/api` 命名空间，由网关 StripPrefix 转发到对象存储，不依赖入口 rewrite）
- 文件二进制不经过网关或业务服务，客户端使用服务端下发的短期签名地址直连对象存储

## 约束

- 本服务是平台通用支撑服务，不归属于会话、订单或用户等单一业务域
- 业务服务只保存稳定的 `objectId` 并通过内部契约登记引用，不保存签名 URL 或对象存储访问密钥
- 存储提供方通过适配器支持 MinIO、OSS、COS；`media.public-bucket`（默认 `gv-media-public`）为匿名只读桶，
  只承载对象键含不可猜测 UUID 的公共图片，敏感对象仍需按授权短期签发访问地址
- 公共图片接口不写媒体归属/引用表：业务行自行保存返回的 URL，引用与清理由业务服务负责
- 上传、读取和对象键规范以 [`13_PLATFORM_MEDIA_CONVENTIONS.md`](../../standards/13_PLATFORM_MEDIA_CONVENTIONS.md) 为准
