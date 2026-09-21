# 第二阶段 SaaS 与多业态后端实施方案

> **变更记录（v2）**：①IM 与 SaaS 独立产品化——两者数据库独立、代码独立、独立部署、可独立销售，公共依赖抽为公共结构共享；新增 `platform-identity-service` 承载 SaaS 平台账号，`im-user-service` 退回 IM 域，不再做 `user.role → IAM` 迁移；②首发业态收敛为 KTV，酒店/足浴降为后续演进。

## 1. 方案定位

| 项目 | 内容 |
| --- | --- |
| 方案集 | `SAAS_PLATFORM` |
| 顺序号 | `02` |
| 范围 | 微服务架构、数据、内部契约、Gateway、支付/履约后端与 PC Admin 后台 BFF |
| 前置方案 | [SAAS_PLATFORM_01_SERVICE](SAAS_PLATFORM_01_SERVICE.md)、[架构总览](../ARCHITECTURE.md)、[订单预约迁移方案](ORDER_RESERVATION_01_SERVICE.md) |
| 配套方案 | [SAAS_PLATFORM_03_APP](SAAS_PLATFORM_03_APP.md) |
| 强制配套 | [SAAS_PLATFORM_04_DATA](SAAS_PLATFORM_04_DATA.md)、[SAAS_PLATFORM_05_API](SAAS_PLATFORM_05_API.md)、[SAAS_PLATFORM_06_TECHNICAL](SAAS_PLATFORM_06_TECHNICAL.md)、[SAAS_PLATFORM_07_EXECUTION](SAAS_PLATFORM_07_EXECUTION.md)、[SAAS_PLATFORM_09_SERVICE](SAAS_PLATFORM_09_SERVICE.md) |
| 状态 | 进入详细实施设计前的执行基线 |

本方案在一阶段当前版本上增量演进，不替换现有 C 端、预约、用户、消息和后台契约。每一批实施必须同时完成新功能、旧功能回归、租户隔离和可回滚验证。

表结构以 `SAAS_PLATFORM_04_DATA` 为准，HTTP/MQ 契约以 `SAAS_PLATFORM_05_API` 为准，租户隔离、并发、资金一致性、回滚和可观测性以 `SAAS_PLATFORM_06_TECHNICAL` 为准；本文件与三份详细方案冲突时，必须先完成评审，不得自行选择性实现。

## 2. 交付目标

首发完成以下后端闭环：

1. 平台可开通租户、组织、门店、员工、角色、商户主体和支付配置。
2. 租户后台可管理资源、商品/服务、价格、预约、订单、会员、资金和报表。
3. （后续）酒店完成预订/入住/押金/钟点房/换房/退房结算。
4. （首发）KTV 完成包厢预订/开台/计时/加项/结台/现金结算，不包含酒水库存和最低消费。
5. （后续）足浴完成项目/房间/技师排班指派/计时/加钟/结单，不包含技师提成。
6. 统一订单、组合支付、退款、押金、交班、日结和审计可追踪。
7. Admin 同时提供平台运营、平台营销和租户运营后台，权限与数据范围严格隔离。
8. 为 B 端 App 提供版本化 REST 契约、门店上下文、待办、资源、开单、服务执行和审批接口。

## 3. 现有系统保护线

| 现有能力 | 一阶段权威 | 二阶段处理 |
| --- | --- | --- |
| IM 账号、聊天资料、设备令牌、IM 平台积分 | `im-user-service` | 保持 IM 域职责；不再作为 SaaS 账号权威；SaaS 账号独立（`platform-identity-service`） |
| IM 授权登录 / 用户信息同步 | `platform-identity-service` + IM OAuth Provider | IM 仅作 C 端入口之一；SaaS 不依赖 IM 运行 |
| 预约、门店和预约积分协作 | `platform-order-service` | 保留预约生命周期；扩展订单关联时使用兼容适配 |
| 消息、离线、回执 | `im-message-service` | 不与经营订单混用；业务通知通过独立事件适配 |
| WebSocket 接入 | `im-access-ws` | 保持 IM 协议；业务资源状态首发使用 REST 轮询/推送，禁止混入聊天事件 |
| IM 后台（积分/媒体/用户管理） | `im-admin-service` | 保持 IM 产品后台，独立于 SaaS 后台；不承载 SaaS 平台/租户后台 |
| Gateway | `gateway` | 新增 `/api/v1/**` 路由、鉴权透传、限流和审计；不承载业务编排 |

