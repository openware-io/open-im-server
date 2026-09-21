# IM 开放平台第三方接入指南（标准接入 · v3）

# IM Open Platform Third-Party Integration Guide (Standard · v3)

> 状态：生效 / Status: Active · 所属方案集 / Series: `IM_OPEN_PLATFORM` · 前置 / Predecessor: `IM_OPEN_PLATFORM_01_SERVICE.md`
>
> **变更记录 / Changelog**
> - v3：补 **A380（SaaS 平台）真实接入实例**（§8）；把接入拆成**前台（用户在场）**与**后台（服务端）**两段并给出差异速查（§6）；
>   补 **IM 后台申请—审批流程**（§4，含页面路径、接口、状态机、密钥交付规则，另见 `IM_OPEN_PLATFORM_06_REVIEW_SOP.md`）；
>   修正 v2 的错误：~~「应用级授权＝接入即授权」~~（现为 **申请 → 待审核 → 审批通过** 才发放密钥）、~~公开 `PUT /open/applications/{appId}`~~（未开放）、status 补 `REJECTED`。
> - v2：补充 WebView 内嵌 H5 的原生桥授权路径（§5.3）。
> - v1：首发。
>
> **v3 highlights**: A380 worked example (§8); front-channel vs back-channel split with a difference table (§6);
> the application-review flow in the IM Admin Console (§4); corrected v2 statements.

本指南面向第三方系统（SaaS / 打车 / 电商 / 本地生活等）的接入开发者。**读完之后你应当能独立完成接入**：申请 → 审批 → 前台授权 → 后台换取凭证 → 验收。

This guide targets developers of third-party systems. After reading it you should be able to complete the integration on your own: apply → review → front-channel authorization → back-channel credential exchange → acceptance.

> **接入层级提示 / Integration levels**：本指南覆盖**开放平台 OAuth 接入**（账号授权 + 用户同步，重量层）。
> 若只需 C 端「服务板块」入口 + 跳转（**无需**账号互通），走轻量层 `IM_OPEN_PLATFORM_04_SERVICE.md`；
> 两条路径独立启用，需要账号互通时再叠加本指南。

---

## 0. 先读这一段：你要做的 8 件事 / What you will actually do

| # | 做什么 | 谁做 | 在哪做 | 产出 | 参考 |
| --- | --- | --- | --- | --- | --- |
| 1 | 准备材料：主体、应用名、**HTTPS 回调地址**、最小 scope | 你 | 本地 | 一份申请信息 | §3.1 |
| 2 | 提交接入申请 | 你（可脚本） | `POST /open/applications` | `appId` + `PENDING` | §3.2 |
| 3 | 审核申请 | **平台运营** | IM 后台「第三方接入」页 | `APPROVED` + `appSecret`（**只展示一次**） | §4 |
| 4 | 保存密钥 | 你 | 你的服务端密钥管理 | `appId` / `appSecret` | §4.4 |
| 5 | 前台：把用户送去授权 | 你的客户端（H5/App） | `GET /oauth/authorize` | 回调带 `code` + `state` | §5 |
| 6 | 后台：用 code 换 token | **你的服务端** | `POST /oauth/token`（带 PKCE verifier） | `access_token` / `open_id` / `id_token` | §6 |
| 7 | 后台：取用户资料、建/绑你的账号 | 你的服务端 | `GET /oauth/userinfo` | 你的登录态 | §6、§8.4 |
| 8 | 自测 + 验收 | 你 + 平台 | §9 脚本 / §10 清单 | 全链路通过 | §9、§10 |

> 全程只有 **第 3 步**需要平台侧介入；其余都可自助完成。密钥只在第 3 步出现一次，**不要**写进客户端。

---

## 1. 选择接入路径 / Choose your path

| 路径 | 适用 | 需要账号互通 | 复杂度 | 文档 |
| --- | --- | --- | --- | --- |
| **A. 服务板块服务项**（轻量） | 只要在 IM 的 C 端「服务板块」有入口、能跳转 | 否 | 低 | `IM_OPEN_PLATFORM_04_SERVICE.md` |
| **B. 开放平台 OAuth**（本指南，重量） | 要用 IM 账号登录你的系统、同步昵称/头像/手机号、后续消息触达 | 是 | 中 | 本指南 |

