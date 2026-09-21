# 重构后端与 App 首发联调方案

> 状态：待实施
>
> 适用范围：`gv_im_server`、`D:\projects\cnb\gv_chat_app`
>
> 最后核查：2026-07-31
>
> 媒体上传、读取 URL 与存储配置章节已由 [MEDIA_UPLOAD_02_APP](MEDIA_UPLOAD_02_APP.md) 和[平台媒体对象与私有访问工程规范](../standards/13_PLATFORM_MEDIA_CONVENTIONS.md)取代；本文件中与媒体 URL、`/upload`、公共 Bucket 或旧 `IM_MEDIA_*` 配置有关的描述不再作为实施依据。

## 1. 目标与决策

本项目尚未上线。本方案定义 Flutter App 新分支对接新微服务 Gateway 的**首发 REST v1 与原生 WebSocket v1** 协议；App 一次性完成正式工程改造。旧 NestJS、Socket.IO 和历史对接方案仅作为需求、页面行为和测试场景的参考，不进入运行架构，也不提供兼容、双调用或回退实现。

关键决策：

1. App 必须一次性改造实时传输层。Socket.IO 不是普通 WebSocket 的客户端封装；首发 App 唯一使用原生 WebSocket v1，不保留 Socket.IO fallback、旧地址或双协议实现。
2. App 不得直连 `3100/3200/3300/3400/3001` 等服务端口，也不得使用 `/internal/**`；所有外部请求只到 Gateway。
3. RTC 是既有必保首发能力，必须在本次实现权威 REST ICE 与 `rtc:signal` v1；消息撤回命令和 `chat:recall_notify` 也属于本次首发必保能力。输入状态、群变化、已读/删除/清空通知等其他非 RTC WebSocket 事件按当前接入能力完成后单独专项治理；本次不保留 Socket.IO 或双协议回退。
4. 首发前一次性将外部路径调整为 REST `/api/v1/**` 与实时 `/ws/im/v1`；未来破坏性变化通过新增版本演进，不能覆盖 v1 语义。
5. REST 与实时契约独立版本化，App 以构建参数选择环境，不再把家庭、办公、模拟器和生产地址写死在源码中。
6. 不兼容的是旧技术接入，不是业务行为。已实现的页面交互、状态流转、权限校验、异常提示和数据结果必须在 v1 中等价实现；路径、DTO 或传输协议变化不能成为删减业务能力的理由。

## 2. 当前基线

### 2.1 HTTP

App 已集中使用 Dio，并在 `AppConfig` 中通过 `GV_API_BASE` 组合 `/api` 前缀。这是正确的集中入口，但当前默认地址及 HTTP、WebSocket 复用同一个 base 的假设不适合首发 v1 联调。

已确认的首批 REST 差异：

完整的逐接口方法、路径、样例、错误处理与 E2E 编号以第 5.4 节“REST v1 契约冻结与交付”为实施输入；本节只保留差异摘要。

| 当前 App 调用 | 新后端现状 | App 改造 |
| --- | --- | --- |
| `/auth/register`、`/auth/login` | 路径已存在 | 对照 token、用户信息、错误码和角色字段，改为 v1 API client |
| `/users/**`、`/friends/**`、`/groups/**`、`/messages/**` | 多数路由存在 | 逐项比较 DTO、分页、枚举、空值和时间格式；不能因路径相同跳过测试 |
| `/points/me`、`/points/me/ledger` | 新端为 `/points/balance`、`/points/ledger` | 修改 App API 与积分模型映射 |
| `/rtc/ice-config` | 新端为 `/rtc/ice-servers` | 修改 API；同时等待权威 RTC 信令能力完成 |
| `/upload` 和 `/uploads/**` | 上传接口存在，需接入 OSS | 使用 OSS 受管媒体域名返回的稳定 URL，禁止拼接内部服务地址 |
| `/config/client`、`/config/app-update`、`/miniapp/services` | 新端有对应管理域能力 | 以 v1 响应模型重写解析与降级逻辑 |

### 2.2 实时协议

当前 `SocketService` 使用 `socket_io_client`，以 `auth: { token }` 建连，监听并发送 `chat:receive`、`chat:ack`、`chat:read_notify`、`chat:recall_notify`、`chat:typing`、`rtc:signal` 等 Socket.IO 事件。

首发后端使用原生 WebSocket v1：

```json
{ "event": "chat:send", "data": { "clientMsgId": "...", "toId": "..." } }
```

