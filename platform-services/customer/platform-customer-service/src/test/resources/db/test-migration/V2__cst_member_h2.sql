-- H2 (MODE=MySQL) test schema：客户档案 + 姓名/客户号盲索引。
-- 镜像生产迁移 V1（cst_member 基线）+ V4（IM 绑定列）+ V5（cst_member_name_token），
-- 供「按姓名真的能搜到」的集成测试使用（生产迁移里的去重/清理/索引不参与测试）。

CREATE TABLE cst_member (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  account_id BIGINT,
  im_account VARCHAR(64),
  im_username VARCHAR(128),
  im_bound_at TIMESTAMP,
  member_no VARCHAR(64) NOT NULL,
  name_cipher VARCHAR(255),
  phone_cipher VARCHAR(255),
  phone_digest VARCHAR(64),
  level_id BIGINT,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  joined_at TIMESTAMP,
  expires_at TIMESTAMP,
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_cst_member_tenant_no UNIQUE (tenant_id, member_no)
);

CREATE TABLE cst_member_name_token (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  token CHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_cst_member_name_token UNIQUE (tenant_id, member_id, token)
);

CREATE INDEX idx_cst_member_name_token_token ON cst_member_name_token (tenant_id, token);
