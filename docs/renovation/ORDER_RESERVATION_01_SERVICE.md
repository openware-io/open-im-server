# IM Order 域预约业务迁移方案

## 1. 目标与已确认决策

本文定义将旧工程 `D:\projects\cnb\gv_chat_server` 的预约能力迁移至本仓库 Java 微服务架构的方案。

以下决策已确认：

1. 在 `im-services/order` 下新建 `im-order-api` 与 `im-order-service`。
2. 预约是 `order` 域的首个子域，不单独拆分为 reservation 微服务。
3. `im-order-service` 是预约业务规则、权威数据、Flyway 脚本、MyBatis 持久化、预约单号、核销、积分协作和领域事件的唯一所有者。
4. 所有 order 域业务表统一使用 `ord_` 前缀。
5. `im-admin-service` 继续对外提供 `/admin/reservations/**`，供既有 `gv_chat_admin` 页面使用；它仅承担后台适配与鉴权，不拥有预约表、Mapper、迁移脚本或预约业务规则。
6. 移动端已经按旧服务完成对接，预约公开 REST 路径、JSON 字段、状态值、校验和错误语义必须保持兼容。
7. 当前没有存量数据需要迁移；新 order 库由 Flyway 在空库上创建。

## 2. 范围

### 本次包含

- App 服务类型、门店、创建预约、我的预约、预约详情和积分规则接口。
- 后台预约订单查询、详情、核销、服务类型、门店和积分规则管理接口。
- 新增 `ord_` 预约表及 order 服务 Flyway 基线。
- order 到 user 的积分扣减、幂等和审计配套改造。
- 后台新预约通知、用户核销通知及积分变动通知。
- 契约、迁移、领域、应用、基础设施和端到端测试。

### 本次不包含

- 通用订单、购物车、支付、退款、库存、履约等完整订单能力。
- 旧预约数据回灌或双写同步。
- 重写 `gv_chat_admin` 的预约页面，或修改其既有后台请求路径。
- 为预约重新设计移动端 API。

## 3. 目标架构与职责边界

```text
移动端
  -> gateway /api/reservations/**
  -> im-order-service
  -> ord_* 预约表
  -> im-user-service 内部积分接口

gv_chat_admin
  -> gateway /api/admin/reservations/**
  -> im-admin-service
  -> 受服务身份保护的预约内部接口
  -> im-order-service
  -> ord_* 预约表

im-order-service Outbox
  -> RocketMQ
  -> im-access-ws / 推送适配器
```

网关继续将 `/api/admin/**` 路由至 `im-admin-service`；不为后台预约增加指向 order 服务的网关路由。仅新增 `/api/reservations/** -> im-order-service` 的移动端路由。

`admin -> order` 使用同步内部 API，必须启用既有内部服务 HMAC 鉴权、超时、请求关联 ID；写操作必须携带幂等键。`im-admin-service` 严禁直接访问任何 `ord_` 表。

## 4. Maven 模块与代码归属

```text
im-services/
  order/
    pom.xml
    im-order-api/
    im-order-service/
```

`im-order-api` 提供供 `im-admin-service` 消费的稳定内部请求、响应和客户端契约；不包含 Spring Boot 应用、PO、Mapper、实体、Flyway 或领域实现。

`im-order-service` 遵循 `api -> application -> domain <- infra` 分层，负责：

- `api.client.reservation`：移动端 HTTP Controller；
- `api.internal.reservation`：后台适配层调用的内部 Controller；
- `application.reservation`：命令、查询和事务编排；
- `domain.reservation`：预约聚合、策略和仓储端口；
- `infra.persistence.reservation`：PO、Mapper 和仓储实现；
- `infra.rpc.user`：带签名的积分客户端；
- `infra.messaging`：Outbox Relay 和事件发布；
- `src/main/resources/db/migration`：order 服务专属数据库脚本。

`im-admin-service` 仅负责请求校验、`SecurityUser` 获取、后台响应映射和 order 内部客户端。它将当前登录管理员 ID 传给 order，用于配置修改与核销审计。

## 5. 数据库与 Flyway

`im-order-service` 使用独立 Flyway 历史表 `order_schema_history`。初始迁移新建：

