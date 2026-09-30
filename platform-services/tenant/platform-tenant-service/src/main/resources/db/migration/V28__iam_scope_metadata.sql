ALTER TABLE iam_permission
    ADD COLUMN scope_level VARCHAR(16) NOT NULL DEFAULT 'TENANT' COMMENT '权限适用作用域 PLATFORM/TENANT/STORE',
    ADD COLUMN domain_code VARCHAR(64) NOT NULL DEFAULT 'core' COMMENT '权限所属业务域',
    ADD COLUMN grantable_levels VARCHAR(64) NOT NULL DEFAULT 'TENANT,STORE' COMMENT '允许授予的目标作用域，逗号分隔',
    ADD COLUMN menu_code VARCHAR(128) NULL COMMENT '关联菜单 code';

ALTER TABLE iam_role
    ADD COLUMN scope_level VARCHAR(16) NULL COMMENT '角色作用域 PLATFORM/TENANT/STORE',
    ADD COLUMN domain_code VARCHAR(64) NULL COMMENT '角色所属业务域';

UPDATE iam_permission
SET scope_level = CASE WHEN code LIKE 'platform.%' THEN 'PLATFORM' ELSE 'TENANT' END,
    domain_code = CASE WHEN code LIKE 'ktv.%' THEN 'ktv' ELSE 'core' END,
    grantable_levels = CASE
      WHEN code LIKE 'platform.%' THEN 'PLATFORM'
      WHEN code IN ('tenant.tenant.manage', 'tenant.store.manage', 'iam.role.manage') THEN 'TENANT'
      ELSE 'TENANT,STORE'
    END;

UPDATE iam_role
SET scope_level = CASE
      WHEN code = 'platform.operator' THEN 'PLATFORM'
      WHEN code LIKE 'store.%' THEN 'STORE'
      ELSE 'TENANT'
    END,
    domain_code = 'core'
WHERE scope_level IS NULL OR domain_code IS NULL;

ALTER TABLE iam_permission
    ALTER COLUMN scope_level DROP DEFAULT,
    ALTER COLUMN domain_code DROP DEFAULT,
    ALTER COLUMN grantable_levels DROP DEFAULT;
