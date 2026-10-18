# ADR 0007：`version` 具名例外（已撤回）

## 状态

**已撤回（2026-10-18）**。原决策保留 `ClientRelease.version` 与 `InventoryStock.version` 的字段名，
现改为实际改名，不再接受任何 `version` 具名例外。

## 撤回原因

原 ADR 把“避免数据库/API 迁移”置于规范之上。保留字段名会让评审者继续把 `version` 当成乐观锁，
门禁也只能检查标记、无法检查语义，等于把误导制度化。

## 实际处理

- `ord_inventory_stock.version` → `revision`（V2 迁移，PO/领域/mapper/adapter/测试同步）；
- `adm_client_release.version` → `release_version`（V2 迁移，领域/PO 改名 `releaseVersion`）；
  API DTO 仍输出 JSON 字段 `version`，仅作外部兼容映射；
- `reserved-identifiers-reviewed.txt` 删除两条例外；
- `standards/10` 恢复“非锁字段不得叫 `version`”，门禁不再接受例外标记。

## 后续

若未来确有业务字段必须命名为 `version`，应先证明它不是持久化锁字段，并通过新 ADR 明确边界；
不得复用本 ADR。
