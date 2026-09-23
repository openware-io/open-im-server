-- 客户（cst_member）历史脏数据：**去重 → 清理 → 建索引**（顺序不可调换）。
--
-- 背景（ACK 租户 100 实测）：cst_member 63 行，account_id 全非空，但 COUNT(DISTINCT account_id) = 23
-- —— 同一个 SaaS 账号有 2~4 条重复客户（40 行是重复）；姓名/手机号整列为空（phone_digest 全是空串的
-- SHA-256）；cst_point_account 44 行全部 0 积分且无流水；cst_wallet_account 目前 1 行（有流水）。
-- 用户口径：「没有关联的可以都删除，不要留垃圾数据」，但**业务单据与资金事实一律不重写、不冒险**。
--
-- 本迁移做三件事，且**严格按此顺序**：
--   1) 去重：每个 (tenant_id, account_id)（非空）分组保留 id 最小的一行为「保留行」；
--      同 (tenant_id, im_account)（非空）同理（V4 刚加列，首个部署时全为 NULL，此分支为空操作，
--      但保留它可让本迁移在部分数据已绑定 IM 的环境上同样正确）。
--   2) 清理：删除「重复且无任何业务引用/资金事实」的客户行，连同它们名下**空账户**（先删空账户再删客户，
--      否则会留下孤儿账户）；以及 account_id IS NULL AND im_account IS NULL 且无任何引用/非空账户的行。
--   3) 建索引：先删干净才允许建 (tenant_id, account_id) 上的索引；若去重后**仍**存在重复（因为第 1 步
--      把「有引用的重复行」保留了下来），则退化为**非唯一索引** idx_*，由应用层保证唯一（见下方说明）。
--
-- 安全边界（宁可留数据，也不能破坏订单/资金事实）：
--   * 判定「有引用」= 被 ord_order.customer_id 或 ord_reservation.customer_id 指向；
--   * 判定「有资金事实」= 名下储值账户 available_amount<>0 或 frozen_amount<>0 或有 cst_wallet_ledger 流水，
--     或名下积分账户 available_points<>0 或 frozen_points<>0 或有 cst_point_ledger 流水；
--   * 命中的重复行**保留**：不删、不改引用（跨域改单风险大）——它只是「不再是唯一索引的目标」；
--   * 从未关联（account_id IS NULL AND im_account IS NULL）的行，只有在**完全没有**上述引用/非空账户时才删。
--
-- 跨域表存在性：order 域与本域共库（open_saas）但各自独立的 Flyway 历史表，全新库上 ord_* 可能尚未建表。
-- 因此 ord_* 的引用判定用 information_schema 探测后再拼进动态 SQL：表不存在 ⇒ 不可能有引用 ⇒ 跳过该判定。
--
-- 幂等/可重跑：临时表先 DROP 再建；去重与清理都是「按当前数据重新计算集合」，重复执行结果一致
-- （第二次执行时已经没有重复行、也没有可删行，各 DELETE 影响 0 行）；索引创建前先查
-- information_schema.statistics，已存在则跳过 —— 空数据上执行等价于无操作。
--
-- ⚠️ 唯一索引 vs 非唯一索引（本迁移的最终选择，也是本迁移最重要的一条边界）：
--   去重只删「无引用」的重复行；**带业务引用的重复行会保留**（同上边界）。一旦存在这种行，
--   (tenant_id, account_id) 上就仍然有重复值，此时**不能**建唯一索引（会直接让迁移失败）。
--   因此本迁移在「去重后重新统计」的基础上二选一：无重复 → uk_cst_member_tenant_account（唯一约束），
--   仍有重复 → idx_cst_member_tenant_account（非唯一，应用层 create/getOrCreateByAccount 幂等兜底）。
--   两种环境的 schema 会因此不同，这是「不删有引用的数据」与「尽量拿到 DB 级唯一约束」之间
--   唯一安全的折中；索引名互斥，后续迁移请按「两个名字都可能存在」处理。
--
-- 人工核对 SQL（上线前在目标库上跑，用来预判本迁移的删除量与最终索引形态）：
--   -- A. 重复分组与非保留行
--   SELECT tenant_id, account_id, COUNT(*) c, MIN(id) keep_id
--     FROM cst_member WHERE account_id IS NOT NULL
--    GROUP BY tenant_id, account_id HAVING c > 1;
--   -- B. 被单据引用的客户（这些行一定会被保留）
--   SELECT DISTINCT customer_id FROM ord_order WHERE customer_id IS NOT NULL
--    UNION SELECT DISTINCT customer_id FROM ord_reservation WHERE customer_id IS NOT NULL;
--   -- C. 去重后是否仍有重复（决定建 uk_ 还是 idx_）
--   SELECT COUNT(*) FROM (SELECT 1 FROM cst_member WHERE account_id IS NOT NULL
--                          GROUP BY tenant_id, account_id HAVING COUNT(*) > 1) x;
--
-- 本机演练（MySQL 8.0.46，按 ACK 租户 100 的形状造数：63 行客户 / 23 个不同 account_id /
-- 44 行 0 积分且无流水的积分账户 / 1 个有 2 条流水的储值账户 / 4 单 + 7 预约引用）：
--   客户 63 → 32（删 31 条重复且无引用；9 条被单据引用或持有非空账户的重复行保留）
--   积分账户 44 → 23（删 21 条 0 积分且无流水，全部属于被删客户），储值账户 1 → 1（有流水，保留）
--   孤儿账户 0；剩余重复分组 8 → 建 idx_cst_member_tenant_account（非唯一）
--   重跑本文件结果不变（幂等）；空库 / 无 ord_* 表的库上执行均无报错
--   无任何「有引用的重复行」时（干净库）→ 建 uk_cst_member_tenant_account（唯一）

