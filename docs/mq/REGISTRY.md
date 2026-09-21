# MQ 资源台账

## 登记规则

- 本台账仅登记仓库中已经实现的 RocketMQ 资源。
- 资源名称、责任模块、消费目的、顺序键和幂等键必须与 `protocol-mq` 常量及服务实现保持一致。
- 新增或修改资源时，必须同步遵守 [12 RocketMQ 治理规范](../standards/12_ROCKETMQ_CONVENTIONS.md)。

## 已实现资源

| 类型 | 名称 | 责任模块 | 消费目的 | 顺序键 | 幂等键 |
| --- | --- | --- | --- | --- | --- |
| Topic | `im_message_command_send_v1` | `im-access-ws` 生产，`im-message-service` 消费 | 提交消息权威写入命令 | `conversationId` | `commandId` |
| Topic | `im_message_command_read_v1` | `im-access-ws` 生产，`im-message-service` 消费 | 提交消息已读状态命令 | `userId` | `commandId` |
| Topic | `im_message_command_recall_v1` | `im-access-ws` 生产，`im-message-service` 消费 | 提交消息撤回命令 | `msgId` | `commandId` |
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
| Consumer Group | `im-message-service-read-command-consumer` | `im-message-service` | 消费已读命令并完成权威写入 | `userId` | `commandId` |
| Consumer Group | `im-message-service-recall-command-consumer` | `im-message-service` | 消费撤回命令并完成权威写入 | `msgId` | `commandId` |
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
