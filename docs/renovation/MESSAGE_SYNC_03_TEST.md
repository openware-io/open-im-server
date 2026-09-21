# 消息序号同步测试与验收方案

## 方案信息

- 方案集：`MESSAGE_SYNC`
- 顺序号：`03`
- 实施边界：自动化测试、服务联调与验收
- 前置方案：[MESSAGE_SYNC_01_SERVICE.md](MESSAGE_SYNC_01_SERVICE.md)、[MESSAGE_SYNC_02_APP.md](MESSAGE_SYNC_02_APP.md)
- 后置方案：无
- 适用标准：[20 Maven 工程规范](../standards/20_MAVEN_ENGINEERING_CONVENTIONS.md)、[40 可观测性规范](../standards/40_OBSERVABILITY_CONVENTIONS.md)

## 服务端测试矩阵

必须使用真实 MySQL 的 Testcontainers 或等效集成环境；禁止只通过内存 Repository 验证 SQL 与事务行为。

| 场景 | 断言 |
| --- | --- |
| 私聊写入 | 消息、发送方/接收方同步索引、两个用户序号和 Outbox 同事务成功 |
| 群聊写入 | 接收时刻全部成员及发送方各有一条索引；退出群后不再收到后续索引 |
| 群规模 | 500 人群可完整写入索引；超过 `maxMembers` 的异常成员快照会拒绝发送并整体回滚 |
| 事务失败 | 任一索引或 Outbox 写入失败时，消息和所有序号均回滚 |
| 分页 | 超过 500 条时从 `afterSyncSeq=0` 分页全量拉取，无重复、无遗漏、`nextSyncSeq` 单调前进 |
| 删除与撤回 | 删除索引/消息后同步不会泄露正文；游标可越过已删除索引，不死循环 |
| 清空与权限 | 客户端清空不删除 MySQL 消息或索引；群全局删除仅群主/管理员可执行 |
| 已读授权 | 用户只能标记自身同步索引中的可见消息；伪造 `msgId` 不产生读状态 |
| 多设备 | 发送方另一设备可通过自己的索引同步到已发送消息 |
| 投影故障 | Mongo 失败、延迟或重放不会影响 MySQL 同步结果；重放幂等 |
| 网关 | 每位接收方的实时帧包含其专属 `syncSeq`，缺失映射时投递重试而非静默成功 |

新增或调整测试位置：

```text
im-services/message/im-message-service/src/test/.../application/MessageApplicationServiceTest.java
im-services/message/im-message-service/src/test/.../infra/persistence/message/repository/*IntegrationTest.java
im-services/message/im-message-service/src/test/.../api/controller/MessageControllerIntegrationTest.java
gateways/im-access-ws/src/test/.../message/StoredMessageEventListenerTest.java
```

## App 测试矩阵

1. 从 `lastSyncedSyncSeq=0` 连续处理多页响应，验证本地消息、会话和水位原子推进。
2. 第二页本地持久化失败，验证水位停在第一页且重试幂等。
3. 重复 HTTP 页、重复 WebSocket 帧均只展示一次消息。
4. 收到大于本地水位的实时帧时，先补齐 HTTP 缺口再提交实时消息。
5. 账号切换、登出、网络重连取消旧同步任务，水位完全隔离。
6. 已读请求失败不回退同步水位，重试成功后未读数刷新正确。

## 双用户端到端验收

准备 A、B、C 三个用户和包含 A/B 的群组；在真实 Gateway、MySQL、Redis、Mongo 环境执行：

1. B 离线期间，A 向 B 发送超过 500 条私聊消息；B 首次登录后分页补齐，核对消息数量、`msgId`、`syncSeq` 和会话内 `seq`。
2. A 在群内发送消息，B 退出群后 A 再发送；B 只同步到退出前的索引，C 无法同步该群消息。
3. B 同步至中途断网、强杀并重启，验证从最后已落盘水位继续。
4. B 在线时人为丢弃一条 WebSocket 帧，验证后续帧的 `syncSeq` 触发 HTTP 补洞。
5. A 与 B 双端登录；A 的第二终端可同步到第一终端发出的消息。
6. C 对 A/B 的 `msgId` 调用已读接口，验证没有写入读状态；后台消息审计与历史查询正常，且不请求同步接口。
7. 清空 B 当前设备的会话，验证仅该设备隐藏历史；B 的另一设备和 MySQL 权威消息不受影响。
8. 验证 Push 仅触发同步，不含可展示的消息正文或媒体访问地址。

## 发布门禁与运行观测

发布前必须完成：OpenAPI 生成校验、相关 Maven 模块测试、Gateway 测试、Flutter 测试、上述真机双用户联调。客户端、Gateway、消息服务必须同版本发布；由于不兼容旧协议，禁止灰度混用，并验证旧客户端被明确拒绝而非静默降级。

生产指标：同步请求数、分页数、同步条数、耗时、`syncSeq` 缺口、未授权已读、索引写入失败、Outbox 堆积、Mongo 投影延迟。日志不得记录消息正文、访问令牌或媒体签名 URL。

验收通过条件：无重复/遗漏/水位倒退；任何 Mongo、Redis 或 WebSocket 短暂故障均可由 MySQL 同步恢复；索引和消息写入的事务一致性经集成测试证明。
