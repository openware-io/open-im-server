# SaaS 菜单、权限与租户总部实施计划（2.2.0）

> **状态**：执行稿 v1.8；P0–P7-C2 的代码实现已完成，P8 进入最终验证收口。P1/P2 已采用 V9 两段式菜单、功能域父节点、声明式剪枝和空父节点剔除；P8 仍以 Kind 部署、浏览器验收、规范门禁及双仓库远端同步为完成条件。
> **适用分支**：`develop/2.2.0-auth`
> **配套方案**：`SAAS_MENU_PERMISSION_01_ADMIN.md`（方案 A）、`SAAS_MENU_PERMISSION_02_SERVICE.md`（服务端权限规格）、`SAAS_TENANT_HEADQUARTERS_01_SERVICE.md`（方案 B）
> **目的**：把方案 A/B 拆成可以逐批开发、测试、发布和回滚的工程任务。本文是实施顺序和门禁的唯一入口；业务归属仍以配套方案为准。
> **本轮发布口径**：这是无线上存量数据的开源大版本，实施完成并通过全量验证后直接全量推送；不做旧接口并行和线上兼容过渡，但仍必须保留可执行的代码/迁移回滚步骤。

## 1. 先冻结的工程基线

### 1.1 规范优先级

发生冲突时按以下顺序处理：

1. `docs/ENGINEERING_RULES.md`（生效工程规范）；
2. `docs/standards/00_DDD_MICROSERVICE_SOFTWARE_ENGINEERING_OUTLINE.md`、`10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md`、`20_MAVEN_ENGINEERING_CONVENTIONS.md`、`21_PERSISTENCE_STACK.md`、`43_E2E_TESTING_CONVENTIONS.md`；
3. `docs/business/ACCOUNT_PERMISSION_MODEL.md`（账号权限业务基线）；
4. 本实施计划；
5. 方案 A/B 的具体页面和接口设计。

任何无法同时满足的条款必须进入第 12 节决策清单，不得由实现人员自行“兼容”。

### 1.2 不改变的硬约束

- 现有业务路由 `path` 不改；菜单只改变分组、顺序和入口可见性。
- 权限 `code` 不改；存量 44 个权限码保持原值，不能从 `module/resource/action` 三列反推 code。
- 菜单不是安全边界；服务端必须继续通过 `TenantContext`、权限码和数据范围返回 403。
- 业态不复制角色；使用 `scope_level × domain_code` 两轴模型。
- `platform.operator` 的现状 44/44 全量持有必须被显式登记和 CI 保护，不得未经决策删除。
- 跨服务只依赖稳定 `*-api`、内部 HTTP 或可靠事件；禁止 `*-service` Maven 依赖、跨库 Mapper 和 Admin 直查领域表。
- MySQL 是权威库；Flyway 只增不改，迁移脚本只放在拥有数据的 `*-service`。
- 新写接口使用显式 Command、`Idempotency-Key`/`commandId`、乐观版本、标准错误、审计和 requestId。

### 1.3 领域归属修正

客户、积分、储值三类能力的权威数据均属于 **Customer 域**（`platform-customer-service`，表前缀 `cst_`）。Payment 域只提供收款/退款等支付事实和渠道能力，不负责 `cst_wallet_*`、`cst_point_*` 的账户与流水模型。总部 BFF 可以编排各领域 API，但不能把这些表迁移到 Admin 或 Payment。

## 2. 目标架构和边界

```text
客户端
  └─ gateway
      └─ platform-admin-service（菜单/上下文/总部 BFF 编排）
          ├─ platform-tenant-api/service（租户、组织、门店、IAM 上下文）
          ├─ platform-customer-api/service（客户、积分、储值及汇总）
          ├─ platform-order-api/service（订单/预约汇总）
          ├─ common-payment-api/service（收款/退款汇总）
          └─ common-audit-api/service（审计落库）
```

### 2.1 权限判定链

```text
账号 → 角色 → iam_user_role(授权作用域)
     → iam_role_permission → iam_permission 元数据
     → 权限快照（按租户/组织/门店上下文及 business_type）
     → 菜单声明式剪枝（仅入口）
     → 领域接口 PermissionGuard + 数据范围校验（最终安全边界）
```

### 2.2 总部与门店操作边界

- `TENANT` 总部：经营总览、筛选、对比和下钻，首版只读。
- `STORE` 门店：客户关系、积分/储值明细和写操作，所有写入从签名的 `TenantContext.storeId` 取门店。
- 客户主档租户内唯一；本期只记录 `cst_member.origin_store_id` 作为首次新增门店，不引入未确认的多店关系表。
- 积分/储值余额租户内客户共享；每笔流水必须记录实际操作 `store_id`，不能用门店行相加冒充共享余额。
- 配置读取顺序固定为“门店覆盖 > 业态默认 > 租户默认”；多店设置是一次性 Command 展开写入多条门店覆盖，不创建持久化门店组。
- 本期不引入跨门店商品/服务共享目录和跨门店履约调用。

