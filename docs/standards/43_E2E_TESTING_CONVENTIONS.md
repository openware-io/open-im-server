# 43 E2E 测试与验收规范（Testing & Acceptance Conventions）

> 适用对象：`open-im-server`（服务端）、`open-chat-app`（App）、`open-chat-desktop`（PC 客户端）、`open-saas-mobile`/`open-saas-admin`/`portal`（SaaS H5/后台）。本规范随实践渐进补充，改动需同步更新本文与 SDD 台账。

## 1. 测试分层

| 层 | 内容 | 示例入口 |
| --- | --- | --- |
| 单元测试 | 领域/应用服务与基础设施适配器的单测 | `mvnw.cmd -B -ntp -pl <模块> -am '-Dtest=...' test`；Flutter `flutter test test/...`；SaaS `npm test` |
| 接口/契约测试 | 服务 API 契约、OpenAPI 快照、跨服务 DTO/错误码一致 | `OidcProviderContractTest`；`docs/contracts/openapi/*.json` 结构/语义审查；`validate-*` 校验脚本 |
| 集成验证（本地 Kind 真实部署） | 完整业务闭环的真实数据验证，禁止 mock | `scripts/verify/*.ps1`（见 §3）；发布清单同 digest 部署后执行 |
| 端到端真实设备 | Android **Emulator**（当前基准设备 `emulator-5554`）上运行 App；PC 客户端在本地安装真实安装包后测试 | `open-chat-app/integration_test/*.dart`；`open-chat-desktop` 安装包本地验收 |

规则：mock 只能作为开发辅助；任何 CP6/CP7 验收证据必须是真实部署 + 真实数据链路，不能用 mock 结果替代（见 OIDC 计划 CP6 验收规则）。

## 2. 验收门禁顺序

1. 本地代码级：模块单测/接口测试通过 → `validate-*` 工程校验（版本所有权、secret 扫描、Flyway 边界、依赖图等）通过。
2. CP6（本地 Kind + 真实设备）：按统一发布清单把**同一批 ACR 镜像（digest）**部署到本地 Kind → 运行 §3 验证脚本与 App/桌面端真实用例 → 一次性测试数据在 `finally` 中删除并核验不存在。
3. CP7（ACK 回归）：仅在 CP6 通过后，用**同一份发布清单（同一 digest，含 `im-user-service`）**在 ACK 回归，重复 OAuth/OIDC 与 SaaS KTV 关键用例。
4. 正式发布动作（push / merge / 生产发布 / 官网支持状态）必须经用户明确授权。

## 3. 现有验证脚本（`scripts/verify/`）

| 脚本 | 用途 |
| --- | --- |
| `oidc-hybrid-preflight.ps1` | 发版前预检：issuer、Redis 前缀、secret/配置一致性 |
| `verify-oidc-cb-session.ps1` | C/B 会话隔离、CSRF、logout/re-login 真实 OAuth 回调验证 |
| `verify-a380-ktv-closure.ps1` | SaaS A380 KTV 预约/履约/账单/结算闭环（真实数据） |
| `ack-auth-contract.ps1` | ACK 环境授权契约验证 |
| `local-integration.ps1` | 本地集成入口 |
| `e2e-ktv-smoke.ps1` | KTV 冒烟 |
| `verify-unread-secret-toggle.ps1` | 未读角标开关排除实景：临时写入 secret/secret_group 未读投影 → 开关 ON 计入 / OFF 排除(0/空) → finally 清理恢复（需一次性账号参数） |
| `verify-audit-storage.ps1` | 审计独立 schema/分区/幂等台账/归档清单不变量（需数据库直连） |
| `verify-audit-platform.ps1` | 审计平台查询与保留策略 |
| `verify-currency-e2e.ps1` | 币种/金额展示与结算口径端到端 |
| `verify-wallet-token-display.ps1` | 储值币（代币）与积分展示口径 |
| `verify-reservation-room-type.ps1` | 预约房型闭环 |
| `release-status.ps1` | 发布状态与制品哈希核对 |

### 3.1 运行前置条件（**脚本不满足这些条件时失败属预期，不代表被测系统有缺陷**）

每个脚本的头部注释即为该脚本的**权威用法**（参数名、默认端点、所需外部输入）。通用前置：

- **外部凭据由操作者注入，不落仓库**：需要管理员口令的脚本一律从环境变量读取（如 `IM_SAAS_ADMIN_PASSWORD`），仓库内**不存在**该口令明文——这是刻意的凭据治理，**不是缺失配置**。未注入时脚本会明确报"需要提供口令"。
- **端点按环境传入**：脚本默认端点面向**本地端口转发**约定（网关/管理端/移动端各有约定端口），与集群 **Ingress 的单一 NodePort 入口不是同一访问方式**。经 Ingress 运行时须显式传入对应参数；端口值以脚本头部默认值为准，不假设统一。
- **需要数据库直连的脚本**（如 `verify-audit-storage.ps1`）必须先建立端口转发（用例见脚本头部 Usage），并要求本机存在对应**数据库客户端**；两者缺一即报"客户端不可用或服务不可达"。
- **面向特定环境的脚本不得当作本地用例**：例如 `ack-auth-contract.ps1` 面向 ACK（其默认命名空间与网关域名即体现这一点），在本地 Kind 上运行属**误用**，应以该环境参数显式运行。
- **只读与一次性数据**：不产生持久副作用；需要写入一次性数据的脚本须在 `finally` 中清理并核验无残留（见 §5）。


外部 Playwright 项目：`D:\projects\cnb\oidc-hybrid-e2e`（H5 真实环境 C/B/存储契约用例，`OIDC_E2E_MODE=real` 指向真实部署后运行）。

## 4. 真实设备规范

- Android：统一使用 AVD（API 35，Google APIs，x86_64，当前命名 `codex-oidc-api35`，设备 `emulator-5554`）；运行命令以 `dart run tools/run_integration_tests.dart --device=emulator-5554 ...` 为基准；App 访问 Kind 用 `http://10.0.2.2:<NodePort>`/`ws://10.0.2.2:30002/ws/im/v1`。
- PC 客户端（`open-chat-desktop` / `open-chat-admin`）：本地构建安装包（`npm run build:win` 等）并**本地安装后**走核心链路（登录、会话、服务板块/WebView 打开、设置），不能只用 typecheck/build 代替。
- 报告：集成测试报告落 `open-chat-app/build/test-reports/<run>/report.html`；关键命令与结果记录到 SDD 台账。

## 5. 测试数据治理

- 一次性账号统一带可识别前缀（如 `cp6*`、`e2e*`），测试结束在 `finally` 中调用服务端清理接口删除，并核验库中/Redis 中无残留（示例：`saas:user-session:*` 计数归零、无 active 测试账号）。
- ACK 环境不创建测试数据；任何外部环境试验须先经用户允许。

## 6. 需要持续补充的用例面

- 群聊解散后禁发消息、@成员名单与成员资料一致、消息编辑同步、加好友/成为好友通知、注册报错 i18n、登录设备列表、未读角标（含后台关闭私密/私密群聊后的排除）。
- 服务板块（小程序服务列表）→ A380 / A380 后台的跳转与权限完整性。
