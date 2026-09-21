-- 小程序服务入口种子数据（示例，供后台增删改查、客户端展示联调）
INSERT INTO adm_miniapp_service_type (name, sort_order, created_at, updated_at) VALUES
('便民服务', 0, NOW(3), NOW(3)),
('休闲娱乐', 1, NOW(3), NOW(3));

INSERT INTO adm_miniapp_service_item (type_id, name, link, introduction, icon, status, is_top, sort_order, created_at, updated_at) VALUES
(1, '外卖', 'https://www.example.com/takeout', '示例：外卖服务入口', NULL, 1, 0, 0, NOW(3), NOW(3)),
(1, '打车', 'https://www.example.com/taxi', '示例：打车服务入口', NULL, 1, 0, 1, NOW(3), NOW(3)),
(2, 'KTV 预订', 'https://www.example.com/ktv', '示例：KTV 预订服务入口', NULL, 1, 1, 0, NOW(3), NOW(3));
