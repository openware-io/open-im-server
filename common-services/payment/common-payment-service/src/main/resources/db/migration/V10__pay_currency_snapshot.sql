-- 币种快照（docs/standards/16_CURRENCY_CONVENTIONS.md §2.1 / §5）。
--
-- 背景：多币种能力只做「租户级单一来源 + 默认 USD + 已结算单据锁定快照」。
-- 已有币种列（pay_intent.currency_code / pay_transaction.currency_code / pay_channel_transaction.currency）
-- **不重复新增**；本迁移只给「确实没有币种列、但会落金额」的对象补
-- `currency_code varchar(3) NOT NULL DEFAULT 'USD'`（列名与既有币种列一致）。
--
-- 对象清单与判定依据（逐表核对，来源：docs/currency-scope-inventory-2026-09-18.md §1.6 与 §6.3）：
--   pay_refund         —— 退款单：落 requested_amount/approved_amount，无币种列 → 新增。
--                         退款必须退**原币种**（跨币种退款是合规禁区，§6.3），写入时取原订单币种。
--   pay_shift          —— 收银班次：落 opening_cash/expected_cash/actual_cash/difference_amount，无币种列 → 新增。
--                         交班长短款必须能按币种分别盘点，禁止把不同币种静默相加（§6.3）。
--   pay_daily_closing  —— 日结：按门店+营业日唯一，汇总金额（summary_json）与币种强相关，无币种列 → 新增。
--   pay_collect        —— 组合收款（支付分腿 + 找零口径的唯一落库记录）：金额只存在于 request_json/
--                         response_json 里，无独立列 → 新增顶层 currency_code，便于对账按币种过滤。
--
-- 不加列的表：pay_intent / pay_transaction（已有 currency_code，只补「确实写入」）、
--             pay_channel_transaction（已有 currency，属 common-payment-channel 既有形态）。
--
-- 迁移回填：按用户本次口径，**历史行全部回填 USD**（不做人民币改写、不做汇率换算）。
-- 新列带 DEFAULT 'USD'，加列时既有行已是 'USD'；下面 UPDATE 显式声明回填口径并兜住 '' 脏值。
ALTER TABLE `pay_refund`
  ADD COLUMN `currency_code` varchar(3) NOT NULL DEFAULT 'USD'
  COMMENT '币种快照（退款退原币种：取原订单/原支付流水币种，缺省 USD）' AFTER `approved_amount`;

ALTER TABLE `pay_shift`
  ADD COLUMN `currency_code` varchar(3) NOT NULL DEFAULT 'USD'
  COMMENT '币种快照（本班次现金盘点币种；不同币种不得合并交班）' AFTER `difference_amount`;

ALTER TABLE `pay_daily_closing`
  ADD COLUMN `currency_code` varchar(3) NOT NULL DEFAULT 'USD'
  COMMENT '币种快照（日结出具币种；跨币种需分币种日结）' AFTER `summary_json`;

ALTER TABLE `pay_collect`
  ADD COLUMN `currency_code` varchar(3) NOT NULL DEFAULT 'USD'
  COMMENT '币种快照（本次组合收款/支付分腿与找零的币种）' AFTER `state`;

UPDATE `pay_refund` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';
UPDATE `pay_shift` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';
UPDATE `pay_daily_closing` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';
UPDATE `pay_collect` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';
