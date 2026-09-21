-- =============================================================================
-- C 端预约迁移（E-MIG）：Backfill + Verify + Switch + Rollback
-- 目标库：gv_saas（platform-order-service）；源库：gv_im（im-order-service）
-- 说明：本脚本在 gv_saas 会话内执行，跨库读写 gv_im（同一 MySQL 实例）。
--       阶段一 Expand 的建表已由 Flyway V2__ord_reservation.sql 完成；V3 增加 idempotency_key 列。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0) 执行前准备（运维替换变量 + 物化账号映射）
-- -----------------------------------------------------------------------------
-- 【必填】__MIGRATION_TENANT_ID__：存量迁移租户的 id，替换为
--   SELECT id FROM tnt_tenant WHERE tenant_code = 'MIGRATION';
--   说明：历史预约统一落到该租户，之后按账号/门店归属再二次拆分到真实租户（另行方案）。
-- 【必填】mig_user_customer：旧 user_id → SaaS customer_id 映射（由账号迁移 E-MIG 产出）。
--   若账号迁移已将旧 user_id 对齐到 cst_customer.account_id，可改用下方「简单映射」分支。

-- 简单映射分支（仅当旧 user_id == cst_customer.account_id 时可用，否则跳过）：
-- CREATE TEMPORARY TABLE mig_user_customer AS
--   SELECT c.account_id AS user_id, c.id AS customer_id
--   FROM gv_saas.cst_customer c
--   WHERE c.tenant_id = __MIGRATION_TENANT_ID__ AND c.account_id IS NOT NULL;

-- 推荐：物化映射表（由账号迁移脚本产出后本脚本只读）
-- CREATE TEMPORARY TABLE mig_user_customer (
--   user_id     bigint unsigned NOT NULL,
--   customer_id bigint unsigned NOT NULL,
--   PRIMARY KEY (user_id)
-- );
-- INSERT INTO mig_user_customer (user_id, customer_id) VALUES ...; -- 账号迁移产出

-- -----------------------------------------------------------------------------
-- [Backfill] 历史预约回填到「存量迁移租户」
-- 字段映射（RESERVATION_E_MIG_01 §2.2）：
--   order_no→reservation_no、user_id→customer_id、store_id→store_id、
--   service_type_id→business_type（1 酒店/HOTEL，2 KTV/KTV，3 足浴/SPA）、
--   person_num→party_size、status(pending_verification→CONFIRMED, verified→CONVERTED)、
--   reserve_date+reserve_time_period→start_at/end_at（占位：默认全天，待门店时区规则）。
-- 幂等：ON DUPLICATE KEY UPDATE 按唯一键 (tenant_id, reservation_no) 跳过重复回填。
-- -----------------------------------------------------------------------------
INSERT INTO gv_saas.ord_reservation
  (tenant_id, store_id, reservation_no, customer_id, business_type, resource_id,
   start_at, end_at, party_size, status, order_id, version,
   created_by, created_at, updated_by, updated_at)
SELECT
  __MIGRATION_TENANT_ID__                    AS tenant_id,
  r.store_id,
  r.order_no                                 AS reservation_no,
  m.customer_id                              AS customer_id,
  CASE r.service_type_id
    WHEN 1 THEN 'HOTEL'
    WHEN 2 THEN 'KTV'
    WHEN 3 THEN 'SPA'
    ELSE 'RETAIL'
  END                                        AS business_type,
  NULL                                       AS resource_id,
  TIMESTAMP(r.reserve_date, TIME('00:00:00')) AS start_at,  -- 占位：待门店时区/时段规则细化
  TIMESTAMP(r.reserve_date, TIME('23:59:59')) AS end_at,    -- 占位
  r.person_num                               AS party_size,
  CASE r.status
    WHEN 'pending_verification' THEN 'CONFIRMED'
    WHEN 'verified' THEN 'CONVERTED'
    ELSE 'PENDING'
  END                                        AS status,
  NULL                                       AS order_id,
  CAST(r.version AS SIGNED)                  AS version,
  r.created_by, r.created_at, r.updated_by, r.updated_at
