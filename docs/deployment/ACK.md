# ACK 部署与运行手册

## 当前发布状态

本项目已使用仓库的 ACK 发布流程部署到 `im-business` 命名空间。ACK 接受由发布构建生成的开发或正式清单：开发镜像 tag 必须是 `<版本>-SNAPSHOT` 且可在 ACR 覆盖；正式镜像 tag 必须是纯 SemVer 且不可覆盖。两类清单都禁止 `latest`、`dirty`、时间戳、提交号及其他构建后缀。构建时间戳、Git 修订与构建标识只通过镜像 label（`IMAGE_VERSION`/`IMAGE_REVISION`/`IMAGE_CREATED`）与发布清单字段（`buildIdentity`/`sourceRevision`）记录。

**部署引用形态**：ACK 工作负载必须按清单使用镜像 tag，即 `<repo>:<模块版本>` 或 `<repo>:<模块版本>-SNAPSHOT`，让控制台直接显示发布版本。部署脚本在发布前校验该 tag 指向清单 digest，再将 tag 写入工作负载；应用容器必须使用 `imagePullPolicy: Always`。发布后脚本校验工作负载 tag 与清单一致，并核验 Pod 实际 `imageID` 的 digest。发布记录必须保留 tag、digest、Git 修订和时间。

> 应用工作负载不得引用 `latest` 或非规范 tag。开发工作负载使用 `:<版本>-SNAPSHOT`，正式工作负载使用 `:<版本>`；基础设施镜像继续使用经审批的固定版本 tag 并保持 `IfNotPresent`。

当前 ACK 正式极光配置：

```properties
JPUSH_ENABLED=true
JPUSH_APNS_PRODUCTION=true
JPUSH_ENDPOINT=https://api.jpush.cn/v3/push
```

`JPUSH_APP_KEY` 和 `JPUSH_MASTER_SECRET` 只允许保存在 `gv-im-secret`，文档、清单和日志不得出现具体值。发布后必须检查 WebSocket Pod 无重启，并确认极光生产标志为 `true`。

消息服务启动时由 Flyway 执行 `V2__add_msg_read_status_sync_index.sql`；应在日志中确认 schema 已升级到 v2，再进行消息同步和离线 Push 验收。

## 审计存储：独立 schema `gv_audit` 的发布前置与开关

审计日志已改为独立 schema + 按 `occurred_at` 月分区（方案见 [AUDIT_STORAGE_01_SERVICE.md](../renovation/AUDIT_STORAGE_01_SERVICE.md)）。
ACK 上的顺序**不可颠倒**：

1. **先建库授权（运维受控步骤，方案 §8-4 决策 B，不新增 k8s 资源）**：把
   `scripts/migration/audit-schema-bootstrap.sql` 里的 `__DB_USERNAME__` 换成 ACK 的库用户 `im_user`，
   在 ACK 的 MySQL 上执行一次。ACK 的 `im_user` 对 `gv_saas.*` 已是全量授权，
   脚本里第二条 `GRANT SELECT ON gv_saas.tnt_tenant` 可跳过（幂等，执行也无害）。
   集群内执行示例（不回显任何口令）：

   ```bash
   kubectl -n im-business exec -i mysql-0 -- sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot' < scripts/migration/audit-schema-bootstrap.sql
   ```

2. **再切 profile**：`k8s/ack/saas-common.yaml` 的 `common-audit-service` 已注入
   `SPRING_PROFILES_ACTIVE=audit-schema`（该 profile 同时切库名 `gv_audit` 与 Flyway 迁移目录
   `classpath:db/migration-audit`，两者必须原子切换）。启动时由 Flyway 建出
   幂等台账、分区主表、归档清单三张表。
   库不存在就切 profile，会让连接/Flyway 直接失败并 `CrashLoopBackOff` —— 这是第 1 步必须在前的唯一原因。
   **不要**给它注入 `INTERNAL_AUDIT_SERVICE_BASE_URL`：审计服务的上报对象就是它自己，
   缺省 `http://localhost:4190` 直接进本 Pod 端口；改成集群内 `common-audit-service:4190`
   会走「Pod 经 Service ClusterIP 回连自己」的 hairpin 路径，Kind 实测以 `ClosedChannelException`
   失败，保留任务的自留审计会静默丢失（只留一条 WARN）。

