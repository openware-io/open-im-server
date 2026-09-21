# 消息推送接入说明

## 推送链路

- 触发入口：聊天消息发送成功后触发离线推送。
- 私聊：对 `toId` 推送。
- 群聊：对群成员批量推送，排除发送者。
- 领域层：`PushService -> PushDeliveryPort -> PushDeliveryAdapter -> PushSender`。
- 离线判定：`OnlineService.isOnline(userId)`，使用 Redis 在线集合。
- 厂商路由：按 `device_tokens.push_provider` 选择具体 `PushSender` 实现。
- 失败隔离：单设备推送失败仅记录错误日志，不影响消息主流程，也不会中断同批次其他设备的发送。

## 推送规则

- 总开关：仅当 `system_configs` 中 `push.enabled=true` 时才触发推送。
- 在线不推：用户在线时不推送。
- 离线才推：只对离线用户推送。
- 多设备：同一用户存在多条 `device_tokens` 记录时，按设备逐条发送。
- 只发启用：只发送 `device_tokens.enabled=true` 的记录。
- 推送数据：业务侧透传数据使用 `Map<String, String>`，会过滤空 key 与 null value。

## JPUSH 载荷规则

- 按极光 REST API v3 的 `/v3/push` 调用。
- 目标设备：按 `registration_id` 单设备发送，一个 `device_token` 对应一次请求。
- 同时下发 `notification` 与 `message`，兼顾通知栏展示和前台透传/自定义处理。
- `alert` 生成规则：优先使用 `body`；`body` 为空时使用 `title`。
- `options.apns_production`：由 `push.jpush.apns-production` 控制。
- `options.time_to_live`：由 `push.jpush.time-to-live-seconds` 控制，单位为秒。

## 配置项（厂商侧）

在启动模块环境变量或 `.env` 中配置：

```properties
JPUSH_ENABLED=true
JPUSH_ENDPOINT=https://api.jpush.cn/v3/push
JPUSH_APP_KEY=your-app-key
JPUSH_MASTER_SECRET=your-master-secret
JPUSH_APNS_PRODUCTION=true
JPUSH_TIME_TO_LIVE_SECONDS=86400
```

配置绑定前缀为 `push.jpush.*`。

## 配置项（业务侧总开关）

推送是否启用由 `system_configs` 表的 `push.enabled` 控制。它用于运行时开关，与厂商配置启用是两个维度。

示例，以 MySQL 为例：

```sql
INSERT INTO system_configs (config_key, config_value, config_group, description)
VALUES ('push.enabled', 'true', 'push', '是否启用离线推送')
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);
```

## 数据模型（device_tokens）

- 表：`device_tokens`。
- `user_id`：所属用户。
- `token`：推送令牌，极光对应 `registration_id`。
- `push_provider`：推送渠道，当前使用 `jpush`。
- `platform`：`android`、`ios`、`web`、`windows` 或 `macos`。
- `enabled`：是否启用，查询时只取 `enabled=true`。
- 客户端注册设备令牌时，建议显式传递 `pushProvider=jpush`。
- 未传递 `pushProvider` 时，服务端默认使用 `JPUSH`。

## 使用约束

- 不要将 `AppKey`、`Master Secret` 明文提交到仓库。
- 当前实现默认同一个极光应用同时承载 Android 与 iOS 推送；后续需要拆分多应用时，再扩展为分平台配置。
- 推送发送失败只记录日志，不影响消息主流程，也不会中断同批次其他设备的发送。

## 规则变更与扩展

- 变更“何时推”：在消息发送用例处调整调用点，当前由 WebSocket 发送消息成功后触发。
- 变更“谁算离线”：在 `OnlineService` 的在线集合判定逻辑中调整。
- 新增厂商：新增 `PushSender` 实现并注册为 Spring Bean，同时补充 `PushProvider` 枚举与 `device_tokens.push_provider` 枚举值对应的数据库迁移。
