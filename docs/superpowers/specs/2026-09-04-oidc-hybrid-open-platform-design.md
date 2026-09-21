# OAuth 2.0 + OIDC Hybrid 开放平台改造设计

**状态：** 已完成联合审查与 2026-09-04 权限基线复核，待实施评审；在阻断问题关闭前不得宣称已具备完整 OIDC/B 端企业授权能力

**范围：** `gv_im_server`、`gv_chat_app`、`gv_chat_desktop`、`gv_saas_mobile`、`gv_saas_admin`、`gv_chat_admin`

## 0. 规范优先级与遵循矩阵

本设计是既有工程规范下的领域改造方案，不创建新的工程规则，也不修改既有规则来迁就 OAuth/OIDC 实现。发生冲突时按以下优先级执行：法律/安全基线 > 仓库级 `ENGINEERING_RULES.md` 与治理文档 > 领域/服务规范 > 客户端与前端规范 > 本设计中的实现建议。任何例外必须先形成 ADR、明确责任人、影响范围、回滚方式并通过架构评审。

| 领域 | 必须遵循的权威规范 | 本方案的落点 | 验证门禁 |
| --- | --- | --- | --- |
| 服务架构 | `docs/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md`、`ENGINEERING_GOVERNANCE_01_CONFORMANCE.md` | Provider 归 `im-user-service`；SaaS BFF 归 `platform-identity-service`；租户安装归 `platform-tenant-service`；Gateway 仅做入口、鉴权、限流和上下文透传；遵循 `api -> application -> domain <- infra` | 架构评审、模块依赖检查、统一工程验证 |
| 数据库 | `DATABASE_MIGRATION_STANDARD.md`、各服务 Flyway 规范 | MySQL 为权威库；Redis 只存授权码/会话/缓存/限流/幂等；Mongo 只存消息投影；新表必须使用领域前缀、审计字段和统一字符集；不使用物理外键或 JSON 核心查询字段 | Flyway 校验、迁移演练、Schema Review |
| Maven/发布 | `20_MAVEN_ENGINEERING_CONVENTIONS.md`、发布 Runbook | 版本由服务域父 POM 所有；依赖先经 BOM；协议模块变更后执行 `install`；发布沿用现有 Runbook、ACK 和版本规则 | `invoke-engineering-validation.ps1`、CI Full、发布检查单 |
| API 契约 | `docs/contracts/openapi/README.md` | Java DTO、OpenAPI snapshot、Bridge、Flutter、Electron、H5 和官网示例必须同源核对；禁止只维护手工 Markdown 契约 | OpenAPI 导出/差异检查、契约测试 |
| Flutter | `gv_chat_app/docs/architecture.md`、`project-conventions.md` | 非 UI 抽象进 `packages/gv_core`，UI 基础进 `packages/gv_ui`；业务留在 services/repositories/providers/screens；依赖经 `AppInjectionModule` 注册；文案进入中英文 ARB | generator、`dart format`、`flutter analyze`、相关测试 |
| Electron | `gv_chat_desktop/docs/architecture.md`、`coding-guidelines.md`、`contracts.md` | 渲染层按 views → stores → services → repositories → models；Node 能力只在主进程；preload 仅白名单 `contextBridge`；密钥使用既有安全存储 | lint、单测、契约检查 |
| Web/H5/后台/官网 | `gv_chat_admin` 前端规范与错误码规范、SaaS security contract、官网 i18n/发布规范 | 同源 `/api/v1`、HttpOnly/CSRF、服务端租户上下文；后台遵循既有页面组件、分页、字段和错误码映射；官网中英双语且只描述已验收能力 | error-message 校验、构建、E2E、静态站发布检查 |

上述规范文件是实施时的唯一解释来源；若规范文件后续升级，本设计和执行计划必须同步修订后再开发。

## 0.1 当前权限基线复核（2026-09-04）

