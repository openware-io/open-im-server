-- 清理开发期演示数据：门店店长绑定和手工 HOTEL 开通必须由租户管理员通过正式流程产生。
-- 保留本迁移以兼容已经执行过 V5 的本地数据库；全新数据库执行后不会留下演示权限。
DELETE FROM `iam_user_role`
WHERE `account_id` = 100
  AND `tenant_id` = 100
  AND `store_id` = 100
  AND `role_id` = (SELECT `id` FROM `iam_role` WHERE `code` = 'store.manager' LIMIT 1);

DELETE FROM `tnt_business_entitlement`
WHERE `tenant_id` = 100
  AND `business_type` = 'HOTEL'
  AND `source` = 'MANUAL';
