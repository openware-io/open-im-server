-- 管理域（admin，adm_ 前缀）迁移基线
-- 基线重建（批次 2）：本文件是原 V1~V12 的**累积终态**，库清空后由 Flyway 从 V1 一次性重建；下一个变更从 V2 起。
-- 口径依据：docs/DATABASE_MIGRATION_STANDARD.md、docs/ENGINEERING_RULES.md（DC-11 全表四审计列）。
--
-- 合并来源与逐条处理：
--   V1  管理域基线：adm_system_config / adm_app_release / adm_report / adm_violation / adm_sensitive_word /
--       adm_miniapp_service_type / adm_miniapp_service_item / adm_projection_event / adm_user_view /
--       adm_message_view / adm_conversation_view 共 11 张表 → 并入本文件。
--   V2  客户端发布治理重构：新建 adm_client_release / adm_client_release_artifact /
--       adm_client_release_policy / adm_client_release_audit_log / adm_client_release_operation → 并入本文件；
--       其末尾的 `RENAME TABLE adm_app_release TO adm_app_release_retired_v1` **不并入**（见下方「有意丢弃」）。
--   V3  adm_system_config 默认值播种 20 条 → 保留在文件末尾，幂等（INSERT IGNORE）。
--   V4  +feature.secretGroupChatEnabled → 并入配置种子。
--   V5  +5 条私密群聊功能开关 → 并入配置种子。
--   V6  +feature.chatDeleteEnabled → 并入配置种子。
--   V7  小程序服务类型 2 条 + 服务项 3 条示例种子 → 保留（幂等由 NOT EXISTS 与唯一键保证，见「种子数据」）。
--   V8  adm_miniapp_service_item +audience 列（并入建表）；注册「运营后台」类型并登记运营小程序「KTV 业务」→ 保留。
--   V9  「KTV 预订」升级为「A380 更多服务」（consumer）→ 保留为末尾 UPDATE。
--   V10 修复指向统一门户的 A380 服务项 → 保留为末尾 UPDATE（与 V9 命中同一行，幂等）。
--   V11 adm_miniapp_service_item +hidden、adm_miniapp_service_type +hidden（并入建表）；
--       audience='operator' 的项与「运营后台」分组置 hidden=1 → 保留为末尾 UPDATE。
--   V12 运营入口改指 A380 B 端 OAuth/OIDC 页面 → 保留为末尾 UPDATE。
--
-- 规范化（门禁 flyway-conventions 口径）：
--   1. 每表补齐四个审计列 created_by / updated_by（bigint unsigned NOT NULL DEFAULT 0）与
--      created_at / updated_at（datetime(3) NOT NULL）；表与列标识符全部加反引号。
--   2. 主键统一 `id` bigint unsigned NOT NULL AUTO_INCREMENT；原业务键主键降级为唯一键（见下）。
--   3. 索引名统一 idx_<完整表名>_<语义> / uk_<完整表名>_<语义>。
--   4. 时间列统一 datetime(3)，表尾统一 ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci + 表注释。
--   5. 表名保持 `adm_` 前缀不变（`adm_` 是已登记前缀，本文件不重命名任何表）。
--
-- 主键转换（业务键 → id，原键保留为唯一键）：
--   adm_projection_event  : event_id        → UNIQUE KEY uk_adm_projection_event_event_id
--   adm_user_view         : user_id         → UNIQUE KEY uk_adm_user_view_user_id
--   adm_message_view      : msg_id          → UNIQUE KEY uk_adm_message_view_msg_id
--   adm_conversation_view : conversation_id → UNIQUE KEY uk_adm_conversation_view_conversation_id
--   同时把上述 4 张表以及 adm_app_release / adm_report / adm_violation / adm_sensitive_word /
--   adm_miniapp_service_type / adm_miniapp_service_item 的 `id` 由 `int` 统一为 `bigint unsigned`
--   （docs/ENGINEERING_RULES.md：核心表主键必须是 bigint unsigned 自增 id；Java 侧 PO 与仓储签名同步为 Long）。
--
-- 审计列写入方核对（坑 1：NOT NULL 审计列 vs 写入方；未映射的列给 DEFAULT 兜底）：
--   - adm_client_release 五表：PO 显式映射四列且写入方赋值（ClientReleasePersistenceAdapter），无 DEFAULT。
--   - adm_system_config / adm_report / adm_sensitive_word / adm_miniapp_service_type /
--     adm_miniapp_service_item / adm_user_view / adm_message_view：写入方只写 created_at / updated_at
--     （MyBatis-Plus 只提交非 null 字段），故 created_by / updated_by 用 DEFAULT 0 兜底。
--   - adm_violation：PO 只映射 created_at（追加型记录，无 updated_* 字段），故 created_at / updated_at
--     用 DEFAULT CURRENT_TIMESTAMP(3) 兜底，created_by / updated_by 用 DEFAULT 0。
--   - adm_projection_event：写入方是 ProjectionEventMapper 的 @Insert，显式写 created_at，
--     created_by / updated_by 用 DEFAULT 0。
--   - adm_conversation_view：写入方只写 created_at / updated_at（两者可空），created_by / updated_by 用 DEFAULT 0。
--   - adm_app_release（已退役表）：写入方已删除，四列全部用 DEFAULT 兜底。
--
-- 有意丢弃（本文件不写入的语句，逐条说明）：
--   ① `RENAME TABLE adm_app_release TO adm_app_release_retired_v1`（原 V2 末句）：清库重建后不存在
--      「V1 已建、V2 取代」的历史，全仓亦无任何代码引用 adm_app_release_retired_v1；保留它只会在
--      全新库里留下一张空的退役表，故不并入。
--   ② 动态 DDL / `CREATE TABLE IF NOT EXISTS` / `DROP TABLE` / 外键：原 V1~V12 中本就没有动态探测语句
--      （对比 customer 模块的 PREPARE/EXECUTE），本文件亦不含。
--   ③ 跨域数据语句：原 V1~V12 没有任何读取其它服务表的语句，无需外移 scripts/migration/*.sql。
--
-- 种子数据（幂等：库空时全部命中，重建结果与原 V1~V12 依次执行的终态一致；重复执行不产生重复行）：
--   adm_system_config 每个 config_key 都带唯一键判重（INSERT IGNORE）；
--   adm_miniapp_service_type / adm_miniapp_service_item 的运营入口用 NOT EXISTS 判重，
--   示例项由后续 UPDATE 收敛为终态。

-- ---------------------------------------------------------------------------
-- 管理域系统配置
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_system_config`
(
  `id`           bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `config_key`   varchar(128) NOT NULL COMMENT '配置键',
  `config_value` text NOT NULL COMMENT '配置值',
  `config_group` varchar(64) NOT NULL COMMENT '配置分组',
  `description`  varchar(255) DEFAULT NULL COMMENT '配置说明',
  `created_by`   bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`   datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`   bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新人',
  `updated_at`   datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_system_config_key` (`config_key`) COMMENT '配置键唯一约束'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域系统配置表';

-- ---------------------------------------------------------------------------
-- 客户端版本发布（已退役，保留历史数据结构）
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_app_release`
(
  `id`                         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `platform`                   varchar(32) NOT NULL COMMENT '发布平台',
  `version_name`               varchar(64) NOT NULL COMMENT '版本名称',
  `version_code`               int NOT NULL COMMENT '版本号',
  `min_supported_version_code` int DEFAULT NULL COMMENT '最低支持版本号',
  `force_update`               tinyint(1) NOT NULL COMMENT '是否强制更新',
  `download_url`               varchar(1024) NOT NULL COMMENT '安装包下载地址',
  `store_url`                  varchar(1024) NOT NULL COMMENT '应用商店地址',
  `release_notes`              text NOT NULL COMMENT '发布说明',
  `published`                  tinyint(1) NOT NULL COMMENT '是否已发布',
  `published_at`               datetime(3) DEFAULT NULL COMMENT '发布时间',
  `created_by`                 bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`                 datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`                 bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新人',
  `updated_at`                 datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_app_release_platform_version` (`platform`, `version_code`) COMMENT '平台版本唯一约束'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域应用版本发布表';

-- ---------------------------------------------------------------------------
-- 举报
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_report`
(
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `reporter_id`   bigint unsigned NOT NULL COMMENT '举报人用户标识',
  `target_id`     bigint unsigned NOT NULL COMMENT '被举报对象标识',
  `reason`        varchar(255) NOT NULL COMMENT '举报原因',
  `description`   text COMMENT '举报说明',
  `evidence`      text COMMENT '证据内容',
  `status`        varchar(32) NOT NULL COMMENT '处理状态',
  `handled_by`    bigint unsigned DEFAULT NULL COMMENT '处理人用户标识',
  `handle_remark` text COMMENT '处理备注',
  `handled_at`    datetime(3) DEFAULT NULL COMMENT '处理时间',
  `created_by`    bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`    datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`    bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新人',
  `updated_at`    datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_adm_report_reporter_target_status` (`reporter_id`, `target_id`, `status`) COMMENT '举报幂等及状态查询',
  KEY `idx_adm_report_status_created_at` (`status`, `created_at`) COMMENT '按处理状态分页查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域举报表';

-- ---------------------------------------------------------------------------
-- 违规处置
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_violation`
(
  `id`         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`    bigint unsigned NOT NULL COMMENT '违规用户标识',
  `reason`     varchar(255) NOT NULL COMMENT '违规原因',
  `content`    text COMMENT '违规内容快照',
  `msg_id`     varchar(128) DEFAULT NULL COMMENT '关联消息标识',
  `action`     varchar(32) NOT NULL COMMENT '处置动作',
  `remark`     text COMMENT '处置备注',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新人',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间（写入方未赋值，取落库时间）',
  PRIMARY KEY (`id`),
  KEY `idx_adm_violation_created_at` (`created_at`) COMMENT '按时间查询违规记录',
  KEY `idx_adm_violation_user_created_at` (`user_id`, `created_at`) COMMENT '按用户查询违规记录'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域违规处置表';

-- ---------------------------------------------------------------------------
-- 敏感词
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_sensitive_word`
(
  `id`         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `word`       varchar(255) NOT NULL COMMENT '敏感词',
  `category`   varchar(32) NOT NULL COMMENT '敏感词分类',
  `level`      varchar(32) NOT NULL COMMENT '风险等级',
  `enabled`    tinyint(1) NOT NULL COMMENT '是否启用',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_sensitive_word_word` (`word`) COMMENT '敏感词唯一约束',
  KEY `idx_adm_sensitive_word_enabled` (`enabled`) COMMENT '启用敏感词加载查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域敏感词表';

-- ---------------------------------------------------------------------------
-- 小程序服务类型（分组）
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_miniapp_service_type`
(
  `id`         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`       varchar(128) NOT NULL COMMENT '服务类型名称',
  `sort_order` int NOT NULL COMMENT '排序值',
  `hidden`     tinyint(1) NOT NULL DEFAULT 0 COMMENT '1=隐藏（该分组不展示，组内小程序仍可搜索、可固定）',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_adm_miniapp_service_type_sort_order` (`sort_order`, `id`) COMMENT '服务类型排序查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域小程序服务类型表';

-- ---------------------------------------------------------------------------
-- 小程序服务项
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_miniapp_service_item`
(
  `id`           bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `type_id`      bigint unsigned NOT NULL COMMENT '服务类型标识',
  `name`         varchar(128) NOT NULL COMMENT '服务名称',
  `link`         varchar(2048) NOT NULL COMMENT '跳转链接',
  `introduction` text COMMENT '服务介绍',
  `icon`         varchar(2048) DEFAULT NULL COMMENT '服务图标地址',
  `status`       tinyint(1) NOT NULL COMMENT '发布状态',
  `is_top`       tinyint(1) NOT NULL COMMENT '是否置顶',
  `audience`     varchar(32) NOT NULL DEFAULT 'consumer' COMMENT '面向对象：consumer=消费者服务，operator=运营后台',
  `hidden`       tinyint(1) NOT NULL DEFAULT 0 COMMENT '1=隐藏（列表不展示，可搜索、可固定）',
  `sort_order`   int NOT NULL COMMENT '排序值',
  `created_by`   bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`   datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`   bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新人',
  `updated_at`   datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_adm_miniapp_service_item_type` (`type_id`) COMMENT '按服务类型查询',
  KEY `idx_adm_miniapp_service_item_status_sort` (`status`, `is_top`, `sort_order`, `id`) COMMENT '已发布服务排序查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域小程序服务项表';

-- ---------------------------------------------------------------------------
-- 管理读投影事件幂等表
-- 主键转换：event_id → id（唯一键 uk_adm_projection_event_event_id 保留幂等语义；
-- ProjectionEventMapper.insertIfAbsent 仍按 event_id 的 INSERT IGNORE 语义工作）
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_projection_event`
(
  `id`           bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_id`     varchar(128) NOT NULL COMMENT '事件唯一标识',
  `event_type`   varchar(64) NOT NULL COMMENT '事件类型',
  `created_by`   bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`   datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事件处理时间（原 processed_at）',
  `updated_by`   bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新人',
  `updated_at`   datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间（写入方未赋值，取落库时间）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_projection_event_event_id` (`event_id`) COMMENT '事件唯一标识幂等约束'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理读投影事件幂等表';

-- ---------------------------------------------------------------------------
-- 管理读用户视图
-- 主键转换：user_id → id（唯一键 uk_adm_user_view_user_id），user_id 仍是用例查询键
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_user_view`
(
  `id`             bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`        bigint unsigned NOT NULL COMMENT '用户标识',
  `username`       varchar(128) DEFAULT NULL COMMENT '用户名快照',
  `nickname`       varchar(255) DEFAULT NULL COMMENT '昵称快照',
  `avatar`         varchar(2048) DEFAULT NULL COMMENT '头像地址快照',
  `email`          varchar(255) DEFAULT NULL COMMENT '邮箱快照',
  `phone`          varchar(64) DEFAULT NULL COMMENT '手机号快照',
  `signature`      varchar(1024) DEFAULT NULL COMMENT '个性签名快照',
  `status`         varchar(32) NOT NULL COMMENT '用户状态',
  `status_version` bigint unsigned NOT NULL COMMENT '状态版本',
  `role`           varchar(32) DEFAULT NULL COMMENT '用户角色',
  `created_by`     bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`     datetime(3) DEFAULT NULL COMMENT '源创建时间',
  `updated_by`     bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新人',
  `updated_at`     datetime(3) DEFAULT NULL COMMENT '源更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_user_view_user_id` (`user_id`) COMMENT '用户标识唯一约束',
  KEY `idx_adm_user_view_status_updated_at` (`status`, `updated_at`) COMMENT '按状态和更新时间查询',
  KEY `idx_adm_user_view_username` (`username`) COMMENT '按用户名查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理读用户视图';

-- ---------------------------------------------------------------------------
-- 管理读消息视图
-- 主键转换：msg_id → id（唯一键 uk_adm_message_view_msg_id）
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_message_view`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `msg_id`          varchar(128) NOT NULL COMMENT '消息标识',
  `from_user_id`    bigint unsigned NOT NULL COMMENT '发送人用户标识',
  `to_id`           varchar(128) NOT NULL COMMENT '接收对象标识',
  `conversation_id` varchar(128) NOT NULL COMMENT '会话标识',
  `chat_type`       varchar(32) NOT NULL COMMENT '聊天类型',
  `msg_type`        varchar(32) NOT NULL COMMENT '消息类型',
  `content`         text COMMENT '消息内容快照',
  `client_msg_id`   varchar(128) DEFAULT NULL COMMENT '客户端消息标识',
  `reply_msg_id`    varchar(128) DEFAULT NULL COMMENT '回复消息标识',
  `at_users_json`   text COMMENT '被提及用户列表',
  `status`          varchar(32) NOT NULL COMMENT '消息状态',
  `created_by`      bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`      datetime(3) NOT NULL COMMENT '消息创建时间',
  `updated_by`      bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新人',
  `updated_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间（写入方未赋值，取落库时间）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_message_view_msg_id` (`msg_id`) COMMENT '消息标识唯一约束',
  KEY `idx_adm_message_view_created_at` (`created_at`) COMMENT '按时间查询消息',
  KEY `idx_adm_message_view_from_user_created_at` (`from_user_id`, `created_at`) COMMENT '按发送人查询消息',
  KEY `idx_adm_message_view_conversation_created_at` (`conversation_id`, `created_at`) COMMENT '按会话查询消息'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理读消息视图';

-- ---------------------------------------------------------------------------
-- 管理读会话视图
-- 主键转换：conversation_id → id（唯一键 uk_adm_conversation_view_conversation_id）
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_conversation_view`
(
  `id`                bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id`   varchar(128) NOT NULL COMMENT '会话标识',
  `conversation_type` varchar(32) NOT NULL COMMENT '会话类型',
  `owner_user_id`     bigint unsigned DEFAULT NULL COMMENT '会话所有者用户标识',
  `name`              varchar(255) DEFAULT NULL COMMENT '会话名称',
  `status`            varchar(32) DEFAULT NULL COMMENT '会话状态',
  `member_count`      int DEFAULT NULL COMMENT '成员数量',
  `created_by`        bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`        datetime(3) DEFAULT NULL COMMENT '源创建时间',
  `updated_by`        bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新人',
  `updated_at`        datetime(3) DEFAULT NULL COMMENT '源更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_conversation_view_conversation_id` (`conversation_id`) COMMENT '会话标识唯一约束',
  KEY `idx_adm_conversation_view_created_at` (`created_at`) COMMENT '按时间查询会话',
  KEY `idx_adm_conversation_view_type_updated_at` (`conversation_type`, `updated_at`) COMMENT '按类型查询会话'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理读会话视图';

-- ---------------------------------------------------------------------------
-- 客户端发布聚合
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_client_release`
(
  `id`                    bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `platform`              ENUM('android', 'ios', 'windows', 'macos', 'linux') NOT NULL COMMENT '运行操作系统平台',
  `channel`               ENUM('internal', 'beta', 'stable') NOT NULL COMMENT '发行渠道',
  `version`               varchar(32) NOT NULL COMMENT '展示版本号',
  `build_number`          bigint unsigned NOT NULL COMMENT '平台渠道内递增构建号',
  `status`                ENUM('draft', 'scheduled', 'rolling_out', 'released', 'paused', 'withdrawn', 'archived') NOT NULL COMMENT '发布状态',
  `mandatory`             tinyint(1) NOT NULL DEFAULT 0 COMMENT '命中更新是否强制升级',
  `rollout_percent`       tinyint unsigned NOT NULL DEFAULT 0 COMMENT '灰度比例',
  `rollout_salt`          varchar(64) NOT NULL COMMENT '稳定灰度哈希盐',
  `scheduled_at`          datetime(3) DEFAULT NULL COMMENT '计划发布时间',
  `published_at`          datetime(3) DEFAULT NULL COMMENT '首次对客户端可见时间',
  `release_notes`         text NOT NULL COMMENT '发布说明',
  `store_url`             varchar(1024) DEFAULT NULL COMMENT '受控商店更新地址',
  `compatibility_json`    json NOT NULL COMMENT '已校验的协议兼容快照',
  `active_delivery_guard` varchar(24) GENERATED ALWAYS AS (IF(`status` IN ('scheduled', 'rolling_out'), 'active', NULL)) STORED COMMENT '用于保证同平台渠道仅一个活动发布',
  `row_version`           bigint unsigned NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by`            bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at`            datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`            bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at`            datetime(3) NOT NULL COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_client_release_platform_channel_build` (`platform`, `channel`, `build_number`) COMMENT '平台渠道构建号唯一约束',
  UNIQUE KEY `uk_adm_client_release_platform_channel_version` (`platform`, `channel`, `version`) COMMENT '平台渠道展示版本唯一约束',
  UNIQUE KEY `uk_adm_client_release_active_delivery` (`platform`, `channel`, `active_delivery_guard`) COMMENT '同平台渠道仅一个活动发布',
  KEY `idx_adm_client_release_lookup` (`platform`, `channel`, `status`, `build_number` DESC) COMMENT '按平台渠道与状态查构建号',
  KEY `idx_adm_client_release_schedule` (`status`, `scheduled_at`) COMMENT '定时发布扫描',
  CONSTRAINT `ck_adm_client_release_build_number` CHECK (`build_number` > 0),
  CONSTRAINT `ck_adm_client_release_rollout_percent` CHECK (`rollout_percent` <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端发布聚合';

-- ---------------------------------------------------------------------------
-- 客户端发布制品
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_client_release_artifact`
(
  `id`                    bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `release_id`            bigint unsigned NOT NULL COMMENT '所属客户端发布 ID',
  `architecture`          ENUM('universal', 'x64', 'arm64') NOT NULL COMMENT '目标架构',
  `package_type`          ENUM('apk', 'google-play', 'app-store', 'msix', 'exe', 'dmg', 'pkg', 'flatpak', 'appimage') NOT NULL COMMENT '安装包类型',
  `download_url`          varchar(1024) DEFAULT NULL COMMENT '受控 HTTPS 制品地址',
  `sha256`                char(64) DEFAULT NULL COMMENT '直装制品 SHA-256 摘要',
  `size_bytes`            bigint unsigned DEFAULT NULL COMMENT '直装制品字节数',
  `signing_metadata_json` json DEFAULT NULL COMMENT '签名或公证元数据',
  `created_by`            bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at`            datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`            bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at`            datetime(3) NOT NULL COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_client_release_artifact_target` (`release_id`, `architecture`, `package_type`) COMMENT '同发布同架构同包型唯一约束',
  CONSTRAINT `ck_adm_client_release_artifact_size` CHECK (`size_bytes` IS NULL OR `size_bytes` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端发布制品';

-- ---------------------------------------------------------------------------
-- 客户端最低支持版本策略
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_client_release_policy`
(
  `id`                   bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `platform`             ENUM('android', 'ios', 'windows', 'macos', 'linux') NOT NULL COMMENT '运行操作系统平台',
  `channel`              ENUM('internal', 'beta', 'stable') NOT NULL COMMENT '发行渠道',
  `minimum_build_number` bigint unsigned NOT NULL COMMENT '最低受支持构建号',
  `row_version`          bigint unsigned NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by`           bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at`           datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`           bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at`           datetime(3) NOT NULL COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_client_release_policy_platform_channel` (`platform`, `channel`) COMMENT '平台渠道唯一约束',
  CONSTRAINT `ck_adm_client_release_policy_minimum_build` CHECK (`minimum_build_number` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端最低支持版本策略';

-- ---------------------------------------------------------------------------
-- 客户端发布不可变审计日志
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_client_release_audit_log`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `release_id`      bigint unsigned DEFAULT NULL COMMENT '所属客户端发布 ID',
  `policy_id`       bigint unsigned DEFAULT NULL COMMENT '所属客户端最低支持版本策略 ID',
  `action`          ENUM('create', 'update', 'submit', 'rollout', 'pause', 'resume', 'withdraw', 'archive', 'upsert_policy', 'scheduled_publish') NOT NULL COMMENT '发布治理动作',
  `before_status`   ENUM('draft', 'scheduled', 'rolling_out', 'released', 'paused', 'withdrawn', 'archived') DEFAULT NULL COMMENT '变更前发布状态',
  `after_status`    ENUM('draft', 'scheduled', 'rolling_out', 'released', 'paused', 'withdrawn', 'archived') DEFAULT NULL COMMENT '变更后发布状态',
  `request_id`      varchar(64) NOT NULL COMMENT '请求标识',
  `idempotency_key` char(36) NOT NULL COMMENT '幂等键',
  `reason`          varchar(512) NOT NULL COMMENT '操作原因',
  `payload_digest`  char(64) NOT NULL COMMENT '请求载荷摘要',
  `created_by`      bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at`      datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`      bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at`      datetime(3) NOT NULL COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_client_release_audit_log_idempotency` (`idempotency_key`) COMMENT '幂等键唯一约束',
  KEY `idx_adm_client_release_audit_log_release_created` (`release_id`, `created_at` DESC) COMMENT '按发布查审计日志',
  KEY `idx_adm_client_release_audit_log_policy_created` (`policy_id`, `created_at` DESC) COMMENT '按策略查审计日志',
  CONSTRAINT `ck_adm_client_release_audit_subject` CHECK (`release_id` IS NOT NULL OR `policy_id` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端发布不可变审计日志';

-- ---------------------------------------------------------------------------
-- 客户端发布写操作幂等记录
-- ---------------------------------------------------------------------------
CREATE TABLE `adm_client_release_operation`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `idempotency_key` char(36) NOT NULL COMMENT '幂等键',
  `action`          ENUM('create', 'update', 'submit', 'rollout', 'pause', 'resume', 'withdraw', 'archive', 'upsert_policy', 'scheduled_publish') NOT NULL COMMENT '发布治理动作',
  `request_digest`  char(64) NOT NULL COMMENT '请求载荷摘要',
  `release_id`      bigint unsigned DEFAULT NULL COMMENT '所属客户端发布 ID',
  `policy_id`       bigint unsigned DEFAULT NULL COMMENT '所属客户端最低支持版本策略 ID',
  `created_by`      bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at`      datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`      bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at`      datetime(3) NOT NULL COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_client_release_operation_idempotency` (`idempotency_key`) COMMENT '幂等键唯一约束',
  CONSTRAINT `ck_adm_client_release_operation_subject` CHECK (`release_id` IS NOT NULL OR `policy_id` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端发布写操作幂等记录';

-- ===========================================================================
-- 种子数据（原 V3~V12 的累积终态；保持幂等）
-- ===========================================================================

-- ============ 系统配置默认值（原 V3 + V4 + V5 + V6） ============
-- INSERT IGNORE：已有管理员自定义值不被覆盖
INSERT IGNORE INTO `adm_system_config` (`config_key`, `config_value`, `config_group`, `description`, `created_at`, `updated_at`) VALUES
('app.name', 'WV Chat', 'app', '应用显示名称', NOW(3), NOW(3)),
('app.announcement', '', 'app', '系统公告（留空不显示）', NOW(3), NOW(3));

-- ============ 功能开关（feature） ============
INSERT IGNORE INTO `adm_system_config` (`config_key`, `config_value`, `config_group`, `description`, `created_at`, `updated_at`) VALUES
('feature.privateChatEnabled', 'true', 'feature', '云端单聊开关', NOW(3), NOW(3)),
('feature.groupChatEnabled', 'true', 'feature', '云端群组开关', NOW(3), NOW(3)),
('feature.recallEnabled', 'true', 'feature', '消息撤回开关', NOW(3), NOW(3)),
('feature.readReceiptEnabled', 'true', 'feature', '已读回执开关', NOW(3), NOW(3)),
('feature.voiceCallEnabled', 'true', 'feature', '语音通话开关', NOW(3), NOW(3)),
('feature.videoCallEnabled', 'true', 'feature', '视频通话开关', NOW(3), NOW(3)),
('feature.channelEnabled', 'true', 'feature', '频道（单向广播）开关', NOW(3), NOW(3)),
('feature.secretChatEnabled', 'true', 'feature', '私密聊天（E2EE）开关', NOW(3), NOW(3)),
('feature.imageAuditEnabled', 'false', 'feature', '图片内容审核开关', NOW(3), NOW(3)),
('feature.voiceAuditEnabled', 'false', 'feature', '语音内容审核开关', NOW(3), NOW(3)),
('feature.secretGroupChatEnabled', 'true', 'feature', '私密群聊开关（对应 Telegram 群组业务 · 逐成员加密）', NOW(3), NOW(3)),
('feature.groupDeleteEveryoneEnabled', 'true', 'feature', '私密群聊删除所有人/撤回开关', NOW(3), NOW(3)),
('feature.groupEditMessageEnabled', 'true', 'feature', '私密群聊编辑消息开关', NOW(3), NOW(3)),
('feature.groupAnonymityEnabled', 'false', 'feature', '私密群聊匿名发言开关', NOW(3), NOW(3)),
('feature.groupInviteLinkEnabled', 'true', 'feature', '私密群聊邀请链接开关', NOW(3), NOW(3)),
('feature.groupPinnedEnabled', 'true', 'feature', '私密群聊置顶/公告开关', NOW(3), NOW(3)),
('feature.chatDeleteEnabled', 'true', 'feature', '聊天删除/撤回/清空总开关', NOW(3), NOW(3));

-- ============ 通话配置（rtc） ============
INSERT IGNORE INTO `adm_system_config` (`config_key`, `config_value`, `config_group`, `description`, `created_at`, `updated_at`) VALUES
('rtc.videoQuality', '720p', 'rtc', '视频清晰度（360p/480p/720p/1080p）', NOW(3), NOW(3)),
('rtc.videoBitrate', '1500', 'rtc', '视频码率（kbps）', NOW(3), NOW(3)),
('rtc.audioBitrate', '64', 'rtc', '音频码率（kbps）', NOW(3), NOW(3)),
('rtc.maxCallDuration', '120', 'rtc', '最大通话时长（分钟，0 不限制）', NOW(3), NOW(3));

-- ============ 上传配置（upload） ============
INSERT IGNORE INTO `adm_system_config` (`config_key`, `config_value`, `config_group`, `description`, `created_at`, `updated_at`) VALUES
('upload.maxImageSize', '10', 'upload', '图片大小限制（MB）', NOW(3), NOW(3)),
('upload.maxFileSize', '50', 'upload', '文件大小限制（MB）', NOW(3), NOW(3)),
('upload.maxVideoSize', '100', 'upload', '视频大小限制（MB）', NOW(3), NOW(3));

-- ============ 推送配置（push） ============
INSERT IGNORE INTO `adm_system_config` (`config_key`, `config_value`, `config_group`, `description`, `created_at`, `updated_at`) VALUES
('push.enabled', 'true', 'push', '推送总开关', NOW(3), NOW(3)),
('push.apnsEnabled', 'true', 'push', 'APNs（iOS）推送开关', NOW(3), NOW(3)),
('push.fcmEnabled', 'false', 'push', 'FCM（Firebase）推送开关', NOW(3), NOW(3)),
('push.jpushEnabled', 'true', 'push', '极光 JPush 推送开关', NOW(3), NOW(3));

-- ============ 小程序服务入口种子数据（原 V7，示例，供后台增删改查、客户端展示联调） ============
INSERT INTO `adm_miniapp_service_type` (`name`, `sort_order`, `created_at`, `updated_at`) VALUES
('便民服务', 0, NOW(3), NOW(3)),
('休闲娱乐', 1, NOW(3), NOW(3));

-- 保持原 V7 的 ID 顺序：type_id=1 便民服务、type_id=2 休闲娱乐
INSERT INTO `adm_miniapp_service_item` (`type_id`, `name`, `link`, `introduction`, `icon`, `status`, `is_top`, `sort_order`, `created_at`, `updated_at`) VALUES
(1, '外卖', 'https://www.example.com/takeout', '示例：外卖服务入口', NULL, 1, 0, 0, NOW(3), NOW(3)),
(1, '打车', 'https://www.example.com/taxi', '示例：打车服务入口', NULL, 1, 0, 1, NOW(3), NOW(3)),
(2, 'KTV 预订', 'https://www.example.com/ktv', '示例：KTV 预订服务入口', NULL, 1, 1, 0, NOW(3), NOW(3));

-- ============ 注册「运营后台」服务类型并登记 B 端「KTV 业务」（原 V8，audience 列已并入建表） ============
INSERT INTO `adm_miniapp_service_type` (`name`, `sort_order`, `created_at`, `updated_at`)
SELECT '运营后台', 2, NOW(3), NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM `adm_miniapp_service_type` WHERE `name` = '运营后台');

INSERT INTO `adm_miniapp_service_item` (`type_id`, `name`, `link`, `introduction`, `icon`, `status`, `is_top`, `audience`, `sort_order`, `created_at`, `updated_at`)
SELECT t.`id`, 'KTV 业务', 'app://business/ktv', '运营后台：开台/计时/结台/收款', NULL, 1, 0, 'operator', 0, NOW(3), NOW(3)
FROM `adm_miniapp_service_type` t
WHERE t.`name` = '运营后台'
  AND NOT EXISTS (SELECT 1 FROM `adm_miniapp_service_item` i WHERE i.`name` = 'KTV 业务' AND i.`audience` = 'operator');

-- ============ C 端入口：把「KTV 预订」示例项升级为「A380 更多服务」（原 V9） ============
UPDATE `adm_miniapp_service_item`
SET `name` = 'A380 更多服务',
    `link` = 'https://www.miniservice.dev.example.com/a380',
    `introduction` = 'A380 更多服务（酒店/KTV/酒吧/足浴/美食/出行）',
    `audience` = 'consumer',
    `is_top` = 1,
    `status` = 1,
    `updated_at` = NOW(3)
WHERE `link` = 'https://www.example.com/ktv';

-- ============ 修复历史环境中仍指向统一门户的 A380 服务项，统一到 SaaS 移动服务域名（原 V10） ============
UPDATE `adm_miniapp_service_item`
SET `name` = 'A380 更多服务',
    `link` = 'https://miniservice.dev.example.com/a380/',
    `introduction` = 'A380 更多服务（酒店/KTV/酒吧/足浴/美食/出行）',
    `audience` = 'consumer',
    `is_top` = 1,
    `status` = 1,
    `updated_at` = NOW(3)
WHERE `name` = 'A380 更多服务'
   OR `link` IN (
       'https://admin.dev.example.com/a380',
       'https://admin.dev.example.com/a380/',
       'https://miniservice.dev.example.com/a380',
       'https://www.miniservice.dev.example.com/a380'
   );

-- ============ 小程序服务「隐藏」属性（原 V11，hidden 列已并入建表） ============
-- 语义：隐藏项不出现在「服务」列表（含其所在分组），但仍可被搜索到，也仍可固定到快捷应用区。
-- 运营端入口默认隐藏：运营后台通过搜索进入或固定到快捷应用区，不占用普通用户的服务列表。
UPDATE `adm_miniapp_service_item`
SET `hidden` = 1, `updated_at` = NOW(3)
WHERE `audience` = 'operator';

-- 「运营后台」分组本身也隐藏：即使组内还有未隐藏的小程序，分组名也不再展示。
UPDATE `adm_miniapp_service_type`
SET `hidden` = 1, `updated_at` = NOW(3)
WHERE `name` = '运营后台';

-- ============ 运营入口改指已登记的 A380 B 端 OAuth/OIDC 页面（原 V12） ============
-- 不能把 app:// 协议交给 WebView。
UPDATE `adm_miniapp_service_item`
SET `link` = 'https://miniservice.dev.example.com/b/',
    `introduction` = '运营后台：开台/计时/结台/收款',
    `audience` = 'operator',
    `hidden` = 1,
    `status` = 1,
    `updated_at` = NOW(3)
WHERE `audience` = 'operator'
  AND (`name` = 'KTV 业务' OR `link` = 'app://business/ktv');
