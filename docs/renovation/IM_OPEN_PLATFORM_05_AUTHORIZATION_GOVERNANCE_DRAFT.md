# IM 开放平台授权治理待规范（Draft）

> 状态：待评审 / Draft · 版本：0.1 · 适用范围：SaaS 及未来第三方系统接入 IM 的授权、身份同步与撤销。
>
> 本文是审查结论的归档，不代表当前版本已经上线、已经完成生产合规，或已经确定所有环境的地址、凭证和部署方式。任何“必须”均指后续正式接入前的目标要求；当前实现状态以代码和测试为准。

## 1. 目标与边界

### 1.1 目标

建立一套可复用的第三方授权基线，使 SaaS、打车、电商、本地生活等系统能够以统一方式接入 IM，同时满足：

- IM 只负责入口、身份授权和授权范围内的用户资料/触达能力；
- 第三方拥有独立账号、数据和业务闭环，不依赖 IM 才能运行；
- 授权可解释、可撤销、可审计，敏感资料按最小权限提供；
- 本地、开发、测试、预发布、ACK/生产环境相互隔离，不能交叉使用凭证或地址。

### 1.2 不在本文范围内

- IM 聊天、好友、群组数据向第三方开放；
- 第三方直接访问 IM 数据库或内部接口；
- 服务板块“入口 + 跳转”的轻量接入规范（见 `IM_OPEN_PLATFORM_04_SERVICE.md`）；
- 当前版本的具体域名、账号、appSecret、ACK 配置值。

## 2. 当前版本基线

当前系统采用“两层授权”模型：

| 层级 | 含义 | 当前定位 |
| --- | --- | --- |
| 应用级 | 第三方申请应用、登记回调地址和 scope；confidential 后端才使用受保护的 client secret，public client 不持有 secret | 已有实现骨架，治理流程仍需补齐 |
| 用户级 | 用户通过 OAuth Authorization Code + PKCE 授权指定 scope | 已有 Provider/Client 骨架，同意与撤销闭环未完成 |

当前已有的实现能力：

- 授权码有效期 5 分钟、一次性兑换；
- PKCE 仅支持 `S256`；
- 运行时精确匹配 `redirect_uri`；
- access token 2 小时、refresh token 轮换；
- confidential client secret 以不可逆哈希保存；Flutter/Electron/H5 public client 不得持有 secret；
- SaaS 侧通过 `platform-identity-service` 完成账号绑定和资料落库。

当前不得对外承诺的能力：

- `/oauth/authorize` 已经有真实用户同意页；
- 撤销后所有 token、userinfo 和事件已经即时失效；
- 所有 scope 均有白名单、敏感等级和字段级过滤；
- 已经具备完整人工审核、主体认证、审计查询和事件运营能力；
- 已经确定开发、测试、ACK/生产的 endpoint、回调域和凭证管理方式。

## 3. 目标标准流程

```text
第三方主体登记
  → 应用审核（主体/用途/隐私政策/回调/Scope）
  → 按客户端类型登记 client_id（confidential 后端另行托管 client_secret）
  → 用户发起 Authorization Code + PKCE
  → IM 登录并展示同意页
  → 用户确认 scope
  → IM 返回一次性 code
  → 第三方后端换取 token
  → 按 scope 调 userinfo / 订阅资料事件
  → SaaS 建立独立账号绑定
  → 用户或平台撤销
  → token、userinfo、事件立即失效
```

### 3.1 应用申请与审核

申请资料至少包括：主体信息、应用名称、业务用途、隐私政策、服务条款、数据字段、保存期限、回调地址、申请 scope、安全联系人和环境信息。

审核通过后才可签发凭证。appSecret 只显示一次，服务端只保存不可逆哈希；重置密钥必须使旧密钥立即失效并留下审计记录。

### 3.2 用户授权

授权请求必须包含 `client_id`、`redirect_uri`、`response_type=code`、`scope`、`state`、`code_challenge` 和 `code_challenge_method=S256`。

- 用户未登录时先完成 IM 登录；
- 首次授权、scope 增加或敏感 scope 变化时必须展示同意页；
- 用户拒绝时返回标准 `access_denied`；
- JWT、access token、refresh token 不得进入 URL、Referer、日志或前端长期存储；
- WebView 由 IM 原生容器持有 IM 登录态，H5 只接收短期 code 和 PKCE 参数。

### 3.3 Token 与资源访问

- Token 端点优先使用标准参数和客户端认证方式；
- 授权码必须校验 client、redirect_uri、PKCE、有效期和一次性；
- refresh token 必须轮换并检测重放；
- userinfo 必须按“最终批准 scope”过滤返回字段；
- 第三方只能取得其登记且用户批准的资料，不得通过 userinfo 获取聊天、好友、群组数据。

### 3.4 撤销与解绑

必须同时支持：用户撤销单个应用授权、平台撤销应用、第三方主动解绑和 token revocation。

撤销后必须立即阻断：

1. access token；
2. refresh token；
3. userinfo；
4. 尚未投递的资料事件；
5. 后续触达任务。

第三方业务数据原则上保留，但应移除 IM 登录关系，并按隐私政策处理同步资料。

