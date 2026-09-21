# C 端预约账号/数据迁移（E-MIG）实施骨架

> 定位：执行 `SAAS_PLATFORM_07_EXECUTION` §E-MIG（依赖图 line 45）与 `SAAS_PLATFORM_01/02/05` 的预约迁移描述。
> 目标：C 端现有预约 `ord_reservation`（im-order-service，IM 库 `gv_im`）属 SaaS 业务，账号/数据迁至 SaaS（platform-order-service + `gv_saas` 库）。
> 原则：不动 IM 库已执行 Flyway；不删 C 端旧契约；Expand→Backfill→Verify→Switch 四阶段，可回滚。

## 1. 现状与边界

| 项 | 现状（迁移前） | 目标（迁移后） |
| --- | --- | --- |
| 权威服务 | im-order-service（IM 库 `gv_im`） | platform-order-service（SaaS 库 `gv_saas`） |
| 表 | `ord_reservation`（无 tenant_id，`user_id` 归 IM 账号） | `ord_reservation`（`tenant_id` + `customer_id`） |
| C 端读路径 | `GET /api/v1/reservations/**` → Gateway(StripPrefix=2) → im-order-service `/reservations/**` | Switch 后改指 platform-order-service 兼容读 Controller（本骨架占位） |
| SaaS 新读写 | 无 | `/api/v1/business/reservations/**` → platform-order-service `/business/reservations/**` |
| 账号归属 | IM `user_id` | SaaS 账号：`ord_reservation.customer_id` → `cst_customer.account_id` |

> 账号归属说明：`SAAS_PLATFORM_04` §6.3 使用 `customer_id`（租户客户），其 `account_id` 承载 SaaS 平台账号归属；
> E-MIG 将旧 `user_id` 映射为「存量迁移租户」下的租户客户（`cst_customer`），再落到 `ord_reservation.customer_id`。

## 2. 四阶段（Expand → Backfill → Verify → Switch）

### 2.1 Expand（新建 SaaS 侧表）

- Flyway `V2__ord_reservation.sql` 新增到 platform-order-service（`gv_saas` 库），字段对齐 `SAAS_PLATFORM_04` §6.3。
- 新增 PO/Mapper/状态枚举（见 platform-order-service）。
- 回滚：`DROP TABLE ord_reservation;`（仅当空表、无回填数据时）。

### 2.2 Backfill（历史数据回填）

- 目标：历史预约回填到「存量迁移租户」（`tenant_code='MIGRATION'`，`tenant_id` 由脚本变量 `__MIGRATION_TENANT_ID__` 指定）。
- 脚本占位：`scripts/migration/reservation-backfill.sql`。
- 字段映射：旧 `order_no`→`reservation_no`、`user_id`→`customer_id`（经 `cst_customer` 的账号绑定）、`store_id`→`store_id`、`person_num`→`party_size`、`status`（`pending_verification`→`CONFIRMED`、`verified`→`CONVERTED`）、`reserve_date`+`reserve_time_period`→`start_at`/`end_at`（占位，待门店时区规则）。
- 回滚：按 `reservation_no` 前缀删除已回填行（脚本内提供 DELETE 段）。

### 2.3 Verify（对账校验占位）

- 对账：IM 库 `ord_reservation` 与 SaaS 库 `ord_reservation` 按 `reservation_no` 比对（条数、字段哈希、状态分布）。
- 校验：`tenant_id`/`customer_id` 非空、唯一键 `(tenant_id, reservation_no)` 无冲突、状态映射合法。
- 占位：`scripts/migration/reservation-backfill.sql` 内 `-- VERIFY` 段。

### 2.4 Switch（灰度切换占位）

- 读路径灰度：Gateway `/api/v1/reservations/**` 按 Feature Flag/租户切到 platform-order-service 兼容读 Controller。
- 写路径灰度：新预约写 `/api/v1/business/reservations`；旧写路径按租户灰度停写/转只读。
- 回滚：Gateway 路由回退到 im-order-service；SaaS 表保留不删（数据不丢失）。

## 3. 兼容读路径标注

- 旧 C 端：`GET /api/v1/reservations/**`（Gateway StripPrefix=2 → `/reservations/**`）。
- SaaS 内兼容读占位：`platform-order-service` 的 `ReservationCompatController`（`@RequestMapping("/reservations")`），Switch 阶段启用，只读、不删旧契约。
- SaaS 新契约：`ReservationController`（`@RequestMapping("/business/reservations")`），对应 `SAAS_PLATFORM_05` §5.3。

## 4. 不允许（硬约束）

- 不改 im-order-service 已执行 Flyway（V1/V2）。
- 不删 C 端旧契约与旧 `ord_reservation`（IM 库）表。
- 不在 SaaS 侧复制旧 `ord_reservation_service_type/store/config` 表（服务目录/门店随租户资源迁移，另行方案）。