FROM gv_im.ord_reservation r
LEFT JOIN mig_user_customer m ON m.user_id = r.user_id
ON DUPLICATE KEY UPDATE
  customer_id = VALUES(customer_id),
  status      = VALUES(status),
  updated_at  = VALUES(updated_at);

-- -----------------------------------------------------------------------------
-- [Verify] 对账校验（两边条数 / 关键字段比对）
-- -----------------------------------------------------------------------------
-- 1) 条数对账
SELECT 'source' AS side, COUNT(*) AS cnt FROM gv_im.ord_reservation
UNION ALL
SELECT 'target', COUNT(*) FROM gv_saas.ord_reservation WHERE tenant_id = __MIGRATION_TENANT_ID__;

-- 2) 源有目标无（漏迁）
SELECT r.order_no
FROM gv_im.ord_reservation r
LEFT JOIN gv_saas.ord_reservation t
  ON t.tenant_id = __MIGRATION_TENANT_ID__ AND t.reservation_no = r.order_no
WHERE t.id IS NULL;

-- 3) 目标有源无（多迁）
SELECT t.reservation_no
FROM gv_saas.ord_reservation t
LEFT JOIN gv_im.ord_reservation r ON r.order_no = t.reservation_no
WHERE t.tenant_id = __MIGRATION_TENANT_ID__ AND r.id IS NULL;

-- 4) 关键字段比对（状态/人数/门店/客户映射）
SELECT
  r.order_no,
  r.status           AS src_status,
  t.status           AS dst_status,
  r.person_num       AS src_party,
  t.party_size       AS dst_party,
  r.store_id         AS src_store,
  t.store_id         AS dst_store,
  m.customer_id      AS mapped_customer,
  t.customer_id      AS dst_customer,
  CASE WHEN t.status IS NULL THEN 'MISSING'
       WHEN t.status = CASE r.status WHEN 'pending_verification' THEN 'CONFIRMED' WHEN 'verified' THEN 'CONVERTED' ELSE 'PENDING' END
        AND t.party_size = r.person_num AND t.store_id = r.store_id
       THEN 'OK' ELSE 'DIFF' END AS check_result
FROM gv_im.ord_reservation r
LEFT JOIN mig_user_customer m ON m.user_id = r.user_id
LEFT JOIN gv_saas.ord_reservation t
  ON t.tenant_id = __MIGRATION_TENANT_ID__ AND t.reservation_no = r.order_no
HAVING check_result <> 'OK';

-- 5) 唯一键冲突自检（同租户 reservation_no 重复 / 幂等键重复）
SELECT tenant_id, reservation_no, COUNT(*) AS c
FROM gv_saas.ord_reservation
WHERE tenant_id = __MIGRATION_TENANT_ID__
GROUP BY tenant_id, reservation_no
HAVING c > 1;

SELECT tenant_id, idempotency_key, COUNT(*) AS c
FROM gv_saas.ord_reservation
WHERE tenant_id = __MIGRATION_TENANT_ID__ AND idempotency_key IS NOT NULL
GROUP BY tenant_id, idempotency_key
HAVING c > 1;

-- -----------------------------------------------------------------------------
-- [Switch] 灰度切换占位（由 Gateway Feature Flag/租户灰度控制，非 SQL）
-- -----------------------------------------------------------------------------
-- 读路径：/api/v1/reservations/** 由 im-order-service 切到 platform-order-service(ReservationCompatController)。
-- 写路径：新预约写入 /api/v1/business/reservations(ReservationController)，旧写路径按租户灰度停写。

-- -----------------------------------------------------------------------------
-- [Rollback] 回滚：删除回填到存量迁移租户的历史预约（Switch 失败时）
-- -----------------------------------------------------------------------------
-- DELETE FROM gv_saas.ord_reservation WHERE tenant_id = __MIGRATION_TENANT_ID__;
-- 说明：删除仅影响 SaaS 侧回填数据，IM 库旧数据不受影响，可随时重新 Backfill。