| 表名 | 职责 |
| --- | --- |
| `ord_reservation_service_type` | 预约服务类型及启停状态 |
| `ord_reservation_store` | 预约门店、所属服务类型及启停状态 |
| `ord_reservation` | 预约聚合及核销结果 |
| `ord_reservation_verify_log` | 不可变核销审计记录 |
| `ord_reservation_config` | 积分抵扣配置 |
| `ord_outbox` | 已提交领域事件的可靠发布 |

未来完整订单能力也统一使用 `ord_`，例如 `ord_order`、`ord_order_item`、`ord_payment`。order 的迁移脚本不得放入 `im-admin-service`、`im-user-service` 或共享模块。

`ord_reservation` 至少包含：唯一 `order_no`、`user_id`、`service_type_id`、`store_id`、联系人信息、预约日期/时段/人数、备注、状态、核销管理员/时间、消费金额、积分抵扣金额/数量/状态、乐观锁版本和审计时间。

必须建立 `order_no` 唯一约束，以及 `user_id + created_at`、`status + created_at`、`service_type_id`、`store_id` 索引。金额以 decimal 存储，对外仍按字符串返回以保持旧客户端契约。

### 5.1 枚举字段设计

对外 JSON 中的 `status`、`pointStatus` 必须保持数值，这是既有 App 和后台页面的兼容要求；它不代表数据库和 Java 代码也使用裸 `int`。

预约属于新建空库。字段类型按语义选择，而不是将所有名为 `status` 的字段一律设计为同一种类型：预约生命周期、积分抵扣和 Outbox 发布状态是 order 域拥有的封闭状态集合，使用 MySQL 8 `ENUM`；服务类型和门店的启停只是二值开关，使用受约束的 `TINYINT UNSIGNED`。这与 `im-user-service` 已使用 MySQL `ENUM` 表达账号状态、角色、好友状态和积分流水类型的实践一致，也避免把布尔开关伪装成业务状态机。

| Java 枚举 | 数据表与列 | MySQL 8.0 类型 | 数据库枚举值 | 既有 API 编码 |
| --- | --- | --- | --- | --- |
| `ReservationStatus` | `ord_reservation.status` | `ENUM('pending_verification','verified') NOT NULL DEFAULT 'pending_verification'` | `pending_verification`、`verified` | `1=待核销`，`2=已核销` |
| `ReservationPointStatus` | `ord_reservation.point_status` | `ENUM('not_deducted','deducted','deduction_failed') NOT NULL DEFAULT 'not_deducted'` | `not_deducted`、`deducted`、`deduction_failed` | `0=未抵扣`，`1=已抵扣`，`2=抵扣失败` |
| `OrderOutboxStatus` | `ord_outbox.status` | `ENUM('pending','published','failed') NOT NULL DEFAULT 'pending'` | `pending`、`published`、`failed` | 不对外暴露 |

`ord_reservation_service_type.enabled`、`ord_reservation_store.enabled` 与 `ord_reservation_config.deduct_enabled` 都是开关，使用 `TINYINT UNSIGNED NOT NULL DEFAULT 1` 并分别加 `CHECK (... IN (0, 1))`。为了兼容旧接口，Controller 将 `enabled` 显式映射为响应中的 `status: 0/1`。MySQL 的 `BOOLEAN` 只是 `TINYINT(1)` 别名，因此不使用 `BOOLEAN`。

Java 枚举必须包含明确的数据库值和 API 数值编码，例如 `PENDING_VERIFICATION("pending_verification", 1)`，而不能依赖 `ordinal()`。PO 可以直接以枚举或数据库字符串持久化；PO 到领域对象、领域对象到 DTO 的转换必须显式映射数据库值和 API 编码，遇到未知值立即失败。

MySQL `ENUM` 的内部序号只能由数据库使用，绝不能作为 HTTP、MQ、内部 API 或 Java 持久化的业务编码。未来预约若新增取消、爽约等状态，必须通过新的前向 Flyway `ALTER TABLE ... MODIFY ... ENUM(...)` 迁移同步扩展数据库、Java 枚举、映射、MQ 契约和测试，禁止重排或改写已存在的枚举字面量。当前先只定义旧预约业务实际支持的状态，避免预置尚未实现的“泛化订单”状态。

