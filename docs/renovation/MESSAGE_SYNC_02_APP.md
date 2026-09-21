# 消息序号同步 App 改造方案

## 方案信息

- 方案集：`MESSAGE_SYNC`
- 顺序号：`02`
- 实施边界：Flutter App
- 前置方案：[MESSAGE_SYNC_01_SERVICE.md](MESSAGE_SYNC_01_SERVICE.md)
- 后置方案：[MESSAGE_SYNC_03_TEST.md](MESSAGE_SYNC_03_TEST.md)
- 适用标准：[10 业务微服务 DDD 工程规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)

## 目标与本地状态

App 按账号保存一个全局同步水位，而不是按会话猜测离线消息：

```text
accountId -> lastSyncedSyncSeq
```

该水位只能在一页中的全部消息已按 `msgId` 去重并成功持久化到本地消息库后，原子推进到响应的 `nextSyncSeq`。不得从 WebSocket 时间戳、未读数、服务端消息 `status` 或当前会话 `seq` 推导该水位。

权威消息保留期内，新设备从 `0` 开始分页同步全部可见消息。消息清空是当前设备本地行为：以 `accountId + conversationId -> locallyClearedBeforeSyncSeq` 保存展示过滤水位，不请求服务端删除消息，不影响其他设备，也不回退全局同步水位。

当前实现的 `ImApi.offlineMessages()`、生成客户端 `offlineMessages()` 和 `ChatProvider.loadConversationsFromServer()` 中的离线列表合并逻辑必须删除并替换；不得保留双路径。PC 管理后台不调用此接口，也不保存用户同步水位。

## 实施文件清单

| 文件/模块 | 必须改造 |
| --- | --- |
| `lib/im-services/generated_im_api_client.dart` | 依据 OpenAPI 重新生成 `syncMessages(afterSyncSeq, limit)` |
| `lib/im-services/im_api.dart` | 增加强类型同步封装，删除 `offlineMessages()` |
| `lib/repositories/chat_repository.dart`、`im_chat_repository.dart` | 替换离线列表 Repository 契约为同步分页契约 |
| `lib/providers/chat_provider.dart` | 以 `_synchronizeMessages()` 替换 `loadConversationsFromServer()` 的离线拉取与合并 |
| `lib/providers/chat/chat_realtime_message_coordinator.dart` | 接收 `syncSeq`，处理重复帧和会话内 `seq` 缺口 |
| `lib/im-services/socket_service.dart` | 解析并透传 `chat:receive.syncSeq` |
| 本地持久化层 | 增加按 `accountId` 隔离的同步水位读写、登出清理 |

## 启动、重连与实时交互

1. 登录成功后读取当前账号本地消息和 `lastSyncedSyncSeq`，连接 WebSocket。
2. 调用 `GET /messages/sync?afterSyncSeq=<local>&limit=200`。每页在单个本地存储事务中写入消息、会话预览和水位；`hasMore=true` 时以 `nextSyncSeq` 继续，直到完成。
3. 网络失败、应用被杀或本地写入失败时不推进水位；下次从最后成功水位继续。重复页由 `msgId` 幂等吸收。
4. 实时 `chat:receive` 包含 `syncSeq`：
   - `syncSeq <= local`：仅按 `msgId` 去重，不增加未读或回退水位；
   - `syncSeq = local + 1`：本地事务写入消息和水位；
   - `syncSeq > local + 1`：暂停该消息的最终水位推进，执行 HTTP 同步至该缺口消失，再按序合并。
5. 同一会话的 `seq` 不连续时也触发同步；全局 `syncSeq` 保证所有会话不漏，`seq` 保证会话显示排序正确。
6. 用户实际进入会话并展示消息后才调用 `/messages/read`；已读请求失败可重试，不影响同步水位。
7. 收到 Push 后只触发同步或唤醒 App；Push 正文不参与本地消息写入，也不用于生成会话预览。

## 错误、并发与体验规则

- 同一账号在任意时刻只允许一个同步任务；登录刷新、重连和缺口触发应合并到该任务，避免分页交叉。
- `401`：停止同步，走统一登录失效流程。
- `403`：记录会话/消息授权异常，刷新会话列表；不推进包含该失败页的水位。
- 超时、网络异常、`5xx`：指数退避重试，保留本地内容和水位；不得清空会话列表。
- 大量历史补齐在后台分批运行，首屏先显示本地内容；同步期间仅展示轻量“正在同步”状态，不阻塞发消息。
- 新旧账号切换与登出必须取消同步任务、清空内存任务状态，避免把 A 的水位写入 B。
- App 与消息服务、Gateway 必须同版本发布；连接握手或首帧协商发现服务端不支持 `syncSeq` 时，停止消息功能并提示升级，不尝试回退 `/messages/offline`。

## App 完成定义

首次登录、重装、断网重连、重复实时投递、多设备发送和跨会话交错消息后，App 都能按水位恢复完整消息；会话预览、未读数和消息顺序不重复、不倒退、不漏失。
