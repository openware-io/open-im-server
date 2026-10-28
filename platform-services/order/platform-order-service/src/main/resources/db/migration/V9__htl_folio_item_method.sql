-- 退款/收款方式：现金退款与线上退款必须可区分、可审计。
ALTER TABLE `htl_folio_item`
  ADD COLUMN `method` varchar(16) NULL AFTER `type`,
  ADD COLUMN `reference` varchar(64) NULL AFTER `method`;