服务端下行也使用相同信封；首发目标握手路径为 `wss://{gateway}/ws/im/v1?ticket={urlEncodedTicket}`，ticket 由已认证 REST 请求签发并一次性消费。新端已经具备聊天发送、消息落库后的下行和好友申请/接受的一部分事件，但对 `chat:typing`、`group:join`、`group:leave` 返回“权威契约不可用”，RTC 仅返回不可用信号。因此首发必须以服务端能力验收为前置条件。

## 3. 目标 App 架构

```text
UI / Provider
    │
Feature Repository（会话、好友、群组、通话、积分）
    ├── ImApiV1（REST DTO 与 view model 转换）
    └── ImRealtimeClient（原生 WS、状态机、重连、ACK）
             │
       Gateway: HTTPS / WSS
```

UI 和 Provider 不直接理解 WebSocket 帧、URL、认证参数或服务端 DTO。它们只订阅领域事件和调用 Repository。首发只实现 `ImRealtimeClient`，避免业务页面散落传输协议判断；未来新增协议版本时也保持同一 Repository 边界。

## 4. 配置与本地开发

### 4.1 配置模型

将当前单一 `apiBase` 拆分为独立配置项：

| 构建参数 | 示例 | 用途 |
| --- | --- | --- |
| `GV_API_BASE` | `http://10.0.2.2:3002` | Gateway HTTP 根地址，不含 `/api` |
| `GV_WS_URI` | `ws://10.0.2.2:3002/ws/im/v1` | Gateway 原生 WebSocket v1 端点 |
| `GV_MEDIA_BASE` | `https://media.example.com` | 可选；媒体与 API 域名拆分时使用 |
| `GV_JPUSH_APPKEY` | 构建环境提供 | 保留现有推送配置方式 |

默认值只用于明确的本地开发基线；家庭电脑、办公电脑、Android 模拟器、真机和 CI 通过 `--dart-define` 或受管构建配置覆盖。不得将某一台电脑 IP、端口映射或临时隧道地址提交为全局默认值。

示例：

```powershell
flutter run --dart-define=GV_API_BASE=http://10.0.2.2:3002 --dart-define=GV_WS_URI=ws://10.0.2.2:3002/ws/im/v1 --dart-define=GV_MEDIA_BASE=http://10.0.2.2:9000
```

### 4.2 媒体 URL

媒体解析只接受由服务端返回的绝对媒体 URL，并且必须属于 `GV_MEDIA_BASE` 的受管域名；不得自行拼接 `/uploads/`、Gateway 或服务内部地址。开发环境使用与生产相同 URL/权限语义的本地媒体模拟实现；Release 不允许默认跳过证书校验。

本地 `GV_MEDIA_BASE` 指向 Docker MinIO 的本地受管域名，预发布和生产分别指向真实 OSS 的独立媒体域名；App 不感知 Bucket、Endpoint、Region 或访问密钥。每次发布前必须在预发布的真实 OSS 环境验证上传、私有媒体签名访问、公共媒体缓存、删除与跨端预览，不能只以 MinIO 测试结果作为上线依据。

`GV_MEDIA_BASE`、`GV_API_BASE` 和 `GV_WS_URI` 仅来自 Flutter `--dart-define` 或受管构建参数；服务端 `.env` 不会自动注入 Flutter 进程。本地真机使用开发机 LAN 可访问地址，Android 模拟器使用对应宿主机可访问地址，预发布和生产使用 HTTPS 受管域名。它们不得写死在源码或提交个人 IP；App 不读取任何 `IM_MEDIA_*`、Bucket 或 OSS 密钥参数。

## 5. REST 改造设计

### 5.1 API Client

1. 将 `AppConfig.apiPrefix` 从硬编码 `/api` 改为固定 `/api/v1`；一次性改造不保留 `GV_API_CONTRACT`、无版本路径或版本选择分支。
2. 请求统一附带 `Authorization: Bearer <token>`、`x-lang`、`X-Client-Contract: im-v1`、客户端版本和平台。
3. 将 v1 错误结构转换为单一 `ApiFailure`，包含 HTTP 状态、业务错误码、可展示消息、请求关联 ID 和是否需要重新登录。
4. 401 清除本地 token、用户快照和实时连接；403 保留登录态并显示无权限；网络错误不清除会话。
5. 所有时间、整数 ID、枚举、空数组和分页字段在 DTO 层校验，Provider 不直接对 `Map<String, dynamic>` 作业务判断。

### 5.2 Repository 与模型