现有数据库不直接加上大量 SaaS 业务字段。需要改变权威归属时，先建立新表/新服务、回填和双向校验，再切换读写路径；历史 Flyway 脚本禁止修改。

## 4. 目标服务与职责

下列是逻辑服务边界。运行时代码分三类域：**IM 产品**（`im-user-service`/`im-message-service`/`im-conversation-service`/`im-access-ws`/`im-admin-service`，IM 后台）；**平台/SaaS 产品**（`platform-identity-service`/`platform-tenant-service`/`platform-resource-service`/`platform-order-service`（业务）/`platform-customer-service`/`platform-marketing-service`/`platform-admin-service`，SaaS 后台）；**通用支撑域**（`common-*`，见下表）。三域数据库独立、代码独立、独立部署、可独立销售；公共依赖（`sdk/infrastructure`、`common`、`protocol-mq`）抽为公共结构共享，业务形态不耦合。是否独立部署由容量和团队节奏决定，但数据权威和代码所有权必须按此划分。

> 目录与命名已定：`im-services/`（IM，`im-*`）、`platform-services/`（平台，`platform-*`）、`common-services/`（通用支撑，`common-*`）、`sdk/`（公共 SDK）。支撑域内多服务的能力域（如支付）在 `common-services/` 下下探一层：**支付域** = `common-payment-service`（业务） + `common-payment-channel-service`（渠道），两者均嵌套在 `common-services/payment/` 内，不平级罗列。

| 逻辑服务 | 首发职责 | 权威数据 |
| --- | --- | --- |
| `im-user-service` | IM 账号、聊天资料、设备令牌、IM 平台积分 | Account、LoginIdentity、DeviceToken、PlatformPointLedger（IM 域） |
| `platform-identity-service`（新增） | SaaS 平台账号、登录、凭据、会话、OAuth/第三方登录、用户信息同步、账号绑定 | IdentityAccount、LoginIdentity、OAuthLink、ProfileSyncRecord |
| `platform-tenant-service`（新增） | Tenant Access：租户、组织、门店、商户主体、终端、角色、权限、作用域、上下文切换 | Tenant、Organization、Store、LegalEntity、MerchantAccount、CashierTerminal、Role、Permission、UserRoleAssignment、AuditLog |
| `platform-resource-service`（新增） | 包厢（首发）/房间/足浴房/技师（后续）、排班、资源占用 | Resource、Schedule、Occupation |
| `platform-order-service` | 预约兼容、统一订单、订单明细、KTV 履约（首发）/酒店/足浴（后续） | Order、OrderItem、Reservation、KtvSession（首发）；HotelStay、SpaSession（后续） |
| `platform-customer-service`（新增） | 租户客户、会员、积分、权益、储值账本 | TenantCustomer、Membership、PointLedger、Benefit、StoredValueLedger |
| `platform-marketing-service`（新增） | 租户营销；平台营销对象独立隔离 | Campaign、Coupon、Audience、Grant、Consent、DeliveryTask |
| `im-admin-service` | IM 后台：积分/媒体/用户管理、IM 审计（IM 产品） | IM 域表（`user`/`adm_`），不拥有 SaaS 领域权威表 |
| `platform-admin-service`（新增） | SaaS 后台 BFF：平台运营后台 + 租户后台（权限区分）、菜单聚合、请求校验、上下文透传 | 不拥有 SaaS 领域权威表 |
| `im-access-ws` | IM 长连接和 IM 事件 | 不拥有经营事实 |
| `common-media-service`（由 `common-media-service` 改名） | 媒体对象、上传会话、存储适配、访问授权、媒体引用登记（通用支撑域） | `media_object`、`media_upload_session`（`media_` 前缀） |
| `common-audit-service`（预留） | 内容审核/安全（通用支撑域） | 审核规则、审核日志（`audit_` 前缀） |
| `common-mail-service`（预留） | 邮件触达（通用支撑域） | 邮件模板、发送日志 |
| `common-sms-service`（预留） | 短信触达，预留待配置参数，可配置阿里云/腾讯云等国内外服务商（通用支撑域） | 短信模板、发送日志 |
| `common-payment-service`（新增） | 支付域·业务：支付意图、退款、押金、交班、日结（资金事实） | PaymentIntent、PaymentTransaction、Refund、DepositLedger、Shift、DailyClosing |
| `common-payment-channel-service`（新增） | 支付域·渠道：微信/支付宝/Stripe 的 HTTP、验签、回调、渠道能力查询 | 渠道配置、回调摘要（不拥有业务资金账本） |

