# ADR 0016：组织（Organization）管理的归属与一期形态

## Status / 状态

**proposed（提案中，2026-10-28）**。本 ADR 决定 `tnt_organization` 的管理归属、一期能力边界与授权作用域启用节奏；不改变 ADR-0014 / ADR-0015 已确立的 Tenant Access 归属、角色绑定主体与作用域枚举，只决定这些能力在管理后台的**开放顺序与前置条件**。

## Context / 背景

现状存在一个明确的“半成品”断点：

- 组织已经出现在租户后台顶部的经营上下文标签中（`tenantName / organizationName / storeName / businessType` 拼接展示），用户能看见组织；
- `tnt_organization` 表、`tnt_store.organization_id`、`iam_role_binding.scope_type=ORGANIZATION` 都已存在，后端上下文解析与权限聚合也已按组织展开；
- 但平台后台和租户后台都没有组织的新增、改名、启停、门店归属查看入口，也没有 `OrganizationController`、组织领域模型或组织相关权限码；
- 归档文档 `SAAS_MENU_PERMISSION_01_ADMIN.md`（`> Status: historical`）中的 D-3 曾决定“ORGANIZATION 只留模型位、只做选择器路径展示、不做独立页面”，该结论已被现实界面推翻：组织既然已成为运营可见对象，就必须有明确的生命周期所有者。

同时存在三个会放大风险的事实：

1. **作用域能力半实现**：`Role.create` 只允许创建 `TENANT/STORE` 角色（`Role.java:55-65`），可分配角色查询也排除了 `ORGANIZATION`（`RoleMapper.java:20-29`），但 `RoleBindingApplicationService` 已强制“绑定作用域必须等于角色作用域”（`:75-77`）。因此组织级角色目前只能靠 SQL 种子直插（`V33__demo_tenant_membership.sql:16-28`），API 路径不可达。若此时直接把 `ORGANIZATION` 暴露到组织管理页，会得到一个“能建组织、不能建组织角色”的半成品。
2. **组织状态未被执行**：`IamSnapshotMapper.selectContexts` 与权限聚合没有把 `tnt_organization.status` 纳入判定。组织被停用/关闭后，其角色绑定仍然产生经营上下文与权限——停用操作形同虚设。
3. **上下文语义混同**：前端把 `scopeType='TENANT'` 或 `storeId===0` 一律当作“总部”（`AdminLayout.vue:14`、`utils/context.js:26`），组织级上下文会被误显示为总部并进入租户级总览，数据范围被放大。

本决策必须在既有约束内完成：组织属于 Tenant Access 限界上下文，权威服务是 `platform-tenant-service`（`ENGINEERING_RULES.md:215`、`docs/20-architecture/OVERVIEW.md:54`）；组织一期一层（`tnt_organization` 无 `parent_id`）；业务数据隔离仍以 `tenant_id + store_id` 为准，`organization_id` 是归属/归属快照/授权锚点而非数据分区（`50_PERMISSION_AND_DATA_SCOPE.md`）。

## Decision / 决策

### 1. 归属：租户管理为主，平台只读

组织的生命周期归**租户后台**（`TENANT` 工作区）管理；平台运营不获得独立的组织 CRUD 入口。平台对组织的可见性通过“进入目标租户上下文 + `tenant.organization.view` 权限”获得，且只有只读能力。理由：

- 组织是租户下一级治理对象，租户最了解自己的品牌/事业部/区域划分；
- 平台侧若再建一套组织 CRUD，会形成两个写入口与两套审计口径，违反单一事实源；
- 平台运营已有“切到租户上下文”受控代操作通道，不需要新增平台级接口。

### 2. 形态：保持一层，不引入组织树

一期沿用“租户 → 组织 → 门店”一层结构，`tnt_organization` 不新增 `parent_id`。组织不是目录树，也不是工作区；它是租户内的治理分组与授权作用域锚点。多层组织树属于新的领域决策，须另立 ADR。

### 3. 生命周期：创建、改名、启停/关闭；不物理删除

- 创建：租户管理员创建，`code` 租户内唯一且创建后不可变。
- 改名：仅改 `name`。
- 状态：`ACTIVE ↔ SUSPENDED`；`ACTIVE/SUSPENDED → CLOSED`（终态）。`CLOSED` 前必须无启用门店、无生效中的组织级角色绑定、无 STAFF 成员以该组织为主组织。
- **不提供物理删除**：组织被 `iam_role_binding`、`tnt_membership`、`tnt_store`、审计与历史归属快照引用；物理删除会制造孤儿引用并破坏可追溯性。关闭即软下线。

### 4. 授权作用域：一期不启用组织级角色，二期按白名单启用

一期组织只承担三件事：租户内治理分组、经营上下文路径段、未来组织级授权的锚点。**一期不创建 `scope_level=ORGANIZATION` 的角色，不开放组织级角色分配，不把组织级上下文作为可选工作范围**。理由与前置条件：

