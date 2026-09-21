-- 租户支付方式：平台授权（granted）+ 租户向用户开放开关（user_enabled，默认开）
-- 现金为通用兜底，永远可用，无需授权记录；储值/积分/线上默认不授权，平台显式授权后租户即可用
CREATE TABLE IF NOT EXISTS tenant_payment_method (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT UNSIGNED NOT NULL,
  method VARCHAR(32) NOT NULL,
  granted TINYINT NOT NULL DEFAULT 0,
  user_enabled TINYINT NOT NULL DEFAULT 1,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_payment_method (tenant_id, method)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