## 3. 发布批次总览

每个批次必须单独提交、单独验证；后续批次只能依赖已通过的前置批次。

| 批次 | 交付目标 | 是否改数据库 | 可独立验证 | 回滚边界 |
| --- | --- | --- | --- | --- |
| P0 | 规范、基线、契约和快照冻结 | 否 | 是 | 删除本批文档/契约分支 |
| P1 | 菜单树化（仍由 Java 提供） | 否 | 是 | 回退 Admin 前后端发布 |
| P2 | 菜单数据库化与最终 v1 菜单下发 | `iam_menu` 新表 | 是 | 回退提交并按统一重建脚本重建开发库 |
| P3 | 权限/角色作用域元数据和非法授予拦截 | 加列/回填 | 是 | 停用元数据剪枝，保留列 |
| P4 | 门店数据地基与上下文业态权威 | 分服务迁移 | 是 | 停止新增归因写入，保留可空列；无线上数据不做旧接口并行 |
| P5 | 客户首次建档门店、积分/储值流水门店归因及门店范围 | Customer 域迁移 | 是 | 关闭门店过滤写路径；不删除新列 |
| P6 | 总部总览 BFF、筛选、下钻 | 可选只读投影/无结构变更 | 是 | 关闭总部入口 |
| P7 | 三层配置和多店批量设置 | 各所属域迁移 | 是 | 关闭批量写开关，保留覆盖行 |
| P8 | 前端同步、发布门禁和旧入口清理 | 否/清理迁移 | 是 | 回退提交并按统一重建脚本重建开发库 |

P1/P2 以 V9 菜单结构实际发布并通过浏览器验收为完成条件；不得再以路由存在或旧平铺 `iam_menu` 数据代替。P3 已完成作用域元数据和非法授予拦截。P4A 已完成上下文 `businessType/timezone` round-trip；P4B 已完成门店业态正式写路径；P4C 已完成支付事实门店归因。P5 已完成 Customer 门店归因、写权限、流水范围和同事务 Outbox。P6 已完成 Tenant/Customer/Order/Payment 领域实时汇总、Admin v1 BFF、内部 v2 HMAC 契约、部分失败语义及 SaaS Admin 总览原型。由于本次无线上数据，所有批次完成后统一全量发布，不保留旧新接口并行周期。

## 4. P0：工程基线和契约冻结

### 4.1 工作项

1. 为每个变更服务建立“现状目录、依赖图、数据库所有权、迁移编号、测试入口”清单：`platform-admin`、`platform-tenant`、`platform-customer`、`platform-order`、`common-payment`、`common-audit`、`open-saas-admin`。
2. 固定新增权限登记表：`code`、`scope_level`、`domain_code`、`grantable_levels`、`menu_code`、默认角色、审计动作、接口守卫。
3. 固定现有 v1 总部 API、菜单 API、Customer 汇总 API、Context 响应和错误码契约；本次直接修改 v1 契约，不新增同功能 v2；契约只放对应 `*-api`，不能放 PO/Entity/Mapper。
4. 记录当前 44 个权限、5 个角色、菜单 19 项、现有路由和前端权限映射快照，作为回归基线。
5. 仅为必要的开发验证保留短期开关：`iam.scope.metadata.enabled`、`business-type-filter.enabled`、`customer.store-scope.enabled`、`tenant.overview.enabled`；不设置 `menus.v2` 开关，最终 v1 菜单接口直接切换。

### 4.2 P0 验收

- `scripts/validate/invoke-engineering-validation.ps1` 在未改代码分支通过，或将存量失败登记为基线差异，不得静默跳过。
- API/Service 依赖图无新增业务 `*-service` 依赖。
- 所有新增权限均能从登记表追溯到菜单、角色、守卫、审计和测试。
- 现状快照可在测试中复现，后续每批次都必须比较差异。

## 5. P1：菜单树化，不动权限口径

### 5.1 实现范围

- `platform-admin-service`：把现有 19 项按方案 A 组装为“租户段/门店段”，补 `children`、`sortNo`、`domainCode`；仍使用 Java 静态定义。
- `platform-admin-api`：扩展菜单 DTO，但不删除旧字段。
- `open-saas-admin`：新增两段式渲染、分隔线、租户/门店上下文标题；门店切换先保持整页 reload。
- 不改数据库、权限快照 SQL、后端原有 `GET /admin/menus` 响应形状和所有业务路由。

### 5.2 测试与门禁