`platform-tenant-service` 是 Tenant Access 限界上下文的唯一部署服务，内部以 `tenant` 与 `authorization` 子域组织代码和表。`platform-customer-service` 与 `platform-marketing-service` 是独立限界上下文和部署服务，不合并 Service 模块、权威表或迁移目录；跨域协作只使用 API/事件契约。

### 4.1 IAM 的权威归属与运行时校验

首发将 IAM 随 Tenant Access 部署在 `platform-tenant-service`，是为了让“租户—组织—门店—员工角色—数据范围”在同一一致性边界内维护；这不表示每一个业务请求都必须同步调用租户服务。RBAC 是 IAM 的首发授权模型，平台角色（`tenant_id` 为空）与租户角色（带 `tenant_id`）均由该服务权威管理。

| 层次 | 职责 | 是否每个业务请求同步调用 `platform-tenant-service` |
| --- | --- | --- |
| `platform-identity-service` | SaaS 账号认证、登录令牌、认证失效、OAuth 入口 | 否 |
| `platform-tenant-service` | IAM 角色/权限/数据范围的写入权威；上下文选择、授权版本和失效事件 | 否，仅在登录后选上下文、刷新或高风险复核时调用 |
| Gateway / `platform-admin-service` | 验签、路由级粗粒度拦截、上下文透传 | 否，不替代业务最终鉴权 |
| 订单、支付、资源等领域服务 | 在本地执行“权限编码 + 数据范围 + 业务状态”最终判定 | 否，使用签名上下文和本地授权快照 |

`POST /auth/context/select` 生成短期、签名的 `tenantContextToken`，至少含 `accountId`、`tenantId?`、`organizationId?`、`storeId?`、`authorizationVersion`、`expiresAt`。领域服务验证签名和有效期，并使用 `(accountId, tenantId, authorizationVersion)` 缓存的权限/范围快照；收到 `AuthorizationChanged`、`RoleRevoked`、`StoreAccessChanged` 事件立即逐出缓存。退款审批、跨租户平台操作、导出敏感数据等高风险动作，必须额外调用 IAM 内部授权检查；该检查不可用时拒绝操作并记录审计。普通读写不因 IAM 短暂不可用而逐请求 RPC，但签名失效、版本不一致或授权快照缺失时一律拒绝。

### 4.2 IM 与 SaaS 账号边界（替代旧 User 鉴权迁移）

IM 与 SaaS 解耦后，不存在“把 IM 用户迁移成 SaaS 用户”的动作：`im-user-service` 及其 `user` 表继续作为 IM 域账号权威，`user.role` / `ROLE_*` 继续服务 IM 自身后台鉴权，原样保留、不迁移。SaaS 业务系统的账号与 IAM 从零独立建设。

| 能力 | 归属 | 说明 |
| --- | --- | --- |
| IM 账号、聊天资料、设备令牌 | `im-user-service` | 保持现状，服务 IM 聊天/社交；不作为 SaaS 账号 |
| IM 后台鉴权（`user.role`/`ROLE_*`） | `im-user-service` + `im-admin-service` | 原样保留，服务 IM 自身后台（积分/媒体/用户管理），不进入 SaaS IAM |
| SaaS 平台账号、登录、凭据、会话 | `platform-identity-service`（新增） | 独立注册/登录（手机号+验证码/密码），独立 JWT，不依赖 IM |
| SaaS 租户/组织/门店/角色/权限/作用域 | `platform-tenant-service`（新增） | 独立 RBAC，从零建设，不继承 IM 角色 |
| C 端预约账号归属 | 迁至 `platform-identity-service` | 预约属 SaaS 业务；C 端通过 IM OAuth 入口打通后持 SaaS Token 访问预约 |

IM 与 SaaS 的打通只发生在“入口层”，不影响两套账号权威：

