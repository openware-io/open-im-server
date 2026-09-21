# SaaS 第二阶段 DDD 聚合与服务实现规范

> **变更记录（v2）**：①新增 identity 限界上下文（`platform-identity-service`），User 上下文退回 IM 域；②Transaction Fulfillment 聚合收敛为 KTV 首发，酒店/足浴为后续。

## 1. 方案集与依据

| 项目 | 内容 |
| --- | --- |
| 方案集 | `SAAS_PLATFORM` |
| 顺序号 | `09` |
| 实施边界 | 领域聚合、应用用例、Repository 端口、领域事件与服务内部目录 |
| 前置方案 | [SAAS_PLATFORM_02_SERVICE](SAAS_PLATFORM_02_SERVICE.md)、[SAAS_PLATFORM_04_DATA](SAAS_PLATFORM_04_DATA.md)、[SAAS_PLATFORM_05_API](SAAS_PLATFORM_05_API.md) |
| 工程依据 | [业务微服务 DDD 工程规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)、[工程规范](../ENGINEERING_RULES.md) |

本文将业务设计约束落实为 `api -> application -> domain <- infra`。Controller 只做输入校验、上下文提取与 DTO 转换；Application 只编排用例、事务、幂等、Repository 端口和 Outbox；Domain 持有不变量与状态迁移；Infra 承担 PO/Mapper、缓存、MQ、RPC 和渠道适配。

## 2. 限界上下文与服务边界

| 服务域 | 限界上下文 | 不拥有的事实 |
| --- | --- | --- |
| identity | Platform Identity & Entry：SaaS 平台账号、登录、凭据、OAuth/第三方登录、用户信息同步、账号绑定 | IM 聊天数据、租户角色、订单/支付/会员事实 |
| tenant | Tenant Access：租户、组织、门店、商户主体、角色和作用域授权 | 订单、客户消费、支付交易、资源占用 |
| resource | Resource Scheduling：资源、排班、时段占用 | 订单金额、支付、会员权益 |
| order | Transaction Fulfillment：预约、订单、明细、KTV 履约（首发）/酒店/足浴（后续） | 支付渠道交易、押金余额、角色定义 |
| payment | Funds Collection：支付、退款、押金、班次、日结（通用支撑域·支付域） | 商品定价、订单履约字段、会员等级 |
| customer | Tenant Loyalty：租户客户、会员、积分、权益、储值账户 | 平台积分、平台营销、订单价格计算 |
| marketing | Tenant Marketing：活动、优惠券、同意、发放与核销资格 | 订单总金额、支付交易、会员余额 |
| saas-admin | SaaS Admin BFF：平台运营后台 + 租户后台（权限区分）适配、聚合查询 | 上述任一领域权威写表 |

`Tenant Access` 是一个限界上下文，内部以 `tenant`、`authorization` 子域组织代码和表；其余上下文各自使用独立 `im-services/`、`platform-services/`、`common-services/` 下各自的 `<domain>/<service>-api + <service>-service` 服务域，不能将多个无关可部署服务堆入同一父 POM。IAM 的角色、权限、范围和授权变更由 Tenant Access 权威维护；其他领域只消费签名上下文、本地授权快照和授权变更事件，禁止直接访问 `iam_` 表或将每次业务鉴权实现为同步 IAM RPC。

现有 User 上下文退回 IM 域，继续拥有 IM 账号认证与认证失效；`user.role`/`ROLE_*` 仅服务 IM 后台，不属于 SaaS 授权，也不做迁移。SaaS 平台账号由新增的 identity 上下文（`platform-identity-service`）权威维护；IM 仅作为 C 端入口之一（OAuth + 用户信息同步），不作为 SaaS 账号权威。IM 后台（`im-admin-service`）与 SaaS 后台（`platform-admin-service`）独立，IM 后台不在本 SaaS 限界上下文表中。

## 3. 聚合、命令与不变量

### 3.1 Tenant Access

| 聚合根 | 内部实体/值对象 | 不变量 | 主要命令 | 领域事件 |
| --- | --- | --- | --- | --- |
| `Tenant` | Organization、StoreRef、LegalEntityRef | 门店只能归属本租户一个组织；关闭租户前不得有进行中资金交易 | CreateTenant、ActivateTenant、SuspendTenant、CreateStore | TenantCreated、TenantActivated、StoreCreated、StoreActivated |
| `AuthorizationAssignment` | RoleGrant、DataScope、EffectivePeriod | 分配角色不能超出授予者范围；有效期/状态必须合法 | AssignRole、RevokeRole、ChangeScope | RoleAssigned、RoleRevoked、AuthorizationChanged |
| `MerchantAccount` | ChannelCapability、CredentialReference | 账户只归属一个主体/渠道/环境；密钥不可读取 | RegisterMerchantAccount、VerifyConnection、EnableForStore | MerchantAccountRegistered、MerchantAccountVerified |

