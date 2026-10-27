-- H2 (MODE=MySQL) test schema for order integration test.
-- Mirrors the production MySQL Flyway schema (V1__ord_order_baseline.sql) minus
-- MySQL-only clauses (ENGINE/CHARSET/COLLATE/COMMENT/datetime(3)/unsigned) that H2 cannot parse.

-- 租户域门店快照（生产由 platform-tenant-service 拥有）：库存跨门店调拨只读核对
-- organization_id = store.organization_id、business_type = store.business_type。
CREATE TABLE tnt_store (
  id BIGINT NOT NULL PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  organization_id BIGINT NOT NULL,
  business_type VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE ord_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  organization_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  -- V28：快速开台幂等键（生产列可空 + 唯一键 (tenant_id, idempotency_key)）
  idempotency_key VARCHAR(64),
  business_type VARCHAR(32) NOT NULL,
  customer_id BIGINT,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  -- V24：订单只保留交易状态；status 是收口前的兼容列（与 trade_status 双写）
  trade_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  refund_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
  currency_code CHAR(3) NOT NULL DEFAULT 'USD',
  subtotal_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  discount_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  tax_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  total_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  paid_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  refundable_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  completed_at TIMESTAMP,
  cancelled_at TIMESTAMP,
  hold_reason VARCHAR(255),
  hold_at TIMESTAMP,
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL
);

-- 生产口径的唯一键：并发重复提交由数据库兜底（H2 同样建唯一索引，幂等用例因此是真实约束）。
CREATE UNIQUE INDEX uk_ord_order_idem ON ord_order (tenant_id, idempotency_key);

CREATE TABLE ord_order_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT,
  business_type VARCHAR(32),
  order_id BIGINT NOT NULL,
  item_type VARCHAR(32) NOT NULL,
  catalog_item_id BIGINT,
  resource_id BIGINT,
  name_snapshot VARCHAR(255) NOT NULL,
  unit_price DECIMAL(20,6) NOT NULL,
  quantity DECIMAL(20,6) NOT NULL,
  discount_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  tax_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  total_amount DECIMAL(20,6) NOT NULL,
  -- 币种快照（生产 V1__ord_order_baseline.sql）：明细落库时固化租户币种，历史行回填 USD。
  currency_code VARCHAR(3) NOT NULL DEFAULT 'USD',
  price_snapshot_json JSON,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  source VARCHAR(24) NOT NULL DEFAULT 'MERCHANT',
  inventory_status VARCHAR(24) NOT NULL DEFAULT 'NOT_APPLICABLE',
  inventory_material_id BIGINT,
  inventory_recovery_decision VARCHAR(24),
  inventory_recovery_reason VARCHAR(500),
  inventory_recovery_decided_by BIGINT,
  inventory_recovery_decided_at TIMESTAMP,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE ord_ktv_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  -- V24：组织快照（创建会话时从订单复制）
  organization_id BIGINT,
  store_id BIGINT,
  business_type VARCHAR(32),
  order_id BIGINT NOT NULL,
  room_resource_id BIGINT NOT NULL,
  occupation_id BIGINT,
  room_name_snapshot VARCHAR(128),
  room_code_snapshot VARCHAR(64),
  -- 生产 V1__ord_order_baseline.sql：开台登记人数与服务人员快照（订单列表/房态看板展示）。
  party_size INT,
  server_id BIGINT,
  server_name VARCHAR(64),
  reserved_start_at TIMESTAMP,
  reserved_end_at TIMESTAMP,
  opened_at TIMESTAMP,
  closed_at TIMESTAMP,
  billing_unit VARCHAR(16) NOT NULL DEFAULT 'HOUR',
  billing_start_at TIMESTAMP,
  free_wait_minutes INT NOT NULL DEFAULT 0,
  paused_seconds INT NOT NULL DEFAULT 0,
  pause_started_at TIMESTAMP,
  overtime_rate DECIMAL(20,6) NOT NULL DEFAULT 1.0,
  billing_rule_snapshot_json JSON,
  status VARCHAR(24) NOT NULL DEFAULT 'RESERVED',
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL
);

