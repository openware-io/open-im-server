-- H2 (MODE=MySQL) test schema for customer wallet integration test.
-- Mirrors cst_wallet_account / cst_wallet_ledger from production MySQL Flyway.

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
  created_at TIMESTAMP NOT NULL
);
