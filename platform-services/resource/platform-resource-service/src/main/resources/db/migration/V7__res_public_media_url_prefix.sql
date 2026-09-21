-- 公共媒体 URL 规范化为网关 /api 命名空间：把 res_resource 的包厢图片地址里以 /media-public/ 开头的值
-- 直接改写为 /api/v1/media-public/（与 order 服务 V21__ord_public_media_url_prefix.sql 同一规则、同一批数据）。
--
-- 背景：旧前缀 /media-public/{bucket}/{objectKey} 依赖各环境入口剥前缀，ACK 入口不剥前缀时 MinIO 会把
-- media-public 当桶名返回 403 AccessDenied；新前缀落在所有环境都路由到网关的 /api 命名空间，
-- 由网关 StripPrefix=3 统一剥成 /{bucket}/{objectKey}。
--
-- 写法与边界（只替换「值以 /media-public/ 开头」的前缀，不误伤字符串其它位置）：
--   * JSON 列 image_urls：先 CAST(col AS CHAR) 取 JSON 文本，再用 REPLACE 匹配带左引号的 '"/media-public/'，
--     即只命中数组元素字符串开头的旧前缀；出现在字符串中间或结尾的 /media-public/ 不匹配。
--   * varchar 列 main_image_url：用 LIKE '/media-public/%' 锚定行首，再 SUBSTRING 掉前 14 个字符的旧前缀。
-- 幂等：两条 UPDATE 的 WHERE 都只匹配旧前缀；改写后的值以 /api/v1/ 开头，重复执行匹配 0 行，不产生二次替换。
UPDATE `res_resource`
   SET `image_urls` = REPLACE(CAST(`image_urls` AS CHAR), '"/media-public/', '"/api/v1/media-public/')
 WHERE `image_urls` IS NOT NULL
   AND CAST(`image_urls` AS CHAR) LIKE '%"/media-public/%';

UPDATE `res_resource`
   SET `main_image_url` = CONCAT('/api/v1/media-public/', SUBSTRING(`main_image_url`, CHAR_LENGTH('/media-public/') + 1))
 WHERE `main_image_url` LIKE '/media-public/%';