- **第三方授权（两层）**：①应用级——SaaS 在 IM 开放平台注册为第三方应用/模块，获取 `appId`/`appSecret`，接入即授权（等价微信里的美团模块）；②用户级——OAuth 2.0（Authorization Code + PKCE），IM 作为 Provider、SaaS 作为 Client，用户授权指定 `scope` 后，SaaS 侧绑定或新建 SaaS 账号，`LoginIdentity` 支持 `PHONE/EMAIL/IM` 多类型。
- **用户信息同步**：授权范围内昵称/头像/手机号，IM → SaaS；通过 OAuth userinfo 拉取 + `UserProfileChanged` 事件推送（授权范围内），落 SaaS 账号资料与绑定关系，不共享 IM 聊天数据。

未来若 IAM 的可用性、容量或团队边界需要独立演进，可从 `platform-tenant-service` 拆为独立部署单元；对外保持上下文 Token、内部授权检查 API 和授权变更事件不变，领域服务不直接访问 `iam_` 表，因此无需跟随改造。

## 5. 统一请求上下文与鉴权

### 5.1 账号与租户上下文

登录 Token 只表达 SaaS 账号和认证版本，不把永久角色/门店权限写死在 Token。登录后调用：

```text
POST /api/v1/auth/context/select
  -> 返回短期 tenantContextToken
     { accountId, tenantId, organizationId?, storeId?, authorizationVersion, expiresAt }
```

后续请求携带 `Authorization` 与 `X-Tenant-Context`。服务端从签名上下文和 IAM 关系得到租户、组织、门店，不信任请求体中的 `tenantId`。切换租户/门店必须重新校验角色和数据范围；角色变更通过授权版本或短 Token 立即生效。

内部服务请求必须携带 `requestId`、`tenantId`、`operatorId`、`authorizationVersion` 和服务身份签名。消费者、定时任务和导出任务必须显式保存租户上下文。

### 5.2 权限码

首发权限码按 `module.resource.action`：

```text
tenant.tenant.manage
iam.user.manage / iam.role.manage
catalog.item.manage / catalog.price.manage
resource.manage / resource.schedule.manage
reservation.view / reservation.create / reservation.cancel
order.create / order.settle / order.void / order.reverse_settlement
hotel.stay.check_in / hotel.stay.check_out
ktv.session.open / ktv.session.close
spa.service.start / spa.service.complete
payment.collect / payment.refund.request / payment.refund.approve
deposit.collect / deposit.refund
shift.close / daily_close.submit / daily_close.review
report.view / report.export / audit.view
tenant.payment_channel.configure
marketing.campaign.manage / marketing.coupon.issue
```

服务端必须同时校验权限码、作用域和业务状态。Admin 菜单隐藏不是授权；订单、支付、MQ 消费和导出任务均重新鉴权。

## 6. 数据与迁移实施

### 6.1 统一数据约束

- 所有租户业务表 `tenant_id NOT NULL`，唯一键包含租户边界；高频索引首列使用 `tenant_id`。
- 组织和门店字段作为数据范围条件，异步消息、缓存 key、对象路径和报表任务均带租户。
- 金额使用最小货币单位或高精度 decimal，禁止浮点；订单固化币种、汇率、税率和价格快照。
- 资金表采用追加流水和冲正，不直接覆盖余额；支付回调和业务命令使用稳定幂等键。
- 敏感证件、商户密钥、银行信息加密存储，查询、导出和解密均写审计。

### 6.2 Flyway 与迁移顺序

每个权威服务维护自己的 Flyway 历史表和迁移目录。首发先创建新域空表，不修改旧预约、用户和消息历史表。迁移批次：

1. `tenant/iam`：租户、组织、门店、角色、权限、上下文和审计。
2. `resource`：资源、排班、占用和冲突唯一约束。
3. `order`：统一订单、明细和三种履约表；预约通过关联字段逐步接入。
4. `payment`：支付、退款、押金、班次、日结和对账流水。
5. `customer/marketing`：租户客户、会员、积分、权益、储值、活动和同意。

所有迁移必须有空库启动测试、重复启动测试、回滚恢复演练和租户隔离 SQL 测试。禁止跨服务直接插入别人的表。

## 7. 统一订单与 KTV 履约实现（首发）

### 7.1 公共订单