两条可叠加：先用 A 拿入口，再用 B 做账号互通。

---

## 2. 角色与术语 / Roles and terms

| 术语 | 含义 |
| --- | --- |
| **IM（Provider）** | 开放平台提供方：应用注册、审核、OAuth 授权、用户资料 |
| **第三方（Client）** | 你：持有 `appId` + `appSecret`，引导用户授权并换取凭证 |
| **前台 / Front channel** | **用户在场**的一段：浏览器/WebView 跳转 `authorize` → 用户在 IM 授权页同意 → 302 回你的 `callbackUrl` 带 `code`。**不持有密钥** |
| **后台 / Back channel** | **服务端对服务端**的一段：你的服务端拿 `code` + PKCE `code_verifier` 换 token、取 `userinfo`、撤销。**密钥只在这里使用** |
| `code` | 一次性授权码，**只能用一次**、短有效期；只在回调 URL 上出现 |
| `open_id` | IM 用户在你的应用下的**稳定唯一标识**（同一个人在同一个 `appId` 下恒定） |
| `appSecret` | 应用密钥，仅审核通过/重置时展示一次，仅服务端保存 |

---

## 3. 第 1 步：提交接入申请（第三方）/ Step 1 · Apply

### 3.1 材料清单 / What to prepare

| 材料 | 要求 |
| --- | --- |
| `appName` | 应用名称（必填，用户能在授权页看到，请用真实业务名） |
| `subjectName` | 接入主体（企业/组织名，审核用） |
| `appType` | `THIRD_PARTY`（第三方）；平台自用为 `FIRST_PARTY` |
| `callbackUrl` | **HTTPS 回调地址**；后续 `redirect_uri` 必须与它**精确一致（含尾斜杠）** |
| `scopes` | 最小授权，从下表选，**不要多要** |

**scope 清单 / Scope list**

| scope | 中文 | English | 状态 |
| --- | --- | --- | --- |
| `profile.basic` | 昵称、头像 | Nickname / avatar | 已开放 |
| `profile.phone` | 手机号（脱敏展示） | Phone number (masked) | 已开放 |
| `notify` | 站内信 / 推送 | In-app message / push | **预留，暂不开放**（申请会被驳回） |

### 3.2 调用 / Request

```bash
POST {IM_BASE}/open/applications          # 网关直通 im-user-service，路径不带 /api
Content-Type: application/json

{
  "appName": "A380 智慧门店",
  "subjectName": "深圳某某科技有限公司",
  "appType": "THIRD_PARTY",
  "callbackUrl": "https://miniservice.example.com/a380/",
  "scopes": ["profile.basic"]
}
```

响应 / Response（**不含密钥**，此时为 `PENDING`）：

```json
{ "appId": "app_3f7c9a21", "appName": "A380 智慧门店", "status": "PENDING", "appSecret": null }
```

> `{IM_BASE}`：生产以平台交付的网关域名为准（如 `https://api.example.com`）；本地联调可换成网关地址。
> 网关已直通路由：`/open/**`、`/oauth/**`、`/.well-known/openid-configuration`。

### 3.3 查询审核状态 / Poll status

```bash
GET {IM_BASE}/open/applications/{appId}
```

```json
{ "appId": "app_3f7c9a21", "status": "PENDING", "rejectReason": null, "scopes": ["profile.basic"], "callbackUrl": "https://miniservice.example.com/a380/" }
```

| `status` | 含义 | 你该做什么 |
| --- | --- | --- |
| `PENDING` | 待审核 | 等待；平台审核后状态变化 |
| `APPROVED` | 已通过 | 从平台获取 `appSecret`（见 §4.4），开始联调 |
| `REJECTED` | 已驳回 | 看 `rejectReason`，修正材料后**重新提交申请** |
| `SUSPENDED` | 已吊销 | 停止调用；联系平台运营 |

---

## 4. 第 2 步：IM 后台审批（平台侧）/ Step 2 · Review in IM Admin Console

> 完整审核 SOP（判定标准、驳回话术、密钥交付与重置、吊销回滚）见 **[`IM_OPEN_PLATFORM_06_REVIEW_SOP.md`](./IM_OPEN_PLATFORM_06_REVIEW_SOP.md)**。

