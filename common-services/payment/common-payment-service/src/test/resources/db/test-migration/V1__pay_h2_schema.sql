-- H2 (MODE=MySQL) test schema for payment integration test.
-- Mirrors pay_collect/pay_intent/pay_transaction from production MySQL Flyway.
-- 注：pay_collect.request_json/response_json 在 MySQL 是 JSON，这里用 TEXT —— H2 把通过 JDBC 参数写入的
-- 字符串按「JSON 字符串标量」保存（读回来带引号与转义），无法忠实回放 MySQL 的 JSON 文本往返，
-- 而应用侧只按字符串读写这两列（Jackson 自行解析），故测试用 TEXT 保持与生产一致的可读回语义。

CREATE TABLE pay_collect (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  collect_no VARCHAR(64) NOT NULL,
  order_id BIGINT NOT NULL,
  idempotency_key VARCHAR(64) NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'INIT',
  -- 币种快照（生产 V10__pay_currency_snapshot.sql）：本次组合收款/分腿/找零币种，历史行回填 USD。
  currency_code VARCHAR(3) NOT NULL DEFAULT 'USD',
  request_json TEXT,
  response_json TEXT,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE pay_intent (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  merchant_account_id BIGINT,
  provider VARCHAR(16) NOT NULL,
  payment_method VARCHAR(32) NOT NULL,
  amount DECIMAL(20,6) NOT NULL,
  currency_code CHAR(3) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'CREATED',
  idempotency_key VARCHAR(64) NOT NULL,
  expires_at TIMESTAMP,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL,
  -- 与生产 V1__pay_baseline.sql 一致：租户内分腿幂等键唯一。
  -- 同一笔收款拆两笔现金类分腿（ALIPAY+CASH）时，若幂等键不按分腿序号派生，第二腿会在此唯一键冲突，
  -- H2 侧补上该约束后，拆分收款回归用例才能真正复现生产行为。
  CONSTRAINT uk_pay_intent_tenant_idem UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE pay_transaction (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  payment_intent_id BIGINT NOT NULL,
  provider VARCHAR(16),
  provider_transaction_no VARCHAR(128),
  provider_payload_digest VARCHAR(128),
  amount DECIMAL(20,6) NOT NULL,
  currency_code CHAR(3) NOT NULL,
  exchange_rate DECIMAL(20,6),
  fee_amount DECIMAL(20,6),
  status VARCHAR(24) NOT NULL,
  occurred_at TIMESTAMP NOT NULL,
  raw_reference VARCHAR(255)
);

CREATE TABLE tenant_payment_method (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  method VARCHAR(32) NOT NULL,
  granted INT NOT NULL DEFAULT 0,
  user_enabled INT NOT NULL DEFAULT 1,
  status VARCHAR(24),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_tenant_payment_method UNIQUE (tenant_id, method)
);

INSERT INTO tenant_payment_method (tenant_id, method, granted, user_enabled, status, created_at, updated_at)
VALUES (1001, 'WALLET', 1, 1, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       -- 线上渠道（ALIPAY）组合收款用例需要租户授权，与 tenant-payment-method 同源。
       (1001, 'ALIPAY', 1, 1, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 线上支付渠道配置（镜像生产 V2__pay_channel_config.sql）：
-- ALIPAY/WECHAT/STRIPE 走「租户授权 + 渠道已开通」双重判定，缺少本表时组合收款里的线上分腿会 500。
CREATE TABLE pay_channel_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT,
  channel VARCHAR(16) NOT NULL,
  enabled INT NOT NULL DEFAULT 0,
  merchant_id VARCHAR(64),
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_pay_channel_tenant_store_channel UNIQUE (tenant_id, store_id, channel)
);

INSERT INTO pay_channel_config (tenant_id, store_id, channel, enabled, status, created_at, updated_at)
VALUES (1001, NULL, 'ALIPAY', 1, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

CREATE TABLE ord_order (
  id BIGINT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  -- 币种快照（生产 V1__ord_order_baseline.sql + V22）：收款/退款必须与订单币种一致（禁止跨币种）。
  currency_code CHAR(3) NOT NULL DEFAULT 'USD',
  total_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  paid_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  refundable_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  discount_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  tax_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  completed_at TIMESTAMP,
  updated_at TIMESTAMP
);
CREATE TABLE ord_order_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  total_amount DECIMAL(20,6) NOT NULL,
  status VARCHAR(24) NOT NULL
);
-- 订单币种与集成测试的收款币种（CNY）保持一致：收款/退款必须与订单币种相同，跨币种一律 422。
INSERT INTO ord_order (id, tenant_id, status, currency_code, total_amount, paid_amount, updated_at)
VALUES (9001, 1001, 'WAITING_SETTLEMENT', 'CNY', 8000, 0, CURRENT_TIMESTAMP);
