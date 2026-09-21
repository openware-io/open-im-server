# 重构后端与 PC 管理后台首发联调方案

> 状态：待实施
>
> 适用范围：`gv_im_server`、`D:\projects\cnb\gv_chat_admin`
>
> 最后核查：2026-07-31
>
> 媒体上传、读取 URL 与存储配置章节已由 [MEDIA_UPLOAD_03_ADMIN](MEDIA_UPLOAD_03_ADMIN.md) 和[平台媒体对象与私有访问工程规范](../standards/13_PLATFORM_MEDIA_CONVENTIONS.md)取代；本文件中与代理上传、公共 Bucket、稳定媒体 URL 或旧 `IM_MEDIA_*` 配置有关的描述不再作为实施依据。

## 1. 目标与结论

本项目尚未上线。本方案定义 PC 管理后台新分支与重构后微服务的**正式工程联调和长期演进基线**。新分支直接对接 Gateway 的 v1 契约；旧 NestJS 单体、Vite 对接方案、Socket.IO 和历史数据兼容通道均不属于运行时架构，后续统一下线。

结论如下：

1. PC 管理后台新分支必须按首发正式契约改造并直接联调；旧后台 API 和 Vite 方案仅作为需求与页面行为的盘点来源，不是运行时依赖，也不提供迁移或过渡实现。
2. 新后端必须补齐其声明为对外能力、但当前尚未具备的接口和实时能力；不能用前端的字段兼容代码掩盖服务端能力缺失。
3. `gateway` 只做路由、鉴权透传、审计等横切职责，不能承载旧接口字段转换、跨服务编排或业务逻辑。
4. 现在即冻结第一版版本化外部契约：REST 使用 `/api/v1/**`，实时使用 `/ws/im/v1`；以后的破坏性变化通过新版本并行演进。
5. 旧单体只作为需求、数据模型和测试场景的参考代码；首发环境不部署它，也不建设适配器或双写链路。
6. 不兼容的是旧 Vite 对接和旧接口实现，不是管理业务。现有后台的页面交互、查询/写入结果、权限边界、审批与审计语义、异常反馈必须在 v1 中等价实现；接口重命名或 DTO 重构不能成为移除既有业务的理由。

## 2. 现状基线与已知差异

### 2.1 北向入口

当前工程已经定义了统一北向入口：HTTP 为 `http://localhost:3002/api/**`，原生 WebSocket 为 `ws://localhost:3002/ws/im`。由于尚未上线，首发前应一次性调整为 HTTP `http://localhost:3002/api/v1/**`、WebSocket `ws://localhost:3002/ws/im/v1`；Gateway 将版本前缀路由到用户、消息、会话、管理和预约等服务，服务内部 Controller 不携带外部版本前缀。

PC 管理后台新分支以 Gateway 为唯一北向入口：本地联调直接配置 Gateway 地址，部署环境通过受管环境配置注入 Gateway 域名。浏览器不经 Vite 开发代理重写请求，也不访问旧后端或 `/socket.io`；本地跨域需求由 Gateway 的 CORS 策略按正式调用方式处理。

### 2.2 管理后台接口差异

下表是代码静态核对后的首批改造矩阵。完整的逐接口请求字段、响应字段、状态码、权限和 E2E 编号以第 6 节“PC 管理后台 REST v1 契约与验收矩阵”为唯一实施输入。矩阵中“后端补齐”代表首发阻塞项，而非允许删除既有页面或交互；只有产品另行书面确认废弃的业务才能移出范围。

