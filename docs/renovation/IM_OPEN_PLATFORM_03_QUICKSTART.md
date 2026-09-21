# IM 开放平台第三方接入快速上手（Quickstart）

# IM Open Platform Third-Party Quickstart

> 状态：迁移预览 / Migration preview · 所属方案集 / Series: `IM_OPEN_PLATFORM` · 当前 OAuth/OIDC 规范以 `docs/superpowers/specs/2026-09-04-oidc-hybrid-open-platform-design.md` 为准。

本文给出第三方系统（SaaS / 打车 / 电商 / 本地生活等）接入 IM 开放平台的**最小可跑流程**与可直接复制的参考示例（PKCE S256 生成 + curl 调用）。标准接入规范、前后台差异、A380 实例、用户同步与验收标准见 [IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md](./IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md)；**申请与审核（IM 后台怎么审、密钥怎么交付）见 [IM_OPEN_PLATFORM_06_REVIEW_SOP.md](./IM_OPEN_PLATFORM_06_REVIEW_SOP.md)**。

This doc gives the **minimal runnable flow** and copy-paste examples (PKCE S256 + curl) for third-party systems. See [IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md](./IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md) for the standard spec, front/back-channel split, A380 worked example, user sync and acceptance checklist; the **application review flow is in [IM_OPEN_PLATFORM_06_REVIEW_SOP.md](./IM_OPEN_PLATFORM_06_REVIEW_SOP.md)**.

> **流程有一步在平台侧**：提交申请后状态是 `PENDING`，**不会**返回 `appSecret`；必须等平台运营在 IM 后台「第三方接入」页**通过**后才发放密钥。别跳过这一步直接去调 `/oauth/authorize`。
>
> **One step happens on the platform side**: after you apply, the status is `PENDING` and **no `appSecret` is returned**; the secret is issued only after the platform approves it in the IM Admin Console ("第三方接入" page).

---

## 0. 前置条件 / Prerequisites

- 已开通 IM 开放平台访问入口。网关将 `/open/**` 与 `/oauth/**` 直达 `im-user-service`，路径**不带** `/api`。下文以占位基地址 `https://im.example.com` 表示（本地联调可换网关地址）。
- **应用已审核通过**（`GET /open/applications/{appId}` → `APPROVED`）并已从平台安全渠道拿到 `appSecret`；密钥**只放服务端**，不要写进 H5/App。审核与交付规则见 [IM_OPEN_PLATFORM_06_REVIEW_SOP.md](./IM_OPEN_PLATFORM_06_REVIEW_SOP.md) §3.4。
- 需要一个可登录 IM 的账号用于用户级授权，以及一个回调地址（HTTPS，或自定义 scheme 如 `gvchat://`），且与登记的 `callbackUrl` **精确一致**。
- public client（Flutter/Electron/H5）只使用 `appId + PKCE`，**不得**内置 secret；只有 confidential 后端服务保存 `appSecret`。

---

## 1. 五步最小可跑流程 / 5-Step Minimal Flow

### 第 1 步 / Step 1：申请应用 Register application

`POST /open/applications`

> ⚠️ 这一步只是**提交申请**：响应是 `{ appId, status: "PENDING" }`，**不返回 `appSecret`**。
> 请让平台运营在 IM 后台「第三方接入」页**通过**（该页也有「驳回/重置密钥/吊销」），
> 之后才能拿到 `appSecret` 并继续第 2 步。审核口径与话术见 [06 SOP](./IM_OPEN_PLATFORM_06_REVIEW_SOP.md)。
> 用 `GET /open/applications/{appId}` 轮询状态（`APPROVED` 才继续）。

请求体 Request body：

```json
{
  "appName": "我的第三方系统",
  "appType": "THIRD_PARTY",
  "callbackUrl": "https://partner.example.com/oauth/callback",
  "scopes": ["profile.basic", "profile.phone"]
}
```

curl：

```bash
curl -X POST "https://im.example.com/open/applications" \
  -H "Content-Type: application/json" \
  -d '{"appName":"我的第三方系统","appType":"THIRD_PARTY","callbackUrl":"https://partner.example.com/oauth/callback","scopes":["profile.basic","profile.phone"]}'
```

响应 Response：

```json
{
  "appId": "app_9f3c1a2b8d4e5f67",
  "appSecret": "<returned-once-to-confidential-backend>",
  "appName": "我的第三方系统",
  "status": "APPROVED"
}
```

- `appId` 为不可变客户端标识；confidential `appSecret` 仅返回给后端一次，public client 不接收该字段。
- ⚠️ 平台只保存 secret 哈希；不得把 secret 写入 App、浏览器、构建产物、URL 或日志。
- 字段 Field：`appName`（必填）、`appType`（选填，如 `THIRD_PARTY`）、`callbackUrl`（必填，必须与后续 `redirect_uri` 精确一致）、`scopes`（必填、非空，最小授权）。

