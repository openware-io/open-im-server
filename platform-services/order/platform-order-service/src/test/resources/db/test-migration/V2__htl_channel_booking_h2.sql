-- H2 (MODE=MySQL) 补齐酒店 OTA channel v1 集成测试所需 schema。
-- 生产对应 V30__htl_channel_booking.sql；酒店主数据表在 test-migration V1 中只保留了前台最小列，
-- 这里按生产 V4/V18 补齐只读查询所需的表与 htl_booking 扩展列（不改生产口径，仅测试库补列）。

CREATE TABLE htl_hotel (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  address VARCHAR(255),
  cover_image_url VARCHAR(512),
  timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
  check_in_time VARCHAR(8) NOT NULL DEFAULT '14:00',
  check_out_time VARCHAR(8) NOT NULL DEFAULT '12:00',
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL
);

CREATE TABLE htl_room_type (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  hotel_id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  capacity INT NOT NULL DEFAULT 2,
  bed_type VARCHAR(64),
  area_sqm DECIMAL(8,2),
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL
);

CREATE TABLE htl_rate_plan (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  hotel_id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  base_price_minor BIGINT NOT NULL DEFAULT 0,
  currency VARCHAR(3) NOT NULL DEFAULT 'USD',
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL
);

CREATE TABLE htl_daily_rate (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  room_type_id BIGINT NOT NULL,
  rate_plan_id BIGINT NOT NULL,
  stay_date DATE NOT NULL,
  price_minor BIGINT NOT NULL,
  currency VARCHAR(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL
);

CREATE TABLE htl_policy (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  hotel_id BIGINT NOT NULL,
  access_mode VARCHAR(24) NOT NULL DEFAULT 'SINGLE_STORE',
  entry_points VARCHAR(255),
  payment_mode VARCHAR(24) NOT NULL DEFAULT 'PREPAY',
  deposit_ratio_bps INT NOT NULL DEFAULT 3000,
  stay_deposit_required TINYINT NOT NULL DEFAULT 1,
  stay_deposit_mode VARCHAR(24) NOT NULL DEFAULT 'COLLECT',
  stay_deposit_minor BIGINT NOT NULL DEFAULT 0,
  stay_deposit_ratio_bps INT NOT NULL DEFAULT 0,
  cancel_policy VARCHAR(32) NOT NULL DEFAULT 'FREE_UNTIL_24H',
  points_redeem_enabled TINYINT NOT NULL DEFAULT 0,
  points_max_ratio_bps INT NOT NULL DEFAULT 0,
  invoice_mode VARCHAR(24) NOT NULL DEFAULT 'NONE',
  created_by BIGINT NOT NULL DEFAULT 0,
  updated_by BIGINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL
);

CREATE TABLE htl_channel_booking (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  channel_code VARCHAR(32) NOT NULL,
  external_ref VARCHAR(128) NOT NULL,
  booking_id BIGINT,
  booking_no VARCHAR(64),
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  last_action VARCHAR(24) NOT NULL,
  cancel_reason VARCHAR(500),
  audit_json CLOB,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL
);
CREATE UNIQUE INDEX uk_htl_channel_booking_ref ON htl_channel_booking (channel_code, external_ref);

ALTER TABLE htl_booking ADD COLUMN hotel_id BIGINT;
ALTER TABLE htl_booking ADD COLUMN room_type_id BIGINT;
ALTER TABLE htl_booking ADD COLUMN rate_plan_id BIGINT;
ALTER TABLE htl_booking ADD COLUMN rooms INT NOT NULL DEFAULT 1;
ALTER TABLE htl_booking ADD COLUMN nights INT NOT NULL DEFAULT 1;
ALTER TABLE htl_booking ADD COLUMN source VARCHAR(24) NOT NULL DEFAULT 'WEB';
ALTER TABLE htl_booking ADD COLUMN hold_expires_at DATETIME(3);
ALTER TABLE htl_booking ADD COLUMN booked_by_account_id BIGINT;
ALTER TABLE htl_booking ADD COLUMN cancel_fee_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE htl_booking ADD COLUMN refund_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE htl_booking ADD COLUMN cancelled_at DATETIME(3);
ALTER TABLE htl_booking ADD COLUMN points_used BIGINT NOT NULL DEFAULT 0;
ALTER TABLE htl_booking ADD COLUMN points_discount_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE htl_booking ADD COLUMN version INT NOT NULL DEFAULT 0;
