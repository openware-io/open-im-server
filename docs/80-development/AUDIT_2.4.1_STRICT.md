# 2.4.1 严格合规审计（2026-10-28）

> 结论：**未达到“全部真实实现、全部规范严格遵守”**。
> 核心业务链路和门禁大部分通过，但发现 2 个 P1 级规范/交付缺口和若干 P2 文档/环境缺口。
> 本审计按当前 `main`（`b4c9c289`）及其工作区执行，不修改业务代码。

## 1. 审计范围

- 代码：`open-im-server`、`open-saas-admin`、`open-saas-mobile`、`open-chat-app`
- 架构：服务边界、分层、端口、依赖图、PO/持久层边界
- 数据库：Flyway 约定/不可变性、表前缀、租户列、审计字段、迁移与 H2
- 文档：文档链接/可达性、事实一致性、版本号与发布状态
- 门禁：55 个注册门禁、全量 Maven、前端/Flutter、E2E
- 部署：Kind 当前运行态、镜像/版本、发布记录

## 2. 结论矩阵

| 维度 | 结论 | 证据 |
| --- | --- | --- |
| 架构边界 | **通过** | service-layout / domain-purity / service-api-boundaries / dependency-graph / port-contract / po-containment / persistence-boundaries 全部 PASS |
| 数据库规范 | **通过** | Flyway conventions（166 表/19 roots）、immutability、tenant-sql（81 表）、audit-fields 全部 PASS；V27/V28/V29/V30/V35/V7 已在 live 库 applied |
| 文档链接/可达性 | **通过（修复后）** | 审计中修复了 HANDOFF 未登记导致的 reachability FAIL；links/reachability/governance 现 PASS（257 文档） |
| 全量门禁 | **通过（但含未提交改动）** | `invoke-engineering-validation.ps1 -Scope Full`：exit 0，maven BUILD SUCCESS，55 门禁；工作区含另一路 admin 功能改动，不能等同已提交 main |
| 管理端菜单 | **通过（修复后）** | HQ `100:100:0` 无 `/admin/ktv/config`；KTV 门店 `100:100:100` 有；目标测试 PASS |
| 全量 E2E（菜单修复后） | **通过** | admin **99/99**；C/OTA/渠道 **142 PASS / 0 FAIL / 0 GAP** |
| 账号 | **部分通过** | `admin`/`tenant-admin` + `Admin@123` 实测 200；`platform-operator`/`store-manager`/`ktv-manager` 未 seed |
| C 端 KTV 清库闭环 | **未完全闭环** | 清库后 `res_room_type` 无 KTV 房型；E2E 直接 SQL 插入 `E2E_KTV` fixture 才能创建预约 |
| 版本/发布规范 | **不通过** | 67 个 POM 被全局 bump；根 POM 被改为 2.4.1；曾擅自创建 `v2.4.1` tag（已删除，规范已补禁令） |
| 当前 Kind 发布一致性 | **部分通过** | admin 是 `c5fea953` scoped 热更，其他服务仍是 b9d 发布镜像；尚无菜单修复后的 full clean release |

## 3. P1 发现

### P1-01 版本治理：全仓 lockstep bump + 根 POM 被抬版本

- 事实：自 `c9e3418c` 起共 **67 个 `pom.xml`** 被改，根 `pom.xml` 从 `2.4.0` 变为 `2.4.1`；`im-services`、`common-media/audit/sms/mail`、`group-idaas`、`platform-identity/resource/marketing` 等未迭代服务也被统一改为 2.4.1。
- 规范：`docs/70-operations/RELEASE_RUNBOOK.md` 明确：
  - 根聚合/结构聚合父 POM **只在 MAJOR 变化**；
  - **只 bump 实际改动的版本单元**；
  - **禁止全仓 pom lockstep 统一 bump**。
- 判定：违反版本治理规范。`validate-maven-version-ownership.ps1` 只校验叶子/领域版本一致，未覆盖“是否 lockstep/根版本是否被抬”，因此门禁没拦住。
- 整改建议：单独做一次版本治理修正：根/结构父 POM 保持 2.4.0；仅 order/tenant/admin/customer/payment/payment-channel/gateway/sdk 等实际改动单元使用 2.4.1；重启镜像构建并重发，不要与本次菜单修复混在一起。

### P1-02 擅自创建 Git tag

- 事实：未经用户要求创建并推送 `v2.4.1`；该 tag 已删除（本地 + origin）。
- 规范：正式版本/tag 只在用户明确要求时创建；本轮已在 `RELEASE_RUNBOOK.md` 增加第 7 条，明确 Agent/CI 禁止自行创建/推送、移动/强推 tag。
- 判定：已整改，但审计记录保留为流程违规。

### P1-03 清库后 C 端 KTV 预约没有可用房型

- 事实：清库后 `res_room_type` 没有 KTV 房型（只有 `res_resource` 包厢）；C 端 `POST /me/reservations` 需要 `roomTypeId`，否则返回 `ROOM_TYPE_INVALID` / `ROOM_TYPE_NO_ROOM_AVAILABLE`。
- E2E 现状：`scripts/verify/e2e-c-end-ota-channel.ps1` 直接 `INSERT INTO res_room_type ... 'E2E_KTV'`，再把 `K01` 绑定到该房型，才使 `KTV-C-CREATE` PASS。也就是说：**接口能力真实存在，但清库产品数据不具备可用房型**。
- 判定：C 端 KTV 闭环依赖 B 端先建房型；如果验收标准要求“清库后 C 端可直接预约”，这是未完成项。
- 整改建议：在 resource-service 增加前向 Flyway seed（KTV 房型 + 绑定红包厢），E2E 改用真实 seed; 不要把测试 SQL 当作产品数据。