- 保留当前 UI 所需的 `ImUser`、`ChatMessage`、`FriendItem`、`GroupItem`、预约等界面模型，但增加独立的 v1 transport DTO 或 converter。
- 不为旧单体字段污染界面模型；首发只保留 v1 transport DTO 和 converter。
- 分页统一使用 `items`、`page`、`pageSize`、`total` 的内部模型，服务端不同形状仅在 converter 消化；生成的 Retrofit client 与手写 `ImApi` 共享同一 DTO/错误转换边界。
- OSS 上传响应中的绝对 URL 必须原样保留到消息、头像、表情、MiniApp 和下载逻辑；移除当前将绝对 URL 截取为 path、再以 API 域名重新拼接的行为。相对媒体路径在 v1 中视为协议错误，不得继续解析。
- 对积分、RTC、上传、更新检查和小程序服务分别补充成功、空值、枚举未知值、401、403 和版本不支持的单元测试。

### 5.3 REST 改造投入顺序

1. 登录、用户资料、好友和群组查询。
2. 消息历史、离线消息、搜索、已读、撤回、清空。
3. 上传下载、贴纸、设备 token、举报、积分、版本检查、小程序服务。
4. RTC ICE 配置；仅在实时 RTC v1 用例已经后端验收后开放通话入口。

每一批以 feature flag 控制新增或尚未纳入首发范围的 v1 能力；它只控制 v1 功能入口，不承担旧版本回退或协议选择。既有交互业务不得以 feature flag 下线或降级；其后端契约未完成即为首发阻塞项。首发不保留旧版本 API，也不得在业务页面双调用。

### 5.4 REST v1 契约冻结与交付

本节是 App 改造的唯一 REST 实施与验收基线，覆盖现有 `ImApi` 的全部外部调用。所有路径均以 Gateway 基地址为根，统一前缀为 `/api/v1`；除登录、注册、版本检查外均携带 Bearer token。响应使用各服务 OpenAPI v1 快照中的 DTO，错误统一为 `{ code, message, requestId, retryable }`：`401` 清会话，`403` 保持登录并提示，`400` 显示字段错误，`409` 表示可识别的冲突或重复提交，`5xx` 可重试但不伪造成功。

每个编号必须具备成功、`401`、`403`（适用时）、参数错误、空数据及写操作重复提交测试；`E2E-*` 是首发必须通过的 App 端到端用例编号。OpenAPI 聚合快照、错误码表和本矩阵必须在同一变更中评审并发布；路径相同但样例、错误码或权限未确认的接口视为未完成，阻断对应页面联调。