| 旧后台调用 | 新服务现状 | 处理决策 |
| --- | --- | --- |
| `/auth/login` | 用户服务已有登录 | 保持路径语义；后台改为校验返回 token、角色和错误码是否符合 v1 契约 |
| `/admin/users/**` | 管理服务已有用户列表、详情、状态更新 | 后台改 DTO 和分页适配；保留管理员 403 验证 |
| `/admin/messages` | 管理服务已有消息列表 | 本次完成列表、筛选和分页 DTO 适配；消息趋势统计列入后续专项治理，不阻断本次正式工程接入 |
| `/admin/statistics/**` | 新接口为 `/admin/stats/overview`、`/admin/stats/trend` | 后台改路由与图表模型；不要在浏览器拼接跨服务数据 |
| `/admin/monitor/online/count`、`/online/users`、`/calls`、`/server` | 当前仅有 `/admin/monitor/online` | 本次仅接入已有在线监控摘要；用户明细、通话、服务详情列入后续受控可观测性专项，不直连内部端口 |
| `/admin/server/**` | 当前无对应管理 API | 后续建设受控可观测性专项；本次不暴露服务内部 Actuator、日志文件或节点地址 |
| `/admin/config/batch` 的 `PUT` | 当前是 `POST /admin/config/batch` | 后台按首发 v1 契约统一为 `POST`；不保留旧方法映射 |
| `/admin/points/accounts/:id`、`credits`、`debits` | 新接口为 `balance`、`credit`、`debit` | 后台改路径和请求/响应字段；兼容层不得伪造余额结果 |
| `/admin/device-tokens` 列表、删除 | 当前按用户查询的能力不等价 | 后端补齐管理用查询/删除；不能错误复用用户端接口或移除既有管理交互 |
| `/admin/groups/**` | 当前无完整后台群管理 API | 由会话域提供经管理域授权的稳定接口/投影，不能让后台直连内部 Controller |
| `/admin/content/**`、`/admin/security/keywords` | 新模型使用敏感词、违规、举报 | 统一到 `/admin/security/**`；补齐举报列表、待处理数、检测配置等真正需要的能力 |
| `/admin/app-releases/:id` 详情、删除 | 当前仅列表、创建、更新 | 后端补齐详情/删除；不得将“未实现”伪装为成功或删除既有操作 |
| MiniApp 单项、无分页、批量排序 | 新接口要求 `typeId`，排序为 `POST /sort` | 后台按新能力改页面交互；不保留 `PUT /sort/batch` 的转换或兼容逻辑 |
| `/admin/reservations/**` | 新管理服务已承接同类路由 | 逐项比对 DTO、枚举和审批审计字段后迁移 |
| `/upload`、`/uploads/**` | 上传 Controller 存在；尚未接入 OSS | 后端完成 OSS 和受管媒体域名，后台直接使用服务端返回的绝对 URL |

### 2.3 不能忽略的架构风险

- 管理端目前的接口按旧单体的数据模型编写，路径相同不代表字段、分页、枚举和权限相同。
- 新管理服务的用户、消息和会话读模型是投影，可能存在可预期的最终一致延迟；后台要展示数据更新时间和刷新语义，不能把投影当作强一致写模型。
- 当前上传实现写入服务本地 `uploads/` 目录。多副本部署时，本地磁盘既不能跨实例读取，也不能作为稳定 CDN 源站。
- 管理端的 401/403 行为必须保留：401 清除会话并回登录页；403 明确提示权限不足。普通用户访问 `/api/admin/**` 不得仅返回前端隐藏菜单。

## 3. 目标接入架构与职责边界

```text
PC Admin v1 ────┐
                │ HTTPS / WSS
App v1 ─────────┼──> Gateway ──> 各领域微服务 / im-access-ws
                │
运维与 CI ──────┘       （唯一北向入口）
```

### 3.1 Gateway

Gateway 只负责：路由、TLS、CORS、鉴权头透传、限流、灰度路由、请求关联 ID、访问审计和统一错误边界。不得在其中加入：

- Controller 级业务逻辑；
- 对多个领域服务的同步编排；
- 旧字段到新字段的业务解释；
- 数据库、Redis 业务状态或 MQ 权威写入。

### 3.2 新分支不设 Vite 或旧后端兼容层

新分支和首发环境不创建 `legacy-client-adapter`，不配置 Vite 请求代理或 `/socket.io/**`，也不保留旧 `/api/**` 无版本路由。后台以受管配置中的 Gateway 基地址直接构造 `/api/v1/**` 请求；这样能避免把临时协议、错误码和数据模型带入正式架构。

如未来确实需要支持破坏性变更，才基于已冻结的 v1 契约新增 `/api/v2/**` 或 `/ws/im/v2`。是否需要并行消费、弃用日期和消费者清单必须在当时单独评审；当前新分支不得预埋双协议、双地址或过渡代码。

## 4. 契约版本策略

### 4.1 推荐路由策略

首发即使用明确版本边界：

| 通道 | 外部路径 | 消费者 | 生命周期 |
| --- | --- | --- | --- |
| REST v1 | `/api/v1/**` | PC 管理后台与 App | 首发正式长期契约 |
| 实时 v1 | `/ws/im/v1` | App | 首发正式长期契约 |

客户端还应发送 `X-Client-Contract: im-v1`、应用版本和平台信息。它们用于审计、灰度和未来弃用统计，不得作为授权依据。

### 4.2 契约资产与变更规则