- 后端：菜单树结构、19 项数量、租户/门店归属、旧接口 401/200/scope 回归。
- 前端：所有现有 path 可达；无门店上下文不渲染门店段；现有徽标和待处理数量不丢失。
- 更新位置耦合测试，改为按 `code/path/scope` 断言，不按数组下标断言。
- 直接访问业务接口的 403 测试必须继续通过，证明菜单改动没有替代接口鉴权。

### 5.3 退出条件和回滚

退出条件是后端单测、前端单测、路由扫描和工程验证全部通过。回滚只回退前端/菜单树代码，不涉及数据恢复。

## 6. P2：`iam_menu` 和声明式菜单下发

### 6.1 数据和接口

1. 在 `platform-admin-service` 所属迁移目录建立 `iam_menu`（菜单自身归 Admin 域），使用 `code` 唯一键和 `parent_code`，字段至少包括 `scope_level`、`domain_code`、`required_permission`、`required_grant`、`path`、`sort_no`、`i18n_key`、审计字段。
2. 种子使用幂等 SQL；Flyway 版本按模块独立编号，不使用全仓全局编号。
3. 保持现有有效菜单接口 v1 路径和名称，直接替换其实现为数据库菜单查询和声明式剪枝；不新增 v2。无客户端、测试或脚本消费者的旧菜单接口/兼容代码可以在本批次删除，删除前必须完成全仓引用扫描。
4. 剪枝顺序固定：上下文 → scope → `domain_code/business_type` → 权限快照 → 父节点空子节点删除 → sort。
5. `path` 叶子必须命中 `open-saas-admin/src/router/index.js`；分组节点 path 必须为空。

### 6.2 验收

- 二次执行种子无数据变化；重复请求响应稳定。
- 租户/组织上下文没有 `storeId` 时，租户级菜单不会被错误剪掉；门店级菜单 fail-closed。
- 未授权用户直接调用被隐藏 path 仍返回 403。
- CI 完成菜单 code 唯一、父子无环、权限存在、路由存在、ACTIVE 权限至少被角色持有六项校验。

## 7. P3：权限元数据、角色和授予校验

### 7.1 迁移顺序

1. `iam_permission` 增加 `scope_level/domain_code/grantable_levels/menu_code`，先允许默认值用于回填。
2. 按 44 个实测权限清单回填，生成 diff 清单人工复核。
3. `iam_role` 增加角色 `scope_level/domain_code`，回填 5 个预置角色；不新增业态角色。
4. 回填完成后移除默认值，新增权限必须显式提供元数据。
5. `IamController.assignUserRole/assignPermissions/togglePermission` 统一校验：目标作用域属于 `grantable_levels`、租户管理员不能提交 PLATFORM、门店 ID 属于当前租户且有效；失败返回 `INVALID_GRANT_SCOPE`。
6. `selectPermissionCodes` 仅在 P4 之后启用 `business_type` 过滤；P3 不改变权限集合，先保证元数据和授予安全。

### 7.2 验收

- 44 个权限 code、角色持有量和 `platform.operator` 44/44 全量与基线一致。
- 非法 PLATFORM/TENANT/STORE 授予均为 400/403，不产生半条绑定。
- 授权写入幂等，审计包含操作者、目标账号、scope、权限 code、requestId。
- 元数据剪枝关闭时，旧权限快照行为不变；打开后只改变菜单和显式业态过滤，不旁路 PermissionGuard。

## 8. P4：门店数据地基与业态权威

### 8.1 必做数据基础

- 派生订单/支付子表按所属服务补 `store_id`、`business_type`：先可空双写，再分批回填，再一致性校验，最后才可置 `NOT NULL`。
- `iam_audit_log` 增加可索引 `store_id`；旧审计记录按可推导关系回填，否则保留 NULL 并在查询中明确“历史不可按店筛选”。
- `mkt_campaign_scope` 由 Marketing 域维护；不得由 Admin 直接建表。
- `tnt_store.business_type` 增加正式写路径、权限和审计；以门店配置为唯一权威，订单/预约入参不一致时 422 `BUSINESS_TYPE_MISMATCH`，不接受请求体覆盖。
- Context DTO 增加 `businessType`、`timezone`；切换上下文必须强制刷新权限快照和菜单缓存。

### 8.2 分阶段验收

| 阶段 | 数据状态 | 必须通过 |
| --- | --- | --- |
| 4A | 上下文契约 | Tenant/Admin 全量测试通过；签发、验签、前端选择器保留 `businessType/timezone` |
| 4B | 门店业态写路径 | 新增 V29 索引；PUT v1 可选 `businessType`，仅 ACTIVE 字典值可写；成功/失败审计、权限快照失效；Tenant 88+1 项测试通过 |
| 4C | 支付门店归因 | Payment V11 为 `pay_collect/pay_transaction/pay_refund` 增加可空 `store_id` 和索引；新写路径双写，历史不可推导值保留 NULL；Payment 125 项测试通过 |
| 4D | 订单/预约权威校验 | 创建路径以签名上下文业态为准，缺省请求体使用上下文值，显式错配返回 `BUSINESS_TYPE_MISMATCH`；Order 定向回归通过 |