| 编号 | 业务与 App 方法 | REST v1 方法与路径 | 必保行为 / E2E |
| --- | --- | --- | --- |
| A-01 | 注册 `register` | `POST /auth/register` | 用户名/密码/昵称校验、重复用户名；`E2E-AUTH-01` |
| A-02 | 登录 `login` | `POST /auth/login` | token、用户、角色、错误提示；`E2E-AUTH-02` |
| A-03 | WS ticket | `POST /auth/ws-ticket` | 已认证、一次消费、60 秒过期；`E2E-AUTH-03` |
| U-01 | 我的资料 `getMe` | `GET /users/me` | 用户资料 DTO；`E2E-USER-01` |
| U-02 | 修改资料 `updateMe` | `PUT /users/me` | 仅允许字段、更新后回显；`E2E-USER-02` |
| U-03 | 修改密码 `changePassword` | `PUT /users/me/password` | 旧密码校验、全部会话失效；`E2E-USER-03` |
| U-04 | 注销账号 `deleteAccount` | `DELETE /users/me` | 密码确认、会话清除；`E2E-USER-04` |
| U-05 | 用户详情/搜索 | `GET /users/{id}`、`GET /users/search` | 搜索词、空结果、隐私字段；`E2E-USER-05` |
| F-01 | 好友列表/待处理申请 | `GET /friends`、`GET /friends/requests/pending` | 列表/空态；`E2E-FRIEND-01` |
| F-02 | 申请/处理好友 | `POST /friends/request`、`PUT /friends/request/{id}` | `toUserId`、`message`、`action` 枚举与重复操作；`E2E-FRIEND-02` |
| F-03 | 删除/备注/拉黑好友 | `DELETE /friends/{id}`、`PUT /friends/{id}`、`POST /friends/{id}/block` | 权限与最终关系状态；`E2E-FRIEND-03` |
| G-01 | 建群/我的群 | `POST /groups`、`GET /groups/mine` | 名称、成员、空态；`E2E-GROUP-01` |
| G-02 | 群详情/成员 | `GET /groups/{id}`、`GET /groups/{id}/members` | 成员可见性；`E2E-GROUP-02` |
| G-03 | 改群/成员管理/退出/解散 | `PUT /groups/{id}`、`POST /groups/{id}/members`、`DELETE /groups/{id}/members/{userId}`、`POST /groups/{id}/leave`、`DELETE /groups/{id}` | 群主/管理员权限、解散后的 REST 状态；`E2E-GROUP-03` |
| M-01 | 会话历史与锚点历史 | `GET /messages/history` | `peerId`、`chatType`、`beforeMsgId`、`afterMsgId`、`centerMsgId`、`beforeCount`、`afterCount`、`pageSize` 一次性支持；`E2E-MSG-01` |
| M-02 | 离线/未读/搜索 | `GET /messages/offline`、`GET /messages/unread-count`、`GET /messages/search` | cursor/分页/筛选/空态；`E2E-MSG-02` |
| M-03 | 已读/删除/清空 | `POST /messages/read`、`POST /messages/delete-for-everyone`、`POST /messages/clear-private`、`POST /messages/clear-group` | 命令幂等与最终查询状态；`E2E-MSG-03` |
| M-04 | 撤回消息 | `POST /messages/recall` | 发送者、时限与会话成员校验；本端和其他在线端同步为撤回状态；`E2E-MSG-04` |
| R-01 | ICE 配置 | `GET /rtc/ice-servers` | TURN 地址、临时凭据有效期；`E2E-RTC-01` |
| O-01 | 上传文件/字节 | `POST /upload?scope=chat|profile` | multipart、进度、受管绝对 URL、大小/MIME/扫描拒绝；`E2E-MEDIA-01` |
| O-02 | 下载/预览 | 服务端返回的绝对 OSS URL | 公共缓存、私有签名 URL、过期与越权；`E2E-MEDIA-02` |
| C-01 | 客户端能力 | `GET /config/client` | 既有能力首发必须启用；`E2E-CONFIG-01` |
| S-01 | 我的表情 | `GET/POST /user-stickers`、`DELETE /user-stickers/{id}` | URL 归属、重复、删除；`E2E-STICKER-01` |
| X-01 | 举报 | `POST /reports` | `targetId`、原因枚举、证据 URL；`E2E-REPORT-01` |
| P-01 | 推送设备 token | `POST/DELETE /device-tokens` | provider/sdk/token 幂等、解绑；`E2E-PUSH-01` |
| N-01 | 服务板块服务项 | `GET /miniapp/services` | 公开读（无参数）；返回已发布（status=true）服务项，按类型分组（typeName+items），置顶优先；字段 `id/typeId/name/link/introduction/iconObjectId/iconUrl/isTop/sortOrder`；空态；`E2E-MINIAPP-01` |
| Q-01 | 积分余额/流水 | `GET /points/balance`、`GET /points/ledger` | 流水分页、`entryType` 枚举；`E2E-POINTS-01` |
| B-01 | 预约类型/门店/配置 | `GET /reservations/service-types`、`GET /reservations/stores`、`GET /reservations/config` | 类型、门店、空态与可预约时段；`E2E-RESERVATION-01` |
| B-02 | 创建/我的预约/预约详情 | `POST /reservations`、`GET /reservations/me`、`GET /reservations/me/{orderNo}` | 联系人、手机号、时间、人数、状态、重复提交与积分结算结果；`E2E-RESERVATION-02` |
| V-01 | 版本检查 | `GET /config/app-update` | sdk/version、强更/下载地址；`E2E-UPDATE-01` |

> **服务板块（小程序服务项）C 端契约**：入口为 App 头部 A380 板块 → 服务页；数据源为公开读接口 `GET /api/v1/miniapp/services`（Gateway 前缀，无认证），只返回已发布（status=true）服务项，按类型分组、置顶优先。字段以 im-admin-service `MiniappServiceItem` 为准（`link`/`icon`/`status`/`isTop`/`sortOrder`；C 端读返回 `iconObjectId`+`iconUrl`）。外部服务（打车等）注册为服务项的完整规范见 [IM_OPEN_PLATFORM_04_SERVICE](IM_OPEN_PLATFORM_04_SERVICE.md)。

REST 与 WS 的职责在本矩阵中冻结：已读、删除、清空和撤回命令仅走上述 REST 权威命令，不得对同一业务同时发送 REST 与 WS 命令。`chat:recall_notify` 是撤回后服务端唯一的首发下行通知，用于其他在线端立即同步，不是第二个撤回命令。`chat:read`、`chat:delete_notify`、清空通知、`chat:typing`、好友/用户状态/群变化、`reservation:verified` 与 `point:changed` 的跨端实时通知，均留待 v1 接入完成后的专项治理，不回退 Socket.IO。

