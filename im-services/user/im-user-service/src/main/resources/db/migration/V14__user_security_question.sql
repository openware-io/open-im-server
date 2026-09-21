-- 密保问题找回密码：每个用户最多一条安全问答记录，答案以 bcrypt 哈希存储（复用密码哈希器），不落明文。

CREATE TABLE IF NOT EXISTS `user_security_question`
(
  `id`           bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`      bigint unsigned NOT NULL COMMENT 'IM 用户 id',
  `question`     varchar(128) NOT NULL COMMENT '密保问题（预设或自定义）',
  `answer_hash`  varchar(256) NOT NULL COMMENT '密保答案哈希（bcrypt），不落明文',
  `created_by`   bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`   datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`   bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`   datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_security_question_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户密保问题与答案';