-- ---------------------------------------------------------------------------
-- 0) 引用判定谓词（ord_* 按表存在性拼装）
-- ---------------------------------------------------------------------------
SET @has_ord_order := (SELECT COUNT(*) FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_name = 'ord_order');
SET @has_ord_reservation := (SELECT COUNT(*) FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_name = 'ord_reservation');

-- 「该客户有业务引用或非空资金事实」——谓词里固定用别名 m 指代 cst_member。
SET @ref_predicate := CONCAT(
  IF(@has_ord_order > 0, 'EXISTS (SELECT 1 FROM ord_order o WHERE o.customer_id = m.id) OR ', ''),
  IF(@has_ord_reservation > 0, 'EXISTS (SELECT 1 FROM ord_reservation r WHERE r.customer_id = m.id) OR ', ''),
  'EXISTS (SELECT 1 FROM cst_wallet_account w WHERE w.customer_id = m.id ',
  '        AND (w.available_amount <> 0 OR w.frozen_amount <> 0 ',
  '             OR EXISTS (SELECT 1 FROM cst_wallet_ledger wl WHERE wl.wallet_account_id = w.id))) OR ',
  'EXISTS (SELECT 1 FROM cst_point_account p WHERE p.customer_id = m.id ',
  '        AND (p.available_points <> 0 OR p.frozen_points <> 0 ',
  '             OR EXISTS (SELECT 1 FROM cst_point_ledger pl WHERE pl.account_id = p.id)))'
);

-- ---------------------------------------------------------------------------
-- 1) 临时集合：保留行 / 全部重复行 / 待删行
-- ---------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_cst_member_dup_keep;
CREATE TEMPORARY TABLE tmp_cst_member_dup_keep (
  member_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (member_id)
) ENGINE=InnoDB COMMENT='重复分组中「有引用/有资金事实」必须保留的行';

DROP TEMPORARY TABLE IF EXISTS tmp_cst_member_dup_all;
CREATE TEMPORARY TABLE tmp_cst_member_dup_all (
  member_id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (member_id)
) ENGINE=InnoDB COMMENT='所有分组内非保留（非 MIN(id)）的重复行';

DROP TEMPORARY TABLE IF EXISTS tmp_cst_member_dup_delete;
CREATE TEMPORARY TABLE tmp_cst_member_dup_delete (
  member_id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (member_id)
) ENGINE=InnoDB COMMENT='最终确认可删的客户行（重复且无引用 / 从未关联且无引用）';

-- 1.1) 保留集：非保留重复行中「有引用或有资金事实」的行（account_id 与 im_account 两种分组口径）
SET @dup_keep_sql := CONCAT(
  'INSERT IGNORE INTO tmp_cst_member_dup_keep (member_id) ',
  'SELECT m.id FROM cst_member m JOIN (',
  '  SELECT tenant_id, account_id AS gkey, MIN(id) AS keep_id FROM cst_member ',
  '   WHERE account_id IS NOT NULL GROUP BY tenant_id, account_id HAVING COUNT(*) > 1',
  ') d ON d.tenant_id = m.tenant_id AND m.account_id = d.gkey ',
  'WHERE m.id <> d.keep_id AND (', @ref_predicate, ') ',
  'UNION ',
  'SELECT m.id FROM cst_member m JOIN (',
  '  SELECT tenant_id, im_account AS gkey, MIN(id) AS keep_id FROM cst_member ',
  '   WHERE im_account IS NOT NULL GROUP BY tenant_id, im_account HAVING COUNT(*) > 1',
  ') d ON d.tenant_id = m.tenant_id AND m.im_account = d.gkey ',
  'WHERE m.id <> d.keep_id AND (', @ref_predicate, ')'
);
PREPARE stmt FROM @dup_keep_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 1.2) 全部非保留重复行（含保留下来的那些；保留下来的随后会被 1.3 剔除）
SET @dup_all_sql := CONCAT(
  'INSERT IGNORE INTO tmp_cst_member_dup_all (member_id, tenant_id) ',
  'SELECT m.id, m.tenant_id FROM cst_member m JOIN (',
  '  SELECT tenant_id, account_id AS gkey, MIN(id) AS keep_id FROM cst_member ',
  '   WHERE account_id IS NOT NULL GROUP BY tenant_id, account_id HAVING COUNT(*) > 1',
  ') d ON d.tenant_id = m.tenant_id AND m.account_id = d.gkey WHERE m.id <> d.keep_id ',
  'UNION ',
  'SELECT m.id, m.tenant_id FROM cst_member m JOIN (',
  '  SELECT tenant_id, im_account AS gkey, MIN(id) AS keep_id FROM cst_member ',
  '   WHERE im_account IS NOT NULL GROUP BY tenant_id, im_account HAVING COUNT(*) > 1',
  ') d ON d.tenant_id = m.tenant_id AND m.im_account = d.gkey WHERE m.id <> d.keep_id'
);
PREPARE stmt FROM @dup_all_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 1.3) 待删重复行 = 全部非保留重复行 − 保留集
INSERT IGNORE INTO tmp_cst_member_dup_delete (member_id, tenant_id)
SELECT a.member_id, a.tenant_id
  FROM tmp_cst_member_dup_all a
 WHERE NOT EXISTS (SELECT 1 FROM tmp_cst_member_dup_keep k WHERE k.member_id = a.member_id);

