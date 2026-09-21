-- 币种快照（docs/standards/16_CURRENCY_CONVENTIONS.md §2.1 / §5）。
--
-- 背景：多币种能力只做「租户级单一来源 + 默认 USD + 已结算单据锁定快照」。
-- 已有币种列（ord_order.currency_code）**不重复新增**，本迁移只做两件事：
--   1) 给「确实没有币种列、但会落金额」的对象补 `currency_code varchar(3) NOT NULL DEFAULT 'USD'`；
--   2) 给 ord_order.currency_code 补上 DEFAULT 'USD'，使「未配置租户币种 = USD」在库层也成立。
--
-- 列名/类型统一与既有币种列一致（currency_code / char(3) 家族 → varchar(3)，长度 3 足够 ISO 4217）。
--
-- 对象清单与判定依据（逐表核对，来源：docs/currency-scope-inventory-2026-09-18.md §1.1）：
--   ord_order_item            —— 订单/账单明细，落 unit_price/discount/tax/total 四个金额列，无币种列 → 新增。
--   ord_ktv_server_session    —— 服务人员费会话，落 total_amount（服务人员费快照），无币种列 → 新增。
--   ord_inventory_material    —— 采购价 purchase_price 的计价币种（入库成本/成本毛利报表按它归集），无币种列 → 新增。
-- 不加列的表（逐个说明，避免「无金额也加列」）：
--   ord_inventory_transaction —— 只有 quantity_delta/before/after，**没有任何金额列**，入库单金额不在此表，
--                                故不加快照列（口径：无独立金额的纯数量流水不加列）。
--   ord_catalog_item / ord_product —— 价格字典（unit_price/sale_price）不是已结算单据，结账时由明细行快照固化。
--   ord_reservation / ord_ktv_session —— 无独立金额列（会话只有计价规则快照 overtime_rate，非金额）。
--   ord_inventory_stock       —— 库存余额，纯数量。
--
-- 迁移回填：按用户本次口径，**历史行全部回填 USD**（不做人民币改写、不做汇率换算）。
-- 由于新列带 DEFAULT 'USD'，MySQL 加列时已把既有行填为 'USD'；下面的 UPDATE 是显式声明回填口径、
-- 保证「回填规则可见 + 可审计」，并顺带兜住 '' 这类历史脏值。
ALTER TABLE `ord_order_item`
  ADD COLUMN `currency_code` varchar(3) NOT NULL DEFAULT 'USD'
  COMMENT '币种快照（明细落库时的租户币种，已结算明细锁定，改设置不改历史）' AFTER `total_amount`;

ALTER TABLE `ord_ktv_server_session`
  ADD COLUMN `currency_code` varchar(3) NOT NULL DEFAULT 'USD'
  COMMENT '币种快照（服务人员费 total_amount 落库时的租户币种）' AFTER `total_amount`;

ALTER TABLE `ord_inventory_material`
  ADD COLUMN `currency_code` varchar(3) NOT NULL DEFAULT 'USD'
  COMMENT '采购价币种（purchase_price 的计价币种快照）' AFTER `purchase_price`;

-- 缺省 USD：让「租户未配置币种」时插入的订单也自动带上 USD 快照，而不是依赖每个写入点都显式赋值。
ALTER TABLE `ord_order`
  MODIFY COLUMN `currency_code` char(3) NOT NULL DEFAULT 'USD' COMMENT '币种（结账快照，缺省 USD）';

UPDATE `ord_order_item` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';
UPDATE `ord_ktv_server_session` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';
UPDATE `ord_inventory_material` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';
UPDATE `ord_order` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';
