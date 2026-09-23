-- 回填 idt_account.account_type（统一账号模型落地）
-- 依赖 admin 域(saa_admin_account)、tenant 域(iam_user_role) 表，须在所有相关服务启动后执行一次（幂等）。
-- 优先级：PLATFORM_OPERATOR > EMPLOYEE > CUSTOMER（默认）。

USE open_saas;

-- 1) 平台运营：持有 PLATFORM 作用域角色的账号（如 admin）
UPDATE idt_account a
  JOIN iam_user_role r ON r.account_id = a.id AND r.scope_type = 'PLATFORM' AND r.status = 'ACTIVE'
  SET a.account_type = 'PLATFORM_OPERATOR';

-- 2) 员工：被 SaaS 后台账号映射的账号（saa_admin_account.platform_account_id）
UPDATE idt_account a
  JOIN saa_admin_account s ON s.platform_account_id = a.id
  SET a.account_type = 'EMPLOYEE';

-- 3) 客户：被会员档案映射的账号（cst_member.account_id），显式置 CUSTOMER（避免被上面误标）
UPDATE idt_account a
  JOIN cst_member m ON m.account_id = a.id
  SET a.account_type = 'CUSTOMER';

-- 结果校验
SELECT account_type, COUNT(*) AS cnt FROM idt_account GROUP BY account_type;
