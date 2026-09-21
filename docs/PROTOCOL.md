# 协议说明（WebSocket / REST）

本文档用于对齐客户端与服务端的消息协议与事件名。事件名常量以 `common/.../WsEvents` 为唯一事实来源。

## WebSocket 连接

- URL：`ws://{host}:{port}/ws/im/v1?ticket={one-time-ticket}`
- 握手前通过 `POST /api/v1/auth/ws-ticket` 用已认证用户 token 申请一次性 ticket；有效期 60 秒，服务端原子消费，URL 不接受 JWT。
- 协议：Text WebSocket（JSON 文本帧）

## 通用帧格式

客户端到服务端、服务端到客户端均使用统一帧结构：

```json
{
  "event": "chat:send",
  "data": {}
}
```

## 事件表

| 事件 | 方向 | 说明 |
| --- | --- | --- |
| `chat:send` | C→S | 发送消息 |
| `chat:ack` | S→C | 发送方 ACK（服务端已持久化） |
| `chat:receive` | S→C | 接收消息 |
| `chat:read` | C→S | 已读回执 |
| `chat:read_notify` | S→C | 已读状态通知 |
| `chat:recall` | C→S | 撤回消息 |
| `chat:recall_notify` | S→C | 撤回通知 |
| `chat:typing` | C→S/S→C | 输入中提示 |
| `user:status_change` | S→C | 在线状态变更 |
| `friend:request_notify` | S→C | 好友请求通知 |
| `friend:accept_notify` | S→C | 好友接受通知 |
| `group:notify` | S→C | 群事件通知 |
| `rtc:signal` | C→S/S→C | 音视频信令 |
| `heartbeat` | C→S/S→C | 心跳 |
| `error` | S→C | 错误事件 |

## 示例：发送消息（C→S）

```json
{
  "event": "chat:send",
  "data": {
    "toId": "123",
    "chatType": "private",
    "msgType": "text",
    "content": "Hello!",
    "clientMsgId": "uuid-client-generated",
    "replyMsgId": null,
    "atUsers": []
  }
}
```

## 示例：ACK（S→C）

```json
{
  "event": "chat:ack",
  "data": {
    "clientMsgId": "uuid-client-generated",
    "msgId": "1234567890123456789",
    "timestamp": "2026-03-25T10:00:00.000Z"
  }
}
```

## 示例：接收消息（S→C）

```json
{
  "event": "chat:receive",
  "data": {
    "msgId": "1234567890123456789",
    "from": 1,
    "fromUsername": "alice",
    "toId": "2",
    "chatType": "private",
    "msgType": "text",
    "content": "Hello!",
    "replyMsgId": null,
    "atUsers": [],
    "timestamp": "2026-03-25T10:00:00.000Z"
  }
}
```

## 已读回执

```json
{ "event": "chat:read", "data": { "msgIds": ["msg1", "msg2"] } }
```

## 消息撤回

```json
{ "event": "chat:recall", "data": { "msgId": "1234567890123456789" } }
```

## 心跳

客户端定期发送：

```json
{ "event": "heartbeat", "data": { "timestamp": 0 } }
```

服务端回显：

```json
{ "event": "heartbeat", "data": { "timestamp": 0 } }
```

心跳间隔与超时口径以 `common/.../AppConstants` 为准。

## REST API

REST API 以 Swagger 为准。

- Swagger UI：`http://localhost:3000/swagger-ui.html`
- OpenAPI JSON：`http://localhost:3000/api-docs`