## 4. Scope 与数据规范

平台建立唯一 scope registry，每个 scope 固定登记：名称、字段、用途、敏感等级、审核等级、用户展示文案、版本和废弃状态。

首批 scope 建议：

| Scope | 数据 | 等级 | 要求 |
| --- | --- | --- | --- |
| `profile.basic` | 昵称、头像 | 普通 | 同意页说明用途 |
| `profile.phone` | 手机号或脱敏手机号 | 敏感个人信息 | 单独说明、最小化返回、记录同意 |
| `notify` | 站内信/推送 | 高风险能力 | 当前仅作预留，未形成闭环前不得对外开放 |

未知 scope 必须拒绝。scope 变更必须增加授权版本，并触发重新同意；不得仅依靠字符串去重。

## 5. 环境隔离要求

环境矩阵在正式确定前只允许使用占位符，不得把 ACK 配置复制到本地或开发环境。

| 环境 | Provider 地址 | SaaS 地址 | appId/secret | 数据与回调 |
| --- | --- | --- | --- | --- |
| local/kind | 待定 | 待定 | 独立开发凭证 | 本地/局域网回调 |
| dev | 待定 | 待定 | 独立开发凭证 | 开发域名 |
| test | 待定 | 待定 | 独立测试凭证 | 测试域名 |
| ACK/生产 | 待定 | 待定 | 生产密钥托管 | 生产 HTTPS 域名 |

要求：

- 每个环境独立 appId、appSecret、数据库、Redis key 前缀和事件 topic；
- 配置只能通过对应环境 Secret/配置中心注入；
- 本地脚本、Kind manifest 与 ACK manifest 分离；
- 回调地址按环境登记，禁止跨环境复用；
- 文档、官网和示例不得写入真实凭证。

## 6. 当前必须实现的范围

以下项目是“首次对外联调或正式上线前”的阻断项，建议按此顺序实现：

### M0：授权安全闭环

1. 让 `/oauth/authorize` 强制进入 consent 流程，不得自动写入用户授权。
2. 禁止 `im_token` 作为 URL 参数；WebView 只允许原生桥返回短期 code。
3. 建立 scope 白名单，并按 scope 过滤 userinfo 字段。
4. 强制 HTTPS、禁止 fragment、限制 localhost/内网回调，并按环境登记回调。
5. 增加用户撤销、应用撤销和 token revocation；撤销时立即删除/阻断 Redis token。
6. 统一 OAuth 标准错误码：`invalid_request`、`invalid_client`、`invalid_grant`、`invalid_scope`、`access_denied` 等。

### M1：平台治理基线

1. 应用审核状态、审核人、原因和密钥操作形成结构化审计事件。
2. 应用查询接口增加申请人鉴权、防枚举和限流。
3. 重新定义 app 维度 `sub/open_id`，避免不同第三方关联同一用户。
4. 事件投递增加签名、幂等、重试、死信和撤销检查。
5. 明确解绑后的资料保留、删除和再次授权规则。

### M2：产品化增强（可后置）

1. 发布 OAuth metadata/discovery。
2. 发布 OpenAPI、AsyncAPI、SDK 和多语言示例。
3. 建立开发/测试/ACK 环境配置页。
4. 建立端到端自动化验收和发布门禁。
5. 增加 secret 轮换、scope 升级重新同意和应用暂停策略。

## 7. 验收门槛

未满足以下条件，不得把文档状态改为“正式规范”，不得在官网宣称已具备对应能力：

- 新应用必须经历申请、审核、凭证签发流程；
- 首次授权和新增敏感 scope 必须有用户同意记录；
- 只申请 `profile.basic` 时不得返回手机号；
- 授权码重放、PKCE 错误、redirect_uri 不匹配均失败；
- 用户撤销、平台撤销后 token/userinfo/事件均立即失效；
- access token、refresh token、appSecret、手机号明文不进入日志；
- 本地、开发、测试、ACK/生产互不使用对方凭证和回调；
- IM 不可用时 SaaS 仍可使用自身账号和业务数据运行；
- 至少完成一套真实第三方端到端测试，并保留测试记录。

## 8. 官网与规范同步规则

官网开发者页只能描述已经实现并通过验收的能力。当前应标注为“开发预览/待上线”，并暂不承诺：

- 已有用户授权确认页；
- 撤销后 token 即时失效；
- `notify` 已可用；
- SDK 已可下载；
- 固定生产 endpoint 和生产回调地址。

正式发布前，官网、快速上手、OpenAPI 和本规范必须从同一份接口契约生成或逐项核对，尤其是 Provider 端点与 SaaS 回调端点不能混写。

## 9. 参考实现位置

- IM OAuth Provider：`im-services/user/im-user-service`
- SaaS OAuth Client：`platform-services/identity/platform-identity-service`
- 内部应用审核：`/internal/admin/open-applications`
- 内部规范：`IM_OPEN_PLATFORM_01_SERVICE.md`、`IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md`
- 官网开发者页：`meta-cogni-cms/html/developer.html`

> 下一步：先评审第 6 节 M0 范围，确认当前版本必须实现的最小集合，再拆分代码任务和测试任务；M1/M2 不应在未评审前混入本次实现。
