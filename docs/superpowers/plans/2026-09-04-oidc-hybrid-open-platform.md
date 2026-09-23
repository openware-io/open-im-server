# OAuth 2.0 + OIDC Hybrid Open Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 OAuth 骨架、多套身份和 Hybrid 客户端收敛为可验证、可撤销、可按租户授权的 OAuth 2.0 + OIDC 开放平台。

**Architecture:** `gv_im_server` 内既有 User 服务提供唯一 OIDC Provider，Gateway 提供 Resource Server 入口，Tenant/IAM 服务提供企业安装与权限事实；SaaS 后端作为 BFF 保存 confidential 凭据并签发按应用隔离的 HttpOnly Session；Flutter/Electron 只通过受 origin 约束的 Bridge 获取一次性授权码。

**Tech Stack:** Spring Boot 4、Spring Security、Redis、MySQL/Flyway、Flutter WebView、Windows WebView2、Electron、Vue/Vite、Playwright、JUnit、Flutter integration_test。

**Spec:** `docs/superpowers/specs/2026-09-04-oidc-hybrid-open-platform-design.md`

## Global Constraints

- Public Client 不得包含 `client_secret`；Confidential Client 只在 SaaS 后端使用 Secret Manager 注入的独立密钥。
- 授权请求必须使用 `response_type=code`、精确 `redirect_uri`、`state`、`nonce`、`code_challenge` 和 `S256`。
- 授权码与 refresh token 必须原子消费；refresh token 重放撤销整个 token family。
- C/B H5 只使用各自的 HttpOnly Session，不持久化 bearer token、租户上下文或会员 ID。
- WebView 按 `appId` 隔离 Cookie、Local Storage 和 user-data profile；不得清理全局 Cookie。
- 旧 `/identity/oauth/im/bind`、旧自定义 OAuth 参数和未受保护的 `/clients` 只能在迁移期开启，不能作为公开生产能力。
- 所有实现必须补充成功、认证失败、权限失败、重放、撤销和跨租户测试。

## Existing Standards Gate

本计划的所有任务都必须服从仓库现有架构、数据库、Maven、发布、安全、Flutter、Electron、SaaS、管理后台和官网规范；不以本次改造为理由修改这些规范。冲突或例外先提交 ADR，未经评审不得编码。

| 约束 | 执行要求 | 验证 |
| --- | --- | --- |
| 服务边界 | Provider 留在 `im-services/user/im-user-service`；BFF 留在 `platform-services/identity/platform-identity-service`；安装/组织/门店/IAM 按现有 Tenant/IAM 所有权实现；Gateway 不持有业务数据或 Mapper | DDD 分层与模块依赖检查 |
| 数据边界 | MySQL 是权威库；Redis 仅状态/缓存/限流/幂等；Mongo 仅消息投影；新表按 `user_`/`idt_`/`tnt_`/`iam_` 领域前缀命名 | Flyway 校验与 Schema Review |
| 迁移规则 | 迁移放在数据所属服务，版本单调递增，使用 `V{版本}__英文描述.sql`；统一审计字段、InnoDB、utf8mb4、中文注释；不改历史脚本、不使用物理外键、`IF NOT EXISTS` 或 `DROP TABLE` | 迁移演练、统一工程验证 |
| 契约与依赖 | DTO 放 API 模块，数据库/Mapper/Flyway 仅在 service；新增接口同步 OpenAPI snapshot、Bridge、Flutter、Electron、H5 和官网；依赖先检查 BOM，协议模块改动后执行 `install` | OpenAPI diff、Maven validation |
| 客户端与前端 | Flutter 遵循既有分层、DI、生成器、ARB；Electron 遵循主进程/白名单 preload/safeStorage；SaaS 同源 HttpOnly/CSRF；Admin 遵循页面组件、分页、camelCase、错误码映射；官网中英双语 | analyzer/lint/build/E2E |
| 发布与日志 | 沿用现有 Release Runbook、ACK、版本所有权和 `.outputs/logs/build/<yyyyMMdd>/` 日志规则；不得另造发布流程 | `invoke-engineering-validation.ps1` |

每个任务的完成定义均包含：代码/文档符合上述矩阵、相关测试通过、契约已同步、变更记录和回滚点已登记。

