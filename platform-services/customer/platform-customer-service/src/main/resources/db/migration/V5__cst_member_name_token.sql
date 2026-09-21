-- 客户姓名/客户号**盲索引**（blind index）：让「姓名包含」检索在没有明文列的前提下真正可用。
--
-- 问题：name_cipher / phone_cipher 是随机 IV 的 AES-256-GCM 密文（AesGcmCipher：每条密文的 IV 都不同），
-- SQL 既不能 LIKE 也不能建索引；手机号靠额外的 SHA-256 摘要列（phone_digest）做**精确**匹配，
-- 姓名此前完全不可检索（见 MemberApplicationService#applyKeyword 的历史注释）。
--
-- 方案：写入时把「归一化文本」的 1/2/3-gram 逐个做 SHA-256，落到本表；检索时把关键词按**同一规则**
-- 切 token，要求**所有关键词 token 都命中**（GROUP BY member_id + HAVING COUNT(DISTINCT token) = n），
-- 从而得到「包含」语义的候选集，再用 member_id IN (...) 参与分页过滤 —— 不解密、不逐行 LIKE。
-- token 生成规则是纯函数：com.gvchat.platform.customer.application.NameBlindIndex（含单测）。
--
-- 为什么同时索引 member_no（客户号）：客户号的「包含」检索同样走这条通道（任务书 ①：客户号/客户名
-- 包含走盲索引）。客户号本身不是密文，但 LIKE '%x%' 一样用不上索引；把两者放进同一张 token 表，
-- 「客户号 / 姓名」两条通道就能合成一次 IN 过滤。表名沿用任务口径 cst_member_name_token。
--
-- 口径与边界：
--   * token = SHA-256(gram) 的十六进制，定长 64；
--   * 同一 (tenant_id, member_id, token) 唯一 → 重建 token 集合是「先删后插」，天然幂等；
--   * (tenant_id, token) 索引服务检索；查询必须带 tenant_id（本表不被租户拦截器忽略，
--     请求路径上的查询会被 TenantLineInnerInterceptor 再兜一层）；
--   * 既有客户的回填不在迁移里做（密文解密必须在应用层），见 MemberApplicationService
--     #rebuildNameIndexForTenant / MemberNameIndexRebuildJob / POST /business/members/name-index/rebuild。
CREATE TABLE IF NOT EXISTS `cst_member_name_token` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `member_id` bigint unsigned NOT NULL COMMENT '客户档案ID(cst_member.id)',
  `token` char(64) NOT NULL COMMENT '归一化 1/2/3-gram 的 SHA-256 十六进制（姓名 + 客户号）',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_member_name_token` (`tenant_id`, `member_id`, `token`),
  KEY `idx_cst_member_name_token_token` (`tenant_id`, `token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户姓名/客户号盲索引 token（密文不可 LIKE）';
