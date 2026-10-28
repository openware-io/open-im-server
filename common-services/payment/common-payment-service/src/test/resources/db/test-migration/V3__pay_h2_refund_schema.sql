-- H2 (MODE=MySQL) test schema for refund idempotency integration tests.
-- Mirrors production MySQL pay_refund / pay_refund_rule_config from V1__pay_baseline.sql.
-- 只补测试真正读写的列；审计列 created_at/updated_at 非空无默认值，写入方必须提供。

CREATE TABLE pay_refund (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT,
  business_type VARCHAR(32) NOT NULL DEFAULT '',
  order_id BIGINT NOT NULL,
  payment_transaction_id BIGINT,
  request_id VARCHAR(64) NOT NULL,
  requested_amount DECIMAL(20,6) NOT NULL,
  approved_amount DECIMAL(20,6),
  currency_code VARCHAR(3) NOT NULL DEFAULT 'USD',
  provider_refund_no VARCHAR(128),
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  reason VARCHAR(255),
  requested_by BIGINT,
  approved_by BIGINT,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_pay_refund_tenant_req UNIQUE (tenant_id, request_id)
);

CREATE INDEX idx_pay_refund_tenant_store_created ON pay_refund (tenant_id, store_id, created_at);

CREATE TABLE pay_refund_rule_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  business_type VARCHAR(32) NOT NULL DEFAULT '',
  store_id BIGINT,
  approval_threshold DECIMAL(20,6) NOT NULL DEFAULT 0,
  offline_refund_enabled INT NOT NULL DEFAULT 1,
  version INT NOT NULL DEFAULT 0,
  idempotency_key VARCHAR(96),
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_pay_refund_rule_config_scope UNIQUE (tenant_id, business_type, store_id),
  CONSTRAINT uk_pay_refund_rule_config_idempotency UNIQUE (tenant_id, idempotency_key)
);
