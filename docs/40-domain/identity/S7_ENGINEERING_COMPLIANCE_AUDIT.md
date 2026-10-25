# S7 工程规范遵守审计

> 2026-10-24
> 范围：本次 S3–S7 改动、部署、证据

## 1. 本次已修复的违规

| 违规 | 修复 | 证据 |
| --- | --- | --- |
| 直接 `kubectl set image` 部署 payment/H5 | 改为 `build-saas-release` + schema v2 manifest + `k8s-scoped` | `c9736fee`、`local-scoped-development.20261024T045503Z.fa46abe0` |
| 手工 tag `saas-mobile:2.4.0-s7` | 删除，统一 `2.4.0-SNAPSHOT` | 运行态 7 服务全部 2.4.0-SNAPSHOT |
| `k8s-scoped` 未导入本地 Kind 镜像 | 增加 `docker save \| ctr import`，由 manifest 驱动 | `k8s-scoped.ps1` |
| C 下单不绑定 `ord_order.customer_id`，靠手工 SQL | `MemberLookupProvider` 自动绑定 | order 13 customer_id=1 |
| 支付成功不确认 hotel booking | `HotelBookingPaymentReconciler` 60s 对账 | booking 2114013936307535874 CONFIRMED |
| 退款 REFUNDED 不回冲订单 | `OrderBillingRepository.markRefunded` | 89900→59900 |
| 退款审批无并发保护 | 汇总同订单 APPROVED + 订单行锁 | refund 8 超退 422 |

## 2. 门禁结果

通过：

```text
validate-docs-impact
validate-documentation-links
validate-documentation-governance
validate-gate-registry
validate-tenant-sql
validate-audit-fields
validate-autowired-constructor
validate-controller-transaction-boundary
validate-controller-repository-boundary
validate-flyway-immutability
validate-java-comment-quality
validate-persistence-dependency-boundaries
validate-maven-version-ownership
```

已知基线失败（非本次改动）：

```text
validate-text-encoding
  PASS：损坏的历史归档按原字节隔离为 .corrupted，SHA-256 记录在 Markdown 索引

validate-java-fully-qualified-names
  PASS：本轮修复 FQCN 引用

validate-service-api-boundaries
  PASS：application 已迁移 repository 端口

validate-service-layout
  PASS（4 条既有包例外，到期 2026-12-31）

validate-flyway-conventions
  1 条已应用迁移基线：V2__iam_role_profile.sql 使用 IF NOT EXISTS
  另 9 条历史迁移例外仍登记 GATE-FLYWAY-LEGACY-001

validate-flyway-conventions
  9 条历史迁移例外，已登记 GATE-FLYWAY-LEGACY-001，到期 2026-11-15
```

这些基线问题不能当作本次 PASS 证据，也未在本次改动中恶化。

## 3. 仍未完成的规范/工程项

### 数据可复现

```text
cons_admin_account.platform_account_id=101
MEMBERSHIP 1 STORE 102 / STORE 100
CUSTOMER membership for account 101
cst_member account 101
inventory 10/25、10/26、10/27、10/28、10/29、10/30
order 11 customer_id
```

均为手工 SQL，未落 migration/seed；换环境无法复现。

### 发布

```text
仅 Kind 离线 manifest
本地 Kind manifest 发布
本地回滚演练
测试 APK 真机验证
```

### 客户端

```text
Android 为 debug APK + 本地 IP dart-define
未走正式 client release / buildNumber
未验证正式签名包
```

### 证据

```text
无 HAR
无 logcat
无 trace
无 X-Request-Id
无录屏
```

### 测试

```text
SEC-001..010 未全量
ERR-001/002 仅 transport 级
Android 全 C 流未完成
C 在线支付/退款 deferred
```

## 4. 结论

```text
本次新增改动的规范门禁：通过（除已知基线）
发布流程：已从手工 set image 纠正为 manifest
数据/生产发布/证据链：仍未达到 S7 完整 PASS 要求
```
