# SaaS 第二阶段实施执行清单

> **变更记录（v2.0.0）**：①新增 `platform-identity-service`（SaaS 账号/OAuth/同步）与 IM 入口工作包；移除 `user.role → IAM` 迁移任务；②首发业态收敛为 KTV，酒店/足浴履约与押金账本后置；③确立通用支撑域（`common-services/`，`common-media-service`→`common-media-service`）与支付域（支撑域子域：支付业务 + 支付渠道，下探一层）；④版本统一 2.0.0，主干合并后建 `develop/2.0.0-saas-20260826` 开发分支。
>
> **脚手架实现进度（E0–E5 已落库）**：identity 注册/登录标识 + IAM（角色/权限/用户角色）+ 租户上下文过滤器（4 服务注册）+ 资源占用（半开区间）+ KTV 统一订单/计时计费（纯函数 `UnitTimeFeeCalculator`）/结算 + 支付（组合收款、线上渠道默认关、对账摘要）。**后置项**：JWT 签发、线上渠道回调验签/渠道对账文件比对、储值/积分余额校验（E6）、全链路 E2E 与 2.0.0 发版。

## 1. 目的

本文将 `SAAS_PLATFORM_01` 至 `06` 转换为可派工、可验收、可发布的执行计划。每个工作包只有在其输入契约、代码归属、Flyway、测试和回滚条件齐全时才允许开始；不得以页面先行或直接插表替代领域实现。

## 2. 工程落点

| 工作内容 | 当前工程落点 | 交付物 |
| --- | --- | --- |
| 公共上下文/鉴权 | `sdk/infrastructure`、`sdk/common` | `TenantContext`、上下文解析器、审计/幂等通用接口；不放业务规则 |
| SaaS 账号/入口 | 新增 `platform-services/identity/platform-identity-api`、`platform-identity-service` | SaaS 账号、登录、OAuth、用户信息同步、账号绑定 |
| 租户/IAM | 新增 `platform-services/tenant/platform-tenant-api`、`platform-tenant-service` | 租户、门店、IAM、Flyway、内部契约 |
| 资源 | 新增 `platform-services/resource/platform-resource-api`、`platform-resource-service` | 包厢（首发）资源、排班、占用、冲突控制 |
| 订单 | `platform-im-services/order/platform-order-api`、`platform-order-service` | 统一订单、KTV 履约（首发）、预约兼容 |
| 支付（业务） | 新增 `common-services/payment/common-payment-api`、`common-payment-service` | 支付意图、退款、押金、班次、日结 |
| 支付（渠道） | 新增 `common-services/payment/common-payment-channel-service` | 微信/支付宝/Stripe 渠道适配、验签、回调（支付域·渠道） |
| 会员/营销 | 新增 `platform-services/customer/*`、`platform-services/marketing/*` | 租户客户、账本、活动、优惠券 |
| IM 后台 | `im-services/admin/im-admin-api`、`im-admin-service` | IM 积分/媒体/用户管理（IM 产品） |
| SaaS 后台 | 新增 `platform-im-services/admin/platform-admin-api`、`platform-admin-service` | 平台运营/租户 Admin REST 适配，不拥有领域表 |
| 通用支撑（媒体） | `common-services/media/common-media-service`（由 `common-media-service` 改名） | 媒体对象、上传会话、存储适配、访问授权 |
| 通用支撑（短信/邮件/审核） | `common-services/sms/*`、`common-services/mail/*`、`common-services/audit/*` | 短信可配置（阿里云/腾讯云等）、邮件、内容审核 |
| Gateway | `gateways/gateway` | `/api/v1` 路由、上下文透传、限流、路由测试 |
| 契约 | `docs/contracts/openapi/*.json`、`docs/mq/REGISTRY.md`、`sdk/protocol-mq` | OpenAPI 快照、MQ Registry、事件 DTO/Schema |
| B 端 App | 当前 Flutter 工程的 B 端 target | 见 `SAAS_PLATFORM_03_APP` |

新增聚合服务必须按现有父 POM 结构提供 `*-api` 和 `*-service`，并在对应父目录注册（IM→`im-services/pom.xml`、平台→`platform-services/pom.xml`、通用支撑→`common-services/pom.xml`）；API 模块只承载稳定 DTO、客户端与接口契约，不放 PO、Mapper、Flyway 或领域实现。

## 3. 依赖顺序

