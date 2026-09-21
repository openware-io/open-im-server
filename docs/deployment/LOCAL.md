# 本地部署说明

本仓库支持三种相互独立的本地运行方式。日常开发请选择其中一种；不要使用某一种方式的停止命令管理另一种方式。

脚本组织规则见 [`scripts/README.md`](../../scripts/README.md)：部署实现位于 `scripts/deploy/`，运行中系统的冒烟验证位于 `scripts/verify/`，仓库静态检查位于 `scripts/validate/`。

## 方式选择与依赖条件

| 方式 | 适用场景 | 必备条件 | 是否依赖 Docker Desktop | 数据生命周期 |
| --- | --- | --- | --- | --- |
| 直接运行本地进程 | Java 断点、单服务调试或接入已有共享基础设施。 | Windows PowerShell、JDK 25、仓库根目录 `.env`；MySQL、Redis、MongoDB、RocketMQ、MinIO 必须已按 `.env` 地址可用。 | 否；但若由本机容器承载上述基础设施，则需要 Docker Desktop。 | 由外部基础设施决定，脚本不创建、停止或清理。 |
| Docker Compose | 日常完整联调和稳定的本地开发环境。 | Windows PowerShell、Docker Desktop（Linux 容器模式，Docker Engine 已启动）、仓库根目录 `.env`。 | 是。 | 使用命名数据卷；仅 `-ResetData` 删除 Compose 数据卷。 |
| 本地 Kubernetes（Kind） | 验证 Kubernetes Service、发布顺序、滚动发布与版本追溯。 | Windows PowerShell、Docker Desktop（Linux 容器模式，Docker Engine 已启动）、`kind`、`kubectl`、仓库根目录 `.env`。`k9s` 为推荐但非必需。 | 是；Docker Desktop 仅提供容器运行时，不使用其内置 Kubernetes。 | 使用 `emptyDir`；Pod 重建或删除命名空间后数据清空。 |

三种方式都需要可用的 Git、`mvnw.cmd` 所需的网络或 Maven 缓存，以及 JDK 25 完成构建。首次运行先复制并按本机环境填写 `.env`；可使用以下命令初始化模板：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\initialize-local-env.ps1
```

`.env` 中的数据库、缓存、消息队列、对象存储地址与密钥是运行期配置，不得提交到版本库。直接运行模式必须将 `DB_HOST`、`REDIS_HOST`、`MONGODB_HOST`、`ROCKETMQ_NAMESRV_ADDR`、`MEDIA_INTERNAL_ENDPOINT` 和 `MEDIA_PUBLIC_BASE_URL` 指向本地 Java 进程与客户端均可访问的地址；不得使用仅在 Compose 网络中可解析的服务名。

## 服务依赖与启动顺序

基础设施必须先就绪，再启动业务服务。启动“完成”以健康检查通过为准，不能只以进程或容器已创建为准。

| 阶段 | 必须先就绪的组件 | 可启动的组件 | 关键依赖 |
| --- | --- | --- | --- |
| 1 | MySQL、Redis、MongoDB、RocketMQ NameServer、RocketMQ Broker、MinIO | 无 | 所有业务服务依赖 MySQL、Redis、RocketMQ；消息服务额外依赖 MongoDB；媒体支撑服务依赖 MinIO。 |
| 2 | 阶段 1 | `im-user-service`、`im-conversation-service`、`common-media-service`、`im-order-service` | 用户、会话、媒体和订单服务可并行启动；订单服务运行期调用用户服务，因此应等待用户服务健康后再执行涉及用户的操作。 |
| 3 | 用户、会话服务健康 | `im-message-service` | 消息服务调用用户和会话服务，并使用 MongoDB。 |
| 4 | Redis、RocketMQ Broker | `im-access-ws` | 长连接接入层依赖 Redis 和 RocketMQ。 |
| 5 | 用户、消息、会话、订单服务健康 | `im-admin-service` | 管理服务调用用户、消息、会话和订单服务。 |
| 6 | 所有业务服务及长连接接入层可用 | `gateway` | 网关只在所有下游目标可用后启动，避免对外暴露不完整路由。 |

`deploy-local.ps1`、`deploy-docker.ps1` 和 `deploy-k8s.ps1` 都负责上述启动顺序。Compose 和 Kind 会等待容器健康检查；直接运行脚本会等待每个服务端口可用，并在最后校验 Gateway 健康检查。不要用多个手工终端绕过依赖顺序。仅需单服务调试时，先由 Compose 或 Kind 启动完整环境，或确认外部依赖及该服务调用的上游服务已就绪。

## Docker Compose

Docker Compose 用于标准本地开发环境，负责管理 Compose 项目、命名数据卷、MinIO 和 Docker 发布的服务端口。执行脚本会自动启用 `media-local` 配置组，因此媒体上传可在完整环境中直接验证。

```powershell
.\deploy-docker.ps1 -ReleaseManifestPath <逐服务ACR发布清单路径>
```

实现脚本为 `scripts/deploy/docker.ps1`，仓库根目录脚本仅作为兼容入口。

常用参数：

```powershell
.\deploy-docker.ps1 -ReleaseManifestPath <逐服务ACR发布清单路径> -Stop
.\deploy-docker.ps1 -ReleaseManifestPath <逐服务ACR发布清单路径> -ResetData
```

访问地址：

- 网关：`http://127.0.0.1:3002`
- 管理后台：`http://127.0.0.1:8080`
- MySQL：`127.0.0.1:13306`
- Redis：`127.0.0.1:16379`
- MongoDB：`127.0.0.1:27018`

