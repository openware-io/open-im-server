-- 币种快照（docs/standards/16_CURRENCY_CONVENTIONS.md §2.1 / §5）。
--
-- 背景：多币种能力只做「租户级单一来源 + 默认 USD + 已结算单据锁定快照」。
-- 已有币种列（cst_wallet_account.currency_code，且已做同主体同币种唯一 + 跨币种拒绝）**不重复新增**；
-- 本迁移只给「确实没有币种列、但会落金额/资产变动」的流水表补
-- `currency_code varchar(3) NOT NULL DEFAULT 'USD'`（列名与既有币种列一致）。
--
-- 对象清单与判定依据（逐表核对，来源：docs/currency-scope-inventory-2026-09-18.md §1.4 与 §6.3）：
--   cst_wallet_ledger —— 储值/代币流水：落 amount/balance_after，**原表没有币种列**，币种只能回查账户
--                        （§1.4 明确点名这是缺口）→ 新增，账本自证币种，不再依赖关联表。
--   cst_point_ledger  —— 积分调整流水：points 本身不是货币（积分非货币资产），但「调整/抵扣」的账面口径
--                        与订单/储值处于同一租户币种，规范 §5 明确要求给「积分调整」补币种 → 新增。
--
-- 不加列的表：cst_wallet_account（已有 currency_code）、cst_point_account（纯积分余额，非货币）。
--
-- 迁移回填：按用户本次口径，**历史行全部回填 USD**（不做人民币改写、不做汇率换算）。
-- 新列带 DEFAULT 'USD'，加列时既有行已是 'USD'；下面 UPDATE 显式声明回填口径并兜住 '' 脏值。
ALTER TABLE `cst_wallet_ledger`
  ADD COLUMN `currency_code` varchar(3) NOT NULL DEFAULT 'USD'
  COMMENT '币种快照（与钱包账户币种一致；账本自证币种，改租户设置不改历史流水）' AFTER `balance_after`;

ALTER TABLE `cst_point_ledger`
  ADD COLUMN `currency_code` varchar(3) NOT NULL DEFAULT 'USD'
  COMMENT '币种快照（积分调整所依据的租户币种口径；积分本身非货币）' AFTER `points`;

UPDATE `cst_wallet_ledger` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';
UPDATE `cst_point_ledger` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';