```text
E0 现状盘点/契约基线（含 IM/SaaS 边界与预约账号归属）
 └─ E1 SaaS 账号(identity) + 租户/IAM/上下文
     ├─ E2 Gateway + Admin 上下文
     ├─ E3 包厢资源与占用
     │   └─ E4 统一订单 + KTV 履约
     │       └─ E5 支付（现金先行）、班次、日结
     └─ E6 会员/营销
E2 + E4 + E5 ──→ E7 B 端 App 联调
E1 ──→ E-IM IM 入口打通（OAuth + 用户信息同步，可与 E7 并行）
E0 + E-IM ──→ E-MIG 预约迁移（Expand→Backfill→Verify→Switch，IM 库→SaaS 库，历史预约数据回填到平台自建「存量迁移租户」，后续按门店映射分发）
全部 ──→ E8 灰度/发布
```

会员/营销可与资源/订单并行开发，但涉及订单优惠/积分抵扣的写路径只能在 E4 的金额快照契约冻结后接入。B 端 App 可在 E2 后开始登录、菜单和只读资源页面，在 E4/E5 契约完成后接入资金与履约写操作。

## 4. 工作包与完成定义

### E0：现状盘点与基线冻结

**任务**

- [ ] 导出当前 `user`、`order`、`admin` OpenAPI 快照并存入 `docs/contracts/openapi/`；新增服务在 Controller 实现后导出各自快照，禁止手工伪造快照。
- [ ] 列出现有 Flyway 表、服务所有权、外部 REST、内部 HTTP、MQ Topic、Gateway 路由及 C 端/App Admin 调用点。
- [ ] 为现有预约、登录、消息和 Admin 核心流程建立回归用例编号。
- [ ] 在 `docs/mq/REGISTRY.md` 登记新增 Topic、Producer Group、Consumer Group、顺序键、幂等键和消费目的；同步在 `protocol-mq` 定义常量，不创建未使用生产者。
- [ ] 为每个新增服务创建 `docs/im-services/<service>/README.md`，声明职责、入口、依赖、关键指标、告警、关联 ID 与故障处置入口。
- [ ] 盘点 IM 与 SaaS 耦合点：`ord_reservation` 无 `tenant_id` 的账号归属、`user.role`/`ROLE_*` 在 IM 后台的使用范围、C 端 App 对 IM 登录的依赖；冻结 IM/SaaS 边界、IM 库与 SaaS 库的数据分界，以及 C 端预约数据/账号迁移策略。
- [ ] 冻结代码仓库与目录切分：IM 产品代码留在 `im-server`（`im-services/`）；平台（SaaS）产品代码在 `im-server` 内新增 `platform-services/` 目录承载；**代码目录独立**（同仓库分目录隔离，SaaS 模块禁止依赖 im-services、CI 校验依赖方向），独立仓库作为后续演进选项；公共依赖（`sdk/infrastructure`、`common`、`protocol-mq`）维持公共结构。平台服务统一 `platform-*` 命名（不带 `im-` 前缀）。
- [ ] 执行目录/命名迁移：`im-services/`→`im-services/`、`sdk/`→`sdk/`、`common-media-service`→`common-media-service`，产出迁移清单 + 命名校验脚本（同步清理非 SaaS 文档旧名 `common-media-service` 15 处、`sdk/im-*` 5 处）。

**完成定义**：评审通过的现状清单、OpenAPI 基线、MQ 台账和服务 README 模板；任何后续设计可明确指出“保留、适配、迁移或废弃”的现有契约。

### E1：SaaS 账号、租户、IAM 与上下文

**输入**：`SAAS_PLATFORM_04_DATA` 第 3、4 节，`SAAS_PLATFORM_05_API` 第 2 至 4 节。

**任务**

- [ ] 建立 `platform-services/identity` 聚合（`platform-identity-service`）：SaaS 账号、登录、凭据、会话、OAuth/第三方登录、用户信息同步、账号绑定；不依赖 `im-user-service`。
- [ ] 建立 `platform-services/tenant` 聚合、API/Service 模块、独立 Flyway history 和健康检查。
- [ ] 实现租户、组织、门店、商户主体、收银终端、角色、权限、用户角色分配。
- [ ] 确认 `user.role`/`ROLE_*` 仅服务 IM 后台（积分/媒体/用户管理），不作为 SaaS 授权；SaaS IAM 从零建设，不做迁移。
- [ ] 在 `infrastructure` 实现 `TenantContext`、Context Token 校验、MyBatis 租户拦截和无上下文拒绝策略。
- [ ] 实现权限/数据范围快照缓存、授权变更事件逐出和高风险动作的 IAM 在线复核；禁止普通领域请求逐条同步查询 IAM。
- [ ] 实现 `GET /auth/contexts`、`POST /auth/context/select`、平台与租户 Admin API。
- [ ] 实现默认初始化：组织、首门店、预置角色、权限、基础模板。

