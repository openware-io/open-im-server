-- 同一账号只能有一条客户档案：给 cst_member 补 (tenant_id, account_id) 唯一索引。
--
-- 背景（客户管理 1:1 诉求）：现在 `uk_cst_member_tenant_im (tenant_id, im_account)` 已经保证
-- 「一个 IM 账号 → 一条客户」；但**账号维度**上 `(tenant_id, account_id)` 没有唯一索引——
-- 迁移 V6 当时为了兼容历史重复行（同一 account 存在多条档案）把它降级成了非唯一，
-- 于是「同一个账号出现多条客户档案」只能靠应用层判定（`findEarliestByAccountId`）兜着。
--
-- 现在重复行已经清理干净（ACK 实测 21 行客户 / 21 个不同 account_id），可以把它钉回数据库层：
-- 之后同账号再建第二条客户会直接失败（应用层本来就会先命中既有档案返回，这里是最后一道闸）。
--
-- 注意：
--   1. `account_id` 允许 NULL（后台手工建的「待认领客户」）；MySQL 唯一索引允许多个 NULL，不受影响。
--   2. 若某个环境仍有 (tenant_id, account_id) 重复行，本迁移会**直接失败**——这是刻意的 fail-closed：
--      宁可让发布停下来清理数据，也不要带着"随时可能一对多"的状态上线。
--      清理口径见迁移 V6（保留有引用的那一行、合并其余）。
--
-- 回滚：DROP INDEX uk_cst_member_tenant_account ON cst_member;

ALTER TABLE cst_member
    ADD UNIQUE KEY uk_cst_member_tenant_account (tenant_id, account_id);
