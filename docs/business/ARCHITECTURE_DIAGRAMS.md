# 工程架构图集（Architecture Diagrams）

> **性质**：工程详细文档（属[项目事实清单](./PROJECT_FACTS_INVENTORY.md)同类的"工程实际长什么样"）。**四张图**：产品业务图、领域架构图、工程结构图、技术栈架构图。
> **维护**：与事实清单同步更新——**新增/变更业务线、域、服务、仓库、技术栈版本时，必须同步本图集**。
> **依据**：[ACCOUNT_PERMISSION_MODEL](./ACCOUNT_PERMISSION_MODEL.md)、[IM 开放平台方案](../archive/renovation/IM_OPEN_PLATFORM_01_SERVICE.md)、[SAAS_PLATFORM_09](../archive/renovation/SAAS_PLATFORM_09_SERVICE.md)、[技术栈版本矩阵](../standards/22_TECHNOLOGY_STACK_VERSION_MATRIX.md)、[工程规则](../ENGINEERING_RULES.md)。所有节点与库名均为实测。

---

## 一、产品业务图（三层层级与两条业务线）

**一句话**：**IM 是开放平台**（参考微信），**SaaS 是它的第一个第三方接入方**（"美团之于微信"），**集团层统管两者但只管理、不参与业务**。

```mermaid
graph TD
  OS["开源工程 open-im-server<br/>（整仓开源 · 后端总仓 · 全局视角）"]

  subgraph GRP["集团层 · group-services/idaas —— 只管理、不参与业务"]
    GRP1["集团账号 group_account<br/>集团组织 group_org<br/>SSO / 会话 / JWT 签发"]
    GRP2["OAuth 客户端 idaas_client"]
  end

  subgraph IM["IM 业务线 · 类微信 · 本身即开放平台"]
    IM1["IM 服务端<br/>账号·消息·会话·后台"]
    IM2["IM 客户端族 open-chat-*<br/>App / PC 客户端 / PC 管理后台"]
    IM3["官网 open-website · 统一门户 unified-portal · TURN"]
  end

  subgraph SAAS["SaaS 业务线 · 建在 IM 之上的垂直业务"]
    SA1["SaaS 后台 open-saas-admin<br/>平台运营后台 + 租户后台"]
    SA2["SaaS 移动端 open-saas-mobile<br/>对接移动端的 H5"]
    SA3["SaaS 服务端<br/>身份·租户·订单·支付·会员·营销·资源·审计"]
  end

  OS --> GRP
  OS --> IM
  OS --> SAAS

  IM -- "开放平台标准接入流程<br/>应用注册 → 审核 → 用户授权(OAuth2.0+PKCE)<br/>→ 用户同步 → 业务打通" --> SAAS
  GRP -- "① 统管两条业务线<br/>② 唯一关联：SSO 映射<br/>saa_admin_account.idaas_subject → group_account.id" --> SAAS
  GRP -- "统管" --> IM

  classDef grp fill:#f3e8ff,stroke:#9333ea
  classDef im fill:#dbeafe,stroke:#2563eb
  classDef sa fill:#dcfce7,stroke:#16a34a
  class GRP1,GRP2 grp
  class IM1,IM2,IM3 im
  class SA1,SA2,SA3 sa
```

**要点**（均有实测依据）：
- **IM 与 SaaS 互相引用，但各有权威**：SaaS 账号经 `idt_oauth_link`（`provider='IM'`）绑定 IM 账号。
- **集团 → SaaS 的唯一关联是 SSO 映射**（`saa_admin_account.idaas_subject` → `group_account.id`）；**业务表不引用集团 IDaaS 表**。
- ⚠️ **`idt_*` 是 SaaS 平台账号，不是 IDaaS**；IDaaS 是集团的 `group_account`。
- 第三方（打车 / 电商 / 本地生活）未来按**同一标准流程**接入，故本图不是"两个系统的集成"，而是**开放平台的第一个接入方**。

---

## 二、领域架构图（业务线 → 限界上下文 → 权威服务 → 库）