### 4.1 页面入口 / Where to click

IM 后台 → 左侧菜单 **「第三方接入」**（路由 `/open-platform/applications`）。页面提供：

- 筛选：类型（自营 `FIRST_PARTY` / 第三方 `THIRD_PARTY`）、状态（待审核 / 已通过 / 已驳回 / 已吊销）
- 列表列：`appId`、应用名称、主体、类型、**回调地址**、状态、驳回原因
- 操作：**通过**、**驳回**（填原因）、**重置密钥**、**吊销**（仅 `APPROVED` 可见后两者）

### 4.2 审核动作与接口 / Review actions and APIs

| 动作 | IM 后台接口（经网关 `/api/v1`） | 说明 |
| --- | --- | --- |
| 列表 | `GET /api/v1/admin/open-platform/applications` | 支持类型/状态筛选与分页 |
| 详情 | `GET /api/v1/admin/open-platform/applications/{appId}` | 核对主体、回调、scope |
| **通过** | `POST .../{appId}/approve` | 状态 → `APPROVED`，**分配并返回 `appSecret`（本次响应是唯一一次机会）** |
| **驳回** | `POST .../{appId}/reject` `{"reason":"..."}` | 状态 → `REJECTED`；原因会回显给第三方 |
| 重置密钥 | `POST .../{appId}/reset-secret` | 旧密钥立即失效，返回新密钥（同样只展示一次） |
| 吊销 | `POST .../{appId}/revoke` | 状态 → `SUSPENDED`，该应用全部 token/授权失效 |

> 服务内部路径为 `/admin/open-platform/...`（im-admin-service）；**第三方无权调用这些接口**。

### 4.3 状态机 / State machine

```text
（第三方提交申请）
        │
        ▼
     PENDING ──approve──▶ APPROVED ──revoke──▶ SUSPENDED
        │                    ▲                    │
        └──reject──▶ REJECTED │                    │
                     │       └── 重新提交申请 ─────┘（需平台恢复/重新申请）
                     └─ 修正材料后重新 POST /open/applications
```

### 4.4 密钥交付规则 / Secret delivery（重要）

1. `appSecret` **只在「通过」或「重置密钥」的响应里出现一次**；平台不提供二次查看。
2. 平台运营应通过**安全渠道**交付给第三方（密码管理器/加密消息），**不要**用聊天工具明文发。
3. 第三方**只能**把它放在自己服务端的密钥管理里；客户端（H5/App/小程序）**不得**持有。
4. 怀疑泄露 → 平台执行「重置密钥」，旧密钥立刻失效。

---

## 5. 第 3 步：前台接入（用户在场）/ Step 3 · Front channel

### 5.1 发起授权 / Authorize