3. **回填历史（可选但建议）**：Flyway 建表后，按 `scripts/migration/audit-backfill-to-gv-audit.sql`
   的 `[Backfill]` 按月搬历史记录（只搬 `occurred_at >= 24 个月下限` 的月份）。语句带
   `ON DUPLICATE KEY UPDATE gv_audit.iam_audit_log.id = gv_audit.iam_audit_log.id`：
   可安全重跑，且必须写成库表全限定名（`INSERT ... SELECT` 源表同名列会让裸写 `id` 报 `ERROR 1052`）。
   ACK 首次切换时旧表 `gv_saas.iam_audit_log` 只有当月少量记录，一次单月回填即可。

**执行记录（2026-09-19，命名空间 `im-business`）**：按上述顺序执行，`gv_audit` 上 Flyway 跑到 v3、
30 个分区、`pmin`/`pmax` 0 行、归档清单 2024-09 = `ARCHIVED`、自留审计已落库；历史 21 行单月回填后
逐 `id` 对账一致。发布走 `scripts/deploy/ack.ps1 -ReleaseManifestPath <5 目标清单>`，
输出 `ACK release deployed by tag and verified by digest`，
发布记录：`.outputs/releases/ack-saas-full.development.20260919T054303Z.a117f298.json`。

**回退**：删除 `SPRING_PROFILES_ACTIVE` 并重启 `common-audit-service` —— 立刻回到 `gv_saas` 的普通审计表，
镜像与代码都不用回滚；旧表在回退窗口内不得删除或改动。

## 目标与边界

将本项目的后端服务和管理端部署到 ACK 集群中的独立命名空间 `im-business`。

- 不修改既有命名空间中的任何 Deployment、Service、Ingress、证书或中间件。
- 仅创建名称位于 `im-business` 的资源；所有应用和中间件仅通过集群内部 Service 通信。
- 仅通过新增的域名路由公开 `gateway` 和 `pc-admin`，不公开数据库、缓存、消息队列或对象存储的管理端口。
- 实施前必须确认本地 kubeconfig 所连接的集群与 ACK 控制台的目标集群一致；当前 API 查询结果与控制台截图存在差异，未确认前不得执行部署。

## 入口、域名与证书

已确认的两个三级域名：

| 域名 | 后端服务 | 协议与用途 |
| --- | --- | --- |
| `api.dev.example.com` | `gateway:3002` | HTTPS REST API；WSS `/ws/im/v1` |
| `admin.dev.example.com` | `pc-admin:80` | HTTPS 管理后台 |

建议使用一张同时包含两个域名的 SAN 证书，或使用 `*.<主域名>` 通配符证书。证书以 TLS Secret 形式存放在 `im-business`，Ingress 只引用 Secret 名称。私钥不得提交到 Git 仓库或发送到聊天记录。

DNS 记录已创建，实施时仅为上述两个 Host 新增路由规则；仍需确认入口类型为 Nginx Ingress、ALB Ingress、SLB 后的反向代理或其他方案。

控制台截图显示现有 Secret `admin.dev.example.com-tls`、`api.dev.example.com-tls` 位于 `meta-cogni`，类型为自定义 `IngressTLS`。Ingress 不能跨命名空间引用 TLS Secret，因此不直接修改或移动既有 Secret。部署前应在 `im-business` 创建同名（或约定名称）的两个 Secret，类型使用标准 `kubernetes.io/tls`，数据键必须为 `tls.crt` 和 `tls.key`。如果现有 Secret 数据键不同，需要按证书原始文件重新创建；现有 `meta-cogni` Secret 保持不变。

## 应用拓扑

对外仅有如下两个入口：

```text
Internet
  ├─ https://api.<主域名> ──> Ingress ──> gateway ──> 内部业务服务
  │                                      └─> im-access-ws（WebSocket）
  └─ https://admin.<主域名> ─> Ingress ──> pc-admin ──> gateway
```