公共订单只保存：租户/组织/门店、订单号、业态、客户、商业状态、币种、金额汇总、已收/应退汇总、创建/完成/取消时间、创建人、版本号和审计字段。订单明细统一表达商品、服务、套餐、房费、加钟和附加费。

状态：`DRAFT → WAITING_PAYMENT/WAITING_ARRIVAL → SERVING → WAITING_SETTLEMENT → COMPLETED`；异常为 `CANCELLED`、`VOIDED`、`PARTIAL_REFUNDED`、`REFUNDED`。业态专属状态不写入公共 `status`。

### 7.2 履约最小字段与动作

| 业态 | 履约事实 | 动作 |
| --- | --- | --- |
| KTV（首发） | 包厢、预约时段、开台时间、计时规则（计费单位/起算/暂停是否计费/超时加价）、加项、结台时间 | open-session、pause/resume（如配置）、add-item、close-session |
| 酒店（后续） | 房间、房型、入住人、入住/退房时间、钟点房规则、押金关联、房态 | check-in、续住、换房、加项、check-out、清洁确认 |
| 足浴（后续） | 足浴房、项目、技师、服务会话、开始/结束、加钟 | assign、start-service、add-time、complete-service、settle |

资源占用在资源服务事务中判定；订单服务通过内部命令创建/绑定占用。任何冲突返回可识别的 `RESOURCE_OCCUPIED`，客户端不得自行覆盖。

## 8. 支付、押金与资金一致性

支付适配器统一暴露：create intent、query、refund、verify callback、test connection。渠道配置按“主体录入、门店启用”，密钥只在 payment 服务加密托管。

支付流程：`CREATED → PROCESSING → SUCCEEDED/FAILED/CLOSED`；退款：`REQUESTED → PROCESSING → SUCCEEDED/FAILED`。支付和退款均固化 `merchantAccountId`、渠道、币种、汇率和费率快照。

酒店押金独立账本：`COLLECTED → AVAILABLE → APPLIED/REFUNDED → SETTLED`。收银员只能发起退款申请；店长/财务按权限审批；线下退款和反结必须有原因、复核人与审计。

支付回调采用签名校验、幂等键、原文摘要、状态机合法性和金额校验。回调重复、乱序或渠道未知状态不得覆盖已完成资金事实。

## 9. Admin 后台实施方案

### 9.1 后台上下文与入口

后台分三个独立范围：**IM 后台**（`im-admin-service`，属 IM 产品）与 **SaaS 后台**（`platform-admin-service`，属 SaaS 产品）；SaaS 后台内部按权限区分**平台运营后台**与**租户后台**。三者领域与鉴权严格切分，IM 后台与 SaaS 后台独立。

**统一入口**：三个后台共用一个登录入口，登录后按账号权限路由——账号拥有多个后台权限时展示后台入口选择页（IM 后台 / 平台运营后台 / 租户后台），仅拥有单个后台权限时直接进入该后台；权限判断由后端返回，前端不自行推断，避免后台入口分散杂乱。

SaaS 后台统一由 `platform-admin-service` 提供 BFF：

```text
/api/v1/admin/sdk/**  平台运营、平台营销、平台审计
/api/v1/admin/tenant/**    租户、组织、员工、角色、门店、会员、营销
/api/v1/admin/store/**     资源、商品、预约、订单、收银、资金
/api/v1/admin/reports/**   租户/组织/门店报表
/api/v1/admin/audit/**     审计查询
```

平台角色只能访问平台范围；租户管理员先选择租户/组织/门店上下文，再访问对应路由。BFF 只做参数校验、权限上下文透传、响应聚合和旧契约适配；领域服务负责最终鉴权和业务事务。

### 9.1.1 后台切分与渐进演进

IM 后台与 SaaS 后台独立：现有 `im-admin-service` 保持为 IM 产品后台（积分/媒体/用户管理），不再扩展为 SaaS 后台；SaaS 后台由新增 `platform-admin-service` 承担，内部按权限区分平台运营后台与租户后台。

