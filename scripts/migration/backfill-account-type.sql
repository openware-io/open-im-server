-- 回填 idt_account.account_type 到目标模型（S2 代码切换后执行，幂等）。
-- 目标：PLATFORM_OPERATOR / USER；员工/客户身份由 tnt_membership.membership_type 决定。
USE open_saas;

-- 1) 平台运营：持有 PLATFORM 作用域角色的账号
UPDATE idt_account a
  JOIN iam_role_binding r ON r.subject_type = 'ACCOUNT' AND r.subject_id = a.id AND r.scope_type = 'PLATFORM' AND r.status = 'ACTIVE'
  SET a.account_type = 'PLATFORM_OPERATOR';

-- 2) 其余账号统一为 USER（包括原 EMPLOYEE/CUSTOMER）
UPDATE idt_account a
  SET a.account_type = 'USER'
  WHERE a.account_type <> 'PLATFORM_OPERATOR';

-- 结果校验
SELECT account_type, COUNT(*) AS cnt FROM idt_account GROUP BY account_type;