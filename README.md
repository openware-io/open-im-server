# OpenIM Server

## 本地部署

本地 Docker Compose、独立 Kind 集群和直接运行本地进程的部署说明见[部署文档索引](./docs/deployment/README.md)。
脚本目录与职责见[脚本说明](./scripts/README.md)。
Kind 集群资源清单见[Kubernetes 清单说明](./k8s/README.md)。

OpenIM Server 已按微服务方向完成第一阶段拆分：对外入口统一由 `gateway` 暴露 HTTPS/WSS，后端按用户域、聊天域、管理域与长连接接入层拆分部署。

## 当前服务拓扑

- `gateway`：统一入口，只负责路由、鉴权透传、限流等横切能力
- `im-user-service`：认证、用户资料、设备令牌、好友关系链、积分、用户贴纸
- `im-message-service`：消息历史、离线消息、已读、撤回、清空会话消息
- `im-conversation-service`：群组、成员管理、RTC 信令配置
- `common-media-service`：通用媒体上传会话、对象元数据、私有访问授权与业务引用登记
- `im-order-service`：预约订单、核销、门店与预约配置
- `im-admin-service`：管理端接口、客户端配置、版本更新、举报受理、迷你服务配置
- `im-access-ws`：WebSocket 长连接接入、鉴权、在线会话维护、跨实例广播
- `common` / `protocol-ws` / `infrastructure`：共享内核、协议契约、基础设施实现

## 访问入口

- Gateway HTTP：`http://localhost:3002/api/v1/**`
- Gateway WSS：`ws://localhost:3002/ws/im/v1?ticket={one-time-ticket}`
- 用户服务默认端口：`3100`
- 消息服务默认端口：`3200`
- 会话服务默认端口：`3300`
- 管理服务默认端口：`3400`
- 订单服务默认端口：`3500`
- 媒体支撑服务默认端口：`3600`
- 长连接接入层默认端口：`3001`

除 Gateway HTTP/WSS 外，其余服务端口仅用于本地调试和服务间通信；客户端访问媒体控制面必须通过 Gateway 的 `/api/v1/media/**`，文件二进制直连受管对象存储地址。

## 路由约定

外部 REST API 统一使用 `/api/v1/**`。Gateway 对以下业务路由统一执行 `StripPrefix=2`，因此后端 Controller 接收的路径不包含 `/api/v1` 前缀。路由配置的唯一实现来源是 `gateways/gateway/src/main/resources/application.yml`；变更该文件时必须在同一变更中同步更新本表和[网关服务说明](./docs/im-services/gateway/README.md)。

| 客户端请求路径 | 转发后的服务内路径 | 目标服务 | 路由标识 | 说明 |
| --- | --- | --- | --- | --- |
| `/api/v1/auth/**` | `/auth/**` | `im-user-service` | `api-auth` | 注册、登录和一次性 WebSocket 凭证等认证能力。 |
| `/api/v1/users/**` | `/users/**` | `im-user-service` | `api-users` | 用户资料与账号能力。 |
| `/api/v1/device-tokens/**` | `/device-tokens/**` | `im-user-service` | `api-device-tokens` | 设备推送令牌管理。 |
| `/api/v1/friends/**` | `/friends/**` | `im-user-service` | `api-friends` | 好友关系和好友申请。 |
| `/api/v1/points/**` | `/points/**` | `im-user-service` | `api-points` | 用户积分和积分记录。 |
| `/api/v1/user-stickers/**` | `/user-stickers/**` | `im-user-service` | `api-user-stickers` | 用户贴纸能力。 |
| `/api/v1/messages/**` | `/messages/**` | `im-message-service` | `api-messages` | 消息发送、历史、已读与撤回。 |
| `/api/v1/favorites/**` | `/favorites/**` | `im-message-service` | `api-favorites` | 消息收藏与收藏列表。 |
| `/api/v1/conversations/**` | `/conversations/**` | `im-conversation-service` | `api-conversations` | 会话查询和管理。 |
| `/api/v1/groups/**` | `/groups/**` | `im-conversation-service` | `api-groups` | 群组、成员和群角色。 |
| `/api/v1/rtc/**` | `/rtc/**` | `im-conversation-service` | `api-rtc` | RTC 信令配置查询。 |
| `/api/v1/reservations/**` | `/reservations/**` | `im-order-service` | `api-reservations` | 预约创建、查询、取消和核销。 |
| `/api/v1/media/**` | `/media/**` | `common-media-service` | `api-media` | 仅处理上传会话、完成确认、对象状态和访问授权；文件二进制由客户端直连对象存储。 |
| `/api/v1/admin/**` | `/admin/**` | `im-admin-service` | `api-admin` | 管理端后台业务接口。 |
| `/api/v1/config/client/**` | `/config/client/**` | `im-admin-service` | `api-client-config` | 客户端配置下发。 |
| `/api/v1/config/app-update` | `/config/app-update` | `im-admin-service` | `api-app-update` | 应用版本更新检查。 |
| `/api/v1/miniapp/im-services/**` | `/miniapp/im-services/**` | `im-admin-service` | `api-miniapp-services` | 小程序服务配置。 |
| `/api/v1/reports/**` | `/reports/**` | `im-admin-service` | `api-reports` | 举报提交与处理。 |

