-- 仓库商品（物料）描述：仓库管理需要维护一段说明文字，与商品描述保持同一长度口径（≤255，业务侧校验）。
-- 只加列、不改历史迁移、不动已有列；历史数据保持 NULL。
ALTER TABLE `ord_inventory_material`
  ADD COLUMN `description` varchar(255) NULL COMMENT '描述' AFTER `unit`;
