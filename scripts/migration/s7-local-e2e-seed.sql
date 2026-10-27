-- S7 本地 E2E 可复现 seed（仅 Kind/open-im-local 测试环境）
-- 幂等；不替代 Flyway 结构迁移。
USE open_saas;

-- 1) 管理后台租户管理员映射到统一账本账号 101
UPDATE cons_admin_account
   SET platform_account_id = 101, updated_at = NOW(3)
 WHERE username = 'tenant-admin';

-- 2) 账号 101 同时具备 STAFF（后台）与 CUSTOMER（C 端）身份
INSERT INTO tnt_membership
  (tenant_id, account_id, membership_type, status, member_no, authorization_version,
   created_at, updated_at)
SELECT 100, 101, 'STAFF', 'ACTIVE', 'S101', 1, NOW(3), NOW(3)
 WHERE NOT EXISTS (
   SELECT 1 FROM tnt_membership
    WHERE tenant_id = 100 AND account_id = 101 AND membership_type = 'STAFF'
 );

INSERT INTO tnt_membership
  (tenant_id, account_id, membership_type, status, member_no, authorization_version,
   created_at, updated_at)
SELECT 100, 101, 'CUSTOMER', 'ACTIVE', 'C101X', 1, NOW(3), NOW(3)
 WHERE NOT EXISTS (
   SELECT 1 FROM tnt_membership
    WHERE tenant_id = 100 AND account_id = 101 AND membership_type = 'CUSTOMER'
 );

-- 3) C 会员档案（customer_id 与账号绑定）
INSERT INTO cst_member
  (tenant_id, origin_store_id, account_id, member_no, name_cipher, phone_cipher,
   phone_digest, status, joined_at, version, created_at, updated_at)
SELECT 100, 102, 101, 'M101S7', 'v1:e2e', 'v1:e2e', 's7-e2e-101',
       'ACTIVE', NOW(3), 0, NOW(3), NOW(3)
 WHERE NOT EXISTS (
   SELECT 1 FROM cst_member WHERE tenant_id = 100 AND account_id = 101
 );

-- 4) 租户 owner 在 HOTEL 门店 102 / KTV 门店 100 的角色绑定
INSERT INTO iam_role_binding
  (subject_type, subject_id, role_id, scope_type, tenant_id, organization_id, store_id,
   authorization_version, status, created_at, updated_at)
SELECT 'MEMBERSHIP', m.id, r.id, 'STORE', 100, 100, 102, 1, 'ACTIVE', NOW(3), NOW(3)
  FROM tnt_membership m
  JOIN iam_role r ON r.code = 'tenant.owner' AND r.tenant_id IS NULL
 WHERE m.tenant_id = 100 AND m.account_id = 101 AND m.membership_type = 'STAFF'
   AND NOT EXISTS (
     SELECT 1 FROM iam_role_binding b
      WHERE b.subject_type = 'MEMBERSHIP' AND b.subject_id = m.id
        AND b.role_id = r.id AND b.store_id = 102 AND b.status = 'ACTIVE'
   );

INSERT INTO iam_role_binding
  (subject_type, subject_id, role_id, scope_type, tenant_id, organization_id, store_id,
   authorization_version, status, created_at, updated_at)
SELECT 'MEMBERSHIP', m.id, r.id, 'STORE', 100, 100, 100, 1, 'ACTIVE', NOW(3), NOW(3)
  FROM tnt_membership m
  JOIN iam_role r ON r.code = 'tenant.owner' AND r.tenant_id IS NULL
 WHERE m.tenant_id = 100 AND m.account_id = 101 AND m.membership_type = 'STAFF'
   AND NOT EXISTS (
     SELECT 1 FROM iam_role_binding b
      WHERE b.subject_type = 'MEMBERSHIP' AND b.subject_id = m.id
        AND b.role_id = r.id AND b.store_id = 100 AND b.status = 'ACTIVE'
   );

-- 5) 酒店未来 14 天房量（room_type 1，总房量 10）
INSERT INTO htl_room_availability
  (tenant_id, organization_id, store_id, room_type_id, stay_date, total, sold, held, version, created_at, updated_at)
SELECT 100, (SELECT organization_id FROM tnt_store WHERE tenant_id = 100 AND id = 102), 102, 1,
       DATE_ADD('2026-10-25', INTERVAL n DAY), 10, 0, 0, 0, NOW(3), NOW(3)
  FROM (
    SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
    UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
    UNION ALL SELECT 13
  ) days
ON DUPLICATE KEY UPDATE total = VALUES(total), updated_at = NOW(3);

-- 校验
SELECT 'store' AS item, COUNT(*) AS cnt FROM tnt_store WHERE tenant_id = 100 AND code = 'openchat-hotel-001'
UNION ALL SELECT 'staff', COUNT(*) FROM tnt_membership WHERE tenant_id = 100 AND account_id = 101 AND membership_type = 'STAFF'
UNION ALL SELECT 'customer', COUNT(*) FROM tnt_membership WHERE tenant_id = 100 AND account_id = 101 AND membership_type = 'CUSTOMER'
UNION ALL SELECT 'inventory', COUNT(*) FROM htl_room_availability WHERE tenant_id = 100 AND store_id = 102;