## Execution Strategy

- **切换模式：** 正式环境尚无真实存量用户，采用一次性 v1 切换；不做旧新双写、双 Cookie、双协议或 Shadow Mode。
- **数据策略：** local/test/pre-release 使用新数据库或清空重建；不迁移历史演示 OAuth/Session 数据。正式环境按空库初始化，只保留经审核的种子租户、应用和测试账号。
- **凭据策略：** 每个环境、每个 `client_id` 使用独立 secret/JWKS；旧共享 secret 不迁移，部署前生成新值并通过 Secret 注入。
- **发布顺序：** 先迁移数据库和密钥，再发布 Provider/Gateway，再发布 SaaS/H5/客户端，最后发布官网；任一步门禁失败即停止，不允许部分开放。
- **回滚策略：** 只回滚本次制品、配置和数据库备份；不恢复旧公开 bind 接口、旧 Cookie 或客户端 secret。
- **文档策略：** 官网、OpenAPI、Bridge SDK、管理后台字段和后端实际端点必须由同一份 v1 契约核对；未验收能力不得出现在“已支持”内容中。

## Local-First Release Gate

本项目严格执行“本地验证通过后才能发布”：本地验证是发布的硬前置条件，不得用 CI、ACK 健康状态或人工点测替代。任何本地 P0/P1 失败、迁移失败、契约不一致、构建产物含 secret、测试未执行或结果未留档，都必须停止发布。

1. **可用环境边界：** 当前只允许本机 Kind 集群和阿里云 ACK 集群；不虚构或依赖 dev/test 环境。Kind 使用独立 local 数据库、Redis、密钥和回调域，执行真实 Flyway 迁移、种子数据和服务间调用。
2. **本地验证顺序：** 本机 Kind 部署并通过模块单测/契约测试 → 数据库迁移和回滚演练 → 服务集成测试 → H5/Flutter/Electron/Admin 构建与测试 → 使用 Android Emulator 完成 Hybrid/OAuth、C/B 会话和权限链路实测 → Playwright/E2E → 安全预检 → 产物和配置扫描。涉及 API 模块时先 `mvn install`，再执行统一 Changed 校验。
3. **发布授权材料：** 检查点记录必须包含 commit/diff、测试命令与结果、迁移版本、OpenAPI snapshot diff、镜像/artifact digest、环境变量清单、已知风险和回滚点，日志按既有 `.outputs/logs/build/<yyyyMMdd>/` 规则保存。
4. **环境晋级：** 只有本机 Kind 全部通过、Android Emulator 真实测试通过并经 CP6 检查点确认后，才允许发布到阿里云 ACK 做回归测试；ACK 必须再次执行数据库/配置校验、smoke、E2E、权限撤销和镜像一致性验证。ACK 回归通过并经 CP7/发布负责人批准后，才允许正式上线。官网只能在后端、客户端、Kind、Android Emulator 和 ACK 回归全部验收后发布。

## Long-Running Task Checkpoints

为适应 AI 长程任务限制，实施拆成可独立暂停和恢复的波次。每一波只处理一个明确边界；完成后必须生成检查点记录并暂停，待人工确认“结果、差异、风险和下一步”无误后再继续，禁止跨检查点自动推进或一次性修改全部工程。

