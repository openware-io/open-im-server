-- 酒店 OTA channel v1：外部渠道预订单映射（渠道侧幂等账本）。
-- 唯一键 (channel_code, external_ref) 是外部单号的幂等边界；不是 htl_booking 状态机的替代。
-- status 只是 htl_booking.status 的投影，查询/取消响应以 htl_booking 实时状态为准。
CREATE TABLE `htl_channel_booking` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID（渠道凭证绑定）',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID（由 htl_hotel.store_id 解析）',
  `channel_code` varchar(32) NOT NULL COMMENT '渠道编码',
  `external_ref` varchar(128) NOT NULL COMMENT '渠道外部单号',
  `booking_id` bigint unsigned NULL COMMENT '内部酒店预订ID（创建事务内先占位、提交前回填）',
  `booking_no` varchar(64) NULL COMMENT '内部预订号快照（创建事务内先占位、提交前回填）',
  `request_hash` char(64) NOT NULL COMMENT '创建请求规范化指纹，用于同单号重复提交校验',
  `status` varchar(32) NOT NULL COMMENT 'htl_booking.status 投影',
  `last_action` varchar(24) NOT NULL COMMENT '最近动作：CREATE_REQUESTED/CREATED/CANCELLED/CANCEL_REPLAY',
  `cancel_reason` varchar(500) NULL COMMENT '取消原因',
  `audit_json` text NULL COMMENT '审计轨迹 JSON 数组（按动作追加）',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_channel_booking_ref` (`channel_code`, `external_ref`),
  KEY `idx_htl_channel_booking_booking` (`tenant_id`, `store_id`, `booking_id`),
  KEY `idx_htl_channel_booking_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='酒店 OTA 渠道预订映射';