P4 目前仍有一项跨域数据治理留在后续清单：对可可靠推导的历史支付/订单归因进行一次性开发库回填，并在回填后决定是否收紧 `NOT NULL`。本次无线上历史数据，不以兼容旧接口或长期双写开关作为发布前置条件。

### 8.3 P4 实际变更清单

- Tenant：V29 为 `tnt_business_type(status, code)` 增加校验索引；门店 PUT v1 支持可选 `businessType`，仅 ACTIVE 业态可写，成功后清理权限快照。
- Order：订单、预约创建以签名上下文 `businessType` 为权威，显式错配返回 422 `BUSINESS_TYPE_MISMATCH`。
- Payment：V11 为组合收款、交易、退款增加可空 `store_id` 及租户/门店/时间索引；新写路径从签名上下文归因，历史不可推导行保留 NULL。

## 9. P5：Customer 域门店化

### 9.1 数据模型

由 `platform-customer-service` 持有 Flyway 和实体：

1. `cst_member` 新增 `origin_store_id`，只记录首次新增门店；本期不创建 `cst_member_store` 多店关系表，客户主档仍在租户内跨店共享，门店流水按实际操作门店归因。
2. `cst_point_ledger`、`cst_wallet_ledger` 新增可空 `store_id`；账户表不增加门店列，继续表达租户内共享余额。
3. 记录 `origin_store_id` 时必须明确它是来源信息，不作为数据隔离条件；流水 `store_id` 才表示实际操作门店。
4. 历史数据中经审查确认为垃圾数据的客户、积分/储值账户及其仅由该垃圾数据产生的附属记录，可以在 Customer 域一次性清理；清理前必须生成候选清单、关联阻断检查、数量快照和审计记录。存在真实订单、支付、IM 绑定、非垃圾流水或其他领域引用时不得直接删除，必须走领域清理命令或保留数据。
5. 迁移脚本只放 Customer Service；Admin/Payment 只消费 Customer API。

### 9.2 应用层改造

- Controller 只做 DTO 校验和上下文提取；应用服务负责事务、幂等、权限结果使用、聚合加载和端口调用。
- 领域对象负责客户关系唯一性、积分余额不为负、储值余额/币种不变量和流水只追加；禁止在应用层复制可复用业务规则。
- 所有客户主档列表默认按租户共享；积分/储值流水明细在有 `storeId` 时按实际操作门店过滤。客户写入和账户写入无 `storeId` 统一返回 `STORE_CONTEXT_REQUIRED`。
- 积分调整、储值充值/消费/退款忽略或拒绝请求体中的门店字段，以 `TenantContext.storeId` 写流水。
- 每次成功写入必须与本域 Outbox/审计事件在同一业务事务边界内完成；跨服务审计由可靠投递或既有审计 API 最终落库，不能伪造跨库事务，也不能采用“写库后 best-effort 发 MQ”。

### 9.3 测试

- 领域单测：客户关系幂等、共享账户、跨店操作门店归因、余额不变量、币种不一致拒绝。
- 应用测试：重复 command 不重复扣款/加分；业务事实与 Outbox 同事务；审计投递失败可重试且不重复记账。
- API 测试：租户越权、门店越权、无门店上下文、请求体伪造 storeId、403/400 错误码。
- 数据测试：新流水 `store_id` 非空且等于签名上下文；同一客户跨店仍是一份主档并保留首次来源门店；共享余额不重复统计。

### 9.4 当前执行状态

- P5 已完成：Customer V9 增加 `cst_member.origin_store_id`、`cst_wallet_ledger.store_id`、`cst_point_ledger.store_id`；客户首次建档和积分/储值流水写入读取已验签上下文；Outbox、垃圾清理、门店流水读范围和写权限守卫均已落地。Customer reactor 全量 123 项测试通过。
- P6 当前进度：Tenant 门店/业态/状态汇总、Customer 去重客户/共享余额/门店流水汇总、Order 订单成交/已收汇总、Payment 支付事实汇总、Admin v1 BFF 和领域客户端均已完成；所有内部汇总接口统一使用 SDK v2 HMAC + 签名 `TenantContext`，Admin 对领域 5xx/超时返回 `PARTIAL` 与失败码而不读旧缓存。SaaS Admin 总览已展示客户、积分、储值、订单和支付事实。Customer 124 项、Payment 129 项、Tenant 95 项、Order 562 项、Admin 172 项测试通过；前端 48 个测试文件/687 项和生产构建通过。`origin_store_id` 已记录首次新增门店，客户主档仍跨店共享；本期不引入 `cst_member_store`，因此不宣称多店客户关系模型完成。
- `origin_store_id` 只表示首次新增门店，不替代未来需要多店关系时的关系表；本期不把账户余额按门店拆分。