**判据**：表前缀 = 限界上下文；**一个前缀 = 一个域**；业务线由**代码根 + 库名**承载，**不写进表名**。

```mermaid
graph LR
  subgraph DB_IM["库 open_im（IM 业务线）"]
    direction TB
    U["User<br/>user_ (20)"]
    OP["开放平台<br/>open_ (3)"]
    MSG["Message<br/>msg_ (15)"]
    CNV["Conversation<br/>conversation_ (8)"]
    ADM["IM 后台<br/>adm_ (16)"]
  end

  subgraph DB_SA["库 open_saas（SaaS 业务线）"]
    direction TB
    IDT["Identity<br/>idt_ (4)"]
    TNT["Tenant Access<br/>tnt_ (9)"]
    IAM["访问控制（授权）<br/>iam_ (6)"]
    RES["Resource Scheduling<br/>res_ (5)"]
    ORD["Transaction Fulfillment<br/>ord_ (17)"]
    PAY["Funds Collection<br/>pay_ (11)"]
    CST["Tenant Loyalty<br/>cst_ (9)"]
    MKT["Tenant Marketing<br/>mkt_ (7)"]
    CONS["运营控制台<br/>cons_ (2)"]
    AUD["审计域<br/>audit_ (3)"]
  end

  subgraph DB_GRP["库 open_group（集团层）"]
    IDAAS["集团认证服务<br/>idaas_ (3)"]
  end

  subgraph DB_SUP["通用支撑域"]
    MEDIA["Media<br/>media_ (5)"]
    MAIL["Mail<br/>mail_ (3)"]
    SMS["SMS<br/>sms_ (3)"]
    PCH["Payment Channel<br/>paych_（预留）"]
  end

  U -.->|"im-user-service"| OP
  IDT -.->|"idt_oauth_link（provider='IM'）<br/>SaaS 账号绑定 IM 账号"| U
  AUD -.->|"IM 23 处 + SaaS 881 处共同调用"| U
  AUD -.-> IDT
  AUD -.-> ORD
  CONS -.->|"引用权限，不定义权限"| IAM

  classDef imdb fill:#dbeafe,stroke:#2563eb
  classDef sadb fill:#dcfce7,stroke:#16a34a
  classDef grpdb fill:#f3e8ff,stroke:#9333ea
  classDef supdb fill:#fef3c7,stroke:#d97706
  class U,OP,MSG,CNV,ADM imdb
  class IDT,TNT,IAM,RES,ORD,PAY,CST,MKT,CONS,AUD sadb
  class IDAAS grpdb
  class MEDIA,MAIL,SMS,PCH supdb
```

**关键说明**：
- **审计域是独立域**，被 IM（23 处）与 SaaS（881 处）**共同调用**；对外发布 `common-audit-api`，独立 schema `open_audit`、独立 Flyway history。**表已从 `iam_audit_*` 归位为 `audit_*`**（见[例行整改总纲](../archive/renovation/UNIFIED_NAMING_REMEDIATION_PLAN.md)）。
- **`iam_` 只做授权**（无 Identity Management）：角色/权限/作用域授权/审批；**身份**由 `idt_`（SaaS 平台账号）、`group_account`（集团）、`user_`（IM）分别承担。`iam_menu` 在控制台服务，属 IAM 迭代产物。
- **`cons_`** 承载运营后台自身数据（账号、菜单）；`iam_menu` 因属 IAM 迭代产物而保留在 `iam_`。
- **业态（KTV / 酒店 / SPA / 零售）不是域**，是 `ord_` 域上的 `business_type` 列。
- **`paych_`** 为预留（渠道表实际用 `pay_channel_*`，属 `pay_` 域）。

---

## 三、工程结构图（仓库 → 构建单元 → 依赖方向）

### 3.1 工作区（10 个仓库）