将构建和部署以下项目服务：

- `gateway`
- `im-user-service`
- `im-message-service`
- `im-conversation-service`
- `common-media-service`
- `im-order-service`
- `im-admin-service`
- `im-access-ws`
- `pc-admin`（源码目录已确认：`D:\projects\cnb\gv_chat_admin`）

`gateway` 是唯一后端 API 入口。`pc-admin` 的 `/api/` 请求将转发至该网关；CORS 与 WebSocket Origin 白名单配置为 `https://admin.<主域名>`。

### 环境与版本约定

管理端生产镜像在构建时固定注入 `VITE_API_BASE_URL=/api/v1`，浏览器访问 `https://admin.dev.example.com` 时由管理端容器内的 Nginx 将 `/api/` 转发至集群内 `gateway:3002`。本地开发继续使用 `.env.development` 中的 `http://127.0.0.1:3002/api/v1`，两种环境不共用访问地址。

每次发布按「服务域独立版本模型」：后端各服务/网关镜像标签 = 对应模块的独立版本（见 [20 Maven 工程规范](../standards/20_MAVEN_ENGINEERING_CONVENTIONS.md)），管理端 `package.json` 与客户端 `pubspec.yaml` 各自独立版本；镜像标签不使用 `latest`。构建时将版本注入 `VITE_APP_VERSION`，Git 提交与构建时间写入镜像 label 供追溯。

### 发版版本规范

版本号采用各工程已批准的 `主.次.补丁`；开发分支的可部署叶子服务 Maven 版本使用 `<主版本>-SNAPSHOT`，根聚合、结构聚合和领域父 POM保持纯 SemVer。未经用户明确指令，发布流程不得生成正式纯 SemVer 镜像。开发发布使用默认构建生成的 `-SNAPSHOT` 清单，先完成 Kind 回归后可用同一清单发布 ACK；ACK 写入开发 tag，并在 rollout 后核验 Pod 实际 digest 属于本次清单。正式发版步骤：确认版本所有权 → 提升并确认本次目标叶子服务的正式版本 → 提交并保证工作树干净 → 使用 `build-saas-release.ps1 -FormalRelease -FormalTargets <本次服务/前端名称>` 生成逐服务正式清单（含 digest）→ Kind 全量验证 → ACK 按同一正式清单部署。`-FormalTargets` 是强制的精确范围：清单的 `deploymentTargets` 只包含明确晋级的服务或前端，ACK 只按该列表更新工作负载；正式 tag 不可覆盖。

后端无需因 ACK 修改 Java 业务代码。环境差异均由 `im-business` 的 ConfigMap 与 Secret 在运行时注入：内部主机名使用集群 Service DNS，外部入口使用 `api.dev.example.com` 与 `admin.dev.example.com`，敏感项使用新建生产 Secret，不能复用本地 `.env`。媒体服务的公开签名地址使用 `https://api.dev.example.com`；Ingress 将 `/api` 路由到网关、`/ws` 路由到 WebSocket 接入层，其余对象路径路由到 MinIO，MinIO 控制台不公开。

## 第一阶段：自建开源中间件

为保持与本地 Docker Compose 环境一致，第一阶段在 `im-business` 内使用固定版本的开源镜像部署以下单副本中间件：

| 组件 | 镜像版本 | 持久化要求 |
| --- | --- | --- |
| MySQL | `mysql:8.0` | PVC |
| Redis | `redis:7-alpine` | PVC |
| MongoDB | `mongo:7.0.12` | PVC |
| RocketMQ NameServer/Broker | `apache/rocketmq:5.3.1` | Broker PVC |
| MinIO | 固定稳定版本，实施时确定 | PVC |

ACK 生产清单将使用 StatefulSet（或具备等效持久化语义的单副本工作负载）和 PVC，不能沿用本地 Kind 清单中的 `emptyDir`。镜像不得使用 `latest` 标签。各组件只创建 `ClusterIP` Service，凭据通过 `im-business` 中独立的 Secret 注入。

## 镜像与密钥

