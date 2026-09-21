-- 开放平台接入管理：应用归属主体（接入方/业务方）。
-- 同一主体的多个应用（C端/B端等）在管理列表按主体聚合展示，避免遗漏。
ALTER TABLE `open_application`
  ADD COLUMN `subject_name` varchar(128) NULL COMMENT '主体名称（接入方/业务方）' AFTER `app_name`;

-- 存量自营应用归属自营主体（星空AI）。
UPDATE `open_application` SET `subject_name` = '星空AI（自营）' WHERE `app_type` = 'FIRST_PARTY' AND `subject_name` IS NULL;