1. REST：由各服务的 OpenAPI 文档聚合生成发布快照；请求/响应 DTO、错误码、分页与枚举均纳入版本审查。
2. WebSocket：在 `protocol-ws` 中维护事件常量、JSON Schema、上行/下行方向、权限、幂等键、顺序保证和错误码；同时发布人可读的 AsyncAPI 或等价文档。
3. 前端不得依赖 Java DTO、数据库表或内部 `/internal/**` 接口。必要时从 OpenAPI/JSON Schema 生成 TypeScript/Dart 客户端，再在前端保留界面模型适配层。
4. 任何删除字段、改变枚举含义、改变错误码或修改幂等语义，都必须升版本；当前新分支只消费 v1，不实现多版本并行或迁移窗口。

### 4.3 v1 路由、媒体与配置的固定实现

Gateway 必须将所有现有北向 REST 路由改为 `Path=/api/v1/<domain>/**` 并使用 `StripPrefix=2`，使领域服务继续接收其无版本 Controller 路径；删除 `/api/**` 路由。WebSocket Gateway 路由和 `im-access-ws` 处理器统一改为 `/ws/im/v1`，删除 `/ws/im`。路由改造须附带 Gateway 路由测试，覆盖全部领域前缀、404、401、403、OPTIONS/CORS 与 WebSocket Upgrade。

用户域实现 `POST /auth/ws-ticket`，由 Gateway 以 `/api/v1/auth/ws-ticket` 对外发布。该接口仅接受已认证用户，签发有效期不超过 60 秒、一次消费的 ticket，并在 Redis 等共享存储中保存 ticket 到用户、认证版本和过期时间的映射；`im-access-ws` 握手时原子消费并校验该映射。签发、过期、重复消费、账号切换和权限失效均须有契约与集成测试，日志不得记录 ticket 原值。

生产与正式上线媒体固定使用 OSS 和独立受管媒体域名；上传服务经 OSS 适配器写入 Bucket 后只返回 `{ "objectId": "uuid", "url": "https://...", "scope": "public|private", "expiresAt": "RFC3339?" }`，不返回服务本地路径。私有媒体固定通过有效期 15 分钟的 OSS 签名 URL 访问，过期后由 `GET /media/{objectId}/access` 重新获取；缓存、Content-Type、文件大小、文件类型、病毒扫描和删除语义纳入 OpenAPI。Gateway 不代理本地 `uploads/` 目录，开发 profile 使用与生产相同 URL/权限语义的 OSS 模拟实现。

PC 管理后台只使用 REST。对在线监控等需持续刷新的既有页面，矩阵必须明确轮询接口、刷新周期、手动刷新行为、数据时点和读模型延迟提示；未经单独契约定义，不引入后台 WebSocket、SSE 或用户端实时事件。

## 5. 后端实施工作包

### WP-1：定义并冻结首发契约矩阵

PC 管理 REST 的当前冻结版本位于本方案第 6 节；修改任何表中路径、字段、错误码、权限或 E2E 编号时，必须同时更新对应 OpenAPI 快照和后台 API client。App REST 契约则以 App 方案第 5.4 节为唯一来源，两份矩阵不得互相替代。

- 从产品需求、旧后端实现和 PC 后台 `src/api/` 提取首发所需能力；旧代码只用于识别业务行为，不构成接口兼容承诺。
- 为每项定义：HTTP 方法、`/api/v1` 路径、请求字段、响应字段、错误码、认证角色、分页、幂等键、调用页面和负责人。
- 为每项补齐成功、401、403、参数错误、空列表、重复提交和最终一致延迟的请求/响应样例，并以页面操作流绑定 E2E 用例编号；没有负责人、样例或用例编号的条目不得进入实现。
- 标记为“首发实现”“后端需补齐”“产品书面确认废弃”三类；所有既有业务默认属于前两类，禁止以未定义的过渡路由或删除页面替代实现。
- 不以代码阅读的猜测替代联调样本；每项至少保留成功、401、403、参数错误和空列表样本。

### WP-2：补齐权威领域能力

- 用户域：管理员登录后的角色校验、设备令牌管理、积分管理，以及一次性 WebSocket ticket 的签发与失效能力。
- 消息域：本次完成管理查询和分页语义；趋势统计读模型列入后续专项治理。
- 会话域：后台群管理的受控用例；上传由 OSS 适配器负责，返回稳定媒体 URL。
- 管理域：本次完成已有摘要统计、在线监控、举报/敏感词/违规、版本发布、MiniApp、预约等正式接入；用户明细监控、通话和服务详情列入后续受控可观测性专项。
- 接入层：实时通知只通过版本化事件或内部授权接口委托领域服务；不在长连接接入层直接写 MySQL。

### WP-3：媒体与静态资源

