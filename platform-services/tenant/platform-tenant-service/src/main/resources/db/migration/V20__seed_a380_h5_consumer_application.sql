INSERT INTO iam_consumer_application
  (app_id, tenant_id, organization_id, store_id, permissions_json, authorization_version, status, created_at, updated_at)
VALUES
  ('saas-a380-h5', 100, 100, 100, '["reservation.view","reservation.create"]', 1, 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE
  tenant_id = VALUES(tenant_id), organization_id = VALUES(organization_id), store_id = VALUES(store_id),
  permissions_json = VALUES(permissions_json), authorization_version = VALUES(authorization_version),
  status = VALUES(status), updated_at = updated_at;
