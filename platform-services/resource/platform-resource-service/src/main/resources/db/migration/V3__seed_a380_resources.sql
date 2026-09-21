-- A380 KTV 包厢资源（tenant_id=100, store_id=100）
INSERT INTO res_resource (id, tenant_id, store_id, resource_type, resource_code, name, capacity, status, created_at, updated_at) VALUES
(1001, 100, 100, 'KTV_ROOM', 'K01', '小包 K01', 4, 'ENABLED', NOW(3), NOW(3)),
(1002, 100, 100, 'KTV_ROOM', 'K02', '小包 K02', 4, 'ENABLED', NOW(3), NOW(3)),
(1003, 100, 100, 'KTV_ROOM', 'K08', '中包 K08', 8, 'ENABLED', NOW(3), NOW(3)),
(1004, 100, 100, 'KTV_ROOM', 'K09', '中包 K09', 8, 'ENABLED', NOW(3), NOW(3)),
(1005, 100, 100, 'KTV_ROOM', 'K12', '大包 K12', 15, 'ENABLED', NOW(3), NOW(3)),
(1006, 100, 100, 'KTV_ROOM', 'K15', '派对包 K15', 20, 'ENABLED', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE name = VALUES(name), updated_at = updated_at;
