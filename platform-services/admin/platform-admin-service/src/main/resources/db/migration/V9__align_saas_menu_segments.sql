-- 菜单最终收口：租户总部与门店经营两段，功能域使用真实父节点承载。
-- 路径保持不变；仅调整 scope、父子关系、顺序和声明式权限。
ALTER TABLE iam_menu
    MODIFY COLUMN id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    MODIFY COLUMN parent_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN required_permission VARCHAR(128) NULL AFTER domain_code,
    ADD COLUMN required_grant VARCHAR(128) NULL AFTER required_permission,
    ADD COLUMN business_type VARCHAR(32) NULL AFTER required_grant,
    ADD COLUMN i18n_key VARCHAR(128) NULL AFTER business_type,
    ADD COLUMN created_by BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN updated_by BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER created_by,
    MODIFY COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3);

INSERT INTO iam_menu (id, parent_id, code, name, path, icon, scope_level, domain_code, sort_no, status)
VALUES
 (100,0,'tenant.org','组织与门店',NULL,'office-building','TENANT','core',20,'ACTIVE'),
 (101,0,'tenant.finance','资金与支付',NULL,'wallet','TENANT','core',30,'ACTIVE'),
 (102,0,'tenant.iam','人员与权限',NULL,'user','TENANT','core',40,'ACTIVE'),
 (103,0,'tenant.settings','租户设置',NULL,'setting','TENANT','core',50,'ACTIVE'),
 (200,0,'store.ops','门店运营',NULL,'shop','STORE','core',10,'ACTIVE'),
 (201,0,'store.resource','门店资源',NULL,'grid','STORE','core',20,'ACTIVE'),
 (202,0,'store.stock','门店商品与库存',NULL,'goods','STORE','core',30,'ACTIVE'),
 (203,0,'store.crm','客户与资产',NULL,'user','STORE','core',40,'ACTIVE'),
 (204,0,'store.report','门店报表',NULL,'data-analysis','STORE','core',50,'ACTIVE'),
 (205,0,'store.settings','门店设置',NULL,'setting','STORE','core',60,'ACTIVE')
ON DUPLICATE KEY UPDATE name=VALUES(name), status='ACTIVE';

-- P7-B 的旧 pricing 入口与 KTV 配置重复，只保留一个可达入口。
UPDATE iam_menu SET status='INACTIVE' WHERE id=2 AND code='pricing';

UPDATE iam_menu SET parent_id=100, sort_no=10, scope_level='TENANT' WHERE id=10;
UPDATE iam_menu SET parent_id=101, sort_no=10, scope_level='TENANT', required_permission='tenant.currency.manage' WHERE id=27;
UPDATE iam_menu SET parent_id=101, sort_no=20, scope_level='TENANT' WHERE id=20;
UPDATE iam_menu SET parent_id=102, sort_no=10, scope_level='TENANT', required_permission='iam.role.manage' WHERE id=23;
UPDATE iam_menu SET parent_id=102, sort_no=20, scope_level='TENANT', required_permission='iam.role.manage' WHERE id=22;
UPDATE iam_menu SET parent_id=103, sort_no=10, scope_level='TENANT', required_permission='audit.view' WHERE id=26;

UPDATE iam_menu SET parent_id=200, sort_no=10, scope_level='STORE' WHERE id IN (12,13,28,14,17);
UPDATE iam_menu SET parent_id=201, sort_no=10, scope_level='STORE' WHERE id=11;
UPDATE iam_menu SET parent_id=202, sort_no=10, scope_level='STORE' WHERE id=25;
UPDATE iam_menu SET parent_id=202, sort_no=20, scope_level='STORE' WHERE id=24;
UPDATE iam_menu SET parent_id=203, sort_no=10, scope_level='STORE' WHERE id=18;
UPDATE iam_menu SET parent_id=203, sort_no=20, scope_level='STORE' WHERE id=19;
UPDATE iam_menu SET parent_id=203, sort_no=30, scope_level='STORE', required_grant='WALLET' WHERE id=21;
UPDATE iam_menu SET parent_id=204, sort_no=10, scope_level='STORE' WHERE id=15;
-- 同一 KTV 配置入口承载租户/业态默认与门店覆盖，避免总部与门店出现重复入口。
UPDATE iam_menu SET parent_id=103, sort_no=20, scope_level='TENANT', business_type='KTV' WHERE id=16;

UPDATE iam_menu SET parent_id=0, sort_no=10, scope_level='TENANT' WHERE id=29;