以下首发决策已冻结，必须写入 OpenAPI、服务端配置和测试用例：RTC 仅支持一对一音频/视频，不含群通话、屏幕共享和通话记录；`chat`/`profile` 默认写入 private Bucket，仅明确公开的资源写入 public Bucket；上传限制固定为图片 10 MiB、附件 50 MiB、视频 200 MiB，并采用 `.env.example` 中的 MIME 白名单（`image/jpeg,image/png,image/webp`；`application/pdf,text/plain,application/zip`；`video/mp4`）。任何变更均须先更新本节及对应 OpenAPI、环境模板和 E2E，再实施变更。

私有媒体固定使用 OSS 短时签名 `GET` URL，默认有效期 15 分钟；对象元数据和授权由服务端保存，客户端通过 `GET /media/{objectId}/access` 重新获取 URL。该接口返回 `{ "objectId": "uuid", "url": "https://...", "expiresAt": "RFC3339", "scope": "private" }`，仅资源所有者、会话合法成员或具备后台授权的管理员可调用。上传响应的私有资源必须同时返回 `objectId` 与 `expiresAt`；客户端不得把过期 URL 当作永久媒体地址缓存。

#### 5.4.1 字段、样例与错误码冻结规则

所有写操作都携带 `Idempotency-Key: <uuid>`；消息类请求额外携带稳定的 `clientMsgId`（仅 WS 发送消息使用）。列表响应固定为 `{ "items": [], "page": 1, "pageSize": 20, "total": 0, "updatedAt": "RFC3339" }`，游标列表使用 `{ "items": [], "nextCursor": "string|null", "hasMore": false, "updatedAt": "RFC3339" }`。非列表成功响应固定为 `{ "data": {} , "requestId": "uuid" }`；无主体成功响应为 `204`。所有失败响应固定为 `{ "code": "...", "message": "...", "requestId": "uuid", "retryable": false, "fieldErrors": {} }`。

| 编号 | 冻结请求字段与成功 `data` 字段 | 专属错误码 |
| --- | --- | --- |
| A-01 | `{username,password,nickname}` -> `{user:{id,username,nickname,roles},accessToken,expiresAt}` | `AUTH_USERNAME_EXISTS` |
| A-02 | `{username,password}` -> `{user:{id,nickname,roles},accessToken,expiresAt}` | `AUTH_INVALID_CREDENTIALS`,`AUTH_ACCOUNT_DISABLED` |
| A-03 | 空 body -> `{ticket,expiresAt}`；ticket 仅消费一次 | `AUTH_WS_TICKET_EXPIRED`,`AUTH_WS_TICKET_CONSUMED` |
| U-01~U-05 | 更新 `{nickname,avatarObjectId?,bio?}`；改密 `{oldPassword,newPassword}`；注销 `{password}`；搜索 `q,page,pageSize` -> `ImUser{id,username,nickname,avatarUrl?,status}` | `USER_NOT_FOUND`,`USER_PASSWORD_INCORRECT`,`USER_SEARCH_FORBIDDEN` |
| F-01~F-03 | 申请 `{toUserId,message?}`；处理 `{action:accept|reject}`；备注 `{remark?}`；拉黑 `{blocked:boolean}` -> `FriendRelation{id,userId,friendId,status,remark?,createdAt}` | `FRIEND_ALREADY_EXISTS`,`FRIEND_REQUEST_NOT_FOUND`,`FRIEND_RELATION_FORBIDDEN` |
| G-01~G-03 | 建群 `{name,memberIds}`；改群 `{name?,avatarObjectId?,notice?}`；加成员 `{userIds}` -> `Group{id,name,ownerId,memberCount,role,updatedAt}` | `GROUP_NOT_FOUND`,`GROUP_MEMBER_EXISTS`,`GROUP_PERMISSION_DENIED` |
| M-01~M-02 | 历史查询 `peerId,chatType,beforeMsgId?,afterMsgId?,centerMsgId?,beforeCount?,afterCount?,pageSize`；搜索 `keyword,chatType?,peerId?,cursor?` -> `Message{msgId,clientMsgId?,chatType,fromUserId,toId,content,msgType,status,seq,createdAt}` | `MESSAGE_NOT_FOUND`,`MESSAGE_CURSOR_INVALID` |
| M-03 | 已读 `{msgIds}`；删除 `{msgId}`；私聊清空 `{peerId}`；群清空 `{groupId}` -> `204` 或 `{affectedCount}` | `MESSAGE_DELETE_FORBIDDEN`,`MESSAGE_CLEAR_FORBIDDEN` |
| M-04 | `{msgId}` -> `{msgId,status:"recalled",recalledAt}`；服务端随后发 `chat:recall_notify` | `MESSAGE_RECALL_FORBIDDEN`,`MESSAGE_RECALL_EXPIRED`,`MESSAGE_ALREADY_RECALLED` |
| R-01 | 空 body -> `{iceServers:[{urls,username?,credential?}],expiresAt}` | `RTC_ICE_UNAVAILABLE` |
| O-01~O-02 | multipart `file,scope` -> `{objectId,url,scope,contentType,size,expiresAt?}`；私有访问为 `{objectId}` -> 签名 URL | `MEDIA_TYPE_NOT_ALLOWED`,`MEDIA_SIZE_EXCEEDED`,`MEDIA_SCAN_REJECTED`,`MEDIA_ACCESS_DENIED` |
| C-01 / V-01 | `platform,version,buildNumber?` -> `capabilities` / `{version,forceUpdate,downloadUrl?,releaseNotes?}` | `CLIENT_VERSION_UNSUPPORTED` |
| S-01 / X-01 / P-01 | 表情 `{objectId}`；举报 `{targetId,reason,evidenceObjectIds?}`；设备 `{provider,platform,token}` -> 对象或 `204` | `STICKER_QUOTA_EXCEEDED`,`REPORT_DUPLICATE`,`DEVICE_TOKEN_CONFLICT` |
| N-01 / Q-01 | 服务板块 `GET /miniapp/services`（公开、无参数）-> 分组服务项；积分 `page,pageSize` -> `PointBalance{available}`、`PointLedger{entryType,amount,balanceAfter,createdAt}` | `POINTS_ENTRY_TYPE_INVALID` |
| B-01~B-02 | 创建 `{serviceTypeId,storeId,contactName,phone,startAt,peopleCount}`；列表 `page,pageSize,status?` -> `Reservation{orderNo,status,pointsResult?,updatedAt}` | `RESERVATION_SLOT_UNAVAILABLE`,`RESERVATION_STATUS_INVALID`,`RESERVATION_DUPLICATE` |