1. 首发、预发布和正式生产环境均使用 OSS 与独立受管媒体域名，并按环境隔离 Bucket、Endpoint、Region 和访问凭据；本地磁盘不得作为生产或联调环境的媒体源站。
2. 服务端使用受管密钥访问 OSS，凭据不进入仓库、客户端或日志；私有媒体固定采用有效期 15 分钟的 OSS 签名 URL，公共媒体采用版本化 URL 与明确缓存策略；两类均校验 Content-Type、大小、文件类型、病毒扫描和删除语义。
3. 上传响应统一为 `{ "url": "...", "scope": "public|private" }`；不创建旧版适配器、不转换历史格式。
4. 管理后台和 App 均通过稳定媒体 URL 访问资源，禁止拼接内部服务端口或代理本地 `uploads/`。

### WP-3.1：OSS 多环境实施

媒体访问经统一 `MediaStoragePort` 抽象，业务代码不得直接依赖 MinIO 或生产 OSS SDK。上传 API 保持服务端接收文件、校验并写入 `MediaStoragePort` 的模式；如未来需要客户端直传，必须另行定义预签名上传契约、回调确认和未确认对象清理，首发不混用两种流程。

| 环境 | 存储实现 | Bucket 与域名 | 配置与启动 |
| --- | --- | --- | --- |
| 本地开发 | Docker MinIO（S3 兼容模拟） | 独立 `gv-im-local-public`、`gv-im-local-private` Bucket；本地媒体域名 | 在 `docker-compose.yml` 增加 `media-local` profile，提供 `minio`（API `9000`、仅本机控制台 `9001`）和 `minio-init` 服务；初始化服务创建 Bucket、设置 CORS 与生命周期规则 |
| 集成/预发布 | 真实 OSS | 与生产隔离的 Bucket、受管测试域名 | 由受管密钥注入；每次发布执行真实 OSS 冒烟测试 |
| 正式生产 | 真实 OSS | 独立生产 Bucket、HTTPS 媒体域名 | 仅部署平台注入密钥；客户端和仓库均不可获得 Bucket 写密钥 |

所有环境使用同一组配置键：`MEDIA_PROVIDER`、`MEDIA_INTERNAL_ENDPOINT`、`MEDIA_REGION`、`MEDIA_PRIVATE_BUCKET`、`MEDIA_PUBLIC_BASE_URL`、`MEDIA_ACCESS_KEY`、`MEDIA_SECRET_KEY`。前五项可按环境配置；后两项只来自本地未提交 `.env` 或部署密钥管理系统，严禁写入仓库、镜像、前端构建产物和日志。现有 Docker Compose 的 `uploads_data` 卷及服务内 `/app/uploads` 挂载在 OSS 接入完成后移除。

`minio-init` 必须幂等执行：创建两个 Bucket、禁止匿名写入、仅允许 public Bucket 的受控匿名读取、为 private Bucket 配置签名访问、配置前端域名 CORS、配置未完成 multipart 上传的过期清理。服务端在上传前校验文件类型、大小、内容嗅探、哈希和病毒扫描；对象键由服务端生成且不可包含用户输入路径。上传失败或数据库写入失败时清理孤儿对象，删除业务对象时异步删除 OSS 对象并记录可重试任务。

预发布真实 OSS 冒烟测试必须覆盖：公共/私有上传、病毒或类型拒绝、大小超限、签名 URL 过期、越权访问、删除、重试后的幂等、App 真机和后台浏览器预览、以及多服务实例下的可读性。只有该测试和本地 MinIO 集成测试均通过，媒体能力才可进入首发 E2E。

### WP-3.2：参数与配置规范

配置按优先级覆盖：受管部署密钥/环境变量 > 本地未跟踪 `.env` > 应用安全默认值。仓库只提交 `.env.example`，其中只包含变量名、非敏感示例值和注释；`.env`、`.env.local` 等本地文件必须被 Git 忽略。已被 Git 跟踪的 `.env` 必须停止跟踪并轮换其中出现过的所有密钥，不能仅增加忽略规则。