| 客户端请求路径 | 目标服务 | 协议与约束 |
| --- | --- | --- |
| `/ws/im/v1?ticket={one-time-ticket}` | `im-access-ws` | WebSocket 长连接。客户端先调用 `POST /api/v1/auth/ws-ticket` 获取一次性 `ticket`；该入口不适用 REST 的 `StripPrefix=2` 规则。 |
| `/_docs/{user,message,conversation,order,support,admin}/api-docs` | 对应业务服务 | 仅供网关聚合 Swagger 使用；网关移除 `Authorization` 后转发，不属于客户端业务接口。 |

## 本地运行

本仓库提供三种相互独立的本地运行方式。完整的依赖条件、启动顺序、停止范围和验证步骤见[本地运行说明](./docs/deployment/LOCAL.md)；同一时刻应只选择一种方式运行完整环境。

| 方式 | 适用场景 | 入口命令 | 网关地址 |
| --- | --- | --- | --- |
| 直接运行本地进程 | 调试单个服务或 Java 断点；依赖的基础设施必须已由外部环境启动。 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy-local.ps1` | `http://127.0.0.1:3002` |
| Docker Compose | 日常本地联调；Docker Desktop 负责运行全部基础设施和业务容器。 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy-docker.ps1` | `http://127.0.0.1:3002` |
| 本地 Kubernetes（Kind） | 验证 Service 发现、滚动发布、镜像版本与 Pod 可追溯性；Docker Desktop 仅作为容器运行时，不使用其内置 Kubernetes。 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy-k8s.ps1` | `http://127.0.0.1:30002` |

直接运行完整环境时，优先使用 `deploy-local.ps1`，它会按依赖顺序启动所有服务。仅调试单个服务时使用 `run-dev.ps1`，并确保外部基础设施和上游服务已先就绪：

```powershell
powershell ./run-dev.ps1 -Service user
powershell ./run-dev.ps1 -Service conversation
powershell ./run-dev.ps1 -Service support
powershell ./run-dev.ps1 -Service order
powershell ./run-dev.ps1 -Service message
powershell ./run-dev.ps1 -Service admin
powershell ./run-dev.ps1 -Service access-ws
powershell ./run-dev.ps1 -Service gateway
```

## 文档索引

- [架构总览](./docs/ARCHITECTURE.md)
- [00 DDD 微服务软件工程规范纲要](./docs/standards/00_DDD_MICROSERVICE_SOFTWARE_ENGINEERING_OUTLINE.md)
- [工程规范](./docs/ENGINEERING_RULES.md)
- [10 DDD 服务工程规范](./docs/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
- [12 RocketMQ 治理规范](./docs/standards/12_ROCKETMQ_CONVENTIONS.md)
- [20 Maven 工程规范](./docs/standards/20_MAVEN_ENGINEERING_CONVENTIONS.md)
- [21 持久层技术栈标准](./docs/standards/21_PERSISTENCE_STACK.md)
- [22 技术栈版本矩阵](./docs/standards/22_TECHNOLOGY_STACK_VERSION_MATRIX.md)
- [30 文本编码与批量改写规范](./docs/standards/30_TEXT_ENCODING_AND_BATCH_EDITING.md)
- [40 可观测性规范](./docs/standards/40_OBSERVABILITY_CONVENTIONS.md)
- [41 Java 日志规范](./docs/standards/41_JAVA_LOGGING_CONVENTIONS.md)
- [42 日志输出与诊断规范](./docs/standards/42_LOGGING_AND_OUTPUTS.md)
- [Gateway](./docs/im-services/gateway/README.md)
- [用户服务](./docs/im-services/im-user-service/README.md)
- [消息服务](./docs/im-services/im-message-service/README.md)
- [会话服务](./docs/im-services/im-conversation-service/README.md)
- [媒体支撑服务](./docs/im-services/common-media-service/README.md)
- [订单服务](./docs/im-services/im-order-service/README.md)
- [管理服务](./docs/im-services/im-admin-service/README.md)
- [长连接接入层](./docs/im-services/im-access-ws/README.md)
