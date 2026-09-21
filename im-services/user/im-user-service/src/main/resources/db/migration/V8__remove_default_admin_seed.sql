-- 用户域：移除 V1 基线植入的默认管理员 admin/888999（安全修复）。
-- 仅匹配「未改密的默认哈希」，已改密的管理员不受影响；新建库（V1 已不含该种子）时为无操作。
-- 修复后应由运维通过受管流程创建首管理员并强制首登改密，禁止再以固定口令种子初始化。
DELETE FROM `user`
WHERE `username` = 'admin'
  AND `password` = '$2a$10$TMyvg5zRRWXkwk9KYlMgZOq9VIbAmDnjk4kpFSZgWDun7oFmseP9W';
