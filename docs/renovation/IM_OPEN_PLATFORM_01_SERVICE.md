# IM 开放平台第三方接入方案（标准接入流程）

> **变更记录（v2）**：①v1 首发创建：IM 是开放平台，SaaS / 打车 / 电商 / 本地生活等第三方系统按本标准流程接入即可打通业务；当前 SaaS 平台只是第一个接入方。②v2 补充：外部服务在 C 端「服务板块」的入口接入采用**两层模型**——轻量层走「服务板块服务项接入」（见 [IM_OPEN_PLATFORM_04_SERVICE](IM_OPEN_PLATFORM_04_SERVICE.md)），重量层走本方案的 OAuth 开放平台接入；打车等默认先走服务板块，需要 IM 账号体系时再叠加本方案。

## 0. 方案集声明

| 项目 | 内容 |
| --- | --- |
| 方案集 | `IM_OPEN_PLATFORM` |
| 顺序号 | `01` |
| 实施边界 | `SERVICE`（IM 开放平台：应用注册/审核/scope/授权/用户同步/标准接口） |
| 前置方案 | [MULTI_DEVICE_LOGIN_01](MULTI_DEVICE_LOGIN_01_SERVICE.md)（IM 账号/设备/会话）、[SAAS_PLATFORM_05_API](SAAS_PLATFORM_05_API.md) §3.2（SaaS 侧 OAuth 两层授权） |
| 目标工程 | `im-user-service`（账号/授权）、`gateway`（鉴权）、新增开放平台子域（应用注册/scope/事件订阅） |

## 1. 定位与目标

IM 是**开放平台**（参考微信开放平台）：任何第三方业务系统作为「第三方应用/模块」接入 IM，按**标准流程**即可打通——IM 提供入口、身份授权与用户触达，第三方系统保有独立业务闭环、脱离 IM 也能完整运行。

目标：
1. 一套**标准接入流程**（注册→授权→同步→打通），未来系统照做即可。
2. 应用级 + 用户级两层授权，安全可撤销、可审计。
3. 接入文档模板，每个第三方系统一份接入文档，评审/验收可复用。

## 2. 接入方类型

| 接入方 | 关系 | 说明 |
| --- | --- | --- |
| SaaS 平台（当前） | 第三方应用/模块 | 美团之于微信：入口 + 授权 + 用户同步 |
| 打车 / 电商 / 本地生活（未来） | 第三方应用/模块 | 同标准流程接入 |
| IM 自身后台 | 一级应用（平台自用） | 不对外、不走第三方接入 |

> **接入层级（两层模型）**：外部服务（打车等）在 C 端有两条互补路径——「服务板块服务项接入」（轻量：入口 + 跳转 + 展示，**默认路径**，见 [IM_OPEN_PLATFORM_04_SERVICE](IM_OPEN_PLATFORM_04_SERVICE.md)）与「开放平台 OAuth 接入」（重量：账号授权 + 用户同步 + 触达，按需叠加，见本方案）。两条路径可独立启用 / 独立停用。

## 3. 标准接入流程（5 步）

> 实施状态：流程已落地。**申请/审核**见 [`IM_OPEN_PLATFORM_06_REVIEW_SOP.md`](./IM_OPEN_PLATFORM_06_REVIEW_SOP.md)，
> 面向第三方的完整步骤（含前后台两段与 A380 实例）见 [`IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md`](./IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md)。

```text
1 应用申请：第三方 POST /open/applications 提交应用信息/回调域名/申请 scope → 状态 PENDING（此时不发放密钥）
2 平台审核：IM 后台「第三方接入」页 通过（发放 appId + appSecret，密钥仅展示一次）/ 驳回（填原因）→ 状态 APPROVED / REJECTED
3 用户授权：第三方引导用户 OAuth 授权指定 scope（用户级授权，Authorization Code + PKCE；前台拿 code、后台换 token）
4 用户同步：OAuth userinfo 拉取 + UserProfileChanged 事件订阅（授权范围内）
5 业务打通：第三方用自身业务闭环，经 IM 入口/消息触达用户；解绑/撤销即断开（第三方业务数据保留）
```

