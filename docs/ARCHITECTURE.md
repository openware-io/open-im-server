# 架构总览

- `im-access-ws` 负责 WebSocket 长连接接入、鉴权、在线会话维护，以及消息命令上行和消息事件下行。
- `im-message-service` 负责 RocketMQ 命令消费、MySQL 权威落库、`msg_outbox` 事件中转，以及 MongoDB/Redis 热投影。
- MySQL 是唯一权威消息库；MongoDB 只承载热消息与离线收件箱；Redis 只承载热点状态；RocketMQ 负责削峰、解耦和会话级保序。
- 会话空间与隐私边界的产品语义见 [会话空间与隐私边界（产品业务文档）](business/CONVERSATION_TYPES_AND_PRIVACY.md)。

## 总体形态
当前系统采用 “Gateway + 领域微服务 + 长连接接入层” 的结构。
- 外部客户端通过 `gateway` 访问 HTTPS/WSS
- 非长连接请求由 Gateway 路由到各业务微服务
- 长连接消息走 `im-access-ws`
- 服务间通过 HTTP 调用，部署到 K8s 时依赖 Service/DNS 做发现与负载均衡

## 服务边界

### gateway
- 对外统一域名入口
- 负责路由、鉴权透传、限流、灰度、审计等横切能力
- 不承载业务编排
### im-user-service
- 用户资料
- 注册登录
- 设备令牌
- 设备密钥（E2EE：`/device-keys/**` 注册/续期/恢复）
- 好友关系链
- 用户积分
- 用户贴纸

### im-message-service
- 消息历史
- 离线消息拉取
- 已读回执
- 撤回与清空消息
- 频道消息游标拉取（`/messages/channel/{channelId}`）
- 私密消息密文存储（`/secret-messages/**`，服务端不解密）+ 定时销毁引擎（已读后计时 + 周期扫描）

### im-conversation-service
- 群组与群成员管理
- 群角色与禁言规则
- 频道（单向发布/订阅，`/channels/**`：创建/订阅/成员角色/消息权限）
- 私密聊天（E2EE 会话，`/secret-chats/**`：握手状态机/安全码指纹/销毁策略）
- RTC 配置查询

### common-media-service
- 受控媒体直传会话与对象元数据
- 私有媒体短期访问授权
- 对象存储适配、扫描与生命周期清理
- 通用媒体引用登记

### im-admin-service
- 管理端后台接口
- 客户端配置下发
- 版本更新检查
- 举报创建与处理
- 小程序服务配置
### im-access-ws
- WebSocket 连接建立与鉴权
- 在线会话维护
- 跨实例广播
- 向聊天域服务查询权限与投递结果
## 数据与基础设施

- MySQL：唯一权威库，承载关系型业务事实、权威写入与可追溯数据；关系型持久层主栈为 MyBatis-Plus `3.5.17`。
- MongoDB：仅承担消息热投影，不得承载权威业务事实。
- Redis：仅承担在线状态、热点状态、跨实例投递与广播协同，不得承载权威持久化数据。
- 业务关系库服务通过 `mybatis-plus-spring-boot4-starter` 与运行时 `mysql-connector-j` 接入 MySQL；持有迁移脚本的服务追加 `spring-boot-starter-flyway` 与 `flyway-mysql`。
- Flyway：由持有本域 MySQL 表结构的服务分别维护迁移脚本；当前为 `im-user-service` 与 `im-message-service`
## 关键约束

- Gateway 只做横切，不做业务编排
- 所有外部 REST 路径统一保留 `/api` 前缀
- `im-access-ws` 不维护单机内存级在线状态作为最终事实，跨实例协同必须依赖 Redis
- 接入层不拥有权威持久层，只负责接入、协议转换和命令投递；权威业务数据必须由对应业务服务写入 MySQL
- 好友关系归属用户域，群归属聊天域，管理能力收敛到管理域
