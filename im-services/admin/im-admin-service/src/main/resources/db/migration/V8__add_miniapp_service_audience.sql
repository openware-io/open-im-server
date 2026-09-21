-- 服务项新增「面向对象」标记：区分消费者服务（固定展示）与运营后台（仅搜索展示）。
ALTER TABLE adm_miniapp_service_item
  ADD COLUMN audience varchar(32) NOT NULL DEFAULT 'consumer' COMMENT '面向对象：consumer=消费者服务，operator=运营后台' AFTER is_top;

-- 注册「运营后台」服务类型，并将 B 端「KTV 业务」后台登记为运营小程序（默认不进固定展示，仅搜索结果可见）。
INSERT INTO adm_miniapp_service_type (name, sort_order, created_at, updated_at)
SELECT '运营后台', 2, NOW(3), NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM adm_miniapp_service_type WHERE name = '运营后台');

INSERT INTO adm_miniapp_service_item (type_id, name, link, introduction, icon, status, is_top, audience, sort_order, created_at, updated_at)
SELECT t.id, 'KTV 业务', 'app://business/ktv', '运营后台：开台/计时/结台/收款', NULL, 1, 0, 'operator', 0, NOW(3), NOW(3)
FROM adm_miniapp_service_type t
WHERE t.name = '运营后台'
  AND NOT EXISTS (SELECT 1 FROM adm_miniapp_service_item i WHERE i.name = 'KTV 业务' AND i.audience = 'operator');