### 第 2 步 / Step 2：生成 PKCE 并引导用户授权 Generate PKCE & authorize

先本地生成 `code_verifier` 与 `code_challenge`（S256）：

```text
code_challenge = BASE64URL( SHA256( code_verifier ) )   # 不带填充 no padding
```

- `code_verifier`：43–128 字符 ASCII 随机串（常见做法：32 字节随机数的 base64url，约 43 字符）。
- `code_challenge`：对 verifier 做 SHA-256，结果 base64url（`+`→`-`、`/`→`_`、去掉 `=`）。生成脚本见 [第 3 节](#3-参考示例完整可复制)。

引导用户浏览器跳转：

```text
GET /oauth/authorize?client_id={client_id}&redirect_uri={callbackUrl}&scope=profile.basic%20profile.phone&state={state}&nonce={nonce}&code_challenge={code_challenge}&code_challenge_method=S256
```

参数 Params：

| 参数 Param | 必填 | 说明 Description |
| --- | --- | --- |
| `client_id` | 是 | 第 1 步得到的客户端标识 |
| `redirect_uri` | 是 | 必须与注册的 `callbackUrl` **精确一致** |
| `scope` | 否 | 空格分隔；缺省使用应用登记的全部 scope；不得超出登记范围 |
| `state` | 否 | 防 CSRF，原样回传 |
| `code_challenge` | 是 | 第 2 步生成 |
| `code_challenge_method` | 否 | 仅支持 `S256`（缺省即 S256） |

要求用户已登录（用户级授权）。IM 会先创建短期授权事务票据，再 302 跳转到 `/oauth/consent?request_id=...`；用户明确同意后才签发授权码。登录 JWT 不得通过 URL 传递。

### 第 3 步 / Step 3：接收回调 Receive callback

授权成功后 IM 返回 `302 Location` 重定向到 `redirect_uri`：

```text
https://partner.example.com/oauth/callback?code={authCode}&state={state}
```

- 校验 `state` 与发起时一致（防 CSRF）。
- `code` 为一次性授权码，有效期 5 分钟（300s）；已做 URL 编码（自定义 scheme 下亦安全）。

### 第 4 步 / Step 4：用 code 换 token Exchange code for token

`POST /oauth/token`

```bash
curl -X POST "https://im.example.com/oauth/token" \
  -H "Content-Type: application/json" \
  -u "{client_id}:{client_secret}" \
  -d '{
    "grant_type": "authorization_code",
    "code": "{authCode}",
    "code_verifier": "{code_verifier}",
    "client_id": "{client_id}",
    "redirect_uri": "https://partner.example.com/oauth/callback"
  }'
```

> confidential 后端使用 HTTP Basic 认证；public client 的 code exchange 必须由其后端/BFF 完成。字段统一使用 OIDC 的 `client_id`、`redirect_uri` 和 snake_case 参数。

响应 Response：

```json
{
  "access_token": "e8f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5",
  "refresh_token": "f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6",
  "expires_in": 7200,
  "open_id": "im_10086",
  "scope": "profile.basic profile.phone"
}
```

- `access_token` 有效期 7200 秒（2 小时）。
- `open_id` 为该应用下稳定的用户标识（由 userId 派生、前缀 `im_`），用于第三方侧绑定用户。
- 刷新 Refresh：confidential 后端使用 HTTP Basic，并提交 `grant_type=refresh_token` + `refresh_token` + `client_id`；无需 `redirect_uri`，旧 `refresh_token` 立即作废并轮换新值。

### 第 5 步 / Step 5：拉取用户资料 Get user info

`GET /oauth/userinfo`（`Authorization: Bearer`）

```bash
curl -X GET "https://im.example.com/oauth/userinfo" \
  -H "Authorization: Bearer {access_token}"
```

响应 Response：

```json
{
  "open_id": "im_10086",
  "nickname": "张三",
  "avatar": "https://cdn.example.com/avatar/10086.png",
  "phone": "138****5678"
}
```

- 仅返回授权范围内的字段；`phone` 脱敏（掩码）。
- `nickname` 为空时回退 `username`。

---

## 2. 五步时序图 / Sequence

```text
第三方后端 ──1. POST /open/applications──▶ IM              # 得 client_id（confidential secret 仅后端托管）
第三方客户端 ──2. GET /oauth/authorize(+PKCE)──▶ IM       # 用户授权
IM ──302 redirect_uri?code&state──▶ 第三方 App
第三方 App ──3. 校验 state，收 code──▶ 自身
第三方 App ──4. POST /oauth/token(code+verifier)──▶ IM    # 得 access_token/refresh_token/open_id
第三方 App ──5. GET /oauth/userinfo(Bearer)──▶ IM         # 得昵称/头像/手机号(脱敏)
```

---

## 3. 参考示例（完整可复制）/ Copy-paste Examples

### 3.1 生成 PKCE S256

bash（需 `openssl`）：

```bash
code_verifier=$(openssl rand -hex 32)   # 64 个十六进制字符
code_challenge=$(printf '%s' "$code_verifier" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')
echo "code_verifier  = $code_verifier"
echo "code_challenge = $code_challenge"
```

Node.js：

```js
const crypto = require('crypto');
const code_verifier = crypto.randomBytes(32).toString('base64url');   // ~43 字符
const code_challenge = crypto.createHash('sha256').update(code_verifier).digest('base64url');  // 无填充
console.log({ code_verifier, code_challenge });
```

### 3.2 全流程一键脚本

```bash
#!/usr/bin/env bash
set -euo pipefail

BASE="https://im.example.com"
CLIENT_ID="<registered-client-id>"
CLIENT_SECRET="<server-only-secret>"
REDIRECT_URI="https://partner.example.com/oauth/callback"
SCOPE="profile.basic profile.phone"

# 1) 生成 PKCE
code_verifier=$(openssl rand -hex 32)
code_challenge=$(printf '%s' "$code_verifier" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')

# 2) 引导用户浏览器授权（人工完成，回调得到 code）
state="s_$(openssl rand -hex 8)"
echo "请在浏览器打开："
echo "$BASE/oauth/authorize?client_id=$CLIENT_ID&redirect_uri=$REDIRECT_URI&scope=profile.basic%20profile.phone&state=$state&nonce=$state&code_challenge=$code_challenge&code_challenge_method=S256"
read -r -p "粘贴回调中的 code: " AUTH_CODE
read -r -p "粘贴回调中的 state（应为 $state）: " RETURN_STATE
[ "$RETURN_STATE" = "$state" ] || { echo "state 校验失败"; exit 1; }

# 3) 换 token
TOKENS=$(curl -sS -X POST "$BASE/oauth/token" \
  -H "Content-Type: application/json" \
  -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d "{\"grant_type\":\"authorization_code\",\"code\":\"$AUTH_CODE\",\"code_verifier\":\"$code_verifier\",\"client_id\":\"$CLIENT_ID\",\"redirect_uri\":\"$REDIRECT_URI\"}")
echo "$TOKENS"
TOKEN=$(echo "$TOKENS" | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

# 4) 拉用户资料
curl -sS -X GET "$BASE/oauth/userinfo" -H "Authorization: Bearer $TOKEN"
```

---

## 4. scope 清单 / Scope List

| scope | 中文 | English | 状态 Status |
| --- | --- | --- | --- |
| `profile.basic` | 昵称 / 头像 | Nickname / avatar | 可用 Available |
| `profile.phone` | 手机号（脱敏） | Phone number (masked) | 可用 Available |
| `notify` | 站内信 / 推送 | In-app messages / push | 预留 Reserved |

- 最小授权 least privilege：只申请业务所需 scope。
- 请求 scope 必须为应用登记 scope 的子集，否则 400。

---

## 5. 撤销与安全 / Revoke & Security

### 5.1 撤销 Revoke

`POST /internal/admin/open-applications/{appId}/revoke`

```bash
curl -X POST "https://im.example.com/internal/admin/open-applications/app_9f3c1a2b8d4e5f67/revoke"
# → {"ok": true}
```

撤销应用即**全量撤销**其用户授权，并清理该应用的 access/refresh token；撤销后 `userinfo` 立即失效，第三方业务数据保留。

### 5.2 安全要点 Security

- `appSecret` 加密托管：只存 SHA-256 哈希，**不落明文**，仅创建时返回一次。
- 全程 HTTPS；`redirect_uri` 与登记值精确匹配；`state` 防 CSRF。
- PKCE S256 绑定授权码与调用方；`access_token` / `refresh_token` 为不透明令牌、服务端存储。
- 日志脱敏；授权 / 撤销 / 同步 / 触达记审计（见 02 指南）。

---

## 6. 与标准指南的关系 / Cross Reference

| 本文章节 | 标准指南章节（`IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md`） |
| --- | --- |
| 第 1 步注册 | §1 应用信息、§3 接口清单 `POST /open/applications` |
| 第 2–4 步授权换 token | §2 授权流程（Authorization Code + PKCE） |
| 第 5 步 userinfo | §4 用户同步（拉取 Pull） |
| §4 scope 清单 | §1 scope 清单 |
| §5 撤销与安全 | §3 revoke、§5 安全规范 |

> 真实实现参考 / Implementation notes：`im-services/user/im-user-service` 的 `OauthController` / `OauthConsentController` / `OpenApplicationController`；种子应用 `saas-ktv`（`V11__init_open_platform.sql`，`appType=THIRD_PARTY`、`callbackUrl=gvchat://oauth/callback`、scope `profile.basic + profile.phone`）可作为联调样例。开发种子密钥仅存哈希，生产密钥务必由审核流程生成并安全保管。
