-- 公共媒体 URL 规范化为网关 /api 命名空间：把 ord_product / ord_inventory_material / ord_catalog_item 里
-- 以 /media-public/ 开头的公开图片地址直接改写为 /api/v1/media-public/（用户已决策：不做过渡兼容，只改数据）。
--
-- 背景：旧前缀 /media-public/{bucket}/{objectKey} 依赖各环境入口剥前缀（本地 kind 由 saas-admin nginx 与
-- 网关 /media-public/** 各自处理），ACK 入口不剥前缀时 MinIO 会把 media-public 当桶名返回 403 AccessDenied。
-- 新前缀落在所有环境都路由到网关的 /api 命名空间，由网关 StripPrefix=3 统一剥成 /{bucket}/{objectKey}。
--
-- 写法与边界（只替换「值以 /media-public/ 开头」的前缀，不误伤字符串其它位置）：
--   * JSON 列 image_urls：先 CAST(col AS CHAR) 取 JSON 文本，再用 REPLACE 匹配带左引号的 '"/media-public/'，
--     即只命中数组元素字符串开头的旧前缀；出现在字符串中间或结尾的 /media-public/ 不匹配。
--     替换结果仍是合法 JSON 文本，按 V14__ord_catalog_item_images.sql 同款方式写回 JSON 列（由 MySQL 解析）。
--   * varchar 列 main_image_url：用 LIKE '/media-public/%' 锚定行首，再 SUBSTRING 掉前 14 个字符的旧前缀。
-- 幂等：两条 UPDATE 的 WHERE 都只匹配旧前缀；改写后的值以 /api/v1/ 开头，重复执行匹配 0 行，不产生二次替换。
UPDATE `ord_product`
   SET `image_urls` = REPLACE(CAST(`image_urls` AS CHAR), '"/media-public/', '"/api/v1/media-public/')
 WHERE `image_urls` IS NOT NULL
   AND CAST(`image_urls` AS CHAR) LIKE '%"/media-public/%';

UPDATE `ord_product`
   SET `main_image_url` = CONCAT('/api/v1/media-public/', SUBSTRING(`main_image_url`, CHAR_LENGTH('/media-public/') + 1))
 WHERE `main_image_url` LIKE '/media-public/%';

UPDATE `ord_inventory_material`
   SET `image_urls` = REPLACE(CAST(`image_urls` AS CHAR), '"/media-public/', '"/api/v1/media-public/')
 WHERE `image_urls` IS NOT NULL
   AND CAST(`image_urls` AS CHAR) LIKE '%"/media-public/%';

UPDATE `ord_inventory_material`
   SET `main_image_url` = CONCAT('/api/v1/media-public/', SUBSTRING(`main_image_url`, CHAR_LENGTH('/media-public/') + 1))
 WHERE `main_image_url` LIKE '/media-public/%';

-- 目录项图片是「关联商品优先、商品无图时取关联物料」的镜像（见 V14），存量同样需要改写。
UPDATE `ord_catalog_item`
   SET `image_urls` = REPLACE(CAST(`image_urls` AS CHAR), '"/media-public/', '"/api/v1/media-public/')
 WHERE `image_urls` IS NOT NULL
   AND CAST(`image_urls` AS CHAR) LIKE '%"/media-public/%';

UPDATE `ord_catalog_item`
   SET `main_image_url` = CONCAT('/api/v1/media-public/', SUBSTRING(`main_image_url`, CHAR_LENGTH('/media-public/') + 1))
 WHERE `main_image_url` LIKE '/media-public/%';