```mermaid
graph TD
  WS["工作区 · 10 个 git 仓库"]

  subgraph R_BE["后端总仓（全局视角 + 工程文档）"]
    BE["open-im-server<br/>im-services / platform-services / common-services / group-services<br/>gateways / sdk / portal / scripts / k8s / docs"]
  end
  subgraph R_IM["IM 客户端族"]
    C1["open-chat-app（App · Flutter）"]
    C2["open-chat-desktop（PC 客户端 · Electron+Vue3）"]
    C3["open-chat-admin（PC 管理后台 · Vue3）"]
    C4["open-chat-turn（TURN）"]
  end
  subgraph R_SA["SaaS 前端族"]
    S1["open-saas-admin（后台 · Vue3）"]
    S2["open-saas-mobile（移动端 H5 · Vue3 双入口）"]
  end
  R4["open-website（官网 · 静态+Nginx）"]
  R5["open-green-pass（独立产品：测试治理平台）"]
  R6[".github（组织级配置）"]

  WS --> R_BE
  WS --> R_IM
  WS --> R_SA
  WS --> R4
  WS --> R5
  WS --> R6

  subgraph DEPLOY["部署映射（build-saas-release.ps1 的权威应用清单）"]
    D1["app  → open-chat-app"]
    D2["pc-admin → open-chat-admin"]
    D3["saas-admin → open-saas-admin"]
    D4["saas-mobile → open-saas-mobile"]
    D5["unified-portal → open-im-server/portal"]
    D6["open-website → open-website"]
  end
  C1 -.-> D1
  C3 -.-> D2
  S1 -.-> D3
  S2 -.-> D4
  BE -.-> D5
  R4 -.-> D6
```

### 3.2 服务端构建单元与依赖方向

```mermaid
graph TD
  subgraph GW["gateways/ —— 边缘接入"]
    WSGW["im-access-ws（WebSocket 长连接）"]
    HTTPGW["gateway（HTTP）"]
  end
  subgraph BIZ["业务服务族（每条业务线各自分区）"]
    IMSVC["im-services/ 上下文/服务-api + 服务-service"]
    SASVC["platform-services/ 上下文/..."]
    COMSVC["common-services/ 能力/...（media/audit/mail/sms/pay/paych）"]
    GRPSVC["group-services/idaas/..."]
  end
  subgraph SDK["sdk/ —— 技术能力与协议"]
    PROTO["protocol-mq（MQ 常量/事件契约）"]
    INFRA["infrastructure（MQ/审计客户端/安全）"]
    COMMON["common（通用类型）"]
  end

  WSGW --> IMSVC
  HTTPGW --> SASVC
  BIZ --> SDK
  IMSVC -->|"跨服务只依赖对方 *-api"| SASVC
  SASVC --> COMSVC
  GRPSVC -.->|"SSO 映射（非业务表引用）"| SASVC

  subgraph LAYER["服务内部分层（10 DDD 规范强制）"]
    L1["api（入站适配）"] --> L2["application（用例/事务）"] --> L3["domain（规则内核）"]
    L4["infra（持久化/MQ/RPC）"] -.->|"实现契约"| L3
  end
  classDef edge fill:#ffe4e6,stroke:#e11d48
  classDef tech fill:#e0e7ff,stroke:#4f46e5
  class WSGW,HTTPGW edge
  class PROTO,INFRA,COMMON tech
```

**结构要点**：一个限界上下文 = 一个服务域（`<svc>-api` 稳定契约 + `<svc>-service` 唯一可部署单元）；Service **不得**依赖其他服务的 Service 制品；跨服务只经 `*-api` 或版本化事件；`domain` 不得依赖 Spring / MyBatis / Redis / MQ。

---

## 四、技术栈架构图

### 4.1 分层技术栈