## 10. P6：总部经营总览与下钻

### 10.1 服务职责

`platform-admin-service` 只做权限、上下文、参数校验、并行调用和响应聚合；Customer/Order/Payment/Tenant 各自提供稳定的只读汇总 API。总部的“实时”定义为同一请求内读取各领域当前权威服务返回的数据，不允许 Admin 直连领域数据库、跨库 SQL、异步旧投影或循环调用门店明细接口拼装。

### 10.2 API 实施顺序

1. 先修改现有 v1 总部 API 契约并登记 `tenant.overview.view`；各领域服务读取自己的当前权威库并返回结果，Admin 不直连数据库；响应返回查询完成时间 `updatedAt`、整体 `dataStatus` 和各分项查询耗时/失败状态。
2. 先接 Tenant 门店/业态列表；再接 Customer 去重客户数、共享积分/储值余额及门店流水；再接订单/支付经营额。
3. 所有 `storeIds` 先做当前账号作用域校验；跨租户、无效业态和无权限不可用空结果掩盖，分别返回标准 400/403。各领域调用设置独立超时、最大门店数量和总响应时限；超时不回填旧缓存，返回 `PARTIAL` 或明确失败。
4. “下钻”只发起一次受保护的 Context 选择，然后跳转现有门店 path；总部不携带隐藏字段绕过门店上下文。

### 10.4 已落地内部契约

- Tenant：`GET /internal/tenant/overview?storeIds=&businessType=`，返回租户内门店 `id/code/name/businessType/status`。
- Customer：`GET /internal/customer/overview?from=&to=&storeIds=`，返回租户去重客户数、共享积分/储值余额及按门店流水变动。
- Order：`GET /internal/order/overview?from=&to=&businessType=&storeIds=`，按门店/业态/币种返回订单数、成交金额 `total_amount` 和已收金额 `paid_amount`。
- Payment：`GET /internal/payment/overview?from=&to=&storeIds=`，按支付渠道/币种返回成功笔数和成功金额。
- 所有 `/internal/{tenant,customer,order,payment}/**` 请求均由 SDK v2 内部 HMAC 过滤器验签；Admin 不跨库查询。领域 5xx/超时在 BFF 响应中标记 `dataStatus=PARTIAL` 和 `failures`，不回填缓存。

### 10.3 验收

- 多业态、多门店筛选结果只来自当前租户；门店行客户数与租户去重客户数可分别复核。
- 共享余额不按门店累加；金额统一使用租户币种（默认 USD）；不做跨国汇率换算。
- 总部入口只读；任何客户/积分/储值写操作必须在 STORE context 且命中对应权限。
- 下钻后刷新菜单和权限快照；切换门店不会残留上一门店权限。

## 11. P7：三层配置和多店批量设置

### 11.0 当前实施进度

- **P7-A 已完成**：`tnt_tenant_config` 增加 `business_type` 维度和配置登记表；营业时间读取实现“门店覆盖 > 业态默认 > 租户默认 > 代码缺省”；批量门店写入先校验租户归属和业态一致性，再在同一事务内展开写入。
- **P7-B 已完成首批**：`tnt_pricing_plan` 和 `pay_channel_config` 增加租户默认、业态默认、门店覆盖三层作用域；内部 v1 读取按门店 > 业态 > 租户解析，写入校验租户/门店业态边界，并支持版本冲突与幂等键。菜单已从平台订阅方案入口调整为租户「KTV 配置」。
- **P7 批量写入闭环已完成**：计价方案下沉 Tenant 域 `/internal/pricing-plans/batch`，支付渠道下沉 Payment 域 `/admin/payment-channels/batch`；Admin v1 BFF 只做 DTO 转发，并在转发 Payment 前调用 Tenant `/internal/pricing-plans/store-scope/validate` 完成门店租户/业态预校验；Payment 负责批量参数、渠道合法性和事务展开；前端多店设置已改用批量端点，不再逐店循环调用。
- **P7-C1 已完成**：积分/预约经营规则的领域配置、三层读取、门店归因和交易校验均已接入；积分获得由收款确认事件驱动，抵扣换算由服务端负责。
- **P7-C2 已完成**：Payment 退款/日结规则与 Order 作废审批规则均已接入领域表、内部 v1 端口、Admin BFF 和 SaaS Admin 配置页；直接作废在生效规则要求审批时返回 `VOID_APPROVAL_REQUIRED`，审批通过复用受保护的作废执行入口。
- **P7 整体实现完成**：仍需通过本轮 P8 的部署、浏览器和远端同步门禁后才可作为发布完成项勾选。