## 6. HTTP 接口兼容矩阵

### 6.1 order 直接提供的移动端接口

| 公开路径 | order 服务内部路径 | 保持的行为 |
| --- | --- | --- |
| `GET /api/reservations/service-types` | `GET /reservations/service-types` | 按排序和 ID 返回已启用服务类型 |
| `GET /api/reservations/stores` | `GET /reservations/stores` | 必填 `serviceTypeId`，仅返回对应启用门店 |
| `POST /api/reservations` | `POST /reservations` | 为当前 JWT 用户创建待核销预约 |
| `GET /api/reservations/me` | `GET /reservations/me` | 当前用户分页列表及状态/类型筛选 |
| `GET /api/reservations/me/{orderNo}` | `GET /reservations/me/{orderNo}` | 仅可查询本人预约详情 |
| `GET /api/reservations/config` | `GET /reservations/config` | 公开积分抵扣规则 |

成功响应继续直接返回对象或数组，不新增 `code/data/message` 包装。ID 与 `orderNo` 保持字符串；`status` 与 `pointStatus` 保持由 Java 枚举显式映射出的数值编码；金额继续使用字符串。接口状态定义保持 `status: 1=待核销、2=已核销`，`pointStatus: 0=未抵扣、1=已抵扣、2=抵扣失败`。

### 6.2 admin 对外提供、order 实际执行的后台接口

| `gv_chat_admin` 既有路径 | `im-admin-service` 职责 | `im-order-service` 职责 |
| --- | --- | --- |
| `GET /admin/reservations` | 管理员鉴权、HTTP 映射 | 分页查询预约单 |
| `GET /admin/reservations/{orderNo}` | 管理员鉴权、响应映射 | 订单详情、积分余额和配置查询 |
| `POST /admin/reservations/{orderNo}/verify` | 提取管理员 ID、转发命令 | 校验并执行核销 |
| `GET/POST/PUT /admin/reservations/service-types/**` | 保持 HTTP 契约 | 服务类型查询与修改 |
| `GET/POST/PUT /admin/reservations/stores/**` | 保持 HTTP 契约 | 门店查询与修改 |
| `GET/PUT /admin/reservations/config` | 保持 HTTP 契约、传递管理员 ID | 配置查询与修改 |

以下文件是既有后台客户端契约基线，本次不重写页面和请求形式：

- `D:\projects\cnb\gv_chat_admin\src\api\reservation.js`
- `D:\projects\cnb\gv_chat_admin\src\views\reservation\orders.vue`
- `D:\projects\cnb\gv_chat_admin\src\views\reservation\catalog.vue`
- `D:\projects\cnb\gv_chat_admin\src\views\reservation\config.vue`

## 7. 需保持的预约规则

1. 创建预约时校验服务类型及门店存在且已启用，并校验门店归属所选服务类型。
2. 预约日期不能早于服务端当天。
3. 预约单号保持 `RS` 前缀且全局唯一。
4. 用户仅能查询自己的预约详情；不存在或不属于当前用户的订单保持旧接口的 404 语义。
5. 仅待核销预约可以核销一次。
6. 仅在配置开启时可使用积分抵扣，抵扣金额不能超过消费金额乘以配置中的最大抵扣比例。
7. 扣减积分计算保持 `ceil(抵扣金额 * 积分比例)`。
8. 核销日志必须记录当前管理员、消费金额、抵扣金额、抵扣积分和备注。

## 8. 积分协作与核销一致性

旧单体可在一个数据库事务内完成扣积分和更新预约；拆分后不使用跨库分布式事务。

`im-user-service` 需要扩展受保护的内部积分扣减命令，至少支持：

- `amount`、`reason`；
- `businessType=2`，表示预约核销抵扣；
- `businessOrderNo=orderNo`；
- `operatorUserId=adminId`；
- `commandId=reservation-verify:{orderNo}`，作为稳定幂等键。

积分流水需增加相同业务字段，并支持 `businessType` 筛选，以满足移动端积分流水的既有契约。同一 `commandId` 的重复请求必须返回首次执行结果，不能重复扣分。

核销执行顺序：