| 检查点 | 范围 | 必须产出 | 继续条件 |
| --- | --- | --- | --- |
| CP0 基线冻结 | 读取规范、确认分支/工作区、记录当天权限现状 | 基线清单、规范矩阵、风险登记、未提交变更清单 | 架构/安全负责人确认范围和优先级 |
| CP1 契约与 ADR | 固化 Provider、BFF、Tenant/IAM、A380 兼容表收敛关系 | ADR、DTO/OpenAPI 草案、表归属和版本分配 | Schema、API 和安全评审通过 |
| CP2 Provider | 只实现 User Provider、Client Registry、授权码/Token/OIDC | Provider 单测、迁移校验、OpenAPI snapshot | 本地 Provider 测试和 Changed 校验通过 |
| CP3 Identity/Tenant/IAM | BFF 映射、安装/用户授权、版本失效、内部认证 | IAM 测试、迁移演练、权限矩阵、审计记录 | P0 权限问题关闭且本地服务集成通过 |
| CP4 客户端/H5 | Flutter/Electron Bridge、profile 隔离、C/B Session、H5 | analyzer/lint/build、Bridge 安全测试、E2E | 本地跨端验证通过，产物扫描无 secret |
| CP5 管理后台/官网 | Admin、开发者页、错误码、双语和契约同步 | 前端构建、错误码校验、OpenAPI/文档 diff | 只展示已验收能力，文档评审通过 |
| CP6 本地发布候选 | 本机 Kind 全量验证 + Android Emulator 真实测试 | Kind 部署记录、Android 测试矩阵、完整验证报告、digest、配置扫描、回滚演练 | Emulator 与 Kind 均通过并人工确认后，才可推送 ACK |
| CP7 ACK 回归 | 发布到阿里云 ACK 后执行完整回归 | ACK rollout、smoke/E2E、权限撤销/恢复、数据库与配置隔离、镜像一致性和回归记录 | ACK 回归通过且发布负责人批准后，才可正式上线 |
| CP8 正式发布后 | 观察认证、权限拒绝、撤销、错误率和回滚指标 | 发布观察报告、遗留风险和关闭项 | 完成收尾评审；否则执行既定回滚 |

每个检查点的固定流程为：读取上个检查点记录 → 小范围实现 → 针对性验证 → 更新 diff/status 和风险 → 保存日志/证据 → 停止并等待确认。若上下文压缩或任务中断，从最近一个已确认检查点恢复，不重复已确认工作，也不跳过验证。

## Permission Baseline Delta

今天已落地的权限基线必须作为本计划的前置输入，而不是被新 OAuth 设计覆盖：Tenant 服务已有 `iam_consumer_application`、消费者上下文、权限快照和 `authorization_version`；`V15__normalize_iam_authorization_versions.sql` 已占用 Tenant 迁移版本，并将 ACTIVE 角色绑定的非正版本归一为 1。

- A380 的 `iam_consumer_application` 是现有兼容注册，不等于完整企业安装。先提交 ADR 决定其与目标 `tnt_app_installation` 的收敛关系；不得并行维护两套事实来源。
- 目标模型必须支持一个应用多企业安装、用户级授权和多维数据范围；`permissions_json` 不得继续作为核心查询事实，必要时在 Tenant/IAM 所属服务新增规范化子表并提供一次性初始化。
- `authorization_version` 需在角色权限、用户角色、应用安装、数据范围和用户授权发生变化时统一递增；资源服务或网关必须用版本/撤销状态重新校验，不能只依赖 30 分钟上下文 JWT。
- 权限快照本地兜底必须有明确 TTL；Redis/数据库不可用时敏感写操作失败关闭。`KEYS` 全量逐出应替换为可控的版本键、事件逐出或 `SCAN`，并补充多实例一致性测试。
- `/internal/iam/**` 必须增加服务间认证、网络策略、调用方白名单和审计；context 选择必须校验请求 appId、会话 appId、注册表 appId 三者一致。
- `/admin/iam/**` 的写操作目前主要依赖网关会话/上下文，执行层仍需按权限码和租户/角色归属校验（至少角色管理、用户角色分配、应用安装/撤销）；不能把“有上下文”当作“有管理权限”。
- 现有 A380 兼容测试继续保留；新增 OIDC 测试不得假设消费者拥有 operator 角色，也不得把消费者权限混入 `iam_user_role`。
- P0：当前 `consumerSnapshot` 未按 account 校验、consumer context 接口未绑定账号，不能直接作为 B 端企业授权；必须先建立用户授权关系并验证安装/用户/角色/数据范围/scope 全链路。
- P0：`authorization_version` 当前只归一为 1，写操作未单调递增；撤销和授权变更必须让旧上下文立即失效，而不是只等待 JWT 到期。
- P1：历史 V14/`iam_consumer_application` 使用 JSON 和 `IF NOT EXISTS`，按历史债务保留但不得仿造；目标表和迁移必须完全遵循数据库规范。

本次复核对应的当天变更包括：Identity 版本从 `2.0.7` 升至 `2.0.8`、`/auth/csrf` 增加禁止缓存响应头、A380 consumer context 接入 Identity session，以及 Tenant `V15` 授权版本归一迁移。上述变更不改变本计划的 OIDC 目标，但要求先完成以下契约确认：A380 兼容 consumer flow 仅作为过渡；B 端企业授权不得复用“按 app 返回固定权限”的接口；Identity/Tenant 版本发布必须在 Maven install、迁移校验和 ACK 镜像一致性门禁后进行。