| 阶段 | 变化 | 兼容与退出条件 |
| --- | --- | --- |
| A：现状固化 | 盘点现有 `im-admin-service` Controller、权限枚举、菜单、Mapper、前端调用与 OpenAPI 快照，冻结 IM 后台边界 | 现有 IM 后台接口、登录行为不变 |
| B：SaaS 后台新建 | 新建 `platform-admin-service`，实现平台/租户上下文解析、菜单 BFF、审计与 `/api/v1/admin/**` 路由 | 平台运营、租户老板、店长、收银员、财务登录后只见并只调其范围内 API |
| C：领域接入 | SaaS BFF 只调用 Identity、Tenant、Resource、Order、Payment、Member、Marketing 的 API | 不在 SaaS Admin 新增领域 Mapper、PO、Flyway 或权威写表 |
| D：既有预约后台迁移 | 现有 `/admin/reservations/**` 属 SaaS 业务，随预约账号/数据归属从 IM 后台迁至 SaaS 后台 | 预约后台页面与接口契约兼容，灰度切换 |

SaaS Admin 可做聚合读模型和响应适配，但不能绕过领域服务修改订单、支付、资源、会员或 IAM 权威数据；需要跨领域编排时由对应领域应用服务或明确的流程编排契约承担，而非写入 Admin Controller。

### 9.2 首发后台菜单

平台：租户、商户主体、国家/币种、渠道能力、平台营销、平台积分预留、支持工单、平台审计。

租户：工作台、组织门店、员工角色、商品服务、价格套餐、资源排班、预约、订单、会员、营销、收银资金、交班日结、报表、审计和门店设置。

门店：工作台、房态/包厢/足浴资源、快速开单、订单操作、入住/开台/服务、收款/押金、退款申请、交班。

### 9.3 Admin API 最小矩阵

| 编号 | 方法与路径 | 领域 |
| --- | --- | --- |
| ADM-01 | `POST /auth/context/select` | IAM/租户 |
| ADM-02 | `GET /admin/sdk/tenants`、`POST /admin/sdk/tenants` | 平台运营 |
| ADM-03 | `GET/POST/PUT /admin/tenant/organizations`、`/stores` | 租户 |
| ADM-04 | `GET/POST/PUT /admin/iam/users`、`/roles` | IAM |
| ADM-05 | `GET/POST/PUT /admin/catalog/items`、`/prices` | 目录 |
| ADM-06 | `GET/POST/PUT /admin/resources`、`/schedules` | 资源 |
| ADM-07 | `GET/POST /admin/reservations`、`POST /admin/reservations/{id}/arrival` | 预约 |
| ADM-08 | `GET /admin/orders`、`GET /admin/orders/{id}`、`POST /admin/orders/{id}/void` | 订单 |
| ADM-09 | `POST /admin/hotel/stays/{id}/check-in`、`check-out` | 酒店 |
| ADM-10 | `POST /admin/ktv/sessions/{id}/open`、`close` | KTV |
| ADM-11 | `POST /admin/spa/sessions/{id}/assign`、`complete` | 足浴 |
| ADM-12 | `GET /admin/payments`、`POST /admin/refunds`、`POST /admin/refunds/{id}/approve` | 资金 |
| ADM-13 | `POST /admin/shifts/open`、`close`、`POST /admin/daily-closing/submit` | 收银 |
| ADM-14 | `GET/POST /admin/members`、`/campaigns`、`/coupons` | 会员营销 |
| ADM-15 | `GET /admin/reports/operations`、`/payments`、`/resources` | 报表 |
| ADM-16 | `GET /admin/audits` | 审计 |

所有写接口使用 `Idempotency-Key`；成功返回 `requestId` 和领域结果；失败统一 `{code,message,requestId,retryable,fieldErrors}`。列表统一分页；时间使用 RFC3339；金额使用字符串或最小单位，不能使用浮点。

### 9.4 通用能力后台（配置与审计）

通用支撑域的能力（媒体、审核、邮件、短信、支付渠道）统一提供后台配置与审计页面，挂平台后台菜单下，按角色分配权限：

| 能力 | 平台运营可配 | 租户可配/可开关 | 审计 |
| --- | --- | --- | --- |
| 支付渠道 | 渠道全局能力开关（微信/支付宝/Stripe） | 租户商户号、门店启用、退款能力开关 | 渠道配置变更、验签失败、回调异常 |
| 短信 | 短信服务商（阿里云/腾讯云等国内及国外）、模板 | 短信签名/模板审批、发送开关 | 发送记录、失败重试、退订 |
| 媒体 | 存储适配（OSS/COS/MinIO）、扫描策略 | 用量/配额 | 上传/访问授权审计 |
| 审核 | 审核规则、回调 | 不开放（平台统一） | 审核命中、人工复核 |