`-ResetData` 仅删除 Compose 数据卷，不影响 Kubernetes 资源。

Compose 从逐服务发布清单校验 ACR 仓库、模块版本和远程摘要后拉取业务镜像，不再本地构建 `gv-im/*:local`。启动后校验容器实际镜像摘要。Compose 会先等待 MySQL、Redis、MongoDB、RocketMQ 与 MinIO，再启动业务服务；Gateway 等待全部下游服务健康后才启动。请使用脚本而非直接执行 `docker compose up`。

本地 Docker 镜像保留策略：同一服务仓库最多保留按本地构建时间排序的最近两个版本；清理前必须排除所有容器正在引用的镜像。该策略只清理本机镜像标签，不删除 ACR 远程制品；基础设施镜像按运行依赖保留，不套用业务镜像版本上限。开发 `-SNAPSHOT` 镜像被覆盖后，旧的本地标签仍按此策略清理。

## Kubernetes（Kind）

Kind 用于验证集群内服务发现、Service 路由与滚动发布行为。Docker Desktop 仅提供容器运行时；不使用其内置 Kubernetes。部署脚本默认创建并切换到 `gv-im-local` Kind 集群，其 `kubectl` 上下文为 `kind-gv-im-local`。

本地 Kind 使用一个控制平面和一个工作节点。控制平面带 `NoSchedule` 污点，业务服务、数据库与中间件均调度到工作节点；这模拟生产环境的基本隔离边界，但不替代生产所需的多控制平面和工作节点池。脚本先发布并等待基础设施，初始化本地 `gv_saas` 数据库及最小访问授权，再发布业务服务、网关和管理后台，最后建立 Windows 本地访问代理。

```powershell
.\deploy-k8s.ps1 -SaasReleaseManifestPath <逐服务ACR发布清单路径> -RegistryPullSecretName <已配置的ACR拉取Secret名称>
```

实现脚本为 `scripts/deploy/k8s.ps1`，仓库根目录脚本仅作为兼容入口。声明式资源位于 [`k8s/local/`](../../k8s/local/)；脚本负责命名空间、从本地 `.env` 生成的 `gv-im-env` Secret、本地镜像、发布顺序及 Windows 本地访问转发。

常用参数：

```powershell
.\deploy-k8s.ps1 -SaasReleaseManifestPath <逐服务ACR发布清单路径> -ValidateOnly
.\deploy-k8s.ps1 -Stop
```

脚本使用命名空间 `gv-im-local` 并创建 `gv-im-env` Secret。开发分支默认使用 `<模块版本>-SNAPSHOT` 镜像标签，允许在 ACR 覆盖；只有明确的正式发布构建才使用不带后缀的纯 SemVer，正式 tag 不可覆盖。根聚合、结构聚合和领域父 POM 保持纯 SemVer，开发后缀由可部署叶子服务的 Maven 版本和镜像版本承载。禁止 `latest`、`dev`、时间戳、提交号及其他临时后缀；构建时间与 Git 修订只写入镜像 label 和发布清单。业务镜像仅由 `scripts/deploy/build-saas-release.ps1` 构建并推送 ACR，开发构建默认运行，正式构建必须显式传入 `-FormalRelease -FormalTargets <本次服务/前端名称>`；脚本拒绝把未列入该范围的 Maven 叶子打成正式包。Kind 使用开发清单进行真实验证；ACK 接受开发或正式清单。Kind 与 ACK 都按清单的版本 tag 部署，并在发布前校验 ACR 中的 tag 与 digest 一致；部署后校验 Pod 实际 `imageID`。`-ImageTag`、`-SaasImageTag` 覆盖入口被拒绝；`-SkipBuild` 仅兼容旧调用，不触发镜像构建。Kind 需要提前配置命名空间及 ACR 拉取 Secret（默认名 `acr-registry`，可通过 `-RegistryPullSecretName` 指定）；`-ValidateOnly` 只校验真实 ACR 清单，不修改集群。`.env` 中值为空的配置会被忽略，避免覆盖 Spring 默认值。