### 11.3 P7-C 已确认实施与验收说明

P7-C 不直接把所有“设置”强行三层化，而是按领域归属、数据事实和风险拆分：

#### P7-C1：低风险经营配置

第一批建议纳入：

| 配置项 | 所属领域 | 允许作用域 | 读取优先级 | 说明 |
| --- | --- | --- | --- | --- |
| 积分获得规则 | Customer | 租户/业态/门店 | 门店 > 业态 > 租户 | 由交易发生门店决定规则，不按客户来源门店决定。 |
| 积分抵扣规则 | Customer/Payment | 租户/业态/门店 | 门店 > 业态 > 租户 | 与组合支付校验一致，不能由前端自行合并。 |
| 积分有效期 | Customer | 租户/业态 | 业态 > 租户 | 暂不允许单店覆盖，避免同一客户跨店规则不一致。 |
| 积分抵扣上限 | Customer | 业态/门店 | 门店 > 业态 | 由门店经营策略决定。 |
| 预约提前时间 | Order | 租户/业态/门店 | 门店 > 业态 > 租户 | 影响预约创建校验。 |
| 取消/改期窗口 | Order | 租户/业态/门店 | 门店 > 业态 > 租户 | 影响订单状态和退款规则，需返回标准错误。 |
| 储值品牌展示名 `wallet_brand_name` | Tenant | 仅租户 | 租户 | 储值账户和余额仍是租户共享资产，不做业态/门店覆盖。 |

P7-C1 明确不纳入：客户多店关系、账户拆分、积分余额拆分、门店客户标签、资源档案和商品库存。

当前进度：Customer/Order 已分别持有积分与预约规则表，提供内部 v1 读写端口；Admin KTV 配置页已提供三层规则编辑，积分抵扣上限、预约创建/取消窗口、积分获得规则计算、规则快照/按流水失效字段和预约改期已接入服务入口。积分获得由收款确认可靠事件消费者触发，积分抵扣倍率金额换算由服务端完成，客户端不自行换算。

#### P7-C2：高风险资金与审批配置

第二批单独实施：

| 配置项 | 所属领域 | 允许作用域 | 风险 |
| --- | --- | --- | --- |
| 退款审批阈值 | Payment | 租户/业态/门店 | 影响资金授权边界，必须审计。 |
| 线下退款是否允许 | Payment | 租户/业态 | 影响资金风险策略。 |
| 作废审批要求 | Order | 业态/门店 | 影响订单终态和库存/资源释放。 |
| 日结时间 | Payment | 门店 | 受门店营业日切点影响。 |
| 日结复核角色 | IAM | 角色/权限 | 属于授权模型，不作为普通配置值。 |

当前进度：Payment 已完成退款规则和门店日结切点的领域表、内部 v1 端口、Admin BFF 与租户 KTV 配置页。退款阈值用于标记高风险，不绕过现有“店长/财务审批”状态机；线下退款仍受独立开关约束。日结切点已接入日结汇总窗口。Order 已新增 `ord_order_void_approval` 和 `ord_void_rule_config` 领域表及 v1 申请、查询、批准执行、驳回、规则读取/保存接口；申请人与审批人分离、门店/租户隔离、版本乐观锁、幂等键和审批后复用既有作废释放/收款校验均已落地。现有 `/business/orders/{id}/void` 在生效规则要求审批时返回 `VOID_APPROVAL_REQUIRED`，审批批准走独立的 `voidOrderApproved` 执行入口，避免规则自拦截。

#### P7-C 统一工程约束

- 每项配置登记 `config_key`、`owner_service`、`value_type`、`scope_policy`、`merge_strategy`、版本策略和审计动作。
- 数据迁移只放所属领域服务；Admin 只能通过稳定 v1 BFF/内部契约调用，禁止跨库 Mapper。
- 每个配置使用显式 DTO/Command、幂等键、乐观版本和标准错误码。
- 批量写入先一次性校验租户、门店、状态、业态和权限，任一失败整批拒绝。
- 读取统一使用“门店覆盖 > 业态默认 > 租户默认”，不得由前端合并 JSON。
- 每个子批次必须具备正常、越权、无门店上下文、重复提交、版本冲突和部分失败无写入测试。

#### P7-C 已确认决策（执行基线）

1. P7-C 按 C1 后 C2 两批执行。
2. 积分获得/抵扣规则允许门店覆盖；积分有效期不允许单店覆盖。
3. 预约提前时间、取消/改期窗口允许门店覆盖。
4. 退款审批阈值允许门店覆盖，列入 C2 并强制审计。
5. 储值品牌名保持租户级。

### 11.2 P8 最终收口清单