```text
GET {IM_BASE}/oauth/authorize
  ?appId=app_3f7c9a21
  &redirect_uri=https%3A%2F%2Fminiservice.example.com%2Fa380%2F   # 必须与 callbackUrl 精确一致
  &scope=profile.basic
  &state=<你的随机串，防 CSRF，回调原样带回>
  &code_challenge=<BASE64URL(SHA256(code_verifier))>
  &code_challenge_method=S256
  &nonce=<可选，OIDC 防重放>
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `appId` | ✅ | 审核通过的应用标识 |
| `redirect_uri` | ✅ | 与登记的 `callbackUrl` **精确一致** |
| `scope` | ✅ | 只能是你被批准的 scope |
| `state` | ✅ | 随机值，回调必须比对，**不匹配就丢弃** |
| `code_challenge` / `code_challenge_method=S256` | ✅ | PKCE；`code_verifier` 自己留好（后台换 token 用） |
| `nonce` | 推荐 | OIDC `id_token` 防重放 |

### 5.2 用户同意页 / Consent

IM 会渲染授权页（`GET /oauth/consent?request_id=...`）：展示**应用名 + 申请的 scope 中文说明 + 同意/拒绝**。

- 用户**同意** → `POST /oauth/consent/approve` → 302 回 `redirect_uri?code=...&state=...`
- 用户**拒绝** → 回调带 `error=access_denied`（你的页面要处理，不要白屏）

### 5.3 WebView 内嵌 H5（小程序模型，推荐给 C 端）

H5 跑在 IM App 的 WebView 里时，IM **原生 App** 用自己的登录态完成上面这套 PKCE，再把 `{code, code_verifier, redirect_uri}` 交给 H5：

```text
IM App（容器）──注入 window.GVBridge.login({appId, scope, redirectUri})──▶ WebView
H5 ──GVBridge.login()──▶ IM App 原生（用自身 JWT 走 /oauth/authorize）
IM App ──{ code, code_verifier, redirect_uri }──▶ H5
H5 ──POST 你的服务端回调接口（body: code + code_verifier）──▶ 你的服务端
你的服务端 ──换 token + 建/绑账号──▶ H5：HttpOnly 会话 Cookie
```

**要点**：IM 的用户 JWT **不进入** H5 的 JS、不进 URL；H5 只拿一次性 `code`。真实落地见 §8.4（A380 用 `POST /api/v1/identity/oauth/im/callback`）。

### 5.4 前台红线 / Front-channel rules

- **绝不**在前台代码里放 `appSecret`（WebView/H5/App 都是 public client）。
- `state` 必须校验；`code` 只能用一次；回调要校验 `redirect_uri` 与会话无关。
- 授权失败/用户拒绝要有明确页面提示，不要停在空白回调页。

---

## 6. 第 4 步：后台接入（服务端）/ Step 4 · Back channel

### 6.1 code 换 token / Exchange

```bash
POST {IM_BASE}/oauth/token
Content-Type: application/x-www-form-urlencoded      # 也支持 application/json

grant_type=authorization_code
&code=<回调拿到的 code>
&redirect_uri=https%3A%2F%2Fminiservice.example.com%2Fa380%2F
&app_id=app_3f7c9a21
&code_verifier=<PKCE 原始随机串>
```

```json
{
  "access_token": "...", "refresh_token": "...", "expires_in": 7200,
  "open_id": "op_9c1f...", "scope": "profile.basic",
  "id_token": "eyJ...", "token_type": "Bearer"
}
```

**请用 `open_id` 作为你系统里与该 IM 用户的绑定键**（同一用户在同一 `appId` 下稳定）。

### 6.2 取用户资料 / User info

```bash
GET {IM_BASE}/oauth/userinfo
Authorization: Bearer <access_token>
```

只返回**授权范围内**的字段（`profile.basic` → 昵称/头像；`profile.phone` → 脱敏手机号）。
**不提供**聊天记录、好友、群组数据。

### 6.3 撤销 / Revoke

| 场景 | 调用 |
| --- | --- |
| 你的服务端主动失效某 token | `POST {IM_BASE}/oauth/revoke?token=<access_token>` |
| 用户在 IM 侧解除对你的授权 | `POST {IM_BASE}/oauth/user/revoke?appId=<appId>`（IM 侧入口触发） |
| 平台吊销整个应用 | IM 后台「吊销」→ 该应用全部 token 失效 |

撤销/解绑后：**你的业务数据保留**，只是不能再靠 IM 登录/触达。

### 6.4 OIDC 发现 / Discovery

```bash
GET {IM_BASE}/.well-known/openid-configuration
```

用于拿到 `authorization_endpoint` / `token_endpoint` / `userinfo_endpoint` / `jwks_uri`，便于用标准 OIDC 客户端库。

### 6.5 安全要求 / Back-channel rules

- `appSecret` 仅存服务端密钥管理；日志脱敏，**不落明文**。
- 回调接口做 `state` 校验 + 一次性 `code` 消费；重复 `code` 必须拒绝。
- 服务端换 token 成功后再建会话；失败给用户可读错误，不要吞掉。

---

## 7. 前后台差异速查 / Front-channel vs Back-channel（最容易搞错的地方）

| 维度 | 前台 / Front channel | 后台 / Back channel |
| --- | --- | --- |
| 运行位置 | 浏览器 / WebView / App | 你的服务端 |
| 用户是否在场 | **在**（要点“同意”） | 不在（静默调用） |
| 是否持有 `appSecret` | **绝不** | **只在这里** |
| 输入 | `appId` + `scope` + `redirect_uri` + PKCE challenge | `code` + `code_verifier` + `app_id` |
| 输出 | 一次性 `code`（+ `state`） | `access_token` / `refresh_token` / `open_id` / `id_token` |
| 能否取用户资料 | 否 | 是（`/oauth/userinfo`） |
| 失败重试 | 重新跳授权（幂等） | `code` 不可重放；token 过期用 `refresh_token` |
| 常见错误 | `redirect_uri` 不精确匹配、缺 `state`、把密钥写进前端 | 用错 `code_verifier`、重复用 `code`、漏校验 `state` |

---

## 8. A380 真实接入实例（端到端）/ Worked example · A380

SaaS 平台（A380）是**第一个第三方接入方**，其实现可作为参考：**前台**在 IM 客户端内的 H5，**后台**是 SaaS 身份服务。

### 8.1 申请与审批

| 项 | 值 |
| --- | --- |
| 应用 | A380 智慧门店（`appType=THIRD_PARTY`） |
| 回调 | 门店 H5 地址（HTTPS，与登记 `callbackUrl` 精确一致） |
| scope | `profile.basic`（仅昵称/头像，够用就不多要） |
| 审批 | IM 后台「第三方接入」→ 通过 → 交付 `appId` / `appSecret` 给 SaaS 服务端 |

### 8.2 前台：H5 拿 code

H5 通过容器桥（§5.3）或直接跳转 `GET /oauth/authorize`（PKCE S256 + `state`）拿到 `code`。

### 8.3 后台：SaaS 身份服务换 token 并绑定

```bash
POST /api/v1/identity/oauth/im/callback          # platform-identity-service
Content-Type: application/json

