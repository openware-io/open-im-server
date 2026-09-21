# 多端登录与设备管理方案（参考微信）

> **变更记录（v1）**：首发创建。桌面端引入后，IM 账号需支持多端同时在线与设备管理，参考微信「主设备 + 副设备扫码授权」模型。

## 0. 方案集声明

| 项目 | 内容 |
| --- | --- |
| 方案集 | `MULTI_DEVICE_LOGIN` |
| 顺序号 | `01` |
| 实施边界 | `SERVICE`（服务端会话/设备/扫码授权） |
| 前置方案 | [架构总览](../ARCHITECTURE.md)、IM 账号域（`im-user-service`） |
| 后置方案 | `MULTI_DEVICE_LOGIN_02_APP`（手机/桌面端交互与扫码页）、`MULTI_DEVICE_LOGIN_03_TEST`（多端联调与验收） |
| 目标工程 | `im-user-service`（会话/设备/扫码）、`im-access-ws`（长连接路由）、`gateway`（鉴权） |

## 1. 背景与目标

桌面端（`gv_chat_desktop`）引入后，同一 IM 账号需要在手机、桌面等多台设备同时在线，并支持设备管理。目标：

1. 多端同时在线，消息实时同步，互不挤下线。
2. 主设备（手机）权威，副设备（桌面/平板/Web）扫码授权登录。
3. 设备可见、可管理、可踢出；登录安全（设备级令牌，撤销即失效）。
4. 兼容现有 E2EE（设备公钥多端注册）。

## 2. 设备分级模型

| 设备类型 | 角色 | 登录方式 | 典型 |
| --- | --- | --- | --- |
| MOBILE | 主设备 | 账号 + 密码/验证码 | 手机 App |
| DESKTOP | 副设备 | 账密直登（允许）+ 扫码授权（可选） | Windows/macOS 桌面端 |
| TABLET | 副设备 | 扫码授权 | iPad |
| WEB | 副设备 | 扫码授权 | Web |

主/副设备的区别只在「登录方式」与「管理权限」：主设备可查看/踢出副设备，副设备只能管理自身。业务语义与消息能力完全一致。

## 3. 登录流程

### 3.1 主设备直登（MOBILE）

```text
手机端输入账号 + 密码/验证码
  → 服务端校验凭据
  → 签发 access token + refresh token（绑定 account_id + device_id + device_type=MOBILE）
  → 注册设备（device_type / device_name / public_key）
  → 建立 WS 长连接
```

### 3.2 副设备登录（DESKTOP/TABLET/WEB）

副设备两种登录方式：

**账密直登（DESKTOP 允许）**：桌面端输入账号 + 密码 → 服务端校验 → 签发副设备 session（device_type=DESKTOP）。桌面端属可信设备（用户自装），允许账密直登，可选开启二次验证（验证码）。

**扫码授权（可选便捷）**：桌面/平板/Web 展示二维码 → 主设备扫码确认，避免在公共/不可信设备输入密码。

```text
桌面端 GET /auth/qrcode → 返回 login_code（短有效期，默认 2 分钟）+ 二维码
桌面端展示二维码，轮询/WS 订阅登录结果
手机端（已登录）扫码 → POST /auth/qrcode/confirm {login_code}
  → 服务端校验 login_code 有效 + 手机端已登录
  → 手机端展示确认页（设备名/类型）
手机端批准 → POST /auth/qrcode/confirm/approve {login_code}
  → 服务端签发副设备 session（account_id + device_id + device_type=DESKTOP）
  → 桌面端收到登录结果 → 换取 access/refresh token → 建立 WS
```

安全要求：登录码一次性、短有效期、绑定展示端；确认必须由已登录主设备完成，不可由未登录客户端自证。

## 4. 会话与令牌