-- V24：一个订单只关联一个 KTV 会话（生产 uk_ord_ktv_session_order）
CREATE UNIQUE INDEX uk_ord_ktv_session_order ON ord_ktv_session (order_id);

-- Outbox / consumed tables exist so the @Scheduled EventOutboxRelay never hits a missing table.
CREATE TABLE ord_event_outbox (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT,
  event_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(32) NOT NULL,
  aggregate_id VARCHAR(64) NOT NULL,
  payload_json JSON NOT NULL,
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

CREATE TABLE ord_event_consumed (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT,
  event_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(32) NOT NULL,
  aggregate_id VARCHAR(64) NOT NULL,
  consumed_at TIMESTAMP NOT NULL,
  -- 与生产基线对齐（DC-11 审计列）：该表写入方只写 consumed_at，故 created_at/updated_at 取库默认值。
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 商品/物料（生产 V1__ord_order_baseline.sql）：
-- ord_product.image_urls / main_image_url 与 ord_inventory_material 同名两列是本次多图改造新增的列；
-- ord_inventory_material.description 是 V15 为仓库商品补的描述列（商品侧 ord_product.description 早就存在）；
-- ord_inventory_material.purchase_price 是 V20 为「库存管理采购价」补的列，可空（未维护 = NULL）。
-- H2 侧用 VARCHAR 承载 JSON 数组文本（MyBatis JacksonTypeHandler 读写 JSON 字符串），
-- 避免 H2 JSON 类型与 MySQL JSON 的隐式转换差异导致单测写入失败。
-- 商品类型 / 服务人员关联（生产 V1__ord_order_baseline.sql）：
-- item_type 历史行一律 PRODUCT（默认值）；server_resource_id 仅服务型商品有值，
-- 唯一键允许「多行同为 NULL」（H2 与 MySQL 的 UNIQUE 都把 NULL 视为互不相同），所以不影响实物商品。
CREATE TABLE ord_product (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  product_code VARCHAR(64) NOT NULL,
  name VARCHAR(255) NOT NULL,
  category VARCHAR(64) NOT NULL,
  item_type VARCHAR(16) NOT NULL DEFAULT 'PRODUCT',
  unit VARCHAR(32) NOT NULL,
  sale_price DECIMAL(20,6) NOT NULL,
  material_id BIGINT,
  server_resource_id BIGINT,
  stock_controlled TINYINT NOT NULL DEFAULT 1,
  catalog_item_id BIGINT,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  sort_order INT NOT NULL DEFAULT 0,
  description VARCHAR(500),
  image_urls VARCHAR(4000),
  main_image_url VARCHAR(512),
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL,
  -- 与生产 V26 一致：同一门店下一个服务人员只能被一个商品占用。
  CONSTRAINT uk_ord_product_server_resource UNIQUE (tenant_id, store_id, server_resource_id)
);

CREATE TABLE ord_inventory_material (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  material_code VARCHAR(64) NOT NULL,
  name VARCHAR(255) NOT NULL,
  category VARCHAR(64) NOT NULL,
  unit VARCHAR(32) NOT NULL,
  description VARCHAR(255),
  safety_stock DECIMAL(20,6) NOT NULL DEFAULT 0,
  -- 采购价（生产 V1__ord_order_baseline.sql）：最小货币单位（分），可空。
  purchase_price DECIMAL(20,6),
  -- 采购价币种（生产 V1__ord_order_baseline.sql）：成本/毛利报表按它归集，历史行回填 USD。
  currency_code VARCHAR(3) NOT NULL DEFAULT 'USD',
  image_urls VARCHAR(4000),
  main_image_url VARCHAR(512),
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL
);

-- 仓库 / 库位（生产 V25）：库存作用域 tenant+business_type+store+warehouse+material 的仓库维度。
-- 一店可多仓；每个 (tenant, business, store) 至少一个 code=DEFAULT 的默认仓（写入路径懒创建）。
CREATE TABLE ord_inventory_warehouse (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  organization_id BIGINT,
  business_type VARCHAR(32),
  store_id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_ord_inventory_warehouse UNIQUE (tenant_id, business_type, store_id, code)
);

-- 库存余额/流水（生产 V1__ord_order_baseline.sql）：
-- 点单目录可点性要查库存余额，库存集成场景要写入库流水，所以这两张表也必须建。
CREATE TABLE ord_inventory_stock (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  -- V24：组织快照（归属/报表，不进入唯一键）与业态快照（作用域）。
  organization_id BIGINT,
  business_type VARCHAR(32),
  store_id BIGINT NOT NULL,
  -- V25：仓/库位。
  warehouse_id BIGINT NOT NULL DEFAULT 0,
  material_id BIGINT NOT NULL,
  on_hand_qty DECIMAL(20,6) NOT NULL DEFAULT 0,
  reserved_qty DECIMAL(20,6) NOT NULL DEFAULT 0,
  -- 移动加权平均成本（生产 V24）：最小货币单位/计量单位；只在入库时重算，出库只按它结转。
  avg_cost DECIMAL(20,6) NOT NULL DEFAULT 0,
  -- 平均成本币种快照（生产 V24），缺省 USD（与 V22 的历史回填口径一致）。
  currency_code VARCHAR(3) NOT NULL DEFAULT 'USD',
  revision INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL,
  -- 生产 V25 口径：作用域唯一（organization 不进唯一键）。
  CONSTRAINT uk_ord_inventory_stock_scope UNIQUE (tenant_id, business_type, store_id, warehouse_id, material_id)
);

CREATE TABLE ord_inventory_transaction (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  -- V24：组织快照与业态快照（作用域）。
  organization_id BIGINT,
  business_type VARCHAR(32),
  store_id BIGINT NOT NULL,
  -- V25：仓/库位。
  warehouse_id BIGINT NOT NULL DEFAULT 0,
  material_id BIGINT NOT NULL,
  transaction_type VARCHAR(24) NOT NULL,
  quantity_delta DECIMAL(20,6) NOT NULL,
  quantity_before DECIMAL(20,6) NOT NULL,
  quantity_after DECIMAL(20,6) NOT NULL,
  -- 入库批次单价 / 出库结转单价（生产 V24）：最小货币单位/计量单位，历史行可空。
  unit_cost DECIMAL(20,6),
  -- 成本发生额（生产 V24）：带符号，与 quantity_delta 同号。
  total_cost DECIMAL(20,6),
  currency_code VARCHAR(3) NOT NULL DEFAULT 'USD',
  source_type VARCHAR(32) NOT NULL,
  source_id VARCHAR(64),
  idempotency_key VARCHAR(128) NOT NULL,
  reason VARCHAR(500),
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  -- 与生产基线对齐：追加型流水无 UPDATE 路径，updated_at 取库默认值，updated_by 为 0。
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 与生产 V11 一致：幂等键租户内唯一，重复扣减/回放只认首次流水。
  CONSTRAINT uk_ord_inventory_tx_idempotency UNIQUE (tenant_id, idempotency_key)
);

-- 商品/服务目录（生产 V1__ord_order_baseline.sql 的 product_id/stock_controlled 与图片两列）：
-- 商品上架/更新会同步目录项（含图片），库存商品的目录项可用性还会反查商品与物料，所以集成测试必须建这张表。
CREATE TABLE ord_catalog_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_id BIGINT,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT,
  category VARCHAR(64) NOT NULL,
  item_type VARCHAR(32) NOT NULL DEFAULT 'PRODUCT',
  name VARCHAR(255) NOT NULL,
  unit VARCHAR(32) NOT NULL DEFAULT '份',
  unit_price DECIMAL(20,6) NOT NULL,
  description VARCHAR(500),
  image_urls VARCHAR(4000),
  main_image_url VARCHAR(512),
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  stock_controlled TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL
);

-- 商品分类字典（生产 V1__ord_order_baseline.sql）：商品管理页的分类 CRUD 与「被商品引用不可删」都用它。
CREATE TABLE ord_product_category (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_ord_product_category_store_name UNIQUE (tenant_id, store_id, name)
);

-- 单据号每日序号（生产 V1__ord_order_baseline.sql）：订单号 O<yyyyMMdd><seq> / 预约号 R<yyyyMMdd><seq>
-- 的当日序号游标，由 DailySerialNumberGenerator 在事务内「自增 → 读回」分配：
-- 唯一键 (tenant_id, biz_type, business_date) 既保证每日一行，也让并发首单只放行一个事务。
CREATE TABLE ord_daily_serial (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  biz_type VARCHAR(16) NOT NULL,
  business_date DATE NOT NULL,
  current_seq BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_ord_daily_serial_key UNIQUE (tenant_id, biz_type, business_date)
);

-- 作废审批规则（生产 V1__ord_order_baseline.sql）：门店覆盖 > 业态默认 > 租户默认。
CREATE TABLE ord_void_rule_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  business_type VARCHAR(64) NOT NULL DEFAULT '',
  store_id BIGINT NOT NULL DEFAULT 0,
  require_approval BOOLEAN NOT NULL DEFAULT FALSE,
  version INT NOT NULL DEFAULT 0,
  idempotency_key VARCHAR(128),
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_ord_void_rule_scope UNIQUE (tenant_id, business_type, store_id),
  CONSTRAINT uk_ord_void_rule_idem UNIQUE (tenant_id, idempotency_key)
);

