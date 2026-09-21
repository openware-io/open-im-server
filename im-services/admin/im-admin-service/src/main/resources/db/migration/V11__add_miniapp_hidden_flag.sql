-- 小程序服务「隐藏」属性。
--
-- 语义：隐藏项不出现在「服务」列表（含其所在分组），但仍可被搜索到，也仍可固定到快捷应用区。
-- 与既有 audience 的区别：audience=operator 只表达"面向运营"的语义，隐藏是独立的展示开关，
-- 消费者小程序同样可能需要隐藏（例如灰度上线、临时下线入口）。
ALTER TABLE adm_miniapp_service_item
  ADD COLUMN hidden tinyint(1) NOT NULL DEFAULT 0 COMMENT '1=隐藏（列表不展示，可搜索、可固定）' AFTER audience;

ALTER TABLE adm_miniapp_service_type
  ADD COLUMN hidden tinyint(1) NOT NULL DEFAULT 0 COMMENT '1=隐藏（该分组不展示，组内小程序仍可搜索、可固定）' AFTER sort_order;

-- 运营端入口默认隐藏：运营后台通过搜索进入或固定到快捷应用区，不占用普通用户的服务列表。
UPDATE adm_miniapp_service_item
SET hidden = 1, updated_at = NOW(3)
WHERE audience = 'operator';

-- 「运营后台」分组本身也隐藏：即使组内还有未隐藏的小程序，分组名也不再展示。
UPDATE adm_miniapp_service_type
SET hidden = 1, updated_at = NOW(3)
WHERE name = '运营后台';