- 组织级角色要成立，必须先有“按组织收敛的数据域”，而当前 `StoreScopeRequestFilter` / `StoreApplicationService` 只校验租户与门店，不校验“目标门店是否属于当前组织”；若贸然把 `tenant.store.manage` 等权限授予组织角色，组织管理员可操作同租户其他组织的门店，属于越权。
- 组织级角色还需要放开 `Role.create`、可分配角色查询、租户侧 `StaffController` 与 BFF、权限 `grantable_levels` 白名单以及角色管理 UI；这些必须同批交付，不能“先给组织页再补授权”。
- 二期启用时的白名单、数据域守卫与测试矩阵见设计文档 `docs/40-domain/identity/ORGANIZATION_MANAGEMENT_DESIGN.md` §8.3；**禁止**把 `ORGANIZATION` 直接批量加进所有权限的 `grantable_levels`。

### 5. 权限与菜单

新增两个权限码，均为 `scope_level=TENANT`、`domain_code=core`：

| 权限码 | grantable_levels | tenant.owner | platform.operator | store.manager |
| --- | --- | --- | --- | --- |
| `tenant.organization.view` | `TENANT,PLATFORM` | 授予 | 授予 | 不授予 |
| `tenant.organization.manage` | `TENANT` | 授予 | 不授予 | 不授予 |

菜单挂在已存在的 TENANT 分组 `tenant.org`（组织与门店）下，`required_permission=tenant.organization.view`；写操作由页面按 `tenant.organization.manage` 二次门禁。菜单不是安全边界，接口仍由服务端 `PermissionGuard` 强制。

### 6. 兼容与数据校准

历史的 `tenant.owner` 若被绑定在 `ORGANIZATION` 作用域（演示种子 `V33` 即如此），会导致“租户老板只能在单个组织内操作”。一期必须做一次幂等数据校准：为这些成员补一条 `TENANT` 作用域的同角色绑定，再撤销原组织绑定，并递增对应 membership 的 `authorization_version`、逐出权限快照。校准只针对预置角色 `tenant.owner`，不触碰自定义组织角色。

### 7. 状态必须被执行

组织状态变更必须真实生效：`SUSPENDED/CLOSED` 组织不得再产生新的组织/门店经营上下文，其组织级绑定不得再参与权限聚合；变更后逐出权限快照，并对受影响成员递增授权版本。该项是组织管理能力的**前置条件**，与 CRUD 同批上线，不能延后。

### 8. 一期不改变租户开通流程

本期不在 `TenantApplicationService.create` 中自动创建默认组织；首个组织由租户管理员在组织管理页创建，页面提供“创建第一个组织”的空态引导。自动初始化默认组织与租户开通模板绑定，另行决策（见设计文档待决项 D-2），避免把平台开通流程与组织页面耦合。

## Consequences / 后果

正面：

- 组织有了唯一生命周期所有者，平台/租户职责与 ADR-0014 的“TENANT 工作区负责组织/门店”一致；
- 零 DDL：只加聚合、接口、权限、菜单与页面，迁移可回滚、无表结构风险；
- 权限与菜单同批声明，符合 `50_PERMISSION_AND_DATA_SCOPE.md` 的“权限码先登记、守卫、菜单、403 测试”闭环；
- 通过数据校准修复“租户老板被锁在单组织”的遗留问题，并为二期组织级授权留下明确入口。

代价与风险：

- 平台不能直接修组织数据，只能通过租户账号或受控代操作；若确有支持诉求，需要单独决策是否给平台 `manage`（当前决定：不给）；
- 一期组织级角色不可用，租户若需要“组织管理员”要等到二期；设计中必须显式说明，不能让页面暗示已支持；
- 组织状态强制会改变现有演示上下文的可见性（组织上下文不再出现），前端上下文选择器与相关守卫测试需要同步调整；
- 数据校准改变授权数据，必须在发布证据中保留校准前后核对结果。

后续动作：新增 ADR 无需再改本 ADR；二期启用组织级角色时，新 ADR 记录白名单与数据域守卫方案，本 ADR 保持 `proposed` 直至一期实现验收后转 `accepted`。

## Alternatives / 备选方案

1. **平台管理组织**：平台运营最接近“开通配置”，但与“组织是租户下一级”语义冲突，且形成平台/租户双写入口。否决。
2. **平台 + 租户双入口**：平台管开通、租户管日常，看似折中，但两套权限、两套审计、两套校验规则难以保持一致，一期维护成本高于收益。否决。
3. **一期直接开放组织级角色**：满足“组织与角色关联”的直觉，但在缺少按组织收敛的数据域前会制造跨组织越权。否决，改为二期白名单启用。
4. **只做展示、不做页面（沿用归档 D-3）**：这正是当前问题的来源，组织可见却不可治理。显式废弃。
5. **引入组织树 / `parent_id`**：当前没有多层组织业务需求，且会牵动上下文解析、权限继承、报表汇总与门店归属规则。推迟到有明确需求时另立 ADR。否决本期引入。

## References / 参考

- [ADR-0014 平台、租户、门店工作区与角色治理](0014-workspace-role-model.md)
- [ADR-0015 IM 与 SaaS 身份联邦、租户成员与授权模型](0015-identity-federation-tenant-iam.md)
- [权限与数据域规范](../../80-development/standards/50_PERMISSION_AND_DATA_SCOPE.md)
- [数据库迁移标准](../../80-development/standards/23_DATABASE_MIGRATION_STANDARD.md)
- [组织管理设计与归属方案](../../40-domain/identity/ORGANIZATION_MANAGEMENT_DESIGN.md)
- 归档决策（仅历史背景，不作为现行契约）：`docs/99-archive/renovation/SAAS_MENU_PERMISSION_01_ADMIN.md` D-3