- 每台设备一个 session：`(account_id, device_id, device_type, device_name, session_token, last_active_at, status)`。
- access token 短效（默认 30 分钟）+ refresh token 按设备撤销；refresh 换新时校验设备未被踢出。
- 踢出/登出/账号禁用 → 撤销对应设备（或全部）session 与 refresh token；WS 会话立即断开。
- 同 device_id 重复登录复用 session（刷新 last_active_at），不重复建 session。

## 5. 设备管理

- 手机端「登录设备管理」列出当前账号所有 session（类型/名称/登录时间/最后活跃/当前设备标记）。
- 主设备可踢出任意副设备；被踢后 token 失效、WS 断开、需重新扫码。
- 副设备可自行退出（只影响自身）。
- 主设备退出可选「仅本机退出」或「退出所有设备」（参考微信：手机退出登录，副设备一并退出）。

## 6. 消息多端同步

- 多端在线：消息经 WS 实时下行到所有在线设备（`im-access-ws` 多端路由）。
- 副设备首登：拉取最近消息历史（漫游），补齐离线期间消息（按 seq/时间游标）。
- 已读回执、会话列表、置顶/免打扰等会话状态多端同步（服务端权威，多端拉取同一投影）。
- 离线消息：各端上线后各自拉增量，按 seq 幂等，不重不漏。

## 7. E2EE 多端

- 既有 `user_device_key`（设备公钥）已支持每设备一个公钥；副设备登录时注册自身公钥（客户端生成，服务端只存转发）。
- 私密聊天/密群按「接收方所有在线设备公钥」分别加密，各端以自身私钥解密（Signal 多设备模型）。
- 设备被踢出/注销 → 撤销该设备公钥，后续消息不再向该设备加密。

## 8. 数据表设计

| 表 | 用途 | 关键字段/说明 |
| --- | --- | --- |
| `user_device_session`（新增） | 设备会话权威 | account_id、device_id、device_type、device_name、session_token、last_active_at、status（ACTIVE/KICKED/LOGOUT）、created_at；唯一 `(account_id, device_id)` |
| `user_device_token`（既有） | 推送令牌 | 复用，增补 device_type/device_name |
| `user_device_key`（既有） | E2EE 设备公钥 | 复用，增补 device_type |

## 9. API 契约

| 方法与路径 | 用途 |
| --- | --- |
| `GET /auth/qrcode` | 副设备取登录码 + 二维码 |
| `POST /auth/qrcode/confirm` | 主设备扫码后确认 |
| `POST /auth/qrcode/confirm/approve` | 主设备批准登录 |
| `GET /devices` | 当前账号设备列表 |
| `POST /devices/{deviceId}/kick` | 主设备踢出副设备 |
| `POST /devices/{deviceId}/logout` | 副设备自退出 |
| `POST /devices/logout-all` | 退出所有设备 |

## 10. 验收

- 手机直登 + 桌面扫码登录均建立独立 session；多端同时在线互不挤下线。
- 主设备可踢出副设备；踢出后副设备 token 失效、WS 断开、重新扫码才可登录。
- 消息实时多端同步；副设备登录后拉历史消息不重不漏。
- E2EE 私密消息按多设备公钥加密，各端可解密；踢出设备后不再收密文。
- 登录码一次性、短有效期、过期/重复确认均拒绝。

## 11. 变更清单

- 服务端：`im-user-service`（会话/设备/扫码）、`im-access-ws`（多端路由）、`gateway`（鉴权）。
- OpenAPI：新增 `auth/qrcode`、`devices` 系列。
- Flyway：新增 `user_device_session`，扩展 `user_device_token`/`user_device_key`。
- 客户端：App 扫码确认页 + 设备管理页；桌面端扫码登录页 + 消息漫游拉取。

> 说明：本方案针对 IM 产品（聊天）的 C 端多端登录。SaaS 产品（B 端 App / Admin）的多端登录属账号+密码直登 + 会话管理（无扫码），其设备级撤销与「单端登录」策略可在 IAM 会话方案中单独定义，不混入 IM 聊天设备模型。
