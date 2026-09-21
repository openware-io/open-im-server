# im-admin 批次 0 HTTP 兼容矩阵

本文记录批次 0 的历史兼容矩阵。客户端发布治理已完成一次性切换：旧发布路由不再兼容，其余路由仍按本矩阵保持稳定。

| 路径前缀 | 用途 | 鉴权基线 | 兼容要求 |
| --- | --- | --- | --- |
| `/admin/**` | 管理后台用户、好友、消息、积分、配置、版本、小程序、安全、监控和统计 | `ADMIN` | 保持原路径、方法、分页参数、请求字段和响应形状 |
| `/client/release-check` | 客户端版本检查 | 公开 | `POST`；返回明确升级决策，旧 `/config/app-update` 已移除 |
| `/config/client/**` | 客户端功能开关 | 公开 | 保持现有功能键和布尔值语义 |
| `/reports` | 客户端创建举报 | 已认证 | 保持请求体、当前操作人获取方式和返回字段 |
| `/miniapp/im-services/**` | 客户端小程序服务查询 | 公开 | 保持已发布服务列表响应字段 |

| 路径 | 方法 | 控制器 | 当前响应类型 |
| --- | --- | --- | --- |
| `/admin/users` | GET | `AdminUserController` | `PageResult<User>` |
| `/admin/users/{id}` | GET | `AdminUserController` | `User` |
| `/admin/users/{id}/status` | PUT | `AdminUserController` | `Map<String, Object>` |
| `/admin/users/{id}` | DELETE | `AdminUserController` | `AdminUserDeleteResponse`（级联计数）；IM 侧口径：不能删自己 409 `CANNOT_DELETE_SELF`、管理员账号 409 `ADMIN_ACCOUNT_UNDELETABLE`、用户名不一致 400 `USERNAME_CONFIRM_MISMATCH`、账号不存在 404 `USER_NOT_FOUND`（不引入 SaaS 域门禁） |
| `/admin/audit-logs` | GET | `AdminAuditLogController` | `AdminAuditLogPage`（透传 `common-audit-service`，与 SaaS 后台同一套审计） |
| `/admin/friends` | GET | `AdminFriendController` | `PageResult<Friend>` |
| `/admin/messages` | GET | `AdminMessageController` | `PageResult<Message>` |
| `/admin/device-tokens/{userId}` | GET | `AdminDeviceTokenController` | `List<DeviceToken>` |
| `/admin/user-stickers` | GET | `AdminUserStickerController` | `PageResult<UserSticker>` |
| ~~`/admin/points/accounts/{userId}/balance`~~ | ~~GET~~ | 已移除（2026-09-10） | 积分能力已迁至 SaaS：见 `platform-customer-service` `MemberController` `/members/{id}/points`、`POST /members/{id}/points/adjust`，后台页面为 `gv_saas_admin` `views/tenant/points.vue` |
| ~~`/admin/points/accounts/{userId}/ledger`~~ | ~~GET~~ | 已移除（2026-09-10） | 同上；积分流水在 SaaS 侧会员积分明细 |
| ~~`/admin/points/accounts/{userId}/credit`~~ | ~~POST~~ | 已移除（2026-09-10） | 同上；加积分统一走 SaaS `POST /members/{id}/points/adjust` |
| ~~`/admin/points/accounts/{userId}/debit`~~ | ~~POST~~ | 已移除（2026-09-10） | 同上；扣积分统一走 SaaS `POST /members/{id}/points/adjust` |
| ~~`/admin/coins/accounts/**`~~ | ~~GET, POST~~ | 已移除（2026-09-10） | 代币/钱包能力已迁至 SaaS：`gv_saas_admin` `views/tenant/wallet.vue` |
| `/admin/config` | GET, PUT | `AdminConfigController` | `List<SystemConfig>`, `SystemConfig` |
| `/admin/config/batch` | POST | `AdminConfigController` | `Map<String, Object>` |
| `/admin/client-releases` | GET, POST | `ClientReleaseController` | `PageResult<ClientRelease>`, `{data,requestId}` |
| `/admin/client-releases/{id}` | GET | `ClientReleaseController` | `{data,requestId}` |
| `/admin/miniapp/service-types` | GET, POST | `AdminMiniappServiceTypeController` | `List<MiniappServiceType>`, `MiniappServiceType` |
| `/admin/miniapp/service-types/{id}` | PUT, DELETE | `AdminMiniappServiceTypeController` | `MiniappServiceType`, empty |
| `/admin/miniapp/service-types/sort` | POST | `AdminMiniappServiceTypeController` | `Map<String, Boolean>` |
| `/admin/miniapp/services` | GET, POST | `AdminMiniappServiceItemController` | `PageResult<MiniappServiceItem>`, `MiniappServiceItem` |
| `/admin/miniapp/im-services/{id}` | PUT, DELETE | `AdminMiniappServiceItemController` | `MiniappServiceItem`, empty |
| `/admin/security/sensitive-words` | POST | `AdminSecurityController` | `SensitiveWord` |
| `/admin/security/sensitive-words/{id}` | PUT, DELETE | `AdminSecurityController` | `SensitiveWord`, empty |
| `/admin/security/violations` | GET, POST | `AdminSecurityController` | `PageResult<Violation>`, `Violation` |
| `/admin/security/reports/{id}` | PUT | `AdminSecurityController` | `Report` |
| `/admin/monitor/online` | GET | `AdminMonitorController` | `Map<String, Object>` |
| `/admin/stats/overview` | GET | `AdminStatsController` | `Map<String, Object>` |
| `/admin/stats/trend` | GET | `AdminStatsController` | `Map<String, Object>` |
| `/client/release-check` | POST | `ClientReleaseController` | `{data:{decision,...},requestId}` |
| `/config/client` | GET | `ClientConfigController` | `Map<String, Object>` |
| `/miniapp/services` | GET | `ClientMiniappServiceController` | `Map<String, Object>` |
| `/reports` | POST | `ReportController` | `Report` |

管理端角色基线为 `ADMIN`，操作人从 Spring Security 当前认证主体取得。现有 HTTP 端点尚未统一要求操作理由和请求关联 ID；批次 1 前新增这两个字段时必须提供可选兼容与审计迁移方案。认证失败保持现有统一响应处理器，业务错误码保持 `GlobalExceptionHandler` 当前映射。

内部 user 积分接口路径固定为 `/internal/admin/points/accounts/**`。批次 0 冻结服务身份方案为 HMAC-SHA-256 请求签名：调用方身份固定为 `im-admin-service`，使用 `X-Internal-Service`、`X-Internal-Key-Id`、`X-Internal-Timestamp`、`X-Internal-Request-Id` 和 `X-Internal-Signature` 请求头；签名覆盖方法、路径、时间戳、请求 ID 与请求体摘要。密钥仅通过部署环境提供，接收方按 key ID 查找最小权限密钥，并拒绝超过五分钟的时间戳和重复请求 ID。调用方使用 `INTERNAL_USER_SERVICE_BASE_URL` 显式配置目标地址；批次 1 实现签名校验、超时、幂等键和审计关联。现有端点不得被视为已经完成服务身份保护。