| 配置组 | 固定变量 | 规则 |
| --- | --- | --- |
| 环境与实现 | `IM_DEPLOY_ENV`、`MEDIA_PROVIDER` | 值固定为 `local|minio`、`integration|oss`、`staging|oss`、`production|oss`；启动时拒绝未知组合 |
| 连接与定位 | `MEDIA_INTERNAL_ENDPOINT`、`MEDIA_REGION`、`MEDIA_PUBLIC_BASE_URL` | Endpoint 仅供服务端访问；Public Base URL 是客户端可访问的 HTTPS/本地 LAN 地址，绝不返回 Docker 服务名或 `localhost` 给真机 |
| 容器 | `MEDIA_PRIVATE_BUCKET` | 命名固定为 `gv-im-${IM_DEPLOY_ENV}-private`；值为空时启动失败 |
| 凭据 | `MEDIA_ACCESS_KEY`、`MEDIA_SECRET_KEY` | 本地来自 `.env`，集成/预发布/生产来自密钥管理系统；禁止使用根账号、写入日志或传给客户端 |
| 本地 MinIO | `MINIO_API_PORT`、`MINIO_CONSOLE_PORT`、`MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD` | API 默认 `9000`，控制台默认 `9001` 且只绑定本机；Root 凭据仅用于初始化，不由业务服务使用 |
| 上传限制 | `MEDIA_MAX_IMAGE_BYTES`、`MEDIA_MAX_ATTACHMENT_BYTES`、`MEDIA_MAX_VIDEO_BYTES`、`MEDIA_ALLOWED_*_TYPES` | 首发固定为 10 MiB、50 MiB、200 MiB；MIME 固定为 `.env.example` 所列图片、附件、视频白名单。业务变更必须同步更新 OpenAPI、前端校验和安全测试 |

`docker-compose.yml` 的 `media-local` profile 使用 `.env` 注入 MinIO 与服务端参数；Compose 内服务端 Endpoint 固定为 `http://minio:9000`。`MEDIA_PUBLIC_BASE_URL` 必须由开发者设为本机 LAN 可访问地址，以保证浏览器、Android 真机和模拟器均能访问返回 URL；不可提交个人 IP。服务启动时校验所有必填变量、Bucket 区分、URL scheme、文件限制和 provider/环境组合，任一不合法即 fail-fast。

新电脑首次运行执行 `powershell -ExecutionPolicy Bypass -File .\scripts\initialize-local-env.ps1`；脚本从 `.env.example` 生成未跟踪 `.env`，为数据库、JWT、内部服务、文档和 MinIO 凭据生成随机本地值，并自动探测局域网媒体地址。`run-dev.ps1` 与 `deploy-local.ps1` 在 `.env` 缺失时调用该脚本。开发者须在真机联调前检查 `MEDIA_PUBLIC_BASE_URL` 是否为设备可访问的开发机地址；需要指定时传入 `-MediaHost <LAN-IP>`，不得把该 IP 提交到仓库。

### WP-4：安全与可观测性

- 管理路由统一使用 `ROLE_ADMIN`；内部 `/internal/admin/**` 只接受服务间认证，永不暴露给浏览器。
- 切换后强制重新登录；新令牌的签名、`authentication_version`、用户状态和角色都由新用户域校验。
- 日志记录 `requestId`、`userId`、`commandId`、`eventId` 等定位字段，不记录 token、密码和消息正文。
- 记录 v1 调用量、失败率、延迟、4xx/5xx、实时连接数、鉴权失败和客户端版本；为未来新增版本预留维度，但不实现双协议。

## 6. PC 管理后台 REST v1 契约与验收矩阵