{
  "code": "<授权码>",
  "code_verifier": "<PKCE 原始串>",
  "redirect_uri": "https://<门店H5>/a380/",
  "app_id": "<A380 的 appId>",
  "state": "<前台原样带回>",
  "nonce": "<可选>"
}
```

服务端行为（真实实现）：

1. 校验 `state`；
2. 用 `code` + `code_verifier` 调 IM `POST /oauth/token`；
3. 按 `open_id` **建/绑** SaaS 账号（`idt_account` + `idt_login_identity(IM)` + `idt_oauth_link`）；
4. 种 **HttpOnly 会话 Cookie** 并把 `authenticated=true` 返回给 H5 —— 之后业务请求只带这个 SaaS 会话，**不再每次问 IM**。

> C 端与 B 端用不同 `appId` 区分（`-h5` / `-b` 结尾），会话 Cookie 也分 C/B 两套。
> 旧入口 `POST /api/v1/identity/oauth/im/bind`（直接传 `im_access_token`）**已停用**：返回 `410 LEGACY_BIND_DISABLED`，请改用上面的授权码回调。

### 8.4 验收结果 / What "done" looked like

- 用户从 IM 进入门店 H5 → 授权一次 → 门店内已完成登录（无需在门店侧再登录/注册）。
- IM 侧解除授权后：门店业务数据仍在，门店登录态失效需重新授权。
- IM 不可用时，门店已有登录态的用户仍能继续使用门店业务。

---

## 9. 最小闭环自测（可复制）/ End-to-end smoke test

```bash
IM_BASE=https://api.example.com
APP_ID=app_3f7c9a21
REDIRECT=https%3A%2F%2Fminiservice.example.com%2Fa380%2F

# 0) 生成 PKCE
VERIFIER=$(openssl rand -base64 48 | tr -d '=+/' | cut -c1-64)
CHALLENGE=$(printf '%s' "$VERIFIER" | openssl dgst -binary -sha256 | openssl base64 | tr '+/' '-_' | tr -d '=')
STATE=$(openssl rand -hex 16)

# 1) 申请（首次）→ 记下 appId，等待 IM 后台「通过」
curl -s -X POST "$IM_BASE/open/applications" -H 'Content-Type: application/json' \
  -d '{"appName":"联调应用","appType":"THIRD_PARTY","callbackUrl":"https://miniservice.example.com/a380/","scopes":["profile.basic"]}'

# 2) 查状态（APPROVED 之后才继续）
curl -s "$IM_BASE/open/applications/$APP_ID"

