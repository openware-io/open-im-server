# IM 开放平台 · 第三方接入申请与审核 SOP

> 状态：生效 / Status: Active · 所属方案集 / Series: `IM_OPEN_PLATFORM`
> 前置 / Predecessor: `IM_OPEN_PLATFORM_01_SERVICE.md` · 配套 / Companion: [`IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md`](./IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md)（第三方接入指南）
>
> 本文回答两件事：**第三方怎么申请**、**平台运营在 IM 后台怎么审**。审核不是「点一下通过」——材料、回调地址、scope 都要按下面的标准逐项核。

---

## 1. 流程总览 / Overview

```text
第三方                          平台运营（IM 后台）                    系统
  │  POST /open/applications ─────────────────────────────────▶ 建应用，status=PENDING
  │  GET  /open/applications/{appId}（轮询）◀────────────────── 状态/驳回原因
  │                                    │ 打开「第三方接入」页 ◀── 待审列表
  │                                    ├─ 通过 ────────────────▶ APPROVED + 发放 appSecret（仅此一次）
  │                                    ├─ 驳回（填原因）────────▶ REJECTED，原因回显给第三方
  │                                    ├─ 重置密钥 ─────────────▶ 旧密钥立即失效，返回新密钥
  │                                    └─ 吊销 ────────────────▶ SUSPENDED，该应用全部 token 失效
  │  ◀── 安全渠道交付 appId / appSecret ─┘
  │  前台授权 → 后台换 token → 联调 → 验收
```

**唯一需要平台侧介入的环节就是审核**；其余第三方可自助完成。

---

## 2. 第三方侧：申请 / Applicant side

### 2.1 提交前自检（避免被驳回）

| 检查项 | 通过标准 |
| --- | --- |
| 应用名 `appName` | 真实业务名，用户能在授权页看懂（不要「测试」「demo」「123」） |
| 主体 `subjectName` | 与企业/组织一致，能对得上商务关系 |
| 回调地址 `callbackUrl` | **HTTPS**；无 fragment（`#`）；与线上实际页面**逐字符一致**（含尾斜杠）；生产环境（非 `localhost`） |
| scope | 只申请真正要用的：`profile.basic`（昵称/头像）、`profile.phone`（脱敏手机号，需说明用途）；`notify` 当前**不开放** |
| 类型 `appType` | 第三方填 `THIRD_PARTY`（`FIRST_PARTY` 仅平台自用） |
| 联系方式 | 技术对接人 + 邮箱/手机（审核有疑问时联系） |

### 2.2 提交与跟踪

```bash
# 提交
curl -s -X POST "$IM_BASE/open/applications" -H 'Content-Type: application/json' -d '{
  "appName":"XXX 门店服务","subjectName":"XXX 科技有限公司",
  "appType":"THIRD_PARTY",
  "callbackUrl":"https://service.example.com/callback/",
  "scopes":["profile.basic"]}'

# 跟踪（PENDING → APPROVED/REJECTED）
curl -s "$IM_BASE/open/applications/$APP_ID"
```

- `REJECTED`：按 `rejectReason` 修正**后重新提交**（同一主体可再次申请）。
- `APPROVED`：向平台运营索取 `appSecret`（**只有这一次**），存进你的服务端密钥管理。
- 密钥丢失：请平台在「第三方接入」页点**重置密钥**（旧密钥立即失效）。

---

## 3. 平台侧：审核 SOP / Reviewer side

### 3.1 入口

IM 后台 → 左侧菜单 **「第三方接入」**（路由 `/open-platform/applications`）。

| 区域 | 字段 | 用途 |
| --- | --- | --- |
| 筛选 | 类型（自营/第三方）、状态（待审核/已通过/已驳回/已吊销） | 日常只看「第三方 + 待审核」 |
| 列表 | `appId`、应用名称、**主体**、类型、**回调地址**、状态、**驳回原因** | 逐项核对 |
| 操作 | **通过** / **驳回** / 重置密钥 / 吊销 | `APPROVED` 后才有重置与吊销 |

对应接口（服务内部路径 `/admin/open-platform/...`，前端经网关 `/api/v1`）：

| 动作 | 接口 |
| --- | --- |
| 列表 | `GET /api/v1/admin/open-platform/applications?status=PENDING&appType=THIRD_PARTY` |
| 详情 | `GET /api/v1/admin/open-platform/applications/{appId}` |
| 通过 | `POST /api/v1/admin/open-platform/applications/{appId}/approve` |
| 驳回 | `POST /api/v1/admin/open-platform/applications/{appId}/reject` `{"reason":"..."}` |
| 重置密钥 | `POST /api/v1/admin/open-platform/applications/{appId}/reset-secret` |
| 吊销 | `POST /api/v1/admin/open-platform/applications/{appId}/revoke` |

