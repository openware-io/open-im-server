-- 管理域数据库基线：仅适用于尚未执行此基线的新建数据库。

CREATE TABLE `adm_system_config`
(
  `id`           int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `config_key`   varchar(128) NOT NULL COMMENT '配置键',
  `config_value` text NOT NULL COMMENT '配置值',
  `config_group` varchar(64) NOT NULL COMMENT '配置分组',
  `description`  varchar(255) DEFAULT NULL COMMENT '配置说明',
  `created_at`   datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at`   datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_system_config_key` (`config_key`) COMMENT '配置键唯一约束'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域系统配置表';

CREATE TABLE `adm_app_release`
(
  `id`                           int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `platform`                     varchar(32) NOT NULL COMMENT '发布平台',
  `version_name`                 varchar(64) NOT NULL COMMENT '版本名称',
  `version_code`                 int NOT NULL COMMENT '版本号',
  `min_supported_version_code`   int DEFAULT NULL COMMENT '最低支持版本号',
  `force_update`                 tinyint(1) NOT NULL COMMENT '是否强制更新',
  `download_url`                 varchar(1024) NOT NULL COMMENT '安装包下载地址',
  `store_url`                    varchar(1024) NOT NULL COMMENT '应用商店地址',
  `release_notes`                text NOT NULL COMMENT '发布说明',
  `published`                    tinyint(1) NOT NULL COMMENT '是否已发布',
  `published_at`                 datetime(3) DEFAULT NULL COMMENT '发布时间',
  `created_at`                   datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at`                   datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_app_release_platform_version` (`platform`, `version_code`) COMMENT '平台版本唯一约束'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域应用版本发布表';

CREATE TABLE `adm_report`
(
  `id`            bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `reporter_id`   bigint NOT NULL COMMENT '举报人用户标识',
  `target_id`     bigint NOT NULL COMMENT '被举报对象标识',
  `reason`        varchar(255) NOT NULL COMMENT '举报原因',
  `description`   text COMMENT '举报说明',
  `evidence`      text COMMENT '证据内容',
  `status`        varchar(32) NOT NULL COMMENT '处理状态',
  `handled_by`    bigint DEFAULT NULL COMMENT '处理人用户标识',
  `handle_remark` text COMMENT '处理备注',
  `handled_at`    datetime(3) DEFAULT NULL COMMENT '处理时间',
  `created_at`    datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at`    datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_adm_report_reporter_target_status` (`reporter_id`, `target_id`, `status`) COMMENT '举报幂等及状态查询',
  KEY `idx_adm_report_status_created_at` (`status`, `created_at`) COMMENT '按处理状态分页查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域举报表';

CREATE TABLE `adm_violation`
(
  `id`         bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`    bigint NOT NULL COMMENT '违规用户标识',
  `reason`     varchar(255) NOT NULL COMMENT '违规原因',
  `content`    text COMMENT '违规内容快照',
  `msg_id`     varchar(128) DEFAULT NULL COMMENT '关联消息标识',
  `action`     varchar(32) NOT NULL COMMENT '处置动作',
  `remark`     text COMMENT '处置备注',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_adm_violation_created_at` (`created_at`) COMMENT '按时间查询违规记录',
  KEY `idx_adm_violation_user_created_at` (`user_id`, `created_at`) COMMENT '按用户查询违规记录'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域违规处置表';

CREATE TABLE `adm_sensitive_word`
(
  `id`         bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `word`       varchar(255) NOT NULL COMMENT '敏感词',
  `category`   varchar(32) NOT NULL COMMENT '敏感词分类',
  `level`      varchar(32) NOT NULL COMMENT '风险等级',
  `enabled`    tinyint(1) NOT NULL COMMENT '是否启用',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_sensitive_word_word` (`word`) COMMENT '敏感词唯一约束',
  KEY `idx_adm_sensitive_word_enabled` (`enabled`) COMMENT '启用敏感词加载查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域敏感词表';

CREATE TABLE `adm_miniapp_service_type`
(
  `id`         int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`       varchar(128) NOT NULL COMMENT '服务类型名称',
  `sort_order` int NOT NULL COMMENT '排序值',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_adm_miniapp_service_type_sort_order` (`sort_order`, `id`) COMMENT '服务类型排序查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域小程序服务类型表';

CREATE TABLE `adm_miniapp_service_item`
(
  `id`           int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `type_id`      int NOT NULL COMMENT '服务类型标识',
  `name`         varchar(128) NOT NULL COMMENT '服务名称',
  `link`         varchar(2048) NOT NULL COMMENT '跳转链接',
  `introduction` text COMMENT '服务介绍',
  `icon`         varchar(2048) DEFAULT NULL COMMENT '服务图标地址',
  `status`       tinyint(1) NOT NULL COMMENT '发布状态',
  `is_top`       tinyint(1) NOT NULL COMMENT '是否置顶',
  `sort_order`   int NOT NULL COMMENT '排序值',
  `created_at`   datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at`   datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_adm_miniapp_service_item_type` (`type_id`) COMMENT '按服务类型查询',
  KEY `idx_adm_miniapp_service_item_status_sort` (`status`, `is_top`, `sort_order`, `id`) COMMENT '已发布服务排序查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域小程序服务项表';

CREATE TABLE `adm_projection_event`
(
  `event_id`     varchar(128) NOT NULL COMMENT '事件唯一标识',
  `event_type`   varchar(64) NOT NULL COMMENT '事件类型',
  `processed_at` datetime(3) NOT NULL COMMENT '处理时间',
  PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理读投影事件幂等表';

CREATE TABLE `adm_user_view`
(
  `user_id`        bigint NOT NULL COMMENT '用户标识',
  `username`       varchar(128) DEFAULT NULL COMMENT '用户名快照',
  `nickname`       varchar(255) DEFAULT NULL COMMENT '昵称快照',
  `avatar`         varchar(2048) DEFAULT NULL COMMENT '头像地址快照',
  `email`          varchar(255) DEFAULT NULL COMMENT '邮箱快照',
  `phone`          varchar(64) DEFAULT NULL COMMENT '手机号快照',
  `signature`      varchar(1024) DEFAULT NULL COMMENT '个性签名快照',
  `status`         varchar(32) NOT NULL COMMENT '用户状态',
  `status_version` bigint NOT NULL COMMENT '状态版本',
  `role`           varchar(32) DEFAULT NULL COMMENT '用户角色',
  `created_at`     datetime(3) DEFAULT NULL COMMENT '源创建时间',
  `updated_at`     datetime(3) DEFAULT NULL COMMENT '源更新时间',
  PRIMARY KEY (`user_id`),
  KEY `idx_adm_user_view_status_updated_at` (`status`, `updated_at`) COMMENT '按状态和更新时间查询',
  KEY `idx_adm_user_view_username` (`username`) COMMENT '按用户名查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理读用户视图';

CREATE TABLE `adm_message_view`
(
  `msg_id`          varchar(128) NOT NULL COMMENT '消息标识',
  `from_user_id`    bigint NOT NULL COMMENT '发送人用户标识',
  `to_id`           varchar(128) NOT NULL COMMENT '接收对象标识',
  `conversation_id` varchar(128) NOT NULL COMMENT '会话标识',
  `chat_type`       varchar(32) NOT NULL COMMENT '聊天类型',
  `msg_type`        varchar(32) NOT NULL COMMENT '消息类型',
  `content`         text COMMENT '消息内容快照',
  `client_msg_id`   varchar(128) DEFAULT NULL COMMENT '客户端消息标识',
  `reply_msg_id`    varchar(128) DEFAULT NULL COMMENT '回复消息标识',
  `at_users_json`   text COMMENT '被提及用户列表',
  `status`          varchar(32) NOT NULL COMMENT '消息状态',
  `created_at`      datetime(3) NOT NULL COMMENT '消息创建时间',
  PRIMARY KEY (`msg_id`),
  KEY `idx_adm_message_view_created_at` (`created_at`) COMMENT '按时间查询消息',
  KEY `idx_adm_message_view_from_user_created_at` (`from_user_id`, `created_at`) COMMENT '按发送人查询消息',
  KEY `idx_adm_message_view_conversation_created_at` (`conversation_id`, `created_at`) COMMENT '按会话查询消息'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理读消息视图';

CREATE TABLE `adm_conversation_view`
(
  `conversation_id`   varchar(128) NOT NULL COMMENT '会话标识',
  `conversation_type` varchar(32) NOT NULL COMMENT '会话类型',
  `owner_user_id`     bigint DEFAULT NULL COMMENT '会话所有者用户标识',
  `name`              varchar(255) DEFAULT NULL COMMENT '会话名称',
  `status`            varchar(32) DEFAULT NULL COMMENT '会话状态',
  `member_count`      int DEFAULT NULL COMMENT '成员数量',
  `created_at`        datetime(3) DEFAULT NULL COMMENT '源创建时间',
  `updated_at`        datetime(3) DEFAULT NULL COMMENT '源更新时间',
  PRIMARY KEY (`conversation_id`),
  KEY `idx_adm_conversation_view_created_at` (`created_at`) COMMENT '按时间查询会话',
  KEY `idx_adm_conversation_view_type_updated_at` (`conversation_type`, `updated_at`) COMMENT '按类型查询会话'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理读会话视图';