> 状态：实施输入，首发前冻结
>
> 来源：`D:\projects\cnb\gv_chat_admin\src\api\`（2026-07-31 盘点）
>
> 排除范围：消息趋势、在线用户明细、通话监控、服务器/节点详情、日志检索；这些页面在本次新分支中不开放，后续专项另行冻结。

## 通用约束

所有路径以 Gateway 为根并带 `/api/v1` 前缀；后台操作均须 `Authorization: Bearer <token>` 和 `X-Client-Contract: im-v1`，且要求 `ROLE_ADMIN`。列表响应固定为 `{ "items": [], "page": 1, "pageSize": 20, "total": 0, "updatedAt": "RFC3339" }`；写操作携带 `Idempotency-Key` 并返回 `{ "data": {}, "requestId": "uuid" }` 或 `204`。错误响应固定为 `{ "code", "message", "requestId", "retryable", "fieldErrors" }`，通用错误码采用 App 方案第 5.4.1 节的定义；管理专属错误码以 `ADMIN_*`、`SECURITY_*`、`RESERVATION_*`、`POINTS_*` 命名并写入 OpenAPI。

每个 `E2E-*` 必测成功、401、403、参数错误、空数据；写操作另测重复提交、审计字段和投影延迟。列表筛选参数为空时不传递；所有时间均为 RFC3339，所有金额/积分为整数最小单位，不使用浮点数。

| 编号 | 页面与能力 | REST v1 方法与路径 | 冻结请求 / 成功数据 | E2E |
| --- | --- | --- | --- | --- |
| AD-01 | 管理员登录 | `POST /auth/login` | `{username,password}` -> `{user:{id,roles},accessToken,expiresAt}`；非管理员返回 `FORBIDDEN` | `E2E-ADMIN-AUTH-01` |
| AD-02 | 仪表盘摘要 | `GET /admin/stats/overview` | -> `{usersTotal,messagesTotal,groupsTotal,onlineCount,updatedAt}` | `E2E-ADMIN-STATS-01` |
| AD-03 | 用户/总体趋势 | `GET /admin/stats/trend?days` | `days: 7|30|90` -> `{points:[{date,newUsers,activeUsers?}],updatedAt}`；不承载消息趋势 | `E2E-ADMIN-STATS-02` |
| AD-04 | 用户列表与详情 | `GET /admin/users`、`GET /admin/users/{id}` | `page,pageSize,keyword?,status?` -> `AdminUser{id,username,nickname,status,roles,createdAt,updatedAt}` | `E2E-ADMIN-USER-01` |
| AD-05 | 用户状态 | `PUT /admin/users/{id}/status` | `{status:active|disabled}` -> `AdminUser`；禁止禁用最后一个管理员 | `E2E-ADMIN-USER-02` |
| AD-06 | 消息查询 | `GET /admin/messages` | `page,pageSize,keyword?,chatType?,fromUserId?,toId?,fromAt?,toAt?` -> `AdminMessage{msgId,chatType,fromUserId,toId,msgType,contentPreview,createdAt}` | `E2E-ADMIN-MSG-01` |
| AD-07 | 在线监控摘要 | `GET /admin/monitor/online` | -> `{onlineCount,updatedAt}`；不返回在线用户明细 | `E2E-ADMIN-MONITOR-01` |
| AD-08 | 好友查询 | `GET /admin/friends` | `page,pageSize,userId?,friendId?` -> `AdminFriendRelation{userId,friendId,status,createdAt}` | `E2E-ADMIN-FRIEND-01` |
| AD-09 | 群列表/成员/解散 | `GET /admin/groups`、`GET /admin/groups/{id}/members`、`DELETE /admin/groups/{id}` | 查询 `page,pageSize,keyword?`；解散需 `{reason}` -> `204`，记录审计 | `E2E-ADMIN-GROUP-01` |
| AD-10 | 全局配置 | `GET /admin/config`、`PUT /admin/config`、`POST /admin/config/batch` | 单项 `{key,value,version}`；批量 `{configs:[{key,value,version}]}`，版本冲突 `IDEMPOTENCY_CONFLICT` | `E2E-ADMIN-CONFIG-01` |
| AD-11 | 用户积分与流水 | `GET /admin/points/accounts/{userId}/balance`、`GET /admin/points/accounts/{userId}/ledger` | 流水 `page,pageSize` -> `PointLedger{entryType,amount,balanceAfter,reason,createdAt}` | `E2E-ADMIN-POINTS-01` |
| AD-12 | 积分加/扣 | `POST /admin/points/accounts/{userId}/credit`、`POST /admin/points/accounts/{userId}/debit` | `{amount,reason,commandId}` -> `PointBalance{available}`；扣减不足 `POINTS_INSUFFICIENT` | `E2E-ADMIN-POINTS-02` |
| AD-13 | 设备 token | `GET /admin/device-tokens`、`DELETE /admin/device-tokens/{id}` | `page,pageSize,userId?,platform?` -> `DeviceToken{id,userId,platform,provider,lastSeenAt}` | `E2E-ADMIN-PUSH-01` |
| AD-14 | 敏感词 | `GET/POST /admin/security/sensitive-words`、`PUT/DELETE /admin/security/sensitive-words/{id}` | `{word,category,level,enabled}` -> `SensitiveWord{id,word,category,level,enabled,updatedAt}` | `E2E-ADMIN-SECURITY-01` |
| AD-15 | 违规记录 | `GET/POST /admin/security/violations` | 查询 `page,pageSize,targetId?,status?`；创建 `{targetId,type,reason,evidenceObjectIds?}` -> `Violation` | `E2E-ADMIN-SECURITY-02` |
| AD-16 | 举报与待处理数 | `GET /admin/security/reports`、`PUT /admin/security/reports/{id}`、`GET /admin/security/reports/pending/count` | 处理 `{action:accept|reject,remark?}` -> `Report{id,status,handledBy,handledAt}` | `E2E-ADMIN-SECURITY-03` |
| AD-17 | 应用版本 | `GET/POST /admin/app-releases`、`GET/PUT/DELETE /admin/app-releases/{id}` | `{platform,version,buildNumber,forceUpdate,downloadUrl,releaseNotes,publishedAt?}` -> `AppRelease` | `E2E-ADMIN-RELEASE-01` |
| AD-18 | 小程序服务类型 | `GET/POST /admin/miniapp/service-types`、`PUT/DELETE /admin/miniapp/service-types/{id}`、`POST /admin/miniapp/service-types/sort` | `{name,sortOrder}`；排序 `{items:[{id,sortOrder}]}`；类型无 `icon`/`enabled` 字段 | `E2E-ADMIN-MINIAPP-01` |
| AD-19 | 小程序服务项 | `GET/POST /admin/miniapp/services`、`GET/PUT/DELETE /admin/miniapp/services/{id}` | 列表 `page,pageSize,typeId`（typeId 必填）；写 `{typeId,name,link,introduction?,icon?,status?,isTop?,sortOrder?}` -> `MiniappServiceItem` | `E2E-ADMIN-MINIAPP-02` |
| AD-20 | 预约订单与核销 | `GET /admin/reservations`、`GET /admin/reservations/{orderNo}`、`POST /admin/reservations/{orderNo}/verify` | 查询 `page,pageSize,status?,storeId?`；核销 `{operatorNote?,commandId}` -> `Reservation{orderNo,status,verifiedBy,verifiedAt}` | `E2E-ADMIN-RESERVATION-01` |
| AD-21 | 预约类型 | `GET/POST /admin/reservations/service-types`、`PUT /admin/reservations/service-types/{id}`、`PUT /admin/reservations/service-types/{id}/status` | `{name,durationMinutes,pointsCost?,enabled}` -> `ReservationServiceType` | `E2E-ADMIN-RESERVATION-02` |
| AD-22 | 预约门店 | `GET/POST /admin/reservations/stores`、`PUT /admin/reservations/stores/{id}`、`PUT /admin/reservations/stores/{id}/status` | `{name,address,phone,timezone,enabled}` -> `ReservationStore` | `E2E-ADMIN-RESERVATION-03` |
| AD-23 | 预约配置 | `GET/PUT /admin/reservations/config` | `{advanceDays,slotIntervalMinutes,cancelDeadlineMinutes}` -> `ReservationConfig` | `E2E-ADMIN-RESERVATION-04` |
| AD-24 | 用户表情管理 | `GET /admin/user-stickers`、`DELETE /admin/user-stickers/{id}` | `page,pageSize,userId?` -> `UserSticker{id,userId,objectId,url,createdAt}` | `E2E-ADMIN-STICKER-01` |
| AD-25 | 后台上传/预览 | `POST /upload?scope=profile|emoji|applet`、`GET /media/{objectId}/access` | multipart `file` -> `{objectId,url,scope,contentType,size,expiresAt?}`；私有资源签名 URL 15 分钟 | `E2E-ADMIN-MEDIA-01` |

> **小程序服务项契约更正（与 im-admin-service 现行实现对齐）**：AD-18/AD-19 字段以 `MiniappServiceType`/`MiniappServiceItem` 实际定义为准——服务项写/读字段为 `link`（非 `entryUrl`）、`icon`（非 `iconObjectId`）、`status`（布尔，非 `enabled`）、`sortOrder`（非 `sort`），并新增 `introduction`、`isTop`；单条更新/删除路径统一为 `/admin/miniapp/services/{id}`（非 `/im-services/{id}`）。C 端公开读接口与服务板块接入完整规范见 [IM_OPEN_PLATFORM_04_SERVICE](IM_OPEN_PLATFORM_04_SERVICE.md)。

首发不包含本节顶部列出的后续专项页面。其 API、授权模型、数据时点和 E2E 未冻结前，PC 新分支不得展示入口、调用旧接口或访问 `/internal/**`。

## 7. PC 管理后台改造方案

### 7.1 正式工程接入层

1. 新分支通过受管环境配置提供 Gateway 基地址：本地联调使用 `http://127.0.0.1:3002`，部署环境使用对应的 Gateway 域名；API client 直接请求 `${baseUrl}/api/v1/**`，不使用 Vite 开发代理、重写规则或旧后端地址。
2. 不配置 `/socket.io`。后台若未来确有实时需求，应另行定义后台专用版本化事件，不能沿用用户端 Socket.IO。
3. 媒体仅在后端 OSS 和受管媒体域名完成后，使用服务端返回的绝对 URL 访问；不使用前端代理，也不回退到旧单体。
4. Axios request interceptor 发送 `Authorization`、`X-Client-Contract: im-v1`、后台版本；response interceptor 按 v1 错误模型显示消息。
5. 保留 401、403 的统一行为，并在 token 刷新/登录后校验角色，防止普通用户进入后台路由。

### 7.2 API 与页面层

- 将 `src/api/` 中的每个模块改为 v1 路径和 v1 DTO；禁止页面组件直接写 URL 或读取后端私有字段。
- 每个 API 模块定义 `request DTO -> transport DTO -> view model` 转换边界，图表和表格只消费 view model。
- 对投影类列表显示“数据更新时间”；写操作成功后按后端返回的状态刷新，不假设跨服务读模型立刻可见。
- 对新增且未纳入首发范围的能力可在产品路由中移除或使用受控 feature flag 隐藏，不得保留一个必然 404 的按钮；既有管理业务必须保留等价入口和交互。
- 统计、服务器和运行状态页面只消费管理域审批过的摘要 API；日志检索、节点详情等高风险能力需额外的审计和最小权限设计。

### 7.3 后台验收最小集

| 场景 | 预期 |
| --- | --- |
| 业务一致性回归 | 以现有后台路由、操作流和权限矩阵逐项核验：入口、筛选、分页、写入、审批、审计、成功/失败反馈及最终数据结果与既有业务定义一致；仅允许已评审的产品变更 |
| 管理员登录与刷新页面 | token 保存、角色正确，登录失败显示规范错误 |
| 普通用户访问后台 API | 返回 403，页面不显示敏感数据 |
| 用户/消息分页查询 | 页码、总数、筛选、空状态与旧产品定义一致或有明确变更说明 |
| 配置、积分、预约、审核写操作 | 请求幂等、审计字段存在、失败不产生前端假成功 |
| 统计与在线监控 | 显示数据时点和最终一致延迟，不读取内部服务端口 |
| 上传与媒体预览 | 经 Gateway/媒体域访问成功，部署多副本后仍可读取 |

### 7.4 本次范围与后续专项边界

本次 PC 管理后台改造完成所有已有 Gateway 管理 REST 接口的正式 v1 接入、DTO 转换、权限、错误处理和业务回归。消息趋势统计、在线用户明细、通话监控、服务器/节点详情及日志检索不在本次范围；它们不得通过旧 Vite 后端、旧单体接口或 `/internal/**` 暂时实现。后续专项须重新冻结受控可观测性 API、最小权限、审计字段、数据时点和页面 E2E，再开放对应入口。

## 8. 首发数据、灰度与回滚

### 8.1 首发数据初始化

首发不执行旧系统数据迁移。各服务通过自己的 Flyway 迁移创建权威表结构，并由受控初始化脚本创建必要的管理员、配置、测试数据和媒体存储桶。

初始化脚本必须具备以下特征：

1. 不将密码、JWT 密钥、第三方密钥写入仓库；本地开发基线遵循工程 `.env` 规范，部署环境使用受管密钥注入。
2. 管理员创建、默认配置和演示数据可重复执行，且生产初始化与演示数据严格隔离。
3. 每个领域只初始化自己拥有的数据；禁止跨服务直接插表。
4. 首次部署前以空数据库执行完整 Flyway、服务启动、Gateway 路由和管理后台 E2E 验收。

### 8.2 灰度

- 先在集成环境完成后端契约测试和后台 E2E，再进入预发布环境。
- 后台可按管理员账号白名单、租户（如未来引入）或发布环境灰度，但所有流量始终访问同一 v1 Gateway 契约。
- 每批观察错误率、权限拒绝率、媒体读取失败率、读模型延迟和页面关键操作成功率；超过阈值立即停止扩大范围。

### 8.3 回滚原则

首发回滚面向**本系统自身的部署版本和数据库迁移**，不面向旧单体。每个发布批次应保留：

- 可回滚的 Gateway、服务和后台制品；
- 向前兼容的数据库迁移与数据备份；
- 配置变更记录和 feature flag 状态；
- 已演练的服务降级和恢复步骤。

Flyway 历史脚本不回改。发现结构问题时新增修正迁移；涉及不可逆数据变换时，发布前必须准备备份、恢复和业务停写窗口。

## 9. 发布顺序与完成定义

1. 审查并冻结 `/api/v1/**`、`/ws/im/v1` 契约矩阵。
2. 完成后端缺失能力、媒体架构、鉴权、可观测性和契约测试。
3. 发布 Gateway v1 路由及改造后的 PC 管理后台，执行管理员验收与安全回归。
4. 在空库/初始化数据环境完成预发布 E2E、性能基线和故障演练后首发。
5. 首发后持续监控 v1 契约质量；未来出现破坏性变化时才按评审结果新增 v2，不在 v1 中静默变更语义。

本方案完成的证据必须包括：以现有后台路由和操作流为基线的业务一致性矩阵、全量 REST v1 OpenAPI/错误码快照、Gateway v1 路由测试、自动化契约测试结果、后台 E2E 结果、OSS 上传/媒体权限验证、空库初始化报告、灰度监控报表和恢复演练记录。上述任一既有管理业务缺少契约、后端实现或 E2E 通过记录，均不得发布。