### 3.2 审核判定标准（逐项核）

| # | 检查项 | 通过 | 不通过 |
| --- | --- | --- | --- |
| 1 | 主体真实性 | 有商务关系/合同，主体名一致 | 主体不明、个人名义接企业业务 |
| 2 | 回调地址安全 | HTTPS、生产域名、无 fragment、与业务页面一致 | `http://`、`localhost`、带 `#`、明显占位域名 |
| 3 | scope 最小化 | 只申请必要 scope，`profile.phone` 有正当用途说明 | 全量申请、申请未开放的 `notify` |
| 4 | 应用命名 | 用户可理解的业务名 | 「测试应用」「demo」等 |
| 5 | 应用类型 | 第三方填 `THIRD_PARTY` | 冒用 `FIRST_PARTY` |
| 6 | 重复申请 | 同一主体已有在用应用 → 复用/合并 | 与在用应用重复且无理由 |

**通过**：点「通过」→ **立即把返回的 `appSecret` 通过安全渠道交付**（见 §3.4）。
**驳回**：点「驳回」并填写**可执行**的原因（第三方照着就能改），参考话术 ↓

### 3.3 驳回原因话术（直接可用）

| 场景 | 建议填写 |
| --- | --- |
| 回调地址不合规 | 「回调地址需为 HTTPS 生产域名且与线上页面一致（不要带 `#` 或使用 localhost），请修正后重新提交。」 |
| scope 过大 | 「请只申请必要 scope：昵称/头像用 `profile.basic`；如确需手机号，请在申请说明中写明用途。`notify` 当前未开放。」 |
| 应用名不可用 | 「应用名需为真实业务名称（用户会在授权页看到），请勿使用「测试/demo」等占位名。」 |
| 主体不匹配 | 「接入主体与商务主体不一致，请联系对接人确认主体名称后重新提交。」 |
| 类型填错 | 「第三方接入请使用 `THIRD_PARTY`；`FIRST_PARTY` 仅平台自用。」 |

### 3.4 密钥交付规则（必须遵守）

1. `appSecret` **只在「通过」/「重置密钥」的响应中出现一次**，页面不提供二次查看。
2. 交付走**安全渠道**：密码管理器、加密邮件/加密消息；**禁止**在普通聊天工具明文发送或截图。
3. 交付时一并告知：`appId`、批准的 scope、回调地址、接入文档链接（`IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md`）。
4. 第三方只能把它放在**服务端**；若对方说「放在 H5/App 里」，**拒绝交付**并要求其改架构。

### 3.5 变更、重置与吊销

| 场景 | 动作 | 影响 |
| --- | --- | --- |
| 换回调地址 / 加 scope | 当前**无公开自助修改端点**：由平台按变更流程处理（必要时驳回后重新申请） | 变更前旧配置继续生效 |
| 密钥疑似泄露 | 「重置密钥」 | 旧密钥**立即失效**，第三方需更新服务端配置 |
| 合作终止 / 违规 | 「吊销」 | 状态 `SUSPENDED`；该应用全部 token 与授权失效；第三方业务数据**保留** |
| 误吊销 | 需联系平台恢复（重新申请或后台恢复） | —— |

### 3.6 审计与留痕

- 申请、通过、驳回、重置密钥、吊销均由 im-admin-service 记入**审计日志**（操作人、时间、`appId`、结果）。
- 审核结论（尤其驳回原因、密钥交付方式）应在工单/对接群留痕，便于事后追溯。

---

## 4. 审核时效与职责 / SLA & ownership

| 环节 | 责任方 | 建议时效 |
| --- | --- | --- |
| 材料齐全性初判 | 商务/对接人 | 1 个工作日 |
| 技术项审核（回调/scope） | 平台运营/研发 | 1 个工作日 |
| 通过后交付密钥 | 平台运营 | 审核通过后当天 |
| 联调支持 | 双方研发 | 按项目排期 |

---

## 5. 相关文档 / See also

| 文档 | 内容 |
| --- | --- |
| [`IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md`](./IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md) | 第三方接入指南（前后台两段 + A380 实例 + 自测脚本） |
| `IM_OPEN_PLATFORM_03_QUICKSTART.md` | 快速上手（PKCE + curl） |
| `IM_OPEN_PLATFORM_04_SERVICE.md` | 轻量层：C 端服务板块服务项接入（无需账号互通） |
| `IM_OPEN_PLATFORM_01_SERVICE.md` | 开放平台总体方案与实施边界 |