访问地址：

- 网关：`http://<宿主机局域网IP>:30002`
- IM 管理后台：`http://<宿主机局域网IP>:5173`
- SaaS 管理后台：`http://<宿主机局域网IP>:30081`
- SaaS 移动端：`http://<宿主机局域网IP>:30082`
- 统一门户：`http://<宿主机局域网IP>:30083`
- MinIO API/控制台：`http://<宿主机局域网IP>:30900` / `http://<宿主机局域网IP>:30901`

在 Kind 上下文中，脚本会为网关、IM 管理端、SaaS 管理端、SaaS 移动端、统一门户和 MinIO（API/控制台）创建连接 Kind 节点的本地代理容器。默认绑定 `0.0.0.0`，因此同一局域网内的调试设备可通过宿主机 IP 访问；只需本机访问时可传 `-LocalBindAddress 127.0.0.1`。`-Stop` 只会停止这些本地代理并删除 Kind 集群中的 `gv-im-local` 命名空间。

使用 `k9s -n gv-im-local` 或 `kubectl` 查看集群资源；应用的 Deployment、Pod 和 Service 均被隔离在该命名空间中。

Kubernetes 的数据库与 MinIO 本地存储使用 `emptyDir`。Pod 重建或执行 `-Stop` 后数据会被清空；这与 Docker Compose 数据卷相互隔离。

## 直接运行本地进程

`deploy-local.ps1` 是直接运行模式，不属于容器部署方式。它从本地代码目录启动服务，并将进程 ID 和日志记录在 `.outputs/local-deployment/`。该脚本按用户、消息、会话、媒体支撑、订单、管理、长连接接入层、网关的顺序启动；运行前必须确认上文阶段 1 的外部基础设施已就绪。

```powershell
.\deploy-local.ps1
.\deploy-local.ps1 -Stop
```

实现脚本为 `scripts/deploy/local.ps1`。仅在 `.env` 中的本地依赖可用时使用；该模式不管理 Docker Compose、Kubernetes 资源或其数据。若需要完整媒体能力，`MEDIA_INTERNAL_ENDPOINT` 和 `MEDIA_PUBLIC_BASE_URL` 必须指向可用 MinIO、OSS 或 COS 地址。

## 共存规则

- Docker 与 Kubernetes 脚本不会启动、停止、删除或修改对方的应用容器及数据。
- 直接运行脚本仅管理其在 `.outputs/local-deployment/` 中记录的进程，不会停止 Compose 容器、Kubernetes 资源或其数据。
- Docker Compose 与 Kubernetes 模式可复用镜像层，但 Kubernetes 应用镜像始终使用不可变发布标签。重新构建镜像不会替换正在运行的容器或 Pod；需要运行对应部署脚本完成发布。
- Docker 使用端口 `3002`、`8080`；Kubernetes 使用 `30002`、`5173`，两者的应用访问端口不会冲突。
- Kubernetes 状态使用 `kubectl get pods -n gv-im-local` 查询；Compose 状态使用 `docker compose ps` 查询。

## 验证

三种部署脚本都会验证网关健康检查接口。Kubernetes 脚本还会验证管理后台 SPA 能通过本地访问地址正常返回。

运行中系统的集成冒烟测试为 `scripts/verify/local-integration.ps1`。请以 `SecureString` 传入管理员密码，不要将密码硬编码在脚本中。

SaaS 管理后台本地种子账号为 `admin`（密码由本地初始化脚本/数据库迁移提供）；IM 用户域不再植入默认 `admin` 账号，首次联调请通过 `/api/v1/auth/register` 注册测试用户。Kubernetes 本地存储采用 `emptyDir`，Pod 或命名空间重置后会重新初始化这些数据。