**测试**：双租户 CRUD、跨门店越权、角色变更即时失效、Token 过期、SQL 缺租户条件拒绝、默认初始化幂等。

**阻塞条件**：没有 E1 不得创建任何新的租户经营订单、资金或会员表。

### E2：Gateway 与 SaaS 后台 BFF

**任务**

- [ ] Gateway 配置并测试平台、租户、门店、B App 路由；不向客户端暴露内部服务地址。
- [ ] 新建 `platform-admin-service`：平台/租户上下文解析、菜单 BFF、请求校验和领域客户端；`im-admin-service` 保持 IM 后台不动。
- [ ] 保持既有 `/admin/reservations/**` 兼容路径（随预约迁移逐步转至 SaaS 后台）；新增 `/api/v1/admin/**` 不改变历史语义。
- [ ] 固化 IM 后台（`im-admin-service`）Controller、菜单和 OpenAPI 基线；`ROLE_*` 继续服务 IM 后台，SaaS 后台鉴权统一切换到 IAM。
- [ ] 建立 SaaS Admin 权限矩阵（平台/租户/门店）、审计拦截器和导出任务授权快照。

**完成定义**：平台运营、租户老板、店长、收银员、财务登录后只看到并只能调用其范围内 API；BFF 没有任何新领域 Mapper/表。

### E3：资源与占用

**任务**

- [ ] 创建资源、排班、占用表和 Flyway，完成房间、包厢、足浴房、技师建模。
- [ ] 实现 `HELD/RESERVED/IN_USE/RELEASED` 占用状态、事务锁、过期释放任务和 Outbox。
- [ ] 实现 Admin/B App 资源看板和排班/资源管理接口。

**完成定义**：100 个并发请求抢同一资源/时段时最多一个成功；超时、取消、结单和清洁确认按规则释放或转换状态。

### E4：统一订单与 KTV 履约（首发）

**任务**

- [ ] 在 `platform-order-service` 追加 `ord_order`、`ord_order_item`、`ord_ktv_session`，不修改历史预约语义（酒店/足浴履约表后续）。
- [ ] 实现公共订单状态机、乐观锁、金额快照和订单明细。
- [ ] 实现预约到店/转订单关联；旧 C 端预约接口保持原请求响应。
- [ ] 实现 KTV 开台、计时（计费单位/起算/暂停/超时）、加项、结台。

**完成定义**：KTV 不含支付的履约闭环通过；资源服务和订单服务的失败补偿、重复命令、状态冲突均可恢复。

### E5：支付与收银（现金先行，渠道后置）

**任务**

- [ ] 建立 payment 聚合和渠道适配 SPI；首发实现现金结算，支付宝、微信、Stripe 作为独立专项后置（配置与能力查询预留）。
- [ ] 实现支付意图、回调验签、查询补偿、退款审批、班次、日结和对账摘要（酒店押金账本后续）。
- [ ] 实现组合收款：现金 + 储值币/代币 + 积分逐笔拆分、每笔 ≤ 剩余应收、服务端返回剩余；金额最小货币单位整数运算，抵扣顺序「优惠→积分→储值币/代币→现金」，舍入让利消费者，储值/积分抵扣前校验余额，储值扣减/积分冻结/现金差额**同事务**（同库同事务内完成，失败整体回滚，不跨库）。
- [ ] 在订单结算中接入支付状态，不允许客户端设置已支付。
- [ ] 完成渠道 sandbox 测试、回调乱序/重放测试和真实生产配置审核流程。

**完成定义**：一次支付/退款/押金命令重复提交不重复记账；回调异常不改变成功交易；日结可追溯到订单、渠道交易和班次。

### E6：会员、营销与报表

**任务**

- [ ] 建立租户客户、待认领客户、会员等级、积分/储值账本与权益范围。
- [ ] 实现同币种储值、会员价、折扣、满减、优惠券、发放与核销。
- [ ] 实现营销同意、基础统计、经营/支付/资源利用率报表。
- [ ] 平台积分仅保留领域接口和数据隔离，不开放运营规则。
- [ ] 员工档案与业绩：员工（服务人员/收银/店长等）档案与角色分配，业绩（开单/服务/收款/加项）只读聚合到统一订单/资金维度，跨业态可汇总（D-13）；首发不含提成比例，提成口径后置。

