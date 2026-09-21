CREATE TABLE IF NOT EXISTS iam_consumer_application (
  app_id varchar(64) NOT NULL,
  tenant_id bigint unsigned NOT NULL,
  organization_id bigint unsigned NULL,
  store_id bigint unsigned NULL,
  permissions_json varchar(2000) NOT NULL,
  authorization_version int NOT NULL DEFAULT 1,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE',
  created_at datetime(3) NOT NULL,
  updated_at datetime(3) NOT NULL,
  PRIMARY KEY (app_id),
  KEY idx_iam_consumer_application_scope (tenant_id, organization_id, store_id, status)
);

INSERT INTO iam_consumer_application
  (app_id, tenant_id, organization_id, store_id, permissions_json, authorization_version, status, created_at, updated_at)
VALUES
  ('saas-a380-c', 100, 100, 100, '["reservation.view","reservation.create"]', 1, 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE
  tenant_id = VALUES(tenant_id), organization_id = VALUES(organization_id), store_id = VALUES(store_id),
  permissions_json = VALUES(permissions_json), authorization_version = VALUES(authorization_version),
  status = VALUES(status), updated_at = updated_at;