---

### Task 1: 建立统一 OIDC Provider 与 Client Registry

**Files:**
- Create: `im-services/user/im-user-service/src/main/java/io/openware/im/user/api/controller/OidcMetadataController.java`
- Create: `im-services/user/im-user-service/src/main/java/io/openware/im/user/api/controller/OidcAuthorizationController.java`
- Modify: `im-services/user/im-user-service/src/main/java/io/openware/im/user/application/openplatform/OpenPlatformApplicationService.java`
- Modify: `im-services/user/im-user-service/src/main/java/io/openware/im/user/api/dto/request/OauthTokenRequest.java`
- Modify: `im-services/user/im-user-service/src/main/java/io/openware/im/user/api/dto/response/OauthTokenResponse.java`
- Create: `im-services/user/im-user-service/src/main/resources/db/migration/V20__user_oidc_registry.sql`（确认当前版本后顺延；表名使用 `user_` 前缀）
- Create: `im-services/user/im-user-service/src/test/java/io/openware/im/user/application/openplatform/OidcProviderContractTest.java`

**Interfaces:**
- Consumes: existing `OpenApplicationRepository`, `AuthorizationCodeStore`, `OauthTokenStore`, `OpenUserAuthorizationRepository`。
- Produces: metadata、JWKS、标准 authorize/token/userinfo/introspection/revocation 端点；`id_token`、`token_type`、`scope` 和 `expires_in` 标准响应。

- [ ] **Step 1: Write failing provider contract tests**

测试必须断言：缺失 `response_type/client_id/redirect_uri/state/code_challenge/nonce` 返回标准错误；token 接受 `application/x-www-form-urlencoded`；Public Client 不要求 secret；Confidential Client 支持 HTTP Basic；ID Token 含 `iss/sub/aud/nonce/iat/exp`。

- [ ] **Step 2: Add Flyway registry tables**

创建 User 域的 `user_oidc_client`、`user_oidc_redirect_uri`、`user_oidc_scope`、`user_oidc_consent`、`user_oidc_token_family`，为 client 状态、认证方式、回调白名单、scope 等字段建立唯一索引；`idt_federated_identity` 由 Identity 服务单独迁移，租户和 IAM 表不得放入本迁移。历史 `open_application` 保持不改名，仅在空库初始化时按审核结果生成独立 client 记录。

- [ ] **Step 3: Implement metadata and JWKS endpoints**

从配置注入 issuer，发布 authorize/token/userinfo/introspection/revocation 端点与 JWKS URI；签名密钥使用 `kid`，私钥不写入仓库，启动时拒绝默认密钥。

- [ ] **Step 4: Implement standard authorize and consent transaction**

校验 client、精确回调、scope、PKCE、state、nonce 和当前用户；同意事务绑定用户和 client；approve/deny 使用 CSRF token；成功和拒绝都只回调已登记地址。

- [ ] **Step 5: Implement standard token and ID Token response**

使用表单参数和 HTTP Basic 认证；授权码通过 Redis Lua `GETDEL` 原子消费；生成签名 ID Token 和按 client 派生的 pairwise `sub`；userinfo 只返回最终批准 scope 字段。

- [ ] **Step 6: Implement introspection and revocation**

支持 access/refresh token 撤销、应用撤销、用户撤销；撤销后的 token 在 userinfo 和 Resource Server 均不可用；未知 token 返回成功。

- [ ] **Step 7: Run focused provider tests**

Run: `mvn -pl im-services/user/im-user-service -Dtest=OidcProviderContractTest test`

Expected: 所有标准参数、签名、scope、重放和撤销用例通过。

**规范完成门禁：** DTO 只能位于 `im-user-api`，实现遵循 `api -> application -> domain <- infra`；Flyway 使用服务域父 POM 管理的 starter；导出并更新 OpenAPI snapshot；执行 `mvn install`（涉及 API 模块时）和 `powershell -NoProfile -ExecutionPolicy Bypass -File .\\scripts\\validate\\invoke-engineering-validation.ps1 -Scope Changed`。不得新建重复 Provider 微服务、不得把私钥写入仓库、不得修改历史迁移。