1. order 锁定预约行或通过版本条件更新，防止并发重复核销。
2. order 根据自身配置重新计算抵扣规则和积分，不信任客户端计算结果。
3. order 以稳定 `commandId` 调用 user 的受签名内部扣分接口。
4. 扣分成功后，order 在本地事务中提交预约状态、核销日志和 Outbox。
5. 扣分失败时预约保持未核销且可重试；重复请求不得造成重复扣分。

## 9. 事件、长连接与推送

order 将权威数据与 Outbox 记录在同一数据库事务中提交；业务事务中不得直接发送 RocketMQ。

首批事件：

| 事件 | 生产方 | 消费方 | 顺序/幂等键 |
| --- | --- | --- | --- |
| 预约创建 | order Outbox | access-ws、后台通知适配器 | `orderNo` / `eventId` |
| 预约核销 | order Outbox | access-ws、推送适配器 | `orderNo` / `eventId` |
| 积分变动 | user Outbox | access-ws | `eventId` |

Topic、生产者组、消费者组和载荷需在同一变更内同步登记到 `protocol-mq`、`docs/mq/REGISTRY.md` 和相关服务 README。

后台现有页面监听 `reservation:created`；用户端需要 `reservation:verified`，积分页面需要 `point:changed`。Java `im-access-ws` 当前下行帧格式为 `{"event":"...","data":{...}}`。上线前必须按实际部署的后台和移动端连接协议完成集成验证：若客户端要求 Socket.IO 而不是原生 WebSocket，则 Socket.IO 兼容入口是上线前置条件，不能仅完成 REST 迁移。

离线推送在核销成功后最佳努力发送，失败不得回滚已完成核销；推送仅带业务类型和预约单号，不带手机号、余额或消费金额。

## 10. 实施批次

1. 新增 Maven 聚合、`im-order-api`、`im-order-service`、服务配置、服务说明和移动端网关路由。
2. 新增 order Flyway 基线、`ord_` 表和空库持久化验证。
3. 实现移动端预约接口，并以旧接口示例进行契约测试。
4. 新增 order 内部后台契约，在 admin 实现兼容 Controller，不修改 `gv_chat_admin` 请求方式。
5. 扩展 user 积分命令、积分流水表、幂等能力和查询字段。
6. 实现核销、审计日志、并发/幂等、积分不足和错误映射测试。
7. 接入 Outbox Relay、MQ 台账、WebSocket 消费者和推送适配器。
8. 执行空库启动、服务集成、后台 API 回归、全工程 Maven 编译和真实通知联调。

## 11. 验收标准

- `im-order-service` 可在新空库独立初始化；预约相关业务表均为 `ord_*`，Flyway 历史表为 `order_schema_history`。
- 移动端预约接口的路径、字段类型、状态、分页和异常语义与旧服务兼容。
- `gv_chat_admin` 预约订单、服务/门店、积分配置页面无需修改请求 URL 或请求体即可对接 Java 服务。
- `/admin/reservations/**` 继续受既有 `ADMIN` JWT 角色保护。
- `im-admin-service` 不含 `ord_` Mapper、PO、SQL 或 Flyway 脚本。
- order 的封闭业务状态字段使用服务私有 Java 枚举和 MySQL `ENUM`；二值开关使用受 `CHECK` 约束的 `TINYINT UNSIGNED`。不存在裸 `int` 业务状态或 `ordinal()` 持久化；对外数值编码由显式映射提供。
- 积分流水包含预约关联字段；重复核销请求不会重复扣积分。
- 并发核销只产生一笔已核销预约和一条核销日志。
- 新预约、核销、积分变动与离线推送在真实客户端协议下完成端到端验证。
- 受影响服务测试及根 Maven Reactor 构建通过。

## 12. 实施约束

- 已执行的 Flyway 脚本只允许追加前向迁移，禁止修改历史脚本。
- admin、user 不得共享 order 数据库、Mapper 或 Repository。
- 移动端请求不得传入或指定 `userId`。
- 积分扣减值由 order 服务重新计算，不能采用客户端传值。
- user 幂等扣分成功前，order 不得将预约标记为已核销。
- 实时通知和推送不属于核销核心事务，不得因通知失败回滚业务结果。
- 预约迁移完成前，不提前扩展泛化订单设计。