Repository 端口：`TenantRepository`、`AuthorizationAssignmentRepository`、`MerchantAccountRepository`。授权 Token 解析是 Infra 技术适配；授权范围是否允许由 `AuthorizationAssignment` 领域策略决定。

### 3.2 Resource Scheduling

| 聚合根 | 内部实体/值对象 | 不变量 | 主要命令 | 领域事件 |
| --- | --- | --- | --- | --- |
| `Resource` | ResourceAttributes、AvailabilityState | 资源类型不可在使用中变更；停用时不得存在有效占用 | CreateResource、ChangeStatus、ConfirmCleaning | ResourceCreated、ResourceStatusChanged |
| `ResourceOccupation` | OccupationPeriod、HoldExpiry | 同资源有效时段不可重叠；仅占用所有者可确认/释放 | HoldResource、ConfirmOccupation、ReleaseOccupation、ExpireHold | ResourceHeld、ResourceOccupied、ResourceReleased |
| `Schedule` | ShiftPeriod | 技师服务时段必须在有效排班内 | AssignShift、CancelShift | ResourceScheduled、ResourceScheduleCancelled |

占用冲突校验由 `ResourceOccupation` 聚合与 Repository 的锁定查询协作完成。应用层不能以“先查再插”替代聚合/锁策略。

### 3.3 Transaction Fulfillment

| 聚合根 | 内部实体/值对象 | 不变量 | 主要命令 | 领域事件 |
| --- | --- | --- | --- | --- |
| `Reservation` | ReservationPeriod、Contact | 到店前可取消；转换订单后不能重复转换 | CreateReservation、ConfirmReservation、ConfirmArrival、CancelReservation | ReservationCreated、ReservationConfirmed、ReservationArrived、ReservationCancelled |
| `BusinessOrder` | OrderItem、Money、PriceSnapshot、OrderStatus | 明细、币种和总额一致；完成后不可直接编辑；版本必须匹配 | CreateOrder、AddItem、QuoteSettlement、CompleteOrder、VoidOrder | OrderCreated、OrderItemAdded、OrderSettlementQuoted、OrderCompleted、OrderVoided |
| `KtvSession`（首发） | BillingClock、RoomAssignment | 仅已开台可暂停/恢复/结台；计时不可倒退；计费单位/起算/暂停不计费/超时按快照 | OpenSession、PauseSession、ResumeSession、AddItem、CloseSession | KtvSessionOpened、KtvSessionClosed |
| `HotelStay`（后续） | Guest、StayPeriod、RoomAssignment | 仅入住状态可续住/换房；退房后必须进入清洁流程 | CheckIn、ExtendStay、ChangeRoom、CheckOut | HotelCheckedIn、HotelRoomChanged、HotelCheckedOut |
| `SpaSession`（后续） | TherapistAssignment、ServiceDuration | 技师/房间必须可用；仅服务中可加钟 | AssignTherapist、StartService、AddTime、CompleteService | SpaTherapistAssigned、SpaServiceStarted、SpaServiceCompleted |

`BusinessOrder` 不加载和修改完整履约聚合；Application 用例通过稳定 ID、状态校验和领域事件协调 `KtvSession`（首发）与后续 `HotelStay`、`SpaSession`。跨聚合资源占用由 Resource 服务命令完成，失败采用明确补偿而不是跨库事务。

### 3.4 Funds Collection

| 聚合根 | 内部实体/值对象 | 不变量 | 主要命令 | 领域事件 |
| --- | --- | --- | --- | --- |
| `PaymentIntent` | PaymentMethod、MerchantRoute、Money | 同一用途同一幂等键只创建一次；成功不回退失败 | CreateIntent、AcceptCallback、CloseIntent | PaymentIntentCreated、PaymentSucceeded、PaymentFailed |
| `RefundRequest` | RefundAmount、Approval | 原交易可退款金额不得超限；申请人与审批人分离 | RequestRefund、ApproveRefund、RejectRefund、CompleteRefund | RefundRequested、RefundApproved、RefundSucceeded |
| `DepositAccount` | DepositEntry、Money | 流水余额不可为负；抵扣/退还只使用可用余额 | CollectDeposit、ApplyDeposit、RefundDeposit、ReverseEntry | DepositCollected、DepositApplied、DepositRefunded |
| `CashShift` | CashCount、ChannelSummary | 同一终端同一时间只能一个开放班次；关班差额需原因 | OpenShift、CloseShift、ReviewDailyClosing | ShiftOpened、ShiftClosed、DailyClosingReviewed |