-- KTV reservation (production V1__ord_order_baseline.sql). The scheduler reads
-- overdue reservations during the Spring context test, so the H2 test schema
-- must contain the same table even though other reservation tests seed it.
CREATE TABLE ord_reservation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  reservation_no VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(64),
  customer_id BIGINT,
  business_type VARCHAR(32) NOT NULL,
  resource_id BIGINT,
  room_type_id BIGINT,
  start_at DATETIME(3) NOT NULL,
  end_at DATETIME(3) NOT NULL,
  party_size INT NOT NULL DEFAULT 1,
  contact VARCHAR(128),
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  order_id BIGINT,
  arrived_at DATETIME(3),
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL,
  CONSTRAINT uk_ord_reservation_tenant_no UNIQUE (tenant_id, reservation_no),
  CONSTRAINT uk_ord_reservation_idem UNIQUE (tenant_id, idempotency_key)
);

-- Hotel scheduler tables (production V3/V4/V24). The context loads the booking
-- expiry and payment reconciler schedulers, so these must exist even in tests
-- that mock the hotel application services.
CREATE TABLE htl_booking (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  organization_id BIGINT,
  business_type VARCHAR(32) NOT NULL DEFAULT 'HOTEL',
  booking_no VARCHAR(64) NOT NULL,
  guest_name VARCHAR(128),
  guest_phone VARCHAR(32),
  room_type VARCHAR(64),
  room_no VARCHAR(32),
  check_in_date DATE,
  check_out_date DATE,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRM',
  amount_minor BIGINT NOT NULL DEFAULT 0,
  currency VARCHAR(3) NOT NULL DEFAULT 'USD',
  deposit_order_id BIGINT,
  deposit_amount_minor BIGINT,
  order_id BIGINT,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL
);
CREATE TABLE htl_booking_night (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  booking_id BIGINT NOT NULL,
  stay_date DATE NOT NULL,
  price_minor BIGINT NOT NULL DEFAULT 0,
  currency VARCHAR(3) NOT NULL DEFAULT 'USD',
  status VARCHAR(24) NOT NULL DEFAULT 'HELD',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL
);
CREATE TABLE htl_room_availability (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  organization_id BIGINT,
  store_id BIGINT NOT NULL,
  room_type_id BIGINT NOT NULL,
  stay_date DATE NOT NULL,
  total INT NOT NULL DEFAULT 0,
  sold INT NOT NULL DEFAULT 0,
  held INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL
);
CREATE TABLE htl_room_availability_hold (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  organization_id BIGINT,
  store_id BIGINT NOT NULL,
  booking_id BIGINT NOT NULL,
  room_type_id BIGINT NOT NULL,
  stay_date DATE NOT NULL,
  rooms INT NOT NULL DEFAULT 1,
  status VARCHAR(24) NOT NULL DEFAULT 'HELD',
  expires_at DATETIME(3) NOT NULL,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL
);
