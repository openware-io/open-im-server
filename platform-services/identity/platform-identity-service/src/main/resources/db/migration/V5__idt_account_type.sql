-- 统一账号主体增加账号类型：区分员工/客户/平台运营（业界统一账号模型，见 docs/standards 或 .agents/notes）
-- 员工类型可绑定运营角色（iam_user_role），客户类型走会员（cst_member），平台运营走平台角色。
-- 回填在服务启动后由 scripts/migration/backfill-account-type.sql 显式执行（依赖 admin/tenant 域表，避免跨域 Flyway 顺序依赖）。
ALTER TABLE `idt_account`
  ADD COLUMN `account_type` varchar(32) NOT NULL DEFAULT 'CUSTOMER'
  COMMENT '账号类型 EMPLOYEE/CUSTOMER/PLATFORM_OPERATOR' AFTER `status`;
