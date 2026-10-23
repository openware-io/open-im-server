-- 回填 cst_member.membership_id（S2 迁移脚本，幂等）。
-- 前置：tnt_membership 已按 account_id + tenant_id 建好 CUSTOMER 成员。
-- 不自动创建 membership；未匹配行保留 NULL，由业务补建后重跑。
USE open_saas;

UPDATE cst_member m
  JOIN tnt_membership mem
    ON mem.account_id = m.account_id
   AND mem.tenant_id = m.tenant_id
   AND mem.membership_type = 'CUSTOMER'
   AND mem.status = 'ACTIVE'
SET m.membership_id = mem.id
WHERE m.membership_id IS NULL
  AND m.account_id IS NOT NULL;

-- 校验：未匹配客户
SELECT m.id, m.tenant_id, m.account_id, m.member_no
FROM cst_member m
LEFT JOIN tnt_membership mem ON mem.id = m.membership_id
WHERE m.account_id IS NOT NULL AND mem.id IS NULL
ORDER BY m.id;

-- 校验：account_id 与 membership.account_id 漂移
SELECT m.id, m.account_id AS member_account, mem.account_id AS membership_account
FROM cst_member m
JOIN tnt_membership mem ON mem.id = m.membership_id
WHERE mem.account_id <> m.account_id;