本次复核确认 Tenant 服务已经存在 `iam_consumer_application` 消费应用注册表、`PermissionSnapshotProvider`、Redis 权限快照和 `authorization_version` 字段，并新增 `V15__normalize_iam_authorization_versions.sql` 将存量 ACTIVE 角色绑定的版本归一为 1。该能力解决了 A380 消费者上下文的基础链路，但它不是完整的 OIDC 用户同意、企业安装和用户授权模型，不能直接当作最终方案。

必须在实施前补齐以下边界：

- `iam_consumer_application` 当前把 app、单一租户/组织/门店和 `permissions_json` 绑定在一行，适合作为 A380 兼容注册，不足以表达一个应用被多个企业安装、用户逐项授权和数据范围变更；目标模型需要通过新增规范化关系承接，不能再复制一套未定义归属的注册表。
- 权限快照的本地 `ConcurrentHashMap` 兜底没有独立 TTL；Redis 故障时可能长期保留旧权限。缓存只能加速读取，敏感操作必须可失效、可回源并在依赖不可用时失败关闭。
- 角色权限变更目前执行全量逐出，但没有统一递增授权版本；上下文 JWT 也没有携带 app 绑定，资源服务尚未按版本/撤销状态重新校验。仅凭 30 分钟 JWT 过期不能满足“撤销立即生效”。
- `/internal/iam/**` 依赖“不经过网关”的部署假设，仍需服务间认证、网络策略和审计，不能把路径名称当作安全边界。
- `AuthContextApplicationService` 的 consumer context 选择必须比较请求 context 中的 appId 与服务端会话 appId；当前仅按会话 appId 查询权限时，存在命名空间校验遗漏的风险。
- `/admin/iam/**` 当前控制器层未充分表达“权限码 + 租户/角色归属 + 操作人”的授权约束；拥有有效上下文不应自动获得角色、用户角色或应用授权管理能力。

因此，本设计将 A380 现有注册表视为兼容基线，将 `V15` 视为已占用版本；后续 Tenant 迁移从实际最大版本之后递增，并在 ADR 中固化“兼容表 → 规范化安装/授权关系”的收敛路径。

### 当前阻断问题与验收要求

以下问题来自当前代码复核，必须在 OIDC/B 端能力开放前关闭：

1. `consumerSnapshot(appId, accountId)` 目前按 `appId` 读取应用行，未用 `accountId` 验证用户授权；`/internal/iam/consumer/{appId}/context` 也不接收账号主体。任何持有该应用会话的账号都可能获得同一应用权限，不能满足“企业安装 ∩ 用户授权”模型。修复后必须由服务端同时校验主体、安装状态、用户授权、角色、数据范围和 API scope。
2. `authorization_version` 的 V15 迁移只把非正值归一为 1，现有 IAM 写操作也多次固定写 1 或仅清缓存，没有形成单调递增的授权版本。修复后角色权限、用户角色、安装、数据范围和用户授权变更必须原子递增版本并触发跨实例失效。
3. 权限快照本地缓存没有独立 TTL，Redis 失效时可能继续使用旧权限；Redis `KEYS` 全量扫描也不适合生产。修复后本地缓存必须有上限和 TTL，失效采用版本键、事件或 `SCAN`，敏感写操作在无法确认权限时失败关闭。
4. `/internal/iam/**` 目前主要依赖直连端口和路径约定，未形成服务间认证、调用方白名单、网络策略和审计闭环。修复后缺少内部凭据或调用方不在 allowlist 必须拒绝。
5. Consumer context 的 `contextId` 虽含 `appId`，选择流程仍必须显式比较 context appId、服务端会话 appId 和注册表 appId；不能只依赖字符串前缀或客户端提交值。
6. 当前网关仍使用通用 `__Host-saas_session`，尚未实现 C/B 独立 Cookie 或等价的强制 `client_id` 会话绑定；在切换前必须完成隔离和串读测试。
7. `/admin/iam/**` 的角色、权限和用户角色写接口不能仅凭有效租户上下文放行，必须补充权限码、租户归属、角色层级、操作人和幂等审计校验。

现有 `iam_consumer_application`/V14 脚本中的 JSON 权限、`IF NOT EXISTS` 等属于历史兼容债务，不修改历史脚本；目标新增迁移不得复制这些做法，并必须提供兼容读取、一次性校验和收敛完成的退出条件。

