-- H2 (MODE=MySQL) test schema for customer integration tests.
-- Mirrors cst_wallet_account / cst_wallet_ledger / cst_point_account / cst_point_ledger
-- from production MySQL Flyway. The point tables mirror the real column set exactly:
-- a column that exists here but not in the PO (or the reverse) is precisely the class of
-- defect that "compiles clean + unit tests green" cannot see.

CREATE TABLE cst_wallet_account (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  legal_entity_id BIGINT NOT NULL,
  currency_code CHAR(3) NOT NULL,
  available_amount BIGINT NOT NULL DEFAULT 0,
  frozen_amount BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE cst_wallet_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT,
  wallet_account_id BIGINT NOT NULL,
  entry_type VARCHAR(24) NOT NULL,
  amount BIGINT NOT NULL,
  balance_after BIGINT NOT NULL,
  -- 币种快照（生产 V2__cst_currency_snapshot.sql）：账本自证币种，历史行回填 USD。
  currency_code VARCHAR(3) NOT NULL DEFAULT 'USD',
  order_id BIGINT,
  fx_quote_id BIGINT,
  idempotency_key VARCHAR(64) NOT NULL,
  occurred_at TIMESTAMP NOT NULL,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  -- 与生产基线对齐（DC-11）：账本追加型，写入方不写 updated_*，故 updated_at 取库默认值。
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE cst_point_account (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  program_id BIGINT NOT NULL,
  available_points BIGINT NOT NULL DEFAULT 0,
  frozen_points BIGINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL DEFAULT 0,
  -- created_at / updated_at 没有库默认值（与生产基线一致）：写入方必须显式赋值，
  -- 否则 INSERT 直接失败——这正是积分账户写入路径要拿集成测试覆盖的原因之一。
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_cst_point_account_scope UNIQUE (tenant_id, customer_id, program_id)
);

CREATE TABLE cst_point_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT,
  account_id BIGINT NOT NULL,
  entry_type VARCHAR(24) NOT NULL,
  points BIGINT NOT NULL,
  currency_code VARCHAR(3) NOT NULL DEFAULT 'USD',
  balance_after BIGINT NOT NULL,
  business_type VARCHAR(32),
  business_id BIGINT,
  idempotency_key VARCHAR(64) NOT NULL,
  rule_snapshot_json VARCHAR(2000),
  expires_at TIMESTAMP,
  occurred_at TIMESTAMP NOT NULL,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  -- 与生产基线对齐（DC-11）：账本追加型，PO 上没有 updated_* 映射，取库默认值。
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_cst_point_ledger_idem UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE cst_point_rule_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  business_type VARCHAR(32) NOT NULL DEFAULT '',
  store_id BIGINT NOT NULL DEFAULT 0,
  earn_rate DECIMAL(12,4) NOT NULL DEFAULT 1.0000,
  redeem_rate DECIMAL(12,4) NOT NULL DEFAULT 1.0000,
  expiry_days INT NOT NULL DEFAULT 0,
  redeem_cap_points BIGINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  idempotency_key VARCHAR(128) DEFAULT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_cst_point_rule_config_scope UNIQUE (tenant_id, business_type, store_id)
);

CREATE TABLE cst_event_outbox (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  event_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(32) NOT NULL,
  aggregate_id VARCHAR(64) NOT NULL,
  payload_json VARCHAR(4000) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP,
  published_at TIMESTAMP,
  -- 与生产基线对齐（DC-11 审计列）
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL
);
