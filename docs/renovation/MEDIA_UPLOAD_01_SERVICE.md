# 媒体上传直传改造：服务端

> 方案集：`MEDIA_UPLOAD`；顺序：01；后续：[App](MEDIA_UPLOAD_02_APP.md)、[PC 管理后台](MEDIA_UPLOAD_03_ADMIN.md)。
>
> 长期规则：[平台媒体对象与私有访问工程规范](../standards/13_PLATFORM_MEDIA_CONVENTIONS.md)。

## 目标与边界

将代理上传替换为受控直传：客户端只经 API 获取上传会话和读取授权，文件 PUT/GET 直连 OSS、COS 或 MinIO。媒体能力由独立、可部署的 `common-media-service` 中的 media module 权威拥有；Gateway 只路由 `/api/v1/media/**` 控制面请求，不代理对象字节流。

```text
客户端 -> POST upload session -> Gateway -> common-media-service/media
客户端 <- short-lived PUT URL -- Gateway <- common-media-service/media
客户端 -> PUT binary --------------------> Object storage
客户端 -> POST complete ------> Gateway -> common-media-service/media
读取方 -> GET access URL -----> Gateway -> common-media-service/media
读取方 -> GET binary --------------------> Object storage
```

本次发布是原子协议切换：删除 Conversation 服务的 `POST /upload` 和 Gateway 的 `/api/v1/upload/**` 路由；App、PC 后台及所有业务绑定在同一发布窗口切换到上传会话 API，不保留双轨上传。

## 业务支撑服务边界

`common-media-service` 是可部署的跨域能力服务，不是所有“暂时无处安放”代码的容器。媒体模块适合归属其中，因为头像、聊天、预约凭证、合同和后台附件共享同一套对象生命周期、扫描、临时授权、审计和存储抽象。

| 支撑服务负责 | 业务领域负责 |
| --- | --- |
| 上传会话、对象存储、扫描、缩略图、对象状态、短期签名 URL、对象审计、通用引用登记。 | 用户能否修改头像、聊天成员资格、预约凭证可见性、合同/报表权限及业务对象生命周期。 |
| 校验对象是否 ACTIVE、上传者是否匹配、引用是否存在。 | 以内部授权契约判断当前用户能否访问某个会话/预约/合同引用。 |

未来通用能力只有同时满足“至少两个领域复用”“生命周期和权限模型稳定”“不需要读取其他领域权威表”时才可进入该服务，例如文件媒体、通知模板、通用审计附件。积分、订单、群成员、预约规则等领域业务不得迁入。每新增模块需独立 package、Flyway 前缀、OpenAPI tag、配置前缀和限流指标，避免形成单体式“万能支撑服务”。

## 实施工作

### 服务落位

新增 `common-services/media/common-media-api` 与 `common-services/media/common-media-service`，前者仅发布跨服务 DTO/内部授权契约，后者拥有媒体模块、Flyway、对象存储配置和 REST Controller。根 `im-services/pom.xml` 注册 `support` 聚合模块；Gateway 增加 `IM_SUPPORT_URI`，将 `/api/v1/media/**` 路由到 `common-media-service`。在同一变更中移除 Conversation 服务内的媒体 Controller、存储实现和 `/upload` 路由。

### 存储适配器

在 `common-media-service` 的 media module 新增 provider 无关端口，包含：签发固定对象的 PUT/GET URL、HEAD、复制临时对象到最终 Key、删除、Multipart 初始化/签发 Part/完成/中止、回调签名验证。MinIO、OSS、COS 分别实现适配器；业务 Controller 和其他业务服务不依赖厂商 SDK。

部署配置使用通用键：`MEDIA_PROVIDER`、`MEDIA_INTERNAL_ENDPOINT`、`MEDIA_PUBLIC_BASE_URL`、`MEDIA_PRIVATE_BUCKET`、`MEDIA_REGION`、`MEDIA_ACCESS_KEY`、`MEDIA_SECRET_KEY`、各媒体类别大小/时长/像素上限及浏览器 CORS Origin 白名单。内部 Endpoint 绝不下发给客户端；对外媒体地址必须可被 App 和浏览器访问。Bucket 始终私有，`MEDIA_PUBLIC_BASE_URL` 仅用于生成客户端可访问的受签名地址，不代表公开读。

首次部署的变更清单：新增 support 聚合、媒体模块配置/Mapper/Flyway/异步任务/内部 DTO；新增 Gateway `IM_SUPPORT_URI`、`/api/v1/media/**` 路由和 Support OpenAPI 聚合；新增 Compose 服务、健康检查和媒体环境变量；删除 Conversation 的上传 Controller、媒体配置、存储适配器、Mapper/实体、`/api/v1/upload/**` 与 `/api/v1/media/**` 路由。App 与 PC 源码不在本仓库，其改造按 02、03 方案交付后才能进行集成验收。

### 数据与状态机