通用错误码固定为：`VALIDATION_ERROR`（400）、`AUTH_TOKEN_INVALID`（401）、`AUTH_TOKEN_EXPIRED`（401）、`FORBIDDEN`（403）、`RESOURCE_NOT_FOUND`（404）、`IDEMPOTENCY_CONFLICT`（409）、`RATE_LIMITED`（429）和 `INTERNAL_ERROR`（500）。每项 OpenAPI 操作必须引用本表的字段和错误码；未列字段只能以向后兼容的可选字段新增。

## 6. 原生 WebSocket 改造设计

### 6.1 传输替换

移除对 `socket_io_client` 的业务依赖，使用 Flutter 原生 WebSocket 能力或经批准的轻量 WebSocket 包实现 `ImRealtimeClient`。传输层必须实现：

- 建连、关闭、网络切换、前后台切换和 token 变更后的重连；
- JSON 编解码和严格信封校验；
- 心跳、超时、指数退避和带抖动的重连；
- 单连接顺序发送、重复帧容忍和关闭时资源释放；
- 不将 WebSocket 内部异常直接抛到 UI 线程。

WebSocket 鉴权固定采用短时、一次性 ticket：App 先以 Bearer token 调用 `POST /api/v1/auth/ws-ticket`，服务端返回有效期不超过 60 秒、只可消费一次的 `ticket`；随后连接 `wss://{gateway}/ws/im/v1?ticket={urlEncodedTicket}`。Gateway、接入服务、反向代理和客户端日志必须脱敏 ticket，握手成功即消费并失效；不在 URL 中传递 access token。ticket 获取失败、过期或被重复消费时返回明确错误并要求 App 重新获取，不得回退 Socket.IO 或旧地址。

### 6.2 事件契约

以下表格记录全量实时事件。仅标记为“本次必保”的事件属于本次 App 首发门槛；标记为“专项治理”的事件不得双调用或回退旧协议，待 v1 接入完成后单独冻结 Schema 和投递语义。

所有帧固定为 `{ "version": "v1", "event": "...", "eventId": "uuid", "requestId": "uuid", "sentAt": "RFC3339", "data": {} }`；下行事件额外携带可排序的会话 `seq`（适用时）和业务对象 ID。`eventId` 用于传输去重，`clientMsgId` 用于消息命令幂等，两者不得混用。事件 Schema、权限、必填字段、枚举、最大长度、错误码和成功样例必须发布到 `protocol-ws` 的 JSON Schema 与 AsyncAPI 快照。

