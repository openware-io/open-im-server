-- 频道发现能力：频道号（分享码）+ 名称搜索索引
-- 说明：已有部署库无 code 列，先加列并回填存量行，再建唯一索引。

ALTER TABLE `conversation_channel`
  ADD COLUMN `code` varchar(16) NOT NULL DEFAULT '' COMMENT '频道号（分享码，全局唯一）' AFTER `id`;

-- 存量频道回填：以 LPAD(id) 生成可读频道号（不足 6 位补 0），保证唯一。
UPDATE `conversation_channel` SET `code` = LPAD(`id`, 6, '0') WHERE `code` = '';

ALTER TABLE `conversation_channel`
  ADD UNIQUE KEY `uk_conversation_channel_code` (`code`) COMMENT '频道号唯一约束',
  ADD KEY `idx_conversation_channel_name` (`name`) COMMENT '频道名称搜索';