- [x] 前端批量接口与租户 KTV 配置页面同步，前端 48 个测试文件/687 项通过。
- [x] Tenant 门店范围契约校验端点及跨租户/混合业态测试通过；Payment 全量测试 129 项通过。
- [x] 菜单最终结构的全仓旧入口、旧菜单文案和重复入口扫描；V9 发布后的数据库菜单树与浏览器菜单均已复核，已删除 Admin 中残留的旧平铺菜单死代码；总部总览精确路由已置于泛租户路由之前。
- [x] Admin、Tenant、Payment 受影响模块最终测试和工程变更门禁重新执行并留证：Admin Maven、Gateway 路由测试、前端 687 项/生产构建、Changed 工程规范校验均通过；此前 Tenant/Payment 全量结果仍以本批次记录为准。
- [ ] Order 作废规则新增代码已完成 Order/Admin 编译和 Order 全量测试；待本轮 Admin 全量、前端全量/构建/审计、Changed 规范门禁重新执行并留证。
- [ ] 后端与前端本轮提交、远端推送完成，核对本地/远端提交计数一致。（待本轮收口提交后完成）
- Customer 总览的 `storeIds` 口径已冻结：客户去重数和共享积分/储值余额仍为租户全量，门店筛选只过滤期间流水 delta；前端必须明确展示该口径。

本轮 Kind 运行验收补充：`/api/v1/admin/tenant/overview` 已通过网关精确分流到 Admin v1 BFF，浏览器总部上下文可见客户、积分、储值、订单、支付和 `COMPLETE` 数据状态；为验证储值菜单能力门禁临时写入的租户 `1/100` `WALLET` 授权已清理，当前无临时数据残留。

### 11.1 配置登记

每个配置项由所属领域登记：`config_key`、`owner_service`、`value_type`、`scope_policy`、`merge_strategy`、并发版本策略和审计动作。禁止“通用配置服务”接收任意 JSON 并代写其他域。

### 11.2 写入规则

- 租户默认、业态默认、门店覆盖分别使用显式 Command 和权限。
- 多店请求先一次性校验所有门店租户归属、状态、业态和授权，任何失败整批拒绝。
- 成功后在一个事务中展开写入门店覆盖行；同一 `Idempotency-Key` 可安全重试。
- 读取按“门店覆盖 > 业态默认 > 租户默认”；不得由前端合并配置。

### 11.3 验收

- 单店覆盖不影响同业务其他店；同业态默认能被多店继承；批量失败无部分写入。
- 并发版本冲突返回标准错误，不覆盖较新配置。
- 配置审计包含 scope、storeIds、旧值摘要、新值摘要和 requestId；不得记录敏感明文。

## 12. 决策清单（需要用户确认）

以下是仍会改变数据库、权限或发布行为的决策；已确认的业务结论不重复列入。

| 编号 | 决策 | 推荐值 | 若不确认 |
| --- | --- | --- | --- |
| D-IMPL-1 | 是否允许为门店权限/总部统计回填历史事实数据 | **允许分批在线回填**；不做旧 API 兼容，但必须补齐可推导的 `store_id/business_type` | P4/P5 无法把旧数据纳入门店查询 |
| D-IMPL-2 | 无法可靠推导且确认是垃圾数据的历史记录如何处理 | **允许删除垃圾主档及仅由其产生的关联垃圾数据**；有真实业务引用的记录不得删除；清理前做清单、阻断检查、快照和审计 | 误删真实客户、资产或业务事实 |
| D-IMPL-3 | 是否新增 `cst_member_store` 多店关系表 | **本期不新增；只记录 `cst_member.origin_store_id`，客户信息跨店共享** | 若未来需要显式多店归属，再单独设计关系表和迁移 |
| D-IMPL-4 | 业态入参不一致时采用 400 还是服务端覆盖 | **422 `BUSINESS_TYPE_MISMATCH`**；服务端不覆盖错误请求 | 影响客户端升级策略和错误处理；覆盖会隐藏调用方缺陷 |
| D-IMPL-5 | `platform.operator` 的授权定位 | **保留完整租户级权限**；按租户级管理员使用，新增权限显式登记 + CI 保护 | 若按平台/门店混合语义实现，会破坏现有管理能力 |
| D-IMPL-6 | `iam_menu.code` 冲突的迁移策略 | **允许重命名内部 menu code，保留 path 和权限 code** | 不解决唯一键冲突，`iam_menu` 无法建唯一约束 |
| D-IMPL-7 | 菜单接口版本策略 | **继续使用现有 v1 路径，直接替换实现；无消费者的旧接口/兼容代码删除** | 必须完成全仓引用扫描，避免误删仍被客户端或脚本调用的接口 |
| D-IMPL-8 | 总部首版指标时效 | **实时读取权威领域服务/数据库**；不使用异步投影作为首版结果，返回 `updatedAt`、`dataStatus`/失败状态，并设置超时和查询上限 | 必须接受并行查询和性能门禁，不得用“近实时”替代实时 |
| D-IMPL-9 | 门店业态修改权限归属 | **仅租户管理员/平台受控运维；必须审计并清缓存** | 普通门店人员可改变自身权限菜单口径，存在越权风险 |
| D-IMPL-10 | 存量 DDD 分层技术债是否本期全部重构 | **未接触代码不重构；本次实际修改到的用例顺手治理 DDD 分层问题**。新增/修改用例必须按 `api -> application -> domain <- infra` 实现，触碰的旧代码按同批次迁移 | 若要求全量重构，范围和工期需另行评审 |
| D-IMPL-11 | Customer 首次建档是否必须门店上下文 | **门店建档/充值/积分调整等门店写操作必须有 `TenantContext.storeId`；总部只读和平台清理允许无门店** | 不区分读写会误伤总部实时总览和平台维护任务 |
| D-IMPL-12 | Customer 流水是否本期新增可靠 Outbox | **本期沿用已有审计客户端并补齐可重试投递；若要求严格同事务事实事件，先新增 Customer Outbox 再开放跨域消费** | 仅 best-effort 审计不能作为跨域实时总览事实源 |
| D-IMPL-13 | 客户门店关系模型 | **本期只记录 `cst_member.origin_store_id`（首次新增门店）；不引入多对多关系表，直到出现“客户可显式归属多个门店”的业务需求** | 提前引入关系表会扩大门店过滤、清理和回填范围 |
| D-IMPL-14 | Customer 写权限码 | **本阶段不猜测新增权限码**；现有 IAM 清单只有 `member.pii.view` 与支付权限，没有客户创建/积分调整/储值充值退款的已登记业务权限；先保持 `TenantContext` + 内部服务签名 + 门店上下文 fail-closed，待权限登记表补齐后再统一接入 `PermissionGuard` | 自造权限码会造成菜单、角色、守卫和审计无法追溯 |