### Task 2: 收敛 SaaS BFF、身份映射和 B 端企业授权

**Files:**
- Modify: `platform-services/identity/platform-identity-service/src/main/java/io/openware/platform/identity/infra/oauth/ImOAuthClient.java`
- Modify: `platform-services/identity/platform-identity-service/src/main/java/io/openware/platform/identity/application/OAuthImBindApplicationService.java`
- Modify: `platform-services/identity/platform-identity-service/src/main/java/io/openware/platform/identity/api/controller/OAuthImBindController.java`
- Modify: `platform-services/identity/platform-identity-service/src/main/java/io/openware/platform/identity/application/AuthContextApplicationService.java`
- Modify: `platform-services/identity/platform-identity-service/src/main/java/io/openware/platform/identity/infra/client/TenantServiceClient.java`
- Modify: `platform-services/identity/platform-identity-service/src/main/java/io/openware/platform/identity/infra/security/SaasUserSessionCookie.java`
- Create: `platform-services/identity/platform-identity-service/src/main/resources/db/migration/V6__idt_federated_identity.sql`
- Create: `platform-services/tenant/platform-tenant-service/src/main/resources/db/migration/V16__tnt_app_installation_and_scope.sql`（`V15` 已被权限版本归一迁移占用；以实际当前最大版本顺延）
- Modify: `platform-services/tenant/platform-tenant-service/src/main/java/io/openware/platform/tenant/application/PermissionSnapshotProvider.java`
- Modify: `platform-services/tenant/platform-tenant-service/src/main/java/io/openware/platform/tenant/infra/authorization/PermissionSnapshotCache.java`
- Modify: `platform-services/tenant/platform-tenant-service/src/main/java/io/openware/platform/tenant/infra/persistence/mapper/IamSnapshotMapper.java`
- Modify: `platform-services/tenant/platform-tenant-service/src/main/java/io/openware/platform/tenant/api/controller/InternalIamController.java`
- Modify: `platform-services/tenant/platform-tenant-service/src/main/java/io/openware/platform/tenant/api/controller/IamController.java`
- Modify: `sdk/infrastructure/src/main/java/io/openware/infrastructure/tenant/TenantContextFilter.java`
- Modify: `gateways/gateway/src/main/java/io/openware/gateway/security/SaasSessionAuthenticationFilter.java`
- Create: `platform-services/identity/platform-identity-service/src/test/java/io/openware/platform/identity/application/OidcBffBindingTest.java`
- Create: `platform-services/identity/platform-identity-service/src/test/java/io/openware/platform/identity/application/TenantAppAuthorizationTest.java`

**Interfaces:**
- Consumes: Provider 标准 code exchange、ID Token、JWKS 和租户安装记录。
- Produces: `POST /identity/oauth/im/callback` 只接受 code、verifier、state、nonce transaction；按应用隔离的 SaaS Session；企业授权交集查询。

- [ ] **Step 1: Write failing BFF tests**

覆盖 ID Token issuer/audience/nonce 验证、稳定 subject 绑定、state 重放、C/B Cookie 隔离、未安装租户拒绝、用户 scope 不足拒绝和安装撤销立即失效；额外断言任意 account 不能读取另一个 account 的 consumer 权限，consumer context 的 appId 必须与 session/app registry 一致。

- [ ] **Step 2: Replace username SSO mapping**

以 `(issuer, subject)` 查询 `federated_identity`，禁止通过 username 建立新绑定；账号禁用、注销和状态变更在绑定时重新检查。

- [ ] **Step 3: Remove legacy bearer bind**

删除或改为仅内部服务认证的 `/identity/oauth/im/bind`；网关不再公开代理该路径；所有迁移失败返回明确错误码并记录审计。

- [ ] **Step 4: Enforce per-client SaaS sessions**

使用 `__Host-saas_c_session` 和 `__Host-saas_b_session` 或等价的 server-side `client_id` 绑定；响应体不得返回 session ID；网关从 Cookie 读取服务端会话并注入租户上下文。

- [ ] **Step 5: Reconcile A380 registry with enterprise installation authorization**