渠道回调 DTO 仅在 Infra 层转换为 `PaymentCallbackCommand`；领域层不依赖渠道 SDK、HTTP、JSON 或数据库 PO。

### 3.5 Tenant Loyalty 与 Tenant Marketing

| 上下文 | 聚合根 | 不变量 | 领域事件 |
| --- | --- | --- | --- |
| customer | `TenantCustomer` | 待认领客户只有验证后关联 SaaS 平台账号；不可跨租户合并经营资料 | TenantCustomerClaimed |
| customer | `PointAccount` | 流水追加；冻结积分不可重复抵扣；余额不可为负 | PointsEarned、PointsRedeemed、PointsReversed |
| customer | `WalletAccount` | 同主体/同币种/共享范围才可使用；首发不跨币种 | WalletRecharged、WalletConsumed、WalletRefunded |
| marketing | `Campaign` | 活动期间和范围合法；承担方明确 | CampaignActivated、CampaignEnded |
| marketing | `CouponGrant` | 同券/客户限额不可重复；核销一次 | CouponGranted、CouponRedeemed、CouponReleased |

订单优惠计算由 Order 的价格策略执行；Marketing 只返回活动资格、规则快照和券锁定结果，不能直接修改订单金额。订单完成/退款通过事件触发积分、券核销或反向处理。

## 4. Service 内部目录与对象隔离

每个 `im-<domain>-service` 必须采用：

```text
api/controller + api/dto + api/converter
application/command + query + result + service + assembler
domain/model/aggregate + entity + valueobject
domain/repository + service + policy + event + exception
infra/persistence/po + mapper + converter + repository
infra/messaging + rpc + cache + config
```

禁止 DTO/Command/Result/PO/MQ Payload/领域对象跨层替代。Repository 接口位于 `domain.repository`，Mapper 只处理 PO，Application 不导入 MyBatis Wrapper、PO 或 Mapper，领域对象不使用 Spring、MyBatis、Redis、MQ 或 `@Data`。

## 5. 应用用例与事务边界

| 用例 | 事务内操作 | 事务外/异步操作 |
| --- | --- | --- |
| 创建预约 | Reservation 聚合、资源 Hold 命令记录、Outbox | 通知、看板投影 |
| 入住/开台/派技师 | 履约聚合状态、订单状态、Outbox | 资源确认补偿、通知 |
| 结算支付 | 订单报价/版本校验、支付意图、Outbox | 渠道跳转/回调 |
| 支付回调 | PaymentIntent、交易流水、订单支付汇总、Outbox | 通知、报表投影 |
| 退款 | RefundRequest 审批/流水、Outbox | 渠道退款；回调完成最终状态 |
| 订单退款后会员回滚 | Order 退款事件消费、PointAccount/CouponGrant 领域命令 | 统计投影 |

事务只标记在 Application 用例入口。跨服务同步调用必须通过对方 `*-api` Client，并有超时、失败语义和幂等键；不能把远程 DTO 传入领域对象。

## 6. 测试映射

| 层 | 必测内容 |
| --- | --- |
| Domain | 每个聚合的不变量、状态迁移、值对象校验、领域事件 |
| Application | 幂等、事务边界、Repository 端口、Outbox、补偿和权限结果 |
| Infra | Flyway、PO 映射、Mapper 锁查询、缓存失效、渠道回调、MQ 消费幂等 |
| API | 参数校验、上下文、DTO 转换、HTTP 错误码、权限与分页 |
| E2E | KTV、资金、双租户隔离、Admin、B App、旧 C 端回归 |

## 7. 强制评审清单

- [ ] 新服务拥有一个明确限界上下文、服务父 POM、API 和 Service 模块。
- [ ] 每张新表映射到一个权威聚合或其内部实体，未出现跨域 Mapper。
- [ ] 每条状态转换位于聚合/策略，不在 Controller 或 Mapper。
- [ ] 每个跨服务副作用通过版本化 API 或领域事件，且具有幂等与补偿语义。
- [ ] 每个聚合至少有领域单元测试和状态转移测试。
- [ ] 新增领域事件、MQ 资源、OpenAPI、服务 README、Flyway 在同一变更同步更新。
