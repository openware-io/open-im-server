-- 入库批次单价 + 移动加权平均成本（仓库管理 → 库存成本/毛利报表的地基）。
--
-- 目标链路：入库批次单价 -> 移动加权平均成本 -> 库存成本 -> 毛利报表。
-- 只加列、不改历史迁移、不 DROP、不动已有列；金额一律 decimal(20,6) + **最小货币单位**（分/cent），
-- 与 ord_inventory_material.purchase_price、ord_product.sale_price 同口径，单位为「每一计量单位」。
-- 所有换算/平均在 Java 侧用 BigDecimal 定点计算，库层不引入 double/float。

-- 1) 库存流水：为「入库批次单价」留可追溯的落库位置。
--    unit_cost  = 该次变更的单价（入库=批次单价；出库=当次结转的移动加权平均成本）。
--    total_cost = 该次变更的成本发生额（带符号，与 quantity_delta 同号：入库正、出库负）。
--    currency_code = 该次单价/发生额的币种快照（与 ord_order.currency_code 同为 varchar(3)）。
--    历史行：unit_cost/total_cost 保持 NULL —— 升级前没有采集过批次单价，无法事后还原，
--    NULL 明确表达「该行无成本信息」，绝不用 0 冒充真实成本；currency_code 按全仓缺省回填 USD
--    （与 V22__ord_currency_snapshot.sql 对历史行的回填口径一致：不猜测、不换算，统一 USD）。
ALTER TABLE `ord_inventory_transaction`
  ADD COLUMN `unit_cost` decimal(20,6) NULL COMMENT '单价（最小货币单位/计量单位；入库=批次单价，出库=移动加权平均成本）' AFTER `quantity_after`,
  ADD COLUMN `total_cost` decimal(20,6) NULL COMMENT '成本发生额（最小货币单位，带符号：入库正、出库负）' AFTER `unit_cost`,
  ADD COLUMN `currency_code` varchar(3) NOT NULL DEFAULT 'USD' COMMENT '单价/发生额币种快照' AFTER `total_cost`;

UPDATE `ord_inventory_transaction` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';

-- 2) 库存余额：移动加权平均成本列（同一物料在同一门店的结存成本基准）。
--    avg_cost 只在**入库**时按加权公式重算；出库/消耗只按它结转发生额、不改变它。
--    历史行一次性建账：能取到物料采购价的按采购价建账（一次性近似，不做追溯重估），
--    取不到的保持 0（= 无成本基准，后续首次入库按该批次单价接管）。
--    币种随建账来源一起固化；无建账来源的行保持缺省 USD。
ALTER TABLE `ord_inventory_stock`
  ADD COLUMN `avg_cost` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '移动加权平均成本（最小货币单位/计量单位）' AFTER `reserved_qty`,
  ADD COLUMN `currency_code` varchar(3) NOT NULL DEFAULT 'USD' COMMENT '移动加权平均成本币种快照' AFTER `avg_cost`;

-- 历史结存建账（可审计的一次性回填）：
--   结存数量 > 0 且物料有采购价 → avg_cost = 采购价、币种 = 采购价币种；
--   其余（无采购价 / 无结存）→ 保持 0 + USD（= 尚未建立成本基准）。
UPDATE `ord_inventory_stock` s
JOIN `ord_inventory_material` m
  ON m.`tenant_id` = s.`tenant_id` AND m.`store_id` = s.`store_id` AND m.`id` = s.`material_id`
SET s.`avg_cost` = m.`purchase_price`,
    s.`currency_code` = COALESCE(NULLIF(m.`currency_code`, ''), 'USD')
WHERE s.`on_hand_qty` > 0
  AND m.`purchase_price` IS NOT NULL
  AND m.`purchase_price` > 0;