## 4. P2 发现

### P2-01 顶层文档仍有 2.3.0/2.2.1 旧版本元数据

- `docs/README.md`、`docs/README.en.md`、`docs/10-product/README*` 已在本次审计中改成 `main` / 2.4.1 开发迭代、无 tag。
- 仍有大量 `Status: current (develop/2.3.0)` / `2.2.1` 出现在 `docs/20-architecture`、`docs/40-domain`、`docs/50-services`、`docs/80-development` 的规范/状态头中。部分是历史基线说明，部分是当前状态头，需逐份区分后更新；不能全部盲目替换。

### P2-02 工作区存在另一路未提交的**功能代码 + 文档**

- 当前 `git status` 有：
  - `docs/20-architecture/adr/README.md`、`docs/40-domain/README*.md`、`docs/40-domain/identity/README.md` 修改
  - 未跟踪 `docs/20-architecture/adr/0016-organization-management-boundary.md`
  - 未跟踪 `docs/40-domain/identity/ORGANIZATION_MANAGEMENT_DESIGN.md`
  - **功能代码**：`StaffController.java`、`TenantIamDomainClient.java`、`StaffControllerTest.java` 修改，`TenantIamDomainClientScopeTest.java` 未跟踪
- 这些不属于本轮菜单/版本修复，未纳入 main。审计只能对已提交 main 和当前工作区分别说明；**本轮全量门禁/E2E 跑的是“main + 这批未提交改动”的混合工作区，不是纯 main**。新窗口接手时先决定合入/拆分/清理，避免 `git add -A` 夹带，也不要在这批改动未提交时继续做 clean release。

### P2-03 用户列出的额外 SaaS 账号未 seed

- `cons_admin_account` 只有 `admin`、`tenant-admin` 两行；`platform-operator` / `store-manager` / `ktv-manager` 不存在。
- 这不是密码失效，是清库种子没有这些账号；若要提供，需要同时补角色绑定/门店上下文，否则登录后无可用上下文。

### P2-04 live 发布与 main 不完全一致

- admin live 镜像是 `c5fea953` scoped 热更（菜单修复）；其他服务仍是 2.4.1 发布镜像 b9d27662。
- V7 密码迁移已 applied；但尚未做“菜单修复 + V7 + 当前 main”的一次 full clean release。
- 下一轮若做正式清库发布，应先解决 P1-01 版本治理，再统一构建/部署/E2E，避免再次出现镜像与 main 差异。

### P2-05 门禁覆盖盲区

- `validate-maven-version-ownership.ps1` 不检查“未改动服务是否被 bump”或“根 POM 是否被抬”，是 P1-01 未被拦截的直接原因。
- 本次全量门禁 PASS 仍不能证明版本治理合规；门禁与规范之间存在空白，应补充 check（例如对比基线 POM、禁止 root/结构父版本变化、禁止未改动模块 SNAPSHOT 变化）。

## 5. 已确认通过的部分

- 架构门禁（16 项）全部 PASS，未发现跨服务 `*-service` 依赖、domain 层框架依赖、Controller 直接访问 Repository、PO 泄漏等。
- Flyway：166 张表/19 个 migration root，immutability 通过；V27 删旧列/旧表、V28 删 order hold、V29 改 ktv_reservation、V30 OTA 表、V35 旧权限、V7 密码均 applied。
- 租户隔离：81 张 tenant-scoped 表通过。
- 审计字段/表前缀/PO containment 通过。
- 管理端菜单修复有单测 + 实机 API 双证据。
- admin/tenant-admin `Admin@123` 登录 200；MinIO 登录 204。

## 6. 证据

- 全量门禁：`.../strict-audit-full-gate.log`（2026-10-28 21:47 BUILD SUCCESS，exit 0）
- 全量 E2E（菜单修复后）：admin 99/99；C/OTA/渠道 142 PASS / 0 FAIL / 0 GAP，`strict-audit-e2e.log`
- 架构/DB/文档专项：本轮 16 个 validator 输出（摘要见第 2 节）
- 文档可达性修复：`docs/README.md` 增加 HANDOFF 链接后 PASS（257 文档）
- 菜单修复：`AdminMenuApplicationService.java` + `AdminMenuApplicationServiceTest`
- 版本治理违规：`git diff --name-only c9e3418c -- '*pom.xml'` = 67；根 `pom.xml` = 2.4.1
- 清库 KTV 房型缺口：`res_room_type` 空；E2E 脚本直接 INSERT `E2E_KTV`

## 7. 审计结论

- **业务核心（订单/KTV/酒店/库存/OTA/渠道支付）**：代码与 E2E 证据基本真实，未发现“假实现”。
- **规范/发布治理**：不通过。版本 lockstep、擅自 tag、清库 KTV 房型 seed、文档版本元数据、工作区污染属于必须整改项。
- 在 P1-01/P1-03 修复并做一次 full clean release + 全量 E2E 之前，不能宣称“全部规范严格遵守”。
