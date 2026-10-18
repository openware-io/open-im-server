# ADR 0007：`version` 的两类具名例外

## 状态

已接受（2026-10-18）。

## 背景

`standards/10` 把 `version` 保留给乐观锁。仓库里有两个字段叫 `version`，但不是并发控制：

- `adm_client_release.version`：业务发布版本号（字符串），并发保护用 `row_version`；
- `ord_inventory_stock.version`：库存修订计数器，每次入库/扣减/回补在 SQL 里自增，
  并发保护由 `on_hand_qty - reserved_qty >= n` 的条件 UPDATE 承担，不是 `WHERE version = ?`。

## 决策

不做 DB/API 改名，保留字段名，但必须：

1. 在 PO 字段上写 `reserved-name-exception: BUSINESS_VERSION` 或 `COUNTER` 注释；
2. `reserved-identifiers-reviewed.txt` 登记为对应状态，登记项必须与注释一致；
3. 禁止在这两个 PO 上使用框架 `@Version`，也禁止在更新条件里把该列当作 CAS 谓词；
4. 门禁 `validate-reserved-identifiers.ps1` 检查注释存在、状态匹配、无 `@Version`。

## 后果

- 对外字段名保持兼容，不需要数据库迁移；
- 字段名仍可能误导，因此依赖注释、登记表、ADR 与门禁四件套，而不是只靠口头约定；
- 若未来做 API/DB 重构，可再以新 ADR 改为 `release_version` / `revision`，届时删除本例外。