**完成定义**：退款/作废能反向处理相关优惠与积分；跨租户、跨主体、跨币种储值均被拒绝；报表标注数据时点。

### E7：B 端 App 联调

**任务**：按 `SAAS_PLATFORM_03_APP` 的 `APP-WP-01` 至 `05` 实施；每个 `APP-*` 接口通过 OpenAPI 契约测试、真机 E2E 与权限测试。

**完成定义**：店长、收银、前台/预约、服务员和财务分别完成其角色用例（房务为酒店场景，后续）；离线不发起资金写操作；发布不影响 C 端 App。

### E-IM：IM 第三方授权与入口打通（应用级 + 用户级 OAuth）

**任务**

- [ ] 实现 IM 开放平台：第三方应用（SaaS）注册、`appId`/`appSecret` 发放、授权 `scope` 登记；接入即视为已获 IM 应用级授权（等价微信开放平台里的第三方应用）。
- [ ] 实现 IM OAuth Provider（授权页、code/token 端点）与 SaaS OAuth Client（Authorization Code + PKCE）——用户级授权。
- [ ] 实现 `UserProfileChanged` 事件（授权范围内）与 userinfo 拉取，落 `idt_` 资料与账号绑定关系。
- [ ] C 端预约账号归属迁移：预约走 SaaS Token；C 端 App 同时持有 IM Token（聊天）+ SaaS Token（预约/交易）。
- [ ] 验证 SaaS 在 IM 不可用时仍可完整运行（独立注册/登录、无聊天依赖）。

**完成定义**：SaaS 可在 IM 开放平台注册为第三方应用并获得 `appId`/`appSecret`；C 端可“用 IM 账号登录 SaaS”并在 scope 内同步资料；解绑/撤销授权后 SaaS 业务数据保留；IM 宕机不影响 SaaS 运行。

### E8：预发布、灰度与上线

**任务**

- [ ] 空库初始化、旧数据回填演练、备份恢复和前向修复迁移。
- [ ] KTV、支付（现金）、权限、租户隔离、Admin/B App/C App 的全链路 E2E。
- [ ] 基于租户/门店 Feature Flag 灰度，监控与告警生效。
- [ ] 完成渠道生产验证、密钥轮换、应急预案和人工退款流程演练。

**完成定义**：所有 `SAAS_PLATFORM_01` 第 17.4 节完成标准与 `SAAS_PLATFORM_06` 第 13 节特殊验收均通过。

## 5. 每个工作包的代码交付模板

每个服务工作包必须提交：

1. Maven 模块及 README；
2. Flyway 前向脚本、索引说明和空库测试；
3. `api -> application -> domain <- infra` 分层实现；
4. Controller 实现后导出的 OpenAPI 变更与 `docs/contracts/openapi/<service>.json` 快照；
5. 内部 HTTP DTO/Client，禁止泄漏 PO；
6. Outbox、事件 DTO、`protocol-mq` 资源常量、MQ Registry 变更及消费者幂等；
7. 单元、集成、权限、并发、幂等和回归测试；
8. 运行配置、密钥变量样例、监控指标、告警和回滚说明。

## 6. 不允许的快捷实现

- 不在 `platform-admin-service`（SaaS 后台）编写交易、支付、资源 Mapper 或 Flyway。
- 不在 Gateway 编排订单、支付或资源业务。
- 不用请求体租户 ID 绕过上下文鉴权。
- 不用前端金额、前端资源状态或 Redis 锁作为最终事实。
- 不直接更新余额、积分或押金，必须追加账本。
- 不以双写跨服务替代 Outbox/幂等补偿。
- 不修改已执行 Flyway；不为迁移删除现有 C 端契约。
- 不把 `im-user-service` 账号当作 SaaS 账号权威；不把 `user.role` 迁移成 SaaS IAM 角色；不在 SaaS 领域读 IM 聊天/好友/群组数据。
- 不在 B App 离线执行资金、审批、入住或资源占用写操作。

## 7. 方案集完成判定

详细方案在文档层已完整；代码实施完成必须满足：所有 E0-E8 完成定义均有测试/报告证据，OpenAPI/MQ/Flyway/部署/服务 README 同步更新，且一阶段 C 端和后台回归通过。实现必须同时通过 `SAAS_PLATFORM_09_SERVICE` 的 DDD 评审清单。任一资金、租户隔离、资源并发或旧契约回归项失败，二阶段不得宣布完成。