Identity 服务仅创建 `idt_federated_identity`（V6）；Tenant 服务从 `V15` 之后递增创建 `tnt_app_installation`、`tnt_app_data_scope`，并把现有 `iam_consumer_application` 的 A380 数据一次性映射到目标关系。IAM 授权关系使用 `iam_` 前缀并归既有 IAM 所属服务，必要时将 `permissions_json` 规范化为子表。业务访问计算“安装 ∩ 用户授权 ∩ 角色 ∩ 数据范围 ∩ API scope”，不接受浏览器提交的租户或门店权限快照，也不允许兼容表与目标表双重写入。

- [ ] **Step 6: Enforce permission-version and namespace invalidation**

角色权限、用户角色、安装、数据范围和用户授权变更统一递增授权版本并发布失效事件；快照缓存设置本地/Redis 双层 TTL，资源服务或 Gateway 在敏感请求上校验当前版本和撤销状态。为 `consumer:appId:tenant:organization:store` 增加 appId 一致性校验，拒绝跨 app、跨租户和 stale token；所有 `/admin/iam/**` 写操作增加权限码、租户归属、角色层级和操作人审计校验。

- [ ] **Step 7: Run focused BFF and IAM tests**

Run: `mvn -pl platform-services/identity/platform-identity-service,platform-services/tenant/platform-tenant-service -Dtest='OidcBffBindingTest,TenantAppAuthorizationTest,PermissionSnapshotProviderTest,PermissionSnapshotCacheTest' test`

Expected: 旧 bearer 入口不可用，C/B 会话和企业权限交集测试全部通过。

**规范完成门禁：** Tenant 表迁移不得放在 Identity 服务；会话、租户上下文和错误码遵循既有 SaaS security contract；审计事件接入现有审计设施；OpenAPI 与 Java API DTO 同步。执行服务级测试、`mvn install`（跨模块时）及统一 Changed 校验；不得通过 username 建立新绑定，不得新增无前缀表名或共享 Cookie，不得让 `iam_consumer_application` 与新安装表双写。

### Task 3: 重构 Flutter/Electron Hybrid 容器与 Bridge

**Files:**
- Modify: `gv_chat_app/lib/screens/protocol_webview_screen.dart`
- Modify: `gv_chat_app/lib/services/im_oauth_service.dart`
- Modify: `gv_chat_app/lib/core/config.dart`
- Modify: `gv_chat_app/lib/screens/services_screen.dart`
- Create: `gv_chat_app/lib/services/bridge/bridge_dispatcher.dart`
- Create: `gv_chat_app/lib/services/bridge/bridge_contract.dart`
- Modify: `gv_chat_app/pubspec.yaml`
- Modify: `gv_chat_desktop/src/main/index.ts`
- Create: `gv_chat_desktop/src/main/saas-window.ts`
- Create: `gv_chat_app/integration_test/hybrid_bridge_security_test.dart`

**Interfaces:**
- Consumes: 服务目录中的已审核 `appId/origins/redirectUri/scopes/version` 元数据和 BFF callback。
- Produces: `GVBridge.getAuthCode()`、`GVBridge.openChat()`、`GVBridge.chooseContact()` 的版本化契约；每应用隔离的 WebView/Electron profile。

- [ ] **Step 1: Write failing Bridge security tests**

测试恶意 origin、未知 method、scope 越权、state/nonce 缺失、请求超时、重复 nonce、未登记跳转、C/B profile 串读和登出清理。

- [ ] **Step 2: Remove client secret and local bearer fallback**

删除 `GV_IM_OAUTH_APP_SECRET` 的客户端读取；删除 KTV 直接 `/oauth/token`；禁止 `biz_token` 和 C token 作为 B 端回退凭据。

- [ ] **Step 3: Implement Bridge dispatcher**

使用结构化 `{id, method, params, nonce}` 请求；按当前 URL origin 和服务元数据校验；参数采用固定 schema；返回结构化错误，不把任意 H5 参数拼接进 JavaScript。

- [ ] **Step 4: Implement isolated WebView containers**

移动端使用按 appId 独立的数据存储；Windows 使用按 appId 的 WebView2 user-data-folder；导航只允许精确 HTTPS origin，禁止 file/javascript scheme；不执行全局 Cookie 清理。

