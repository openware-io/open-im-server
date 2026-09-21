-- 平台运营补齐租户经营权限（人工测试发现「仓库管理保存失败」的权限根因）。
-- 背景：SaaS 后台的租户后台菜单（门店/仓库/商品/订单/收银/KTV 等）对平台运营同样可见，
-- 而平台超管 admin 是通过绑定 platform.operator 取得经营权限的
-- （见 docs/business/ACCOUNT_PERMISSION_MODEL.md §9.1：SUPER_ADMIN 不覆盖 platform.operator）。
-- 此前 platform.operator 只有 tenant/iam/member 四项权限，平台运营进入租户上下文后，仓库等
-- 业务接口一律 403 PERMISSION_DENIED。这里把当前全部有效的经营权限码授予 platform.operator，
-- 与「SUPER_ADMIN 全权限」一致；后续新增权限码需按需单独授权。
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.status = 'ACTIVE'
WHERE r.code = 'platform.operator'
  AND r.tenant_id IS NULL
  AND r.status = 'ACTIVE'
  AND NOT EXISTS (
    SELECT 1 FROM iam_role_permission rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
