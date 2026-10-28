-- H2 (MODE=MySQL) test schema for the channel-settlement bridge.
-- Mirrors production V2__pay_channel_settlement.sql (MySQL): idempotency ledger for channel callbacks.
-- 幂等判据：同一 (provider, transaction_id, kind) 只允许一条结算事实，重复回调在此唯一键上回放。

CREATE TABLE pay_channel_settlement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  provider VARCHAR(16) NOT NULL,
  transaction_id VARCHAR(64) NOT NULL,
  event_id VARCHAR(64),
  kind VARCHAR(16) NOT NULL,
  amount DECIMAL(20,6) NOT NULL,
  currency_code CHAR(3) NOT NULL,
  status VARCHAR(24) NOT NULL,
  pay_intent_id BIGINT,
  pay_transaction_id BIGINT,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_pay_channel_settlement_provider_transaction_kind
    UNIQUE (provider, transaction_id, kind)
);

CREATE INDEX idx_pay_channel_settlement_tenant_order ON pay_channel_settlement (tenant_id, order_id);
CREATE INDEX idx_pay_channel_settlement_provider_event ON pay_channel_settlement (provider, event_id);
