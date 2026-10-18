# MQ 资源台账

## 登记规则

- 本台账仅登记仓库中已经实现的 RocketMQ 资源。
- 资源名称、责任模块、消费目的、顺序键和幂等键必须与 `protocol-mq` 常量及服务实现保持一致。
- 新增或修改资源时，必须同步遵守 [12 RocketMQ 治理规范](../standards/12_ROCKETMQ_CONVENTIONS.md)。

## 已实现资源

| 类型 | 名称 | 责任模块 | 消费目的 | 顺序键 | 幂等键 |
| --- | --- | --- | --- | --- | --- |
| Topic | `im_message_command_send_v1` | `im-access-ws` 生产，`im-message-service` 消费 | 提交消息权威写入命令 | `conversationId` | `commandId` |
| Topic | `im_message_command_read_v1` | `im-access-ws` 生产，`im-message-service` 消费 | 提交消息已读状态命令 | `conversationId` | `commandId` |
| Topic | `im_message_command_recall_v1` | `im-access-ws` 生产，`im-message-service` 消费 | 提交消息撤回命令 | `conversationId` | `commandId` |
| Topic | `im_message_event_stored_v1` | `im-message-service` Outbox Relay 生产，`im-access-ws` 消费 | WebSocket 在线下行与离线投递协调 | `conversationId` | `eventId` |
| Topic | `im_message_event_stored_v1` | `im-message-service` Outbox Relay 生产，`im-admin-service` 消费 | 管理端消息读投影 | `conversationId` | `eventId` |
| Topic | `im_user_event_friend_requested_v1` | `im-user-service` Outbox Relay 生产，`im-access-ws` 消费 | 下行好友申请通知 | `toUserId` | `eventId` |
| Topic | `im_user_event_friend_accepted_v1` | `im-user-service` Outbox Relay 生产，`im-access-ws` 消费 | 下行好友接受通知 | `fromUserId` | `eventId` |
| Topic | `im_user_event_friend_accepted_v1` | `im-user-service` Outbox Relay 生产，`im-message-service` 消费 | 消费好友通过事件并生成正式私聊消息 | 好友事件顺序键（申请方/同意方） | `requestId`（事件幂等字段：`eventId`） |
| Topic | `im_user_event_status_changed_v1` | `im-user-service` Outbox Relay 生产，`im-admin-service` 消费 | 管理端用户状态读投影 | `userId` | `eventId` |
| Topic | `im_user_event_authentication_invalidated_v1` | `im-user-service` Outbox Relay 生产，`im-access-ws` 消费 | 关闭认证版本失效用户的 WebSocket 会话 | `userId` | `eventId` |
| Topic | `im_user_event_profile_changed_v1` | `im-user-service` Outbox Relay 生产，`platform-identity-service` 消费 | 用户资料变更同步（授权范围内，按 scope 过滤） | `userId` | `eventId` |
| Producer Group | `im-message-service-producer` | `im-message-service` | 发布消息已存储事件 | - | - |
| Producer Group | `im-access-ws-producer` | `im-access-ws` | 发布消息发送命令 | - | - |
| Producer Group | `im-user-service-producer` | `im-user-service` | 由用户域 Outbox Relay 发布好友/状态/资料变更事件 | - | - |
| Consumer Group | `im-message-service-write-command-consumer` | `im-message-service` | 消费发送命令并完成权威写入 | `conversationId` | `commandId` |
| Consumer Group | `im-message-service-friend-accepted-consumer` | `im-message-service` | 消费好友通过事件并生成正式私聊消息；失败重试并进入 DLQ | 好友事件顺序键（申请方/同意方） | `requestId`（事件幂等字段：`eventId`） |
| Consumer Group | `im-message-service-read-command-consumer` | `im-message-service` | 消费已读命令并完成权威写入 | `conversationId` | `commandId` |
| Consumer Group | `im-message-service-recall-command-consumer` | `im-message-service` | 消费撤回命令并完成权威写入 | `conversationId` | `commandId` |
| Consumer Group | `im-access-ws-delivery-consumer` | `im-access-ws` | 消费已存储事件并下行在线会话 | `conversationId` | `eventId` |
| Consumer Group | `im-access-ws-friend-notification-consumer` | `im-access-ws` | 消费好友事件并下行在线会话 | 事件目标用户 ID | `eventId` |
| Consumer Group | `im-access-ws-authentication-invalidation-consumer` | `im-access-ws` | 消费认证失效事件并关闭本节点用户会话 | `userId` | `eventId` |
| Consumer Group | `im-admin-service-user-status-projection-consumer` | `im-admin-service` | 消费用户状态事件并更新管理读投影 | `userId` | `eventId` |
| Consumer Group | `im-admin-service-message-projection-consumer` | `im-admin-service` | 消费消息存储事件并更新管理读投影 | `conversationId` | `eventId` |
| Consumer Group | `platform-identity-service-user-profile-changed-consumer` | `platform-identity-service` | 消费用户资料变更事件并按 scope 落 idt_ 资料快照 | `userId` | `eventId` |
| Topic | `mkt_coupon_event_redeemed_v1` | `platform-marketing-service` Outbox Relay 生产，`platform-marketing-service` 消费 | 优惠券核销事件（营销域） | `tenantId` | `eventId` |
| Producer Group | `platform-marketing-service-producer` | `platform-marketing-service` | 由营销 Outbox Relay 发布优惠券核销事件 | - | - |
| Consumer Group | `platform-marketing-service-coupon-redeemed-consumer` | `platform-marketing-service` | 消费优惠券核销事件并写入消费去重表 | `tenantId` | `eventId` |
| Topic | `ord_order_event_created_v1` | `platform-order-service` Outbox Relay 生产，`platform-order-service` 消费 | 订单创建事件（订单域） | `tenantId` | `eventId` |
| Topic | `ord_order_event_settled_v1` | `platform-order-service` Outbox Relay 生产，`platform-order-service` 消费 | 订单结算事件（订单域） | `tenantId` | `eventId` |
| Topic | `pay_collect_event_confirmed_v1` | `common-payment-service` Outbox Relay 生产，`common-payment-service` 消费 | 组合收款确认事件（支付域） | `tenantId` | `eventId` |
| Topic | `pay_refund_event_requested_v1` | `common-payment-service` Outbox Relay 生产，`common-payment-service` 消费 | 退款申请事件（支付域） | `tenantId` | `eventId` |
| Producer Group | `platform-order-service-producer` | `platform-order-service` | 由订单 Outbox Relay 发布订单事件 | - | - |
| Producer Group | `common-payment-service-producer` | `common-payment-service` | 由支付 Outbox Relay 发布支付事件 | - | - |
| Consumer Group | `platform-order-service-order-created-consumer` | `platform-order-service` | 消费订单创建事件并写入消费去重表 | `tenantId` | `eventId` |
| Consumer Group | `common-payment-service-collect-confirmed-consumer` | `common-payment-service` | 消费组合收款确认事件并写入消费去重表 | `tenantId` | `eventId` |
| Consumer Group | `platform-order-service-order-settled-consumer` | `platform-order-service` | 消费订单结算事件并写入消费去重表 | `tenantId` | `eventId` |
| Consumer Group | `common-payment-service-refund-requested-consumer` | `common-payment-service` | 消费退款申请事件并写入消费去重表 | `tenantId` | `eventId` |
| Topic | `cst_fact_event_v1` | `platform-customer-service` | 客户域事实事件（由 CustomerEventOutboxRelay 投递） | tenantId | eventId |
| Topic | `res_occupation_event_cancelled_v1` | `platform-resource-service` | 资源占用取消事件（由资源域 Outbox Relay 投递） | tenantId | eventId |
| Topic | `res_occupation_event_released_v1` | `platform-resource-service` | 资源占用释放事件（由资源域 Outbox Relay 投递） | tenantId | eventId |
| Topic | `im_conversation_event_authorization_changed_v1` | `im-conversation-service` | 会话授权变更事件，供接入层下行通知 | conversationId | eventId |
| Topic | `im_conversation_event_secret_chat_created_v1` | `im-conversation-service` | 私密聊天创建事件，供接入层对该会话参与者下行信号 | conversationId | eventId |
| Topic | `im_conversation_event_secret_chat_deleted_v1` | `im-conversation-service` | 私密聊天终止事件，供接入层移除会话并清空本地密文 | conversationId | eventId |
| Topic | `im_conversation_event_secret_chat_destroy_policy_changed_v1` | `im-conversation-service` | 私密聊天销毁策略变更事件，供消息域回补销毁计时 | conversationId | eventId |
| Topic | `im_message_command_secret_destroy_v1` | `im-access-ws` | 私密消息延迟销毁命令（RocketMQ 延迟消息） | conversationId | commandId |
| Topic | `im_message_event_chat_cleared_v1` | `im-message-service` | 会话记录被清空事件，通知其余成员清理端侧缓存与本地记录 | conversationId | eventId |
| Topic | `im_message_event_edited_v1` | `im-message-service` | 消息编辑事件，下行给发送者其它设备与接收方 | conversationId | eventId |
| Topic | `im_message_event_recalled_v1` | `im-message-service` | 消息撤回事件，供接入层下行撤回通知 | conversationId | eventId |
| Topic | `im_message_event_secret_destroyed_v1` | `im-message-service` | 私密消息销毁事件，对在线参与方推送销毁 | conversationId | eventId |
| Topic | `im_message_event_secret_group_stored_v1` | `im-message-service` | 私密群聊消息存储事件，供接入层对每个接收方下发轻量信号 | conversationId | eventId |
| Topic | `im_message_event_secret_stored_v1` | `im-message-service` | 私密消息存储事件，供接入层对接收方下发轻量信号 | conversationId | eventId |
| Topic | `im_user_event_chat_records_purged_v1` | `im-user-service` | 用户聊天记录清理事件，由各域消费者清除该用户数据 | userId | eventId |
| Topic | `im_user_event_data_wipe_requested_v1` | `im-user-service` | 用户数据擦除请求事件，通知各端清理本地数据并关闭会话 | userId | eventId |
| Producer Group | `platform-resource-service-producer` | `platform-resource-service` | 发布资源占用取消/释放事件 | - | - |
| Consumer Group | `platform-customer-service-collect-confirmed-consumer` | `platform-customer-service` | 收款确认后的积分获得消费者：积分成功后才登记 consumed，失败由 MQ 重投 | tenantId | eventId |
| Consumer Group | `platform-resource-service-occupation-consumer` | `platform-resource-service` | 资源占用事件消费（占用状态与可用性维护） | tenantId | eventId |
| Consumer Group | `im-access-ws-chat-cleared-notification-consumer` | `im-access-ws` | 会话记录被清空通知：通知其余成员清理端侧缓存与本地记录 | conversationId | eventId |
| Consumer Group | `im-access-ws-conversation-notification-consumer` | `im-access-ws` | 会话授权变更通知：下行会话授权变化 | conversationId | eventId |
| Consumer Group | `im-access-ws-data-wipe-consumer` | `im-access-ws` | 用户数据擦除请求：通知各端清理本地数据与缓存并关闭会话 | userId | eventId |
| Consumer Group | `im-access-ws-edit-notification-consumer` | `im-access-ws` | 消息编辑通知：向发送者其它设备与接收方下行更新 | conversationId | eventId |
| Consumer Group | `im-access-ws-recall-notification-consumer` | `im-access-ws` | 消息撤回通知：下行撤回 | conversationId | eventId |
| Consumer Group | `im-access-ws-secret-chat-created-consumer` | `im-access-ws` | 私密聊天创建：对对方参与者推 WS 信号（仅元数据），使其同步会话列表并完成握手 | conversationId | eventId |
| Consumer Group | `im-access-ws-secret-chat-deleted-consumer` | `im-access-ws` | 私密聊天终止：对对方参与者推 WS 信号（仅元数据），移除会话并清空本地密文 | conversationId | eventId |
| Consumer Group | `im-access-ws-secret-destroyed-consumer` | `im-access-ws` | 私密消息销毁：对在线参与方推送销毁事件，离线端由 states 增量拉取兜底 | conversationId | eventId |
| Consumer Group | `im-access-ws-secret-group-notification-consumer` | `im-access-ws` | 私密群聊消息存储：对每个在线接收方推轻量信号，后台接收方补 JPush 到达提示 | conversationId | eventId |
| Consumer Group | `im-access-ws-secret-notification-consumer` | `im-access-ws` | 私密消息存储：对在线接收方推轻量信号，后台接收方补 JPush 到达提示 | conversationId | eventId |
| Consumer Group | `im-conversation-service-chat-records-purge-consumer` | `im-conversation-service` | 用户聊天记录清理：清除该用户在会话服务的全部会话数据（账号保留） | userId | eventId |
| Consumer Group | `im-message-service-chat-records-purge-consumer` | `im-message-service` | 用户聊天记录清理：清除该用户在消息服务的全部消息数据（含密文） | userId | eventId |
| Consumer Group | `im-message-service-secret-chat-deleted-consumer` | `im-message-service` | 私密聊天终止：硬删除该会话全部密文（删除即销毁双方消息） | conversationId | eventId |
| Consumer Group | `im-message-service-secret-chat-destroy-policy-changed-consumer` | `im-message-service` | 私密聊天销毁策略变更：对历史消息回补销毁计时 | conversationId | eventId |
| Consumer Group | `im-message-service-secret-destroy-consumer` | `im-message-service` | 私密消息延迟销毁命令：到期后精确销毁对应密文（仅当仍 active 且 destroyAt 已到） | conversationId | commandId |
| Producer Group | `im-conversation-service-producer` | `im-conversation-service` | 经 ConversationAuthorizationOutboxRelay 发布会话授权变更事件 | - | - |
| Producer Group | `im-admin-service-producer` | `im-admin-service` | 仅由共享 MqProducer Bean 注册（该服务只消费、不发布，此 group 不发消息） | - | - |
| Producer Group | `platform-identity-service-producer` | `platform-identity-service` | 仅由共享 MqProducer Bean 注册（该服务只消费、不发布，此 group 不发消息） | - | - |