# 3) 打印授权地址，浏览器打开并同意（拿到回调里的 code）
echo "$IM_BASE/oauth/authorize?appId=$APP_ID&redirect_uri=$REDIRECT&scope=profile.basic&state=$STATE&code_challenge=$CHALLENGE&code_challenge_method=S256"

# 4) 用 code 换 token（后台）
curl -s -X POST "$IM_BASE/oauth/token" -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=authorization_code" -d "code=$CODE" -d "redirect_uri=$REDIRECT" \
  -d "app_id=$APP_ID" -d "code_verifier=$VERIFIER"

# 5) 取用户资料
curl -s "$IM_BASE/oauth/userinfo" -H "Authorization: Bearer $ACCESS_TOKEN"

# 6) 撤销
curl -s -X POST "$IM_BASE/oauth/revoke?token=$ACCESS_TOKEN"
```

---

## 10. 验收清单 / Acceptance checklist

- [ ] 申请 → 审批 → 拿到 `appId`/`appSecret`（密钥只在服务端，**前端 grep 不到**）。
- [ ] `redirect_uri` 与登记 `callbackUrl` 精确一致；不一致时被拒绝且提示可读。
- [ ] 首次授权：用户同意 → 回调 `code` → 换 token → 取 userinfo → 建/绑你的账号 → 你的登录态生效。
- [ ] 用户拒绝授权：回调带 `error=access_denied`，页面有明确提示。
- [ ] `state` 不匹配 / `code` 重放：请求被拒绝。
- [ ] 撤销 token 后 `userinfo` 失效；用户解除授权后你的登录态失效，**业务数据保留**。
- [ ] IM 不可用时，已有登录态的用户仍能完成你的核心业务闭环。
- [ ] 密钥泄露演练：平台重置密钥后旧密钥立即失效。

---

## 11. 错误码与排障 / Errors

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 申请被驳回 `REJECTED` | 材料不全 / scope 过大 / 回调非 HTTPS | 看 `rejectReason`，修正后重新申请 |
| 授权页报应用不可用 | `appId` 不存在或非 `APPROVED` | 用 §3.3 查状态 |
| `redirect_uri` 校验失败 | 与 `callbackUrl` 不完全一致（尾斜杠/端口/大小写） | 改成**逐字符一致** |
| 换 token 失败 | `code` 已用过/过期；`code_verifier` 与 challenge 不匹配 | `code` 只能用一次；verifier 用原始串 |
| userinfo 401 | `access_token` 过期/已撤销 | 用 `refresh_token` 续期或重新授权 |
| 旧接口 `410 LEGACY_BIND_DISABLED` | 用了 `im_access_token` 直传的老绑定接口 | 改用授权码回调（§8.3） |

---

## 12. 安全与数据边界 / Security & data boundary

- `appSecret`：不可逆哈希保存，**只在通过/重置时返回一次**；不落明文、不进日志、不进客户端。
- scope **最小授权**；超范围请求会被拒绝。
- 授权 / 撤销 / 同步 / 触达**全部记审计**，日志脱敏。
- 共享范围**仅限**授权 scope（昵称/头像/脱敏手机号）；**不共享**聊天、好友、群组数据。
- 解绑/撤销后第三方业务数据保留。

---

## 13. 相关文档 / See also

| 文档 | 内容 |
| --- | --- |
| `IM_OPEN_PLATFORM_01_SERVICE.md` | 开放平台总体方案（5 步流程、实施边界） |
| **`IM_OPEN_PLATFORM_06_REVIEW_SOP.md`** | **申请与审核 SOP（平台侧操作手册 + 判定标准 + 密钥交付）** |
| `IM_OPEN_PLATFORM_03_QUICKSTART.md` | 快速上手（PKCE 生成 + curl 全流程） |
| `IM_OPEN_PLATFORM_04_SERVICE.md` | 轻量层：C 端「服务板块」服务项接入 |
| `IM_OPEN_PLATFORM_05_AUTHORIZATION_GOVERNANCE_DRAFT.md` | 授权治理（草案） |

> 对外发布镜像 / Published mirror：`https://example.com/developer.html`（中英双语 / bilingual；本指南的对外镜像页，源文件在 `meta-cogni-cms/html/developer.html`。**接口、流程、示例与验收标准变更时，两者必须同步更新**）。