```mermaid
graph TD
  subgraph CLIENT["客户端层（各自仓库维护版本矩阵）"]
    FA["App · Flutter"]
    FD["PC 客户端 · Electron + Vue 3 + TS"]
    WA["Web 后台 · Vue 3 + Element Plus + Vite"]
    WH["移动端 H5 · Vue 3 + Vite（双入口）"]
    WS["官网 · HTML + Nginx"]
  end

  subgraph EDGE["接入层"]
    NG["网关 / Ingress"]
    ACC["im-access-ws · WebSocket（Netty 4.2.17）"]
    TURN["open-chat-turn · TURN（WebRTC）"]
  end

  subgraph APP["应用层 · Spring Boot 4.0.7 / Java 25"]
    WEB["Spring Web / Springdoc OpenAPI 3.0.1"]
    CLOUD["Spring Cloud 2025.1.2 + Alibaba 2025.1.0.0"]
    SEC["Spring Security + JJWT 0.12.5（JWT 签发/校验）"]
    RPC["gRPC 1.75.0"]
    MQ["RocketMQ Client 5.3.1（Outbox Relay + 幂等消费）"]
    ORM["MyBatis-Plus + MapStruct（持久层）"]
    FLY["Flyway（迁移，只增不改）"]
  end

  subgraph DATA["数据层"]
    MYSQL[("MySQL 8.0<br/>open_im / open_saas / open_audit / open_group")]
    REDIS[("Redis<br/>缓存 / 会话")]
    MONGO[("MongoDB")]
    MINIO[("MinIO<br/>对象存储")]
    RMQ[("RocketMQ 5.3.1<br/>NameServer + Broker")]
  end

  CLIENT --> EDGE --> APP --> DATA
  classDef c fill:#dbeafe,stroke:#2563eb
  classDef d fill:#dcfce7,stroke:#16a34a
  classDef t fill:#fef3c7,stroke:#d97706
  class FA,FD,WA,WH,WS c
  class MYSQL,REDIS,MONGO,MINIO,RMQ d
  class NG,ACC,TURN t
```

### 4.2 版本基线（服务端权威来源 = 根 `pom.xml`；详见 [22 技术栈版本矩阵](../standards/22_TECHNOLOGY_STACK_VERSION_MATRIX.md)）

| 类别 | 组件 | 版本 |
| --- | --- | --- |
| 语言运行时 | Java | **25**（Enforcer 最低要求 25） |
| 构建 | Maven | 3.9.16（`./mvnw` / `mvnw.cmd`） |
| 构建父项 | Spring Boot Parent | **4.0.7** |
| 平台制品 | `sdk` 聚合 | 2.2.0 |
| 代码规范 | Checkstyle | 10.26.1 |
| 编译 | Maven Compiler Plugin | 3.14.1 |
| 质量门禁 | Maven Enforcer Plugin | 3.6.3 |
| 打包 | Spring Boot Maven Plugin | 4.0.7 |
| 微服务 | Spring Cloud | 2025.1.2 |
| 微服务 | Spring Cloud Alibaba | 2025.1.0.0 |
| API 文档 | Springdoc OpenAPI | 3.0.1 |
| 网络 | Netty | 4.2.17.Final |
| RPC | gRPC | 1.75.0 |
| 消息队列 | RocketMQ Client | 5.3.1 |
| 鉴权 | JJWT | 0.12.5 |
| 数据库 | MySQL | 8.0（`mysql:8.0.46` 用于验证） |

---

## 五、图与文档的分工（避免重复维护）

| 图 | 回答的问题 | 权威细节在 |
| --- | --- | --- |
| 一、产品业务图 | 有几条业务线、彼此什么关系 | [ACCOUNT_PERMISSION_MODEL](./ACCOUNT_PERMISSION_MODEL.md)、[IM 开放平台方案](../archive/renovation/IM_OPEN_PLATFORM_01_SERVICE.md) |
| 二、领域架构图 | 有哪些域、表前缀、归谁、放哪个库 | [项目事实清单](./PROJECT_FACTS_INVENTORY.md)（逐行权威） |
| 三、工程结构图 | 仓库怎么分、服务怎么建、依赖往哪走 | [工程规则](../ENGINEERING_RULES.md)、[10 DDD 规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) |
| 四、技术栈架构图 | 用什么技术、什么版本 | [22 技术栈版本矩阵](../standards/22_TECHNOLOGY_STACK_VERSION_MATRIX.md)（**版本唯一权威**） |

> **图只表达结构与关系，不复制细节数值**——细节以对应权威文档为准，避免两处维护。版本号是唯一例外（评审时需一屏可读），故在 4.2 复列并注明权威来源。