在 `common-media-service` 新增 Flyway 迁移，禁止修改已执行迁移。创建 `support_media_object`、`support_media_upload_session` 和媒体引用表，并建立会话、过期清理及业务引用唯一索引。

当前系统尚未上线且不存在需要保留的媒体数据，因此不执行跨服务数据迁移，也不保留 Conversation 的媒体表定义。媒体对象和上传会话从首次部署起仅写入 `support_media_object` 与 `support_media_upload_session`；Conversation 不拥有媒体持久化表、Mapper 或存储适配器。

媒体对象状态使用 `PENDING -> UPLOADED -> SCANNING -> ACTIVE`，异常终态为 `REJECTED`、`DELETED`；上传会话独立使用 `UPLOADING -> COMPLETED`，异常终态为 `CANCELLED`、`EXPIRED`。`complete` 只做 HEAD 与会话收口，异步校验任务流式复算 SHA-256、校验魔数并在通过后复制最终 Key、激活对象。对象进入 `ACTIVE` 前不允许绑定消息、预约或后台资源，也不允许签发 GET URL。

### 控制面接口

`common-media-service` 的 media module 实现并发布以下 OpenAPI；Gateway 将 `/api/v1/media/**` 路由到该服务：

| 接口 | 责任 |
| --- | --- |
| `POST /api/v1/media/upload-sessions` | 验证用户、scope、类型、大小、Hash、配额和幂等键；返回固定临时对象的 PUT URL。 |
| `POST /api/v1/media/upload-sessions/{id}/complete` | HEAD 校验并收口会话；投递异步校验、扫描、复制和激活任务。 |
| `GET/DELETE /api/v1/media/upload-sessions/{id}` | 恢复状态或取消未激活会话。 |
| `GET /api/v1/media/{objectId}` | 查询经授权的媒体激活状态与经校验元数据。 |
| `GET /api/v1/media/{objectId}/access` | 对一个 ACTIVE 对象签发短期 GET URL。 |
| `POST /api/v1/media/access-urls` | 为最多 100 个已授权对象批量签发读取 URL。 |
| `POST /api/v1/media/multipart-upload-sessions` | 大于阈值文件的分片初始化；并实现 Part 签名、完成和取消子路径。 |

所有写请求都校验 `Idempotency-Key`。完整字段、响应、错误码、PUT Header 和状态语义以媒体工程规范第 5 节为准；实施时必须同步更新 Media OpenAPI 快照。

`complete` 必须按“会话归属 -> HEAD -> size/content-type/受限元数据 -> 会话完成和对象待校验 -> 审计”顺序执行，并保证重复调用安全。异步任务按“流式 Hash/魔数与媒体校验 -> 复制最终 Key -> 激活或拒绝 -> 删除临时对象 -> 审计”执行。对象存储回调只补偿上传会话，不能创建消息或业务引用。

### 业务授权

消息服务发送带媒体的 `chat:send` 前，调用内部媒体授权接口，校验对象处于 `ACTIVE`、属于发送者、类型匹配且未被非法绑定；消息持久化后通过 Outbox 调用内部绑定接口登记 `support_media_reference`。预约、用户和后台领域同样只能经内部契约绑定或解绑媒体，客户端不得直接操作引用关系。

访问私有对象时，媒体服务根据引用关系允许上传者、管理员、或该对象所属单聊/群聊的有效成员获取 URL。预约凭证、合同和后台附件按绑定业务实体及后台权限重新授权；知道 `objectId` 不能替代授权。

### 异步与运维

- 扫描任务流式复算 SHA-256，并校验魔数、图片解码/像素、EXIF、音视频容器、时长、编解码和病毒；视频转码、封面、缩略图后续异步生成。
- 定时任务清理过期会话、未完成分片、孤儿临时对象及无引用的逻辑删除对象。
- Gateway 为 init、complete、access 分别限流；浏览器 Origin 使用明确 CORS 白名单。
- 监控 init/PUT/complete 成功率、扫描拒绝率、签发 URL、403、会话过期、孤儿对象、清理滞后和存储成本；日志脱敏签名 URL 与密钥。

## 分阶段与验收

| 阶段 | 交付 | 放行条件 |
| --- | --- | --- |
| S1 | 单对象会话、MinIO 适配器、幂等持久化、PUT/complete/status/access。 | 图片直传、签名限制、幂等和越权测试通过。 |
| S2 | 消息/预约/后台引用、成员授权、异步扫描、清理。 | 接收方续签、删除、扫描拒绝和审计通过。 |
| S3 | OSS/COS 适配器、Multipart、媒体处理和告警。 | 断网 Part 重试、合并、成本和回滚演练通过。 |
| S4 | 首次部署切换发布。 | App、PC、Gateway、业务绑定与媒体服务验收同时通过；不得保留旧上传路由。 |

实施变更必须同步：Flyway、实体/Mapper、存储端口、Controller、OpenAPI、Gateway 限流/CORS、部署模板、消息内部契约、Outbox、单元/集成/E2E 测试。客户端实施见后续两份方案，不能在服务端方案中替代其端侧验收。
