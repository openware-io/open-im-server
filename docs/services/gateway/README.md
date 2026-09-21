# gateway

## 职责

- 对外统一暴露 HTTPS/WSS 入口
- 将外部请求路由到对应微服务
- 承载鉴权透传、限流、灰度、审计等横切能力

## 当前路由

业务 REST 路由统一匹配 `/api/v1/**` 并执行 `StripPrefix=2`。下表路径为客户端实际访问路径，目标 URI 由环境变量配置；完整映射同时维护在仓库根目录 [README](../../../README.md) 的“路由约定”中。

| 客户端路径 | 目标环境变量 | 目标服务 | 路由标识 |
| --- | --- | --- | --- |
| `/api/v1/auth/**`、`/api/v1/users/**`、`/api/v1/device-tokens/**`、`/api/v1/friends/**`、`/api/v1/user-stickers/**` | `IM_USER_URI` | `im-user-service` | `api-auth`、`api-users`、`api-device-tokens`、`api-friends`、`api-user-stickers` |
| `/api/v1/messages/**`、`/api/v1/favorites/**` | `IM_MESSAGE_URI` | `im-message-service` | `api-messages`、`api-favorites` |
| `/api/v1/conversations/**`、`/api/v1/groups/**`、`/api/v1/rtc/**` | `IM_CONVERSATION_URI` | `im-conversation-service` | `api-conversations`、`api-groups`、`api-rtc` |
| `/api/v1/media/**` | `COMMON_MEDIA_URI` | `common-media-service` | `api-media`；仅转发媒体控制面，不代理对象字节流。 |
| `/api/v1/admin/**`、`/api/v1/config/client/**`、`POST /api/v1/client/release-check`、`/api/v1/miniapp/im-services/**`、`/api/v1/reports/**` | `IM_ADMIN_URI` | `im-admin-service` | `api-admin`、`api-client-config`、`api-client-release-check`、`api-miniapp-services`、`api-reports` |
| `/ws/im/v1?ticket={one-time-ticket}` | `IM_ACCESS_WS_URI` | `im-access-ws` | WebSocket 入口，不适用 `StripPrefix=2`。 |

`/_docs/{user,message,conversation,media,admin}/api-docs` 是 Swagger 聚合文档路由：移除 `Authorization` 后转发到对应服务，不属于客户端业务接口。

## 限流

网关对所有 `/api/v1/**` 业务请求按「客户端地址」做令牌桶限流，读、写流量使用独立桶：

- 读流量（`GET`/`HEAD`）：默认 120 次/分钟
- 写流量（`POST`/`PUT`/`PATCH`/`DELETE`）：默认 60 次/分钟
- `OPTIONS`（CORS 预检）、`/_docs/**`、`/swagger-ui/**`、`/v3/api-docs/**`、`/actuator/**`、`/ws/**` 不限流

实现位于 `com.gvchat.gateway.ratelimit`（`RateLimitConfig`、`RateLimitFilter`、`RateLimitProperties`），基于 `spring-boot-starter-data-redis-reactive` 的 Lua 令牌桶脚本。客户端地址通过 `X-Forwarded-For` 解析（信任最后一跳反向代理，如 nginx-ingress），回退到直连地址。阈值、开关、Redis key 前缀均可由环境变量覆盖：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `IM_GATEWAY_RATE_LIMIT_ENABLED` | `true` | 是否启用限流 |
| `IM_GATEWAY_RATE_LIMIT_READ_PER_MINUTE` | `120` | 读流量阈值（次/分钟） |
| `IM_GATEWAY_RATE_LIMIT_WRITE_PER_MINUTE` | `60` | 写流量阈值（次/分钟） |
| `IM_GATEWAY_RATE_LIMIT_KEY_PREFIX` | `rl:gateway` | Redis key 前缀 |

超限时返回 `429 Too Many Requests`，响应体为 `{"code":429,"message":"请求过于频繁，请稍后再试"}`，并携带 `Retry-After: 60` 与 `X-RateLimit-Limit`。Redis 不可用时**放行**（fail-open），避免基础设施抖动阻断合法流量。Redis 连接复用其他服务的 `REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` / `REDIS_PASSWORD`。

## 约束

- 所有 `/api/v1/**` 路由转发时统一 `StripPrefix=2`
- Gateway 不承载业务逻辑或跨服务编排
- 新增、修改或删除路由时，必须同步修改网关配置、根目录路由表、本文档和 OpenAPI 快照