心跳固定为客户端每 30 秒发送一次，连续两个周期未收到心跳回包或任一有效下行帧即断开并按退避策略重连；服务端统一使用关闭码 `4001`（ticket 无效/过期）、`4003`（无权限）、`4008`（心跳超时）和 `1011`（可重试的服务端错误）。`error` 帧必须包含 `code`、`message`、`requestId`、`retryable`；消息发送 ACK 超时、重试次数和离线同步 cursor 的具体值写入事件 Schema，并由契约测试覆盖。

| 事件 | 方向 | App 行为 | 服务端完成 |
| --- | --- | --- | --- |
| `heartbeat` | 双向 | 30 秒心跳，收到回包更新活性 | 已有基础能力；需定义超时和关闭码 |
| `chat:send` | 上行 | 带稳定 `clientMsgId` 发送 | 已有基础能力；需验证幂等 |
| `chat:ack` | 下行 | 将 optimistic message 绑定正式 `msgId` | 已有基础能力；需处理 ACK 超时 |
| `chat:receive` | 下行 | 去重、落入会话、更新未读 | 已有基础能力；需验证多端和群聊顺序 |
| `chat:read` / `chat:read_notify` | 双向/下行 | 更新已读状态 | 本次命令只走 REST；实时投递列入专项治理 |
| `chat:recall_notify` | 下行 | 以 `msgId` 更新本地消息为撤回状态 | 本次必保；撤回命令仅走 `POST /messages/recall`，通知必须带 `msgId`、`conversationId`、`recalledBy`、`recalledAt` 和 `seq` |
| `chat:delete_notify`、清空通知 | 下行 | 删除或清空本地会话缓存 | 本次命令只走 REST；实时投递列入专项治理 |
| `chat:typing` | 双向 | 临时输入提示，超时自动消失 | 专项治理 |
| 好友申请/接受 | 下行 | 刷新申请或好友列表 | 专项治理 |
| 用户状态、群变化、群解散 | 下行 | 更新通讯录和会话 | 专项治理 |
| 预约核销、积分变更 | 下行 | 刷新预约详情和积分余额/流水 | 专项治理 |
| `rtc:signal` | 双向 | 一对一音频/视频通话状态机和信令交换 | 本次必保；后端必须实现并冻结 Schema |
| `error` | 下行 | 映射为可恢复/不可恢复错误 | 须冻结错误码和是否可重试 |

### 6.3 发送、ACK 与断线恢复

1. 发送消息前生成 UUID 形式的 `clientMsgId`，在重发、ACK 和本地消息去重中始终使用同一个值。
2. optimistic UI 可以先显示“发送中”，但只有收到 `chat:ack` 或后续 `chat:receive` 后才更新为成功。
3. ACK 超时后进入“待重试”状态；重连后以原 `clientMsgId` 重发，禁止生成新的业务消息。
4. 收到下行消息时以 `msgId` 去重；同一会话按服务端 `seq` 排序，不能依赖本机时间戳。
5. 重连成功后调用离线消息/会话同步接口补齐断线窗口；同步与实时下行并发时仍按 `msgId` 去重。
6. token 被清除、账号切换或服务端关闭认证失败时，立即销毁旧连接和待发送上下文，防止 A 用户以 B 用户连接发送消息。

### 6.4 能力开关

App 从 `/config/client` 或单独的能力契约读取 v1 能力状态。既有业务对应的能力在首发必须为启用状态；服务端不支持即阻断发布，不能以开关隐藏。能力开关仅适用于产品新增且明确不属于首发范围的能力；禁止发送一个已知会返回“不可用”的实时事件。

能力开关不是安全控制。服务端仍必须验证用户身份、好友关系、群成员身份、禁言、消息内容和 RTC 呼叫状态。

## 7. 首发发布与长期演进

### 7.1 首发发布批次

1. 冻结 REST v1、WebSocket v1、错误码和能力开关契约，并完成后端实现。
2. 发布 App 内测版，先验证登录、基础聊天、断线恢复、上传和推送。
3. 对新增且明确不属于首发范围的能力可独立灰度；所有既有交互业务在首发前完成全量 v1 验收并默认启用，所有能力均使用 v1 协议。
4. 观察崩溃率、连接成功率、重连次数、消息 ACK 延迟、重复消息率、离线补偿量、媒体失败率和 401/403 比例。
5. 达到验收阈值后扩大首发范围；首发后冻结 v1 破坏性变更。

### 7.2 后续版本演进

