# 工程门禁例外登记

> 状态：生效中
> 目的：登记当前无法在不修改已执行迁移的前提下清零的既有门禁债务。
> 约束：例外只覆盖下列文件/行；新增违规不被本例外覆盖。

## GATE-FLYWAY-LEGACY-001

```text
检查：validate-flyway-conventions.ps1
ADR：ADR-0015 / ADR-0008 酒店域边界
范围：9 个既有 hotel/admin/tenant 迁移
原因：CREATE TABLE IF NOT EXISTS / 缺审计列/缺列注释
负责人：platform-order-service + platform-tenant-service 负责人
到期：2026-11-15
补充验证：例外期内新增迁移零违规；tenant-sql 已清零；S5 收口补空库+增量 Flyway
退出：S5 酒店域收口时按迁移规范重建或补新版本
```

文件：

```text
platform-services/admin/platform-admin-service/src/main/resources/db/migration/V2__iam_role_profile.sql
platform-services/order/platform-order-service/src/main/resources/db/migration/V3__htl_booking_frontdesk.sql
platform-services/order/platform-order-service/src/main/resources/db/migration/V4__htl_domain_model.sql
platform-services/order/platform-order-service/src/main/resources/db/migration/V5__htl_inventory_hold.sql
platform-services/order/platform-order-service/src/main/resources/db/migration/V6__htl_stay_folio_housekeeping.sql
platform-services/order/platform-order-service/src/main/resources/db/migration/V7__htl_night_audit.sql
platform-services/order/platform-order-service/src/main/resources/db/migration/V10__htl_refund.sql
platform-services/tenant/platform-tenant-service/src/main/resources/db/migration/V3__tnt_business_entitlement.sql
platform-services/tenant/platform-tenant-service/src/main/resources/db/migration/V7__tnt_workspace_config.sql
```

退出条件：

```text
所有表四审计列齐全
所有列/索引有 COMMENT
无 CREATE TABLE IF NOT EXISTS
Flyway 空库 + 增量通过
validate-flyway-conventions.ps1 PASS
```

## GATE-TENANT-SQL-LEGACY-001

```text
检查：validate-tenant-sql.ps1
范围：RoleMapper 既有 3 条跨租户 SQL
状态：已关闭（补 tenant-agnostic 注释，2026-10-23）
```

## 变更规则

```text
不得扩大文件范围
不得修改已执行迁移来规避
不得用 report-only 代替例外
到期未关闭则阻塞 S5/S7
```

## 相关

- [S2 迁移与冻结计划](../../40-domain/identity/S2_MIGRATION_PLAN.md)
- [身份与授权硬门禁](../../40-domain/identity/IDENTITY_GUARDRAILS.md)
