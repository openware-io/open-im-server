-- 储值管理是租户总部可见的客户资产入口；充值/退还仍由当前门店上下文与 wallet.* 权限控制。
UPDATE iam_menu
   SET parent_id = 101,
       sort_no = 30,
       scope_level = 'TENANT',
       required_grant = NULL,
       status = 'ACTIVE',
       updated_at = CURRENT_TIMESTAMP(3)
 WHERE id = 21
   AND code = 'wallet';