- 修复缺陷、增加可选字段、增加新事件可在 v1 内完成，但必须保持已有字段和语义不变。
- 删除字段、修改枚举含义、改变 ACK/幂等规则、改变认证握手或重构事件载荷时，新增 REST v2 和/或 WebSocket v2；是否需要多版本并行只能在未来另行评审，当前 App 新分支不预埋相关实现。
- App Repository 以版本化 transport DTO 隔离变化；UI 只依赖稳定界面模型。

## 8. 文件级改造清单

| 位置 | 改造内容 |
| --- | --- |
| `lib/core/env_config.dart`、`lib/core/config.dart` | 移除个人 IP 与 `/api`/Socket 复用默认值；新增独立 `GV_API_BASE`、`GV_WS_URI`、`GV_MEDIA_BASE` 的 dart-define 解析，并固定 REST `/api/v1` |
| `lib/im-services/api_client.dart` | v1 base path、公共头、统一 `ApiFailure`、401/403 与认证失效处理 |
| `lib/im-services/generated_im_api_client.dart`、`.g.dart` | 按 OpenAPI v1 更新 Retrofit 注解和 DTO，运行 build_runner 重新生成；覆盖积分、ICE、预约与所有 REST 路径 |
| `lib/im-services/im_api.dart`、`lib/repositories/*.dart` | REST v1 路由与 DTO converter、分页、预约和媒体解析；移除同一业务 REST/WS 双调用 |
| `lib/im-services/socket_service.dart` | 以原生 `ImRealtimeClient` 替换 Socket.IO；保留 `GvSocketClient` 仅作 App 内部抽象，不能保留 Socket.IO 协议行为 |
| `packages/gv_core/lib/src/media/media_url.dart` 及调用点 | 保留 OSS 绝对 URL；拒绝相对媒体路径，改用 `GV_MEDIA_BASE` 仅校验受管域名，不以 API Base 重写 |
| `lib/providers/call_provider.dart` | 解包 WS v1 信封并按 `rtc:signal` Schema 处理一对一音视频通话 |
| `lib/providers/chat_provider.dart` | 对接事件流；实现 ACK、去重、重连同步和发送失败状态 |
| `lib/providers/call_provider.dart` | 仅在 RTC v1 契约完成后替换信令；未完成前由能力开关关闭 |
| `lib/models/*.dart` | UI model 与 transport DTO 分层；补齐未知枚举/可选字段保护 |
| `test/` | 增加 API converter、WS 信封、重连/幂等、401/403 和媒体 URL 测试 |

## 9. 验收、回滚与完成定义

### 9.1 联调验收

| 场景 | 验收标准 |
| --- | --- |
| 业务一致性回归 | 以现有 App 页面和交互清单逐项核验：入口、输入校验、权限、成功/失败反馈、状态流转和最终数据结果均与既有业务定义一致；仅允许已评审的产品变更 |
| 登录与换号 | 新 token 可访问 REST/WS；换号后旧连接完全关闭 |
| 私聊与群聊 | 发送、ACK、接收、离线补偿、顺序和去重正确 |
| 已读、撤回、删除、清空 | 多端一致，断线恢复后状态正确 |
| 好友与群事件 | 目标客户端正确刷新，无未授权事件泄漏 |
| 通话 | 信令、ICE、拒接、忙线、挂断、多端同步和异常关闭均验证 |
| 上传下载 | 图片、文件、音视频在真机和多实例环境可访问 |
| 异常网络 | 飞行模式、弱网、后台恢复、服务重启和 token 失效可恢复或明确失败 |
| 安全 | 日志不包含 token/密码/消息正文；非法事件和无权限操作均被服务端拒绝 |

### 9.2 回滚


- App v1 仅对新增且未纳入首发范围的能力使用 feature flag，不实现多协议双写或旧单体回退。
- 发现既有业务的首发后端问题时，停止放量并回滚本系统的服务/配置制品；不得关闭既有能力来替代修复。新增能力可按 feature flag 单独关闭，App 始终保持同一 v1 契约。
- 数据恢复遵循后端的数据库备份与迁移恢复预案；App 侧仅负责安全地保留本地待同步状态和提示用户重试。

完成证据包括：以现有页面和交互清单为基线的业务一致性矩阵、全量 REST v1 OpenAPI/错误码快照、WebSocket v1 JSON Schema/AsyncAPI 快照、Gateway v1 路由与 WS ticket 集成测试、Flutter 单元与集成测试结果、真机弱网记录、消息一致性抽样报告、RTC 验收记录和恢复演练报告。上述任一既有业务条目缺少契约、后端实现或 E2E 通过记录，均不得发布。