- 项目镜像构建后推送至指定阿里云 ACR 仓库；部署使用不可变版本标签或镜像摘要。
- 公开开源镜像优先经 ACR 企业版镜像仓库/镜像加速拉取，减少公共仓库访问不稳定性。
- ACR 拉取凭据、应用数据库密码、JWT 密钥、服务间认证密钥、推送服务密钥和对象存储凭据均存入 Secret，不写入清单或仓库。
- 生产环境需要单独生成高强度 `JWT_SECRET` 和 `INTERNAL_SERVICE_AUTH_SECRET`，不得复用本地 `.env` 的示例值。

## 后续云原生替换路径

应用通过环境变量和集群 Service 名称连接依赖，后续可逐项替换而不改变 API 域名：

| 当前自建组件 | 后续替换目标 |
| --- | --- |
| MySQL | ApsaraDB RDS MySQL |
| Redis | Tair / 云数据库 Redis |
| MongoDB | 云数据库 MongoDB |
| RocketMQ | 云消息队列 RocketMQ 版 |
| MinIO | OSS |

每次替换遵循“备份或数据迁移 → 预发连通性验证 → 修改 Secret/连接串 → 滚动发布 → 观察与回滚窗口 → 下线旧组件”的顺序。禁止直接删除旧 PVC 或旧中间件。

## 实施前待提供与确认

1. 目标 ACK 集群 ID 与重新下载的 kubeconfig；验证 `kubectl get deploy,pod,svc -A` 与 ACK 控制台一致。
2. `api.<主域名>`、`admin.<主域名>` 的实际域名，以及目标公网入口类型和所属 IngressClass/ALB 信息。
3. 两域名的 DNS 已解析到目标入口；TLS 证书已就绪，或已在 `im-business` 创建约定名称的 TLS Secret。
4. 阿里云 ACR 仓库地址、命名空间、推送权限与集群拉取权限。
5. ACK 可用 StorageClass、云盘配额以及允许创建 PVC 的确认。
6. 第一阶段在集群内运行上述开源中间件并承担相应云盘与节点资源成本的确认。

## 执行与验收

在上述信息齐备且用户明确确认后，按以下顺序执行：

1. 只读验证目标集群、既有入口、StorageClass 和节点可用资源。
2. 生成 ACK 专用清单与部署脚本，统一使用命名空间 `im-business`。
3. 构建项目镜像、推送 ACR，并创建仅限该命名空间的拉取 Secret 和应用 Secret。
4. 部署并验证中间件 PVC、健康检查和内部 DNS。
5. 部署业务服务、网关和管理端，完成滚动发布。
6. 新增两条独立 Ingress 路由，验证 HTTPS、REST、WebSocket、管理端登录和健康检查。
7. 输出资源清单、镜像版本、域名路由和回滚步骤；不触碰其他命名空间资源。

## OIDC 签名密钥注入（多副本一致性，D7）

- im-user-service 的 OIDC ID Token 由 `OidcTokenSigner` 签名，JWKS 端点 `/.well-known/openid-configuration` + `/oauth/jwks` 输出同源公钥。
- 生产/ACK 必须注入固定 RSA 私钥，避免多副本/重启后 JWKS 漂移；本地开发未配置时默认临时生成并打 WARN。
- 配置项（env 前缀 `oidc.`）：
  - `oidc.signing.private-key-pem`：PKCS#8 PEM 内联（注意换行编码）；
  - `oidc.signing.private-key-path`：Secret 挂载文件路径（推荐，如 `/run/secrets/oidc-signing-key.pem`）；
  - `oidc.signing.kid`：固定 kid（可选；缺省由公钥指纹派生 `im-oidc-<hex16>`，注入同 key 即稳定）；
  - `oidc.signing.require-configured`：`true` 时未配置密钥则启动失败（ACK 应为 true）。
- 生成示例（只读，不入库）：
  `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out oidc-signing-key.pem`
  将文件内容作为 Secret 挂载/注入；重启后若更换密钥需保留旧 kid 用于轮换期（轮换发布时新旧 key 短暂并存，见治理文档待办）。
- issuer 注入沿用 `oidc.issuer`（ACK 为 https 域名）。