-- 1.4) 追加「从未关联任何 IM/账号」且无任何引用/非空账户的客户（account_id IS NULL AND im_account IS NULL）
SET @unlinked_delete_sql := CONCAT(
  'INSERT IGNORE INTO tmp_cst_member_dup_delete (member_id, tenant_id) ',
  'SELECT m.id, m.tenant_id FROM cst_member m ',
  'WHERE m.account_id IS NULL AND m.im_account IS NULL ',
  '  AND NOT (', @ref_predicate, ')'
);
PREPARE stmt FROM @unlinked_delete_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2) 先删「空账户」，再删客户（顺序不能反，否则留下孤儿账户）
--    「空」= 0 余额且无任何流水；有流水的账户哪怕已经归零也是财务事实，不动。
-- ---------------------------------------------------------------------------
DELETE w FROM `cst_wallet_account` w
  JOIN tmp_cst_member_dup_delete d ON d.member_id = w.customer_id
 WHERE w.available_amount = 0
   AND w.frozen_amount = 0
   AND NOT EXISTS (SELECT 1 FROM cst_wallet_ledger wl WHERE wl.wallet_account_id = w.id);

DELETE p FROM `cst_point_account` p
  JOIN tmp_cst_member_dup_delete d ON d.member_id = p.customer_id
 WHERE p.available_points = 0
   AND p.frozen_points = 0
   AND NOT EXISTS (SELECT 1 FROM cst_point_ledger pl WHERE pl.account_id = p.id);

-- ---------------------------------------------------------------------------
-- 3) 删 token 行，再删客户行
-- ---------------------------------------------------------------------------
DELETE t FROM `cst_member_name_token` t
  JOIN tmp_cst_member_dup_delete d ON d.member_id = t.member_id;

DELETE m FROM `cst_member` m
  JOIN tmp_cst_member_dup_delete d ON d.member_id = m.id;

-- ---------------------------------------------------------------------------
-- 4) 索引
-- ---------------------------------------------------------------------------
-- 4.1) IM 绑定唯一索引：(tenant_id, im_account)；NULL 可重复（未绑定）。
--      只有「从未关联」这种语义才允许 NULL；一旦绑定就必须唯一，否则又会退回「同一 IM 多个客户」。
SET @im_idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
                        WHERE table_schema = DATABASE() AND table_name = 'cst_member'
                          AND index_name = 'uk_cst_member_tenant_im');
SET @im_index_sql := IF(@im_idx_exists > 0,
  'SELECT 1',
  'ALTER TABLE `cst_member` ADD UNIQUE KEY `uk_cst_member_tenant_im` (`tenant_id`, `im_account`)');
PREPARE stmt FROM @im_index_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4.2) 账号唯一索引：去重后重新统计，仍有重复（存在被保留的、有引用的重复行）就只能建非唯一索引。
SET @dup_remaining := IFNULL((
  SELECT COUNT(*) FROM (
    SELECT 1 FROM cst_member
     WHERE account_id IS NOT NULL
     GROUP BY tenant_id, account_id
    HAVING COUNT(*) > 1
  ) x
), 0);

SET @account_idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
                             WHERE table_schema = DATABASE() AND table_name = 'cst_member'
                               AND index_name IN ('uk_cst_member_tenant_account', 'idx_cst_member_tenant_account'));
SET @account_index_sql := CASE
  WHEN @account_idx_exists > 0 THEN 'SELECT 1'
  WHEN @dup_remaining = 0 THEN
    'ALTER TABLE `cst_member` ADD UNIQUE KEY `uk_cst_member_tenant_account` (`tenant_id`, `account_id`)'
  ELSE
    'ALTER TABLE `cst_member` ADD KEY `idx_cst_member_tenant_account` (`tenant_id`, `account_id`)'
END;
PREPARE stmt FROM @account_index_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

DROP TEMPORARY TABLE IF EXISTS tmp_cst_member_dup_keep;
DROP TEMPORARY TABLE IF EXISTS tmp_cst_member_dup_all;
DROP TEMPORARY TABLE IF EXISTS tmp_cst_member_dup_delete;
