# im-conversation-service

## 当前群聊语义

- 群角色为 `OWNER`、`ADMIN`、`MEMBER`；群主和管理员可修改群资料，普通成员邀请默认允许并可由群资料开关关闭。
- 群创建和邀请均要求目标用户为 ACTIVE；入群容量在群行锁内校验，默认上限为 500。
- 群主离群时优先转让给最早入群的管理员，再按最早入群成员兜底；没有继任者时解散群。
- 成员变更发布授权事件供 WebSocket 刷新；成员列表批量补齐用户名和头像。
- 完整规则见 [聊天可靠性与群治理服务端改造说明](../../renovation/CHAT_RELIABILITY_01_SERVICE.md)。

## 频道语义（单向发布/订阅）

- 频道是"一对多单向广播"会话：仅频道主（owner）可发布消息，订阅者只读。
- 订阅校验基于会话元数据（`isOwner || isSubscribed`），消息服务通过内部接口查询授权。
- 创建频道返回 `myRole/subscribed/memberCount`；删除消息仅 owner 可执行。
- 完整设计见 [频道域服务端设计](../../features/CHANNEL_01_SERVICE.md)。

## 私密聊天语义（E2EE）

- 私密聊天是一对一 E2EE 会话：`userA/userB` 规范化存储（小号在前），同一对用户唯一。
- 握手状态机：`pending -> ready`，双方提交公钥后计算 SHA-256 安全码指纹；`safeCode` 展示用，真加密后由客户端本地计算。
- 销毁策略：`off/30s/5m/1h/1d`，参与者可设置；消息服务通过内部接口读取策略驱动定时销毁。
- 完整设计见 [私密聊天与 E2EE 服务端设计](../../features/SECRET_CHAT_01_E2EE.md)。

## 职责

- 群组管理
- 群成员管理
- 群角色与禁言
- 频道管理（创建/订阅/角色/成员数）
- 私密聊天会话管理（创建/握手/销毁策略）
- RTC ICE 配置查询

## 当前接口

- `/groups/**`
- `/channels/**`
- `/secret-chats/**`
- `/rtc/**`
- 内部接口 `/internal/channels/**`、`/internal/secret-chats/**`（服务间鉴权，供消息服务查询授权与销毁策略）

## 说明

- 群属于聊天域，不归用户域管理
- 频道、私密聊天同属会话域，由本服务承载会话元数据与授权；消息内容与密文由 `im-message-service` 承载
- 媒体上传、对象访问授权和业务引用登记由 `common-media-service` 统一提供；本服务仅在业务规则中校验会话成员资格