## 4. 应用级授权（审核通过后授权）

- 应用注册：`appId`、`appName`、`appType`、`callbackUrl`、`status`（`PENDING/APPROVED/REJECTED/SUSPENDED`）、审核信息与驳回原因。
- **不是「接入即授权」**：注册只产生 `PENDING` 申请，必须经 IM 后台审批通过才发放 `appSecret`；密钥只在通过/重置时返回一次，仅存服务端。
- scope 清单：`profile.basic`（昵称/头像）、`profile.phone`（手机号）；`notify`（站内信/推送）当前仅预留，未完成能力闭环前不得申请。
- 凭证托管：`appSecret` 仅以不可逆哈希保存，只在审核通过/重置时返回一次明文。

## 5. 用户级授权（OAuth）

- OAuth 2.0 Authorization Code + PKCE；scope 最小授权；用户可随时在 IM 侧撤销。
- 端点：`GET /oauth/authorize`、`POST /oauth/token`、`GET /oauth/userinfo`。

## 6. 用户信息同步

- 授权范围内 IM → 第三方：OAuth userinfo 拉取 + `UserProfileChanged` 事件订阅（推送）。
- 同步内容：昵称/头像/手机号（脱敏）；不共享 IM 聊天/好友/群组数据。
- 解绑/撤销：第三方业务数据保留，仅移除 IM 登录/触达方式。

## 7. 标准接口清单

| 方法与路径 | 说明 |
| --- | --- |
| `POST /open/applications` | 应用注册申请 |
| `GET/PUT /open/applications/{appId}` | 应用信息/scope 管理（平台审核） |
| `GET /oauth/authorize` | 用户授权页 |
| `POST /oauth/token` | code 换 token + userinfo |
| `GET /oauth/userinfo` | 授权范围内用户资料 |
| `POST /oauth/revoke` | 撤销单个 token |
| `POST /oauth/user/revoke` | 用户撤销自身对应用的授权 |
| `POST /internal/admin/open-applications/{appId}/revoke` | 平台全量撤销应用（内部管理） |

## 8. 安全规范

- `appSecret` 不可逆哈希存储不落明文；应用 token 短效 + 按 appId 撤销。
- scope 最小授权 + 授权版本；撤销/解绑即失效。
- 审计：授权/撤销/同步/触达均记审计；日志脱敏（不落 token/手机号明文）。

## 9. 接入文档模板（每个第三方系统一份）

| 章节 | 内容 |
| --- | --- |
| 应用信息 | appId / appName / 回调域名 / scope 申请 |
| 授权流程 | OAuth 时序 + scope 说明 |
| 用户同步 | userinfo 拉取 + 事件订阅 topic |
| 验收 | 授权/同步/撤销/触达的验收用例 |

## 10. 实现计划

- 开放平台子域（**归属 `im-user-service`，作为其开放平台子域**；`open_application` 等表随其迁移，未来规模扩大再独立 `im-open-platform-service`）：应用注册/审核/scope/事件订阅/审计。
- 数据表：`open_application`、`open_application_scope`、`open_user_authorization`。
- Gateway 路由：`/oauth/**`、`/open/**`。
- 契约：OpenAPI + 事件 Schema（`UserProfileChanged`）。

## 11. 验收

- 一个模拟第三方系统（SaaS）按 5 步流程接入，完成授权/同步/业务打通/撤销全链路。
- `appSecret` 不落明文；撤销后 token/userinfo/事件均失效。
- 第三方系统在 IM 不可用时仍独立闭环运行。

## 12. 交付物与官网引导页

- **技术文档**：本方案（仓库内，面向内部/评审）。
- **官网引导页**：在官网做「开发者 / 开放平台」引导页面，按 5 步流程引导第三方接入（注册→授权→同步→打通），含接口文档、scope 说明、SDK 下载、接入示例与验收清单。
- **官网国际化**：官网整体中英双语（i18n），开放平台引导页/文档/错误码双语，支持语言切换与 URL 语言前缀（如 `/zh`、`/en`）。

> 官网（开发者门户）是独立交付物，承载开放平台引导、文档与 SDK；i18n 从第一天纳入，避免后续返工。