## 13. 已识别的规范冲突和处理建议

| 冲突 | 现状 | 处理 |
| --- | --- | --- |
| “不回填历史数据” vs M0/P5 需要 `store_id` 非空 | HQ 方案 B 旧文字与菜单方案 M0 不一致 | 改为“不做旧接口兼容；数据按 D-IMPL-1 决策分批迁移”；无法推导且确认为垃圾的数据按 D-IMPL-2 清理；真实历史保持 NULL |
| 方案表把 customer/payment 并列为 P5 依赖 | 客户/积分/储值表在 Customer 域，Payment 只消费/汇总 | Customer 持有表和迁移；Payment 只提供 API/事件，不跨库 |
| 现有 PO/Mapper 直达应用服务 vs DDD 分层规范 | 存量代码存在技术债 | 未接触代码保留；本次修改到的用例同步抽出领域端口/聚合，按同一批次治理，不扩大到无关模块 |
| 总部 BFF 需要跨域数据 vs 服务边界禁止跨库 | BFF 有编排需求 | 各领域新增只读 API/稳定 DTO；Admin 不持有领域 PO/SQL |
| 规范要求可靠事件 vs 部分现有审计调用为同步客户端 | 审计必须可追溯 | 本批次先保证同事务/既有审计 API 语义；需要异步时使用 Outbox + Relay，禁止“写库后 best effort 发 MQ” |

## 14. 每批次统一交付模板

每个 PR/批次必须同时提交：

1. 变更说明、影响服务、依赖图和数据所有权说明；
2. Flyway 脚本、回填/双写开关、校验 SQL、回滚步骤；
3. API 契约及错误码变更；
4. domain 单测、application 编排测试、API/契约测试、必要的 E2E；
5. 权限登记、菜单关联、审计动作、CI 断言；
6. `invoke-engineering-validation.ps1` 和目标 Maven 验证日志，日志落 `.outputs/logs/`；
7. 发布前后指标、失败处理、降级和回滚演练记录。

## 15. 完成定义（DoD）

只有同时满足以下条件，才能声称本阶段完成：

- 代码、迁移、测试和文档在同一提交链中；
- 工程统一验证入口真实执行并通过；
- 领域边界、Maven 依赖、Flyway 归属和表前缀门禁通过；
- 正常、越权、无上下文、重复命令、并发冲突、部分失败和回滚场景均有证据；
- 数据迁移校验为 0 或有已批准的 NULL/历史不可归属清单；
- 用户界面、接口、权限快照和审计口径一致；
- 发布开关、监控、回滚命令和负责人已记录。

第 12 节所列 D-IMPL-1～10 已确认，因此已进入 P4/P5；后续每个数据子阶段仍必须先完成迁移校验、服务测试和工程规范校验再继续。D-IMPL-10 已确认采用“未接触代码不重构，本次触碰的用例顺手治理 DDD 分层”。