## 0.2 实施节奏与发布硬门禁

实施采用“一个边界、一个检查点、一次确认”的节奏。Provider、Identity/Tenant/IAM、客户端/H5、后台/官网分别独立实现和验证；每个阶段完成后必须提交变更摘要、测试证据、契约差异、迁移状态、风险与回滚点，人工确认后才能进入下一阶段。任务中断时从最近一次已确认检查点恢复，不跳过测试、不重复已确认变更。

当前只有本机 Kind 和阿里云 ACK 两个可用集群。上线顺序固定为：本机 Kind 完整验证 → Android Emulator 完成真实测试 → 检查点确认 → 使用同一制品发布 ACK 回归 → 正式发布 → 发布后观察。未完成本地真实 Flyway、服务集成、跨端构建、E2E、安全预检、产物扫描和 Android Emulator 测试，不得推送 ACK；Kind/Emulator 通过也不代表自动获得正式发布授权，ACK 回归仍须通过并遵循既有 Release Runbook、ACK 和客户端发布规范。

## 1. 目标

将现有 OAuth 授权码骨架、多套账号体系和 SaaS Session 收敛为可互操作的 OAuth 2.0 + OIDC Provider，并为 IM 内嵌 WebView、Flutter、Electron 和 B 端企业应用提供同一套授权语义。

上线前必须满足：客户端不持有密钥；授权码具备 PKCE、state、nonce、一次性消费；敏感权限有用户同意；应用来源、回调和 scope 绑定；C/B 租户权限交集在服务端计算；撤销会阻断令牌和资源访问。

## 2. 非目标

- 本阶段不开放聊天、好友、群组数据库直连。
- 不把长期 `corp_access_token` 当作企业授权事实。
- 不通过清空全局 WebView Cookie 实现隔离。
- 不在 v1 中保留旧 Socket.IO、旧 bearer 绑定或协议双调用。

## 3. 目标架构

### 3.1 身份与授权

`group-idaas`、`im-user open_application`、`platform-identity` 形成唯一 OIDC Provider 与 Client Registry（Provider 实现在既有 User 服务边界内，SaaS 仍由 Identity BFF 消费）。内部账号使用不可变 subject；各业务域通过 `federated_identity(issuer, subject, account_id)` 映射，不再用可变 username 做 SSO 关联。

Provider 发布：

- `/.well-known/openid-configuration`
- `/jwks.json`
- `/oauth/authorize`
- `/oauth/token`
- `/oauth/userinfo`
- `/oauth/introspect`
- `/oauth/revoke`

授权请求使用 `response_type=code`、`client_id`、精确 `redirect_uri`、`scope=openid ...`、`state`、`nonce`、`code_challenge` 和 `S256`。Token 请求使用 RFC 6749 表单参数；Confidential Client 使用 HTTP Basic，Public Client 只用 PKCE。

ID Token 使用 RS256 或 ES256，包含 `iss`、`sub`、`aud`、`azp`、`nonce`、`iat`、`exp`，并通过 `kid` 支持轮换。对外 `sub` 按应用 pairwise 派生。

### 3.2 Hybrid 授权

H5 生成 verifier、state、nonce 和 challenge；Native Bridge 只代发授权请求并返回一次性 code，不持有 client secret。默认采用标准重定向闭环：Native 将 code 和原始 state 回调到已登记的 `redirect_uri`，由 BFF 接收并换码；仅在平台限制重定向时才启用版本化 JS 回调，code 只存在 H5 内存并立即同步提交 BFF，禁止写入 Storage。Bridge Dispatcher 根据服务目录中的已审核元数据固定 `client_id`、origin、redirect URI 和 scope，拒绝 H5 覆盖这些值。

Native 对敏感 scope 展示原生确认。SaaS BFF 在服务端完成 code exchange、ID Token 验签和账号绑定，然后签发按应用隔离的 HttpOnly Session Cookie。C 端和 B 端使用不同 Cookie 名称或强制会话绑定 `client_id`。

### 3.3 企业 B 端