- **支付可开关**：首发接入微信支付、支付宝、Stripe（海外），三者均按租户可开关配置；租户开通前不可见、不可发起。渠道生产密钥由平台加密托管，租户只配置商户号与门店级启用。
- **短信可配置**：`common-sms-service` 开发好能力并预留待配置参数；后台可配置阿里云、腾讯云等国内及国外短信服务商，模板与签名走平台审批。
- **角色分配**：通用能力后台菜单与写操作统一走 IAM 权限码（如 `common.payment_channel.configure`、`common.sms.configure`、`common.audit.view`），平台运营与租户老板按作用域分配；租户不可见其他租户的渠道配置。

## 10. 事件与可靠性

领域服务在同一数据库事务写入业务事实和 Outbox；Relay 发布 RocketMQ。事件至少包含 `eventId、eventType、tenantId、storeId?、aggregateId、occurredAt、schemaVersion`。

首发事件：租户开通、门店启用、预约创建/到店、订单创建/完成、资源占用变化、支付成功、退款成功、押金变化、交班提交、会员积分变化和营销券核销。

消费者必须按 `eventId` 幂等；跨服务不做分布式事务。订单和支付事实以权威服务为准，报表和通知允许最终一致，必须显示数据时点。

## 11. 实施批次与任务清单

### WP-01 基线与上下文

- [ ] 盘点现有服务、数据库、REST、MQ、Gateway、Admin 页面和 C 端预约契约。
- [ ] 建立租户/IAM 表、Flyway、上下文 Token、RBAC 中间件和租户 SQL 测试。
- [ ] 为现有管理员建立兼容角色映射；普通 C 端登录行为不变。

### WP-02 Admin BFF 与租户开通

- [ ] 完成平台/租户/门店路由、菜单权限和上下文切换。
- [ ] 完成租户、组织、门店、员工、角色和商户主体接口。
- [ ] 完成开箱默认配置和幂等初始化脚本。

### WP-03 资源与订单

- [ ] 完成资源、排班、占用冲突。
- [ ] 完成统一订单与 KTV 履约表、状态机和预约兼容关联（酒店/足浴后续）。
- [ ] 完成 KTV 一条可结算闭环（酒店、足浴后续）。

### WP-04 支付与收银

- [ ] 完成现金、支付宝、微信、Stripe 适配接口和配置校验。
- [ ] 完成支付回调、退款、押金、班次、日结和对账。
- [ ] 完成重复回调、重复退款、支付失败和线下退款审批测试。

### WP-05 会员、营销与报表

- [ ] 完成租户客户、会员等级、积分、权益和同币种储值。
- [ ] 完成优惠券、折扣、会员价、手工/事件发券和核销。
- [ ] 完成运营、支付、资源和库存预警基础报表；平台积分只预留边界。

### WP-06 B 端 App 联调

- [ ] 按 [SAAS_PLATFORM_03_APP](SAAS_PLATFORM_03_APP.md) 冻结接口完成联调。
- [ ] 完成弱网、权限、门店切换、重复提交和收银安全测试。

## 12. 验收与发布门槛

- 空库可由各服务 Flyway 初始化，重复启动不产生重复默认数据。
- 既有 C 端登录、预约、消息和既有 Admin 核心流程回归通过。
- 任一账号无法读取其他租户订单、客户、支付、会员或营销数据。
- KTV 完成开台/履约/结算；资源冲突和金额计算由后端保证（酒店、足浴后续）。
- 支付回调、退款、押金和日结具备幂等、审计和可对账流水。
- Admin 平台与租户菜单、API、数据范围和高风险审批一致。
- B 端 App 所需 API 全部有 OpenAPI 快照、契约测试和端到端用例。
- 监控包含请求关联、租户、订单、支付、事件和审计定位字段；日志不记录密码、Token、密钥和完整支付敏感数据。

发布顺序：集成环境契约测试 → 预发布空库与 KTV E2E → Admin 与 B App 联调 → 小范围租户灰度 → 扩大灰度。数据库只追加前向迁移；每批发布保留制品、配置、备份和恢复演练记录。