- [ ] **Step 5: Add consent UX and lifecycle synchronization**

敏感 scope 由 Native 展示应用名、用途和权限；IM 登出或会话撤销时清除对应 profile 并向 H5 发送 `session-expired`，不向 H5 推送 IM JWT。

- [ ] **Step 6: Define desktop policy**

Electron 使用隔离 `BrowserWindow/WebContentsView` 和受限 preload；如果某平台只支持外部浏览器，产品文档明确不承诺 Hybrid，而不是静默伪装成内嵌能力。

- [ ] **Step 7: Run client tests**

Run: `flutter test integration_test/hybrid_bridge_security_test.dart`；`pnpm --dir gv_chat_desktop test`。

Expected: 恶意页面无法借用 IM 登录态，C/B profile 无法互读，客户端构建产物不包含 secret。

**规范完成门禁：** Flutter 抽象放入既有 `gv_core`/`gv_ui` 分层并经 `AppInjectionModule` 注册，文案同步中英文 ARB 后运行 generator、`dart format`、`flutter analyze` 和相关测试；不得因方案直接替换 `webview_flutter`，先完成平台能力 Demo。Electron 契约同步 `docs/contracts.md`，Node 能力只在主进程，密钥不得进入 localStorage；不得使用 `clearAllCache` 或全局 Cookie 清理。

### Task 4: H5、Gateway 和端到端安全验收

**Files:**
- Modify: `gv_saas_mobile/src/c-end/oauth.js`
- Modify: `gv_saas_mobile/src/b-end/oauth.js`
- Modify: `gv_saas_mobile/docs/backend-security-contract.md`
- Modify: `gv_saas_mobile/nginx.conf`
- Modify: `gateways/gateway/src/main/resources/application.yml`
- Modify: `../open-website/html/developer.html`
- Modify: `../open-website/html/js/i18n.js`
- Modify: `../gv_chat_admin/src/views/open-platform/applications.vue`
- Create: `gv_saas_mobile/e2e/oauth-hybrid.spec.js`
- Create: `scripts/verify/oidc-hybrid-preflight.ps1`
- Modify: `docs/renovation/IM_OPEN_PLATFORM_05_AUTHORIZATION_GOVERNANCE_DRAFT.md`
- Review/Modify: `docs/business/ACCOUNT_PERMISSION_MODEL.md`
- Review/Modify: `docs/im-services/tenant/README.md`
- Review/Modify: `docs/renovation/IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md`
- Review/Modify: `docs/renovation/IM_OPEN_PLATFORM_03_QUICKSTART.md`
- Review/Modify: `docs/renovation/SAAS_PLATFORM_02_SERVICE.md`
- Review/Modify: `docs/renovation/SAAS_PLATFORM_05_API.md`
- Review/Modify: `docs/renovation/SAAS_PLATFORM_07_EXECUTION.md`

**Interfaces:**
- Consumes: OIDC Provider、BFF Session、Gateway Resource Server 和 Bridge v1。
- Produces: 可重复执行的跨端验收矩阵、环境预检和文档/代码一致性报告。

- [ ] **Step 1: Unify C/B OAuth transaction**

两端都由同一 SDK 生成并保存 verifier、state、nonce；Bridge 和 redirect fallback 使用同一 callback transaction；回调成功后立即清理 URL 和 sessionStorage。

- [ ] **Step 2: Add browser security headers and endpoint policy**

保持同源 API、`no-referrer`、CSP、`frame-ancestors`；Gateway 对 OAuth 和 Resource Server 路径配置 client/tenant 级限流；拒绝内网、localhost 和未登记回调。

- [ ] **Step 3: Add Playwright E2E**

覆盖首次同意、拒绝、scope 升级、state/PKCE 错误、Cookie 隔离、用户撤销、应用撤销、租户卸载、401 重登和业务 API scope 越权。

- [ ] **Step 4: Add environment preflight**

脚本检查 issuer、HTTPS、JWKS、独立 client secret、Redis key prefix、数据库和回调域是否按 local/dev/test/prod 隔离；发现默认密钥、共享 secret 或生产回调混用时失败退出。

- [ ] **Step 5: Update governance document**