新增租户安装、数据范围和用户授权关系。现有 `iam_consumer_application` 作为 A380 兼容注册保留并逐步迁移；规范化目标表归属和命名必须遵循既有领域边界：Provider 注册表归 User 域并使用 `user_` 前缀，身份映射归 Identity 域并使用 `idt_` 前缀，企业安装和数据范围归 Tenant 域并使用 `tnt_` 前缀，用户授权关系归 IAM 域并使用 `iam_` 前缀；不得在 Identity 服务创建 Tenant 表，也不得在 Tenant 服务复制 OIDC Provider 注册表。

每次资源访问执行：

```text
租户安装授权 ∩ 用户授权 ∩ 角色权限 ∩ 组织/门店范围 ∩ API scope
```

企业安装、卸载、停用、员工离职和 scope 升级均可审计并即时影响访问。服务端使用短期 service token 或服务端安装记录，不向浏览器暴露企业 bearer token。

### 3.4 客户端容器

Flutter 和 Electron 均实现统一 Bridge 契约，但按平台选择容器实现。每个 `appId` 使用独立 Cookie、Local Storage 和 WebView profile；导航只允许注册 HTTPS origin，禁止 `file:`、`javascript:` 和未登记跳转。Windows 使用 WebView2 独立 user-data-folder；不支持的平台明确降级到外部浏览器。

## 4. 数据与接口变更

新增或扩展（具体归属先以 ADR 固化）：User 域 `user_oidc_client`、`user_oidc_redirect_uri`、`user_oidc_scope`、`user_oidc_consent`、`user_oidc_token_family`；Identity 域 `idt_federated_identity`；Tenant 域 `tnt_app_installation`、`tnt_app_data_scope`；IAM 域 `iam_user_app_grant`、必要时的 `iam_consumer_application_permission`（用于淘汰 `permissions_json` 核心查询）；`iam_security_audit_event` 沿用现有审计设施。授权码和短期事务优先存 Redis，不为缓存状态强行建表。历史 `open_application`、`iam_consumer_application` 及历史 Flyway 脚本不重命名、不修改，只通过后续迁移和兼容读取收敛。

所有新迁移必须放在持有该领域数据的服务中，使用递增的 `V{版本}__英文描述.sql`，并满足 InnoDB、utf8mb4、统一排序规则、无物理外键、标准审计字段和中文注释要求；迁移脚本不得使用 `IF NOT EXISTS` 或 `DROP TABLE`。权限快照只能是可失效的 Redis/JWT 缓存，不能替代 MySQL 权威、实时撤销和离职校验。

授权码和 refresh token 必须原子消费。Refresh Token Rotation 每次换码同时颁发新 refresh token 并立即作废旧值；检测到重放时撤销整个 family，并记录设备/客户端绑定信息（设备绑定不能替代 PKCE）。撤销接口不在 URL 传 token；未知 token 返回成功以防枚举。

## 5. 分阶段验收

### M0：上线阻断

- Flutter 和桌面端无 client secret。
- 关闭旧 `/identity/oauth/im/bind` 外部入口。
- Bridge 完成 origin、client、redirect、scope、state、nonce 校验。
- Consent 绑定用户并有 CSRF 防护。
- 授权码原子消费；旧共享密钥全部轮换。

### M1：OIDC 与资源服务

- Discovery、JWKS、ID Token、userinfo、introspection、revocation 可互操作。
- Gateway 和业务 API 真正校验 access token、scope、issuer、audience。
- C/B Session、账号注销、禁用和撤销状态一致。

### M2：企业与客户端生态

- 企业安装授权、数据范围、审计、撤销事件和限流完成。
- Flutter/Electron 隔离容器和跨端 E2E 完成。
- 开发者门户、SDK、OpenAPI 与实际契约一致。

## 6. 安全验收

必须覆盖：授权码重放、PKCE 错误、state/nonce 重放、redirect mix-up、恶意 origin 调 Bridge、C/B Cookie 串读、scope 越权、租户交叉访问、refresh token 重放、撤销后 userinfo/API 访问、Windows WebView2 和外部浏览器降级。
