-- 酒店积分抵扣：记录实际使用积分与抵扣金额（最小货币单位）。
ALTER TABLE `htl_booking`
    ADD COLUMN `points_used` bigint NOT NULL DEFAULT 0 COMMENT '使用积分数' AFTER `refund_minor`,
    ADD COLUMN `points_discount_minor` bigint NOT NULL DEFAULT 0 COMMENT '积分抵扣金额（最小货币单位）' AFTER `points_used`;