把 `IM_OPEN_PLATFORM_05_AUTHORIZATION_GOVERNANCE_DRAFT.md` 中“已有能力”改成代码和测试可证明的状态；同步 `ACCOUNT_PERMISSION_MODEL.md`、Tenant README 以及 IM/SaaS 旧集成/快速开始文档，清理与 v1 冲突的 appSecret、接入即授权、旧参数和“即时撤销”承诺；明确 OAuth-only 与完整 OIDC 的发布边界，并链接本设计与验收脚本。

- [ ] **Step 6: Publish the verified developer guide**

在 `open-website/html/developer.html` 增加应用注册、OIDC 快速开始、PKCE/Bridge、scope、B 端安装授权、撤销和错误码章节；在 `open-website/html/js/i18n.js` 同步补齐 `zh`/`en` 字典。页面只引用已通过 E2E 的 issuer、端点和示例，禁止出现真实 secret、内网地址、旧 `/identity/oauth/im/bind` 或客户端换码示例。

- [ ] **Step 7: Export and review OpenAPI snapshots**

按 `docs/contracts/openapi/README.md` 的既有命令从实现导出 Provider、BFF、Tenant/IAM 管理接口 snapshot，提交到规定目录并进行 diff；同步 Java API DTO、错误码、Bridge/H5/Flutter/Electron contract，删除手工文档中的过期参数。未通过 snapshot review 不得更新官网“已支持”章节。

- [ ] **Step 8: Align admin application governance**

在 `gv_chat_admin/src/views/open-platform/applications.vue` 与 `gv_chat_admin/src/api/openPlatform.js` 中确认应用申请、审核、回调 origin、scope、密钥轮换、企业安装和撤销页面均调用 v1 管理接口；普通用户访问返回 403，操作带 `Idempotency-Key` 并留下审计记录。

- [ ] **Step 9: Run Kind + Android Emulator local security preflight and build candidate**

在本机 Kind 环境执行：`powershell -ExecutionPolicy Bypass -File .\\scripts\\verify\\oidc-hybrid-preflight.ps1`；`npm --prefix gv_saas_mobile test`；`npm --prefix gv_saas_mobile run build`；相关 Maven/Flutter/Electron/Admin 测试与构建；真实 Flyway 迁移演练；OpenAPI snapshot diff；产物 secret 扫描。随后使用 Android Emulator 完成真实登录、Bridge 重定向、C/B profile 隔离、权限上下文选择、越权拒绝、撤销和重新登录测试。所有日志保存到既有 `.outputs/logs/build/<yyyyMMdd>/` 目录，生成 CP6 发布候选报告。

Expected: Kind 预检、H5 单测、构建、E2E、迁移、产物扫描和 Android Emulator 实测全部通过；任何 P0/P1 风险、未执行检查、设备失败或证据缺失都阻止推送 ACK。

- [ ] **Step 10: Run ACK regression before release approval**

仅使用已通过 CP6 的制品和 digest 发布到阿里云 ACK；执行既有 rollout、smoke、E2E、权限撤销/恢复、环境隔离、数据库迁移状态、配置/密钥注入和实际 Pod 镜像一致性检查，生成 CP7 回归报告。ACK 回归未通过时不得正式上线或更新官网。

**规范完成门禁：** H5 保持同源 `/api/v1`、HttpOnly Session、CSRF 和服务端租户上下文；Admin 页面沿用 `admin-page/page-header/admin-card`、统一分页、camelCase、`formatTime`、状态 `el-tag` 与 `src/utils/adminErrorMessage.js`，不得直接展示后端异常文本；官网修改 `developer.html` 与 `html/js/i18n.js` 的中英文词典，只发布已验收能力。执行 `npm run validate:error-messages`、前端 build、OpenAPI snapshot 校验、统一 Changed/Full 验证及既有发布 Runbook；页面路径必须以仓库实际文件为准，不得展示真实 secret、内网地址或旧 bind 接口。

## Execution Notes

任务按 1 → 2 → 3 → 4 顺序实施，并对应 CP2 → CP5；每个任务完成后先运行自身测试、更新证据，再暂停等待检查点确认。CP6 本地发布候选确认前不得进入预发布/ACK，CP7 未获发布负责人批准不得正式上线。当前工作区不自动创建提交，提交和合并由用户在审查计划后决定。
