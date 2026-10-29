# 组织（Organization）管理设计与归属方案

> 状态：`draft`（2026-10-28）  
> 层级：L2 领域设计（Tenant Access / IAM / 管理后台）  
> 关联决策：[ADR-0016](../../20-architecture/adr/0016-organization-management-boundary.md)（拟稿）、[ADR-0014](../../20-architecture/adr/0014-workspace-role-model.md)、[ADR-0015](../../20-architecture/adr/0015-identity-federation-tenant-iam.md)  
> 关联规范：[权限与数据域规范](../../80-development/standards/50_PERMISSION_AND_DATA_SCOPE.md)、[数据库迁移标准](../../80-development/standards/23_DATABASE_MIGRATION_STANDARD.md)、[前端 UI 风格](../../80-development/standards/32_FRONTEND_UI_STYLE.md)、[DDD 服务工程规范](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)  
> 结论：**归租户后台管理、平台只读；一期零 DDL；新增 2 个权限码、1 个菜单节点、1 组 CRUD 接口、1 个页面；一期不启用组织级角色与组织工作区，二期按白名单启用。**

## 0. 摘要（评审先读）

1. 组织不是“业务数据分区”，而是**租户内的治理分组 + 授权作用域锚点 + 经营上下文路径段**。业务隔离仍以 `tenant_id + store_id` 为准（`organization_id` 是归属/快照/授权维度）。
2. 组织当前只有“模型”：`tnt_organization` 表存在，但无领域模型、无 CRUD API、无权限码、无菜单叶子、无页面；同时 `iam_role_binding.scope_type=ORGANIZATION` 已能落库、上下文解析也已按组织展开——这是典型的**半实现**。
3. 因此归属必须收敛：**租户后台是唯一写入口**；平台通过“进入租户上下文 + 只读权限”获得可见性，不建平台侧组织 CRUD。
4. 一期**不**开放 `scope_level=ORGANIZATION` 的角色与组织级工作上下文，因为当前后端没有“按组织收敛的数据域”守卫；贸然开放会把组织管理员变成跨组织越权入口。二期以前置条件 + 权限白名单的方式启用。
5. 本期需要先修两个**前置缺陷**：组织状态（`SUSPENDED/CLOSED`）当前不参与上下文与权限判定；历史 `tenant.owner` 的组织级绑定会让租户老板被锁在单个组织。
6. 接口放 `platform-tenant-service`（网关 `/api/v1/admin/tenant/**` 既有路由），路径 `/admin/tenant/organizations`；权限 `tenant.organization.view` / `tenant.organization.manage`；菜单挂在既有 TENANT 分组 `tenant.org` 下。
7. 工程形态：零 DDL；租户侧 `V36`（权限+授权）、`V37`（owner 绑定校准），管理后台 BFF `V8`（菜单）；Controller 只做协议转换，权限/边界/审计/快照逐出全部在 ApplicationService。
8. 全部改动必须通过 G27/G28/G32/G47/G50/G52/G54/G55 及文档门禁；本文件自身遵守文档治理（状态头、可达性、链接）。

## 1. 问题与现状证据

### 1.1 症状

租户后台顶部上下文标签会显示 `租户 / 组织 / 门店 / 业态` 的层级路径，组织（如“总部”）已直接暴露给运营人员；但：

- 平台后台没有组织的创建/维护入口；
- 租户后台没有组织的创建/维护入口；
- 角色与权限页看不到组织级角色；
- 组织一旦配置错误（名称、状态、门店归属），只能改数据库。

结果是“界面上属于业务的一部分，但产品上没有 Owner”。

### 1.2 现状事实（代码/迁移证据）

| 能力 | 现状 | 证据 |
| --- | --- | --- |
| 组织表 | 已存在，字段 `id/tenant_id/code/name/status` + 4 审计列；唯一键 `(tenant_id, code)`；**无 `parent_id`** | `platform-services/tenant/platform-tenant-service/src/main/resources/db/migration/V1__tnt_iam_baseline.sql:51-63` |
| 组织领域模型 | **不存在**；只有只读查询端口 `OrganizationRepository.findTenantId` 用于角色绑定校验 | `.../domain/iam/repository/OrganizationRepository.java:6-7`、`.../infra/persistence/mapper/OrganizationMapper.java:11-14` |
| 组织 CRUD API | **完全缺失**：无 Controller、ApplicationService、PO、DTO | 全仓检索无 `OrganizationController` / `OrganizationApplicationService` |
| 门店与组织 | `tnt_store.organization_id NOT NULL`，索引 `(tenant_id, organization_id, status)`；门店只能归属一个组织 | `V1__tnt_iam_baseline.sql:67-90` |
| 组织菜单 | 分组节点 `id=100 code='tenant.org' name='组织与门店' path=NULL` 已存在；其下只有门店，**无组织叶子** | `platform-services/admin/platform-admin-service/src/main/resources/db/migration/V1__cons_admin_account.sql:110`、`:87` |
| 组织权限码 | **不存在** `tenant.organization.*` | `V1:484-509`、`V26`、`V27` 等迁移均无该码 |
| 组织级角色绑定 | 表与校验已支持 `scope_type=ORGANIZATION`，`org>0 && store==0`；上下文/权限聚合已按组织展开 | `V10__iam_role_binding.sql:2-25`、`RoleBinding.java:61-74`、`IamSnapshotMapper.java:88-89,115-116` |
| 组织级角色创建 | **API 不可达**：`Role.create` 只允许 `TENANT/STORE`；可分配角色查询也排除 `ORGANIZATION`；而绑定强制 `scopeType == role.scopeLevel` | `Role.java:55-65`、`RoleMapper.java:20-29`、`RoleBindingApplicationService.java:75-77` |
| 组织状态生效性 | **不生效**：上下文查询与权限聚合未按 `tnt_organization.status` 过滤；停用/关闭组织不影响绑定 | `IamSnapshotMapper.java:125-143`（上下文）、`:68-96`（权限） |
| 租户开通 | 只创建 `tnt_tenant` 与初始管理员，**不创建默认组织** | `application/TenantApplicationService.java:59-92`、`docs/40-domain/identity/TENANT_ONBOARDING.md:26-35` |
| 前端组织使用 | 仅作为上下文标签的中段文案与 `organizationId` 参数；无组织 API/页面/路由/权限 | `open-saas-admin/src/components/TenantContextSelector.vue:51-58`、`src/utils/context.js:36-47` |
| 前端“总部”判定 | `scopeType==='TENANT' || storeId===0` 一律当总部，组织级上下文被误判为总部 | `open-saas-admin/src/layout/AdminLayout.vue:14`、`src/utils/context.js:26`、`src/composables/useBackend.js:22` |
| 文档依据 | “一期一层”“只做展示位”来自**已归档**文档，不能作为现行契约 | `docs/99-archive/renovation/SAAS_MENU_PERMISSION_01_ADMIN.md:110,731`（`> Status: historical`） |

### 1.3 根因

1. **生命周期所有者缺失**：组织只有模型与只读消费，没有负责创建/修改/停用它的应用服务。
2. **授权作用域半实现**：`ORGANIZATION` 能写库但 API 不能产生，形成“演示种子可达、产品路径不可达”的裂缝。
3. **上下文语义坍缩**：`tenant / organization / store` 三态在前后端都被压缩成“总部 / 门店”两态，组织被当成总部。
4. **状态不参与授权**：组织状态字段存在，但没有任何解析路径消费它，停用在安全上无效。
5. **开通流程与模型不一致**：新租户无默认组织，而组织又成为门店与上下文的必经层。

### 1.4 不修的后果

- 运营只能改库，缺少审计与二次确认，误操作不可追溯；
- 组织状态停用无效，形成“看起来停用了、实际仍可操作”的安全假象；
- 组织级角色继续靠 SQL 种子存在，权限模型与产品能力长期漂移；
- 平台/租户都“有入口但不完整”，后续每加一个组织相关能力都要再猜一次归属。

## 2. 目标与非目标

### 2.1 目标

| 编号 | 目标 | 可验证判据 |
| --- | --- | --- |
| G1 | 租户管理员可完成组织生命周期管理 | 列表/创建/改名/启停/关闭/查看门店，全部有页面入口与 API |
| G2 | 平台可读不可写 | 平台运营在租户上下文中能看组织列表与详情，页面无写按钮，接口 403 |
| G3 | 组织状态真实生效 | 停用组织后：新上下文不再产生、权限聚合不再包含其组织级绑定；已有缓存被逐出 |
| G4 | 授权数据自洽 | `tenant.owner` 一律租户级；组织级绑定只可能来自二期显式启用 |
| G5 | 工程合规 | 零 DDL；权限/菜单/迁移/文档/测试按门禁同批交付；可回滚 |

### 2.2 非目标（一期明确不做）

- 不做多层组织树 / `parent_id` / 组织层级继承；
- 不做 `scope_level=ORGANIZATION` 角色创建与分配（二期）；
- 不把组织作为可选“工作区/经营上下文”（二期）；
- 不做组织物理删除；
- 不做门店创建/迁移接口（门店创建仍是独立缺口）；
- 不做平台侧组织 CRUD 页面；
- 不改变租户开通流程、不自动创建默认组织（待决项 D-2）；
- 不做组织级账单、法人主体、结算归属（`tnt_legal_entity` 是另一聚合）。

### 2.3 成功判据

1. 租户管理员 3 分钟内可创建组织并看到菜单/列表/空态；
2. 无 `tenant.organization.manage` 的账号（含平台运营）所有写接口返回 403，页面隐藏写操作；
3. 组织停用后，原组织级绑定产生的上下文与权限在下一次选择/快照刷新时消失；
4. `tenant.owner` 的组织级遗留绑定被校准为租户级，且审计/核对记录可查；
5. 全部门禁与文档校验通过，迁移可在空库与增量库执行。

## 3. 既有决策与本次边界

| 决策 | 现行来源 | 本方案关系 |
| --- | --- | --- |
| 组织/门店/员工/角色归 TENANT 工作区 | `ADR-0014`（accepted） | 沿用：租户后台是组织管理归属 |
| 角色绑定 membership；组织角色 `scope=ORGANIZATION` 属目标模型 | `ADR-0015`（accepted） | 沿用模型；**分期启用**，不推翻 |
| 组织属于 Tenant Access，权威服务 `platform-tenant-service` | `ENGINEERING_RULES.md:215`、`20-architecture/OVERVIEW.md:54` | 沿用：接口与聚合放该服务 |
| 平台角色不能直接访问租户业务 | `ADR-0014` | 沿用：平台只能通过受控上下文切换获得只读可见性 |
| 组织一期一层 | 归档 `SAAS_PLATFORM_01_SERVICE.md:16`（historical） | 沿用现状（表无 `parent_id`），本期不扩展；多层需新 ADR |
| 组织只做展示、不做独立页面（D-3） | 归档 `SAAS_MENU_PERMISSION_01_ADMIN.md:731`（historical） | **显式废弃**：界面已展示组织，必须有治理入口；由 ADR-0016 转正 |
| 权限码格式 `module.resource[.action]` | `50_PERMISSION_AND_DATA_SCOPE.md` | 沿用：`tenant.organization.view/manage` |
| 菜单/权限不是安全边界 | `50_PERMISSION_AND_DATA_SCOPE.md:30-45` | 沿用：服务端守卫 + 403 测试 |

> 归档文档只能作为历史背景；任何“现行契约”必须由 ADR 或 `40-domain/` 现行文档承载。本方案与 ADR-0016 一起完成 D-3 的替代。

## 4. 概念模型：组织到底是什么

### 4.1 定义

```text
租户 Tenant
  └── 组织 Organization（一期一层；治理分组 + 授权作用域锚点）
        └── 门店 Store（数据隔离最细粒度；organization_id NOT NULL）
```

- **治理分组**：把同品牌/事业部/区域的门店放在一起，供人阅读与管理；
- **授权作用域锚点**：`iam_role_binding` 可以绑定到组织（二期启用），组织绑定对其下门店上下文生效；
- **上下文路径段**：`contextId = tenantId:organizationId:storeId` 的中段；
- **不是**：数据分区键、工作区、法人主体、结算主体、门店的替代。

### 4.2 对象所有权矩阵

| 对象 | 平台运营 | 租户管理员（TENANT） | 组织级角色（二期） | 门店角色 |
| --- | --- | --- | --- | --- |
| 租户生命周期 | 创建/启停 | 只读 | 不可见 | 不可见 |
| 组织创建/改名/状态 | **只读**（`view`） | **读写**（`manage`） | 不可写 | 不可写 |
| 组织下门店归属 | 只读 | 只读（门店创建接口未实现） | 只读 | 不可见 |
| 租户级角色与绑定 | 只读/受控代操作 | 读写 | 不可写 | 不可写 |
| 组织级角色与绑定 | 不可写 | 二期可写（白名单） | 只读自身 | 不可见 |
| 门店经营数据 | 上下文内受控 | 租户范围 | 组织范围（二期） | 本门店 |

### 4.3 不变量（实现必须写成测试）

| 编号 | 不变量 |
| --- | --- |
| INV-1 | 任何组织读写都以签名上下文的 `tenantId` 为边界；跨租户一律 403/404，不接受请求体/路径指定租户 |
| INV-2 | `code` 租户内唯一，创建后不可变；`name` 可改，去首尾空白，长度 1–128 |
| INV-3 | 门店只能归属一个组织（`tnt_store.organization_id NOT NULL`）；本方案不提供改归属能力 |
| INV-4 | 不物理删除组织；`CLOSED` 前必须无 `ACTIVE/SUSPENDED` 门店、无生效中的 `ORGANIZATION` 绑定、无 STAFF membership 以该组织为主组织 |
| INV-5 | 组织非 `ACTIVE` 时不得产生新的组织/门店上下文，不得参与权限聚合；变更后逐出快照并递增受影响 membership 授权版本 |
| INV-6 | 组织写操作成功与失败都必须写审计（资源 `tnt_organization`）；审计动作码必须在 `AuditActions.java` 登记 |
| INV-7 | 查询必须带 `tenant_id`：`tnt_organization` 未列入租户拦截器忽略清单，复用 MyBatis 租户拦截；跨租户存在性判断用 `@InterceptorIgnore` 显式 SQL |
| INV-8 | 一期组织不进入任何业务表唯一键，不改变订单/库存/报表的数据隔离口径 |

## 5. 归属与信息架构

### 5.1 结论

```text
平台运营后台                         租户后台
─────────────                       ─────────────
租户管理（生命周期）                 租户管理
  └─ 进入租户上下文（受控代操作）        └─ 组织与门店
       └─ 组织管理（只读）                   ├─ 组织管理   ← 本方案新增
                                             └─ 门店
```

- **租户后台**：唯一写入口。菜单路径 `/admin/tenant/organizations`，页面标题“组织管理”。
- **平台后台**：不新增组织页面。平台运营通过顶部上下文切换进入目标租户，在租户后台看到组织菜单；`platform.operator` 只有 `tenant.organization.view`，页面自动进入只读态。平台租户列表页本期不嵌入组织信息（避免跨租户聚合接口与 N+1）。
- **组织级角色（二期）**：入口仍在租户后台的“角色与权限”，组织管理页只提供跳转与说明，不复制一套角色 UI。

### 5.2 菜单位置

```text
租户管理（TENANT 段）
├─ 经营总览
├─ 组织与门店                 ← 既有分组 iam_menu.id=100, code=tenant.org, path=NULL
│  ├─ 组织管理                ← 新增叶子 id=33, code=organization, sort_no=5
│  └─ 门店                    ← 既有 id=10, code=store
├─ 资金与支付
├─ 人员与权限
└─ 租户设置
```

### 5.3 与既有页面的边界

| 页面 | 关系 |
| --- | --- |
| 门店 | 只读展示组织归属；本期不提供门店创建/归属调整。组织详情中的门店列表是只读投影 |
| 运营人员 | 本期不按组织过滤人员；人员仍是租户/门店作用域。二期组织角色启用后再补组织选择 |
| 角色与权限 | 本期不出现 ORGANIZATION 选项；页面文案明确“组织级角色二期开放” |
| 审计日志 | 组织操作写入既有审计；审计页可按资源 `tnt_organization` 过滤（已有资源词表） |
| 经营总览 | 本期不按组织汇总；组织级视图二期随数据域守卫一起开放 |

### 5.4 “总部 / 组织 / 门店”语义

| 上下文 | `contextId` 形态 | 语义 | 一期是否可选 |
| --- | --- | --- | --- |
| 租户总部 | `tenantId::` | 租户全范围（跨组织、跨门店） | 是 |
| 组织 | `tenantId:organizationId:` | 单组织范围 | **否**（二期） |
| 门店 | `tenantId::storeId` 或 `tenantId:organizationId:storeId` | 单门店经营 | 是 |

一期 UI 修复要点：

- `chooseSingleContext` 选择“总部”时必须要求 `organizationId` 为空，不能把组织上下文当总部（`open-saas-admin/src/utils/context.js:26`）；
- 若后端仍返回组织级上下文（遗留绑定/自定义数据），选择器中该项**禁用**并提示“组织级工作范围二期开放，请联系租户管理员校准绑定”，不得默认选中；
- 布局的上下文面板按“租户总部 / 组织（只读范围）/ 门店”三态展示，不再把组织叫总部；
- 数据校准完成后，正常的租户管理员应只看到“租户总部 + 门店”两类候选。

## 6. 领域与数据模型

### 6.1 零 DDL 决策

`tnt_organization` 现有字段足以支撑一期 CRUD、状态与门店计数，不需要新增列、索引或外键：

- 列表按 `tenant_id` + 可选 `status` 查询，既有唯一键 `(tenant_id, code)` 的前缀可用于租户过滤；组织数量级为个位数到几十，不需要额外索引；
- 门店计数通过既有 `StoreRepository.listByTenant(tenantId, null, null, null)` 一次性取回后在应用层分组，不新增跨表 SQL；
- 状态复用 `ACTIVE/SUSPENDED/CLOSED`；
- 不引入 `parent_id`；
- 不引入数据库外键（迁移标准禁止跨库外键，本库既有表也无外键）。

> 零 DDL 是本方案可回滚性的核心：出问题只需回滚代码 + 将菜单置 `INACTIVE` + 撤销权限授权，不涉及表结构恢复。

### 6.2 聚合与分层

```text
domain/organization/model/Organization.java          // record，无审计字段
domain/organization/repository/OrganizationRepository.java
application/OrganizationApplicationService.java      // 事务/权限/边界/审计/快照逐出
api/controller/OrganizationController.java           // 仅协议转换
api/converter/OrganizationConverter.java
api/dto/OrganizationView.java / OrganizationDetailView.java
infra/persistence/po/OrganizationPo.java             // @TableName("tnt_organization")
infra/persistence/mapper/OrganizationMapper.java     // 既有文件扩展
infra/persistence/repository/OrganizationRepositoryAdapter.java
```

- 现有 `domain/iam/repository/OrganizationRepository.findTenantId` 是“为角色绑定服务的只读端口”，本方案把它迁到 `domain/organization/repository`，作为组织聚合的唯一端口，并同步修改唯一调用方 `RoleBindingApplicationService`；避免同一聚合两个端口。
- 领域 record 不承载 `createdBy/createdAt/updatedBy/updatedAt`（G22：审计字段不进领域模型），由适配器在 PO 层填充。
- Controller 禁止 `@Transactional`、禁止持有 Repository/Mapper（G19/G20）；权限、租户边界、状态机、审计、快照逐出全部在 ApplicationService。

### 6.3 领域模型草图

```java
public record Organization(Long id, Long tenantId, String code, String name, String status) {
  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_SUSPENDED = "SUSPENDED";
  public static final String STATUS_CLOSED = "CLOSED";

  public static Organization create(Long tenantId, String code, String name) { /* 校验 code/name，status=ACTIVE */ }
  public Organization rename(String newName) { /* name 校验，code/status 不变 */ }
  public Organization changeStatus(String target) { /* 状态机校验，返回新实例 */ }
  public boolean active() { return STATUS_ACTIVE.equals(status); }
}
```

状态机（仅这些转换合法）：

```text
ACTIVE   → SUSPENDED | CLOSED
SUSPENDED→ ACTIVE    | CLOSED
CLOSED   → （终态，不可再变更）
```

### 6.4 校验规则

| 字段 | 规则 | 失败错误码 |
| --- | --- | --- |
| `code` | 2–64 位，`^[a-z][a-z0-9-]{1,63}$`；租户内唯一；创建后不可变 | 400 `ORGANIZATION_CODE_INVALID` / 409 `ORGANIZATION_CODE_EXISTS` |
| `name` | 去空白后 1–128 位，非空 | 400 `ORGANIZATION_NAME_INVALID` |
| `status` | `ACTIVE/SUSPENDED/CLOSED`，大小写归一 | 400 `ORGANIZATION_STATUS_INVALID` |
| 归属 | 目标组织必须属于当前租户；不存在 → 404；跨租户 → 403 | 404 `ORGANIZATION_NOT_FOUND` / 403 `TENANT_SCOPE_DENIED` |
| 关闭前置 | 无 `ACTIVE/SUSPENDED` 门店、无生效组织绑定、无主组织 membership | 409 `ORGANIZATION_IN_USE` |

### 6.5 关闭/停用的级联语义

- **停用 `SUSPENDED`**：不改变门店与绑定数据，但让组织不可用于新的上下文/权限解析；可恢复。
- **关闭 `CLOSED`**：要求先迁移/关闭门店、撤销组织级绑定、迁移主组织 membership；关闭后不可恢复。审计记录必须包含操作人与前置检查结果。
- 不自动停用门店：门店状态是独立生命周期，自动级联会让“组织停用”意外停掉经营；由操作人先处理门店（页面提示门店数并提供门店页链接）。
- 快照失效：状态变更成功后调用 `PermissionSnapshotProvider.evictAll()`；对受影响 membership 递增 `authorization_version`（按 `iam_role_binding.organization_id = :orgId` 找到 ACTIVE 绑定对应的 membership），保证已签发上下文在下一次校验时失效。

### 6.6 必须补齐的状态执行点

| 位置 | 现状 | 目标 |
| --- | --- | --- |
| `IamSnapshotMapper.selectContexts` | 未过滤组织状态 | 组织级绑定仅在组织 `ACTIVE` 时产出上下文；门店绑定按有效组织（binding.org 或 store.org）过滤 |
| `IamSnapshotMapper.selectActiveStores` | 组织名 join 有状态，但不排除停用组织 | 只展开 `ACTIVE` 组织下的 `ACTIVE` 门店 |
| `IamSnapshotMapper.selectPermissionCodes` / `selectAuthorizationVersion` | 未过滤组织状态 | 组织级绑定仅在组织 `ACTIVE` 时参与聚合；租户级绑定（`organization_id=0`）不受影响 |
| `PermissionSnapshotProvider.contexts` | 使用上述查询 | 保持“授权不放大”；平台上下文同样排除停用组织门店 |
| `ContextController.select` | 以候选上下文为准 | 组织停用后旧 `contextId` 不再是候选 → 403 `CONTEXT_FORBIDDEN`；前端进入重选提示 |

> 这一组改动必须与组织 CRUD 同批上线。否则“停用组织”只是改了一个字段，安全上没有意义。

### 6.7 相邻缺陷：签名上下文的 0 与 null 口径

`iam_role_binding.organization_id/store_id` 是 `NOT NULL DEFAULT 0`，0 表示“非组织/非门店”；但运营人员 BFF 的作用域比较以 null 表示“不限”。签名上下文原样透传 0 时，“租户/组织级上下文”会被当成“storeId=0 的门店上下文”：门店下拉按 `storeId=0` 过滤后为空，开通门店角色被判跨店 403。修复口径：

- `StaffController.requireContext` 在**权限快照查询之后**把 `organizationId/storeId <= 0` 归一为 null（权限查询必须用原值，0 在 SQL 中表示非门店绑定）；
- `TenantIamDomainClient` 的运营门店过滤在比较前归一 `0 → null`；
- 该规则适用于所有消费签名上下文的 BFF 作用域判断；新增 BFF 功能不得只用 `== null` 区分“有/无门店”。

回归测试：`StaffControllerTest`（组织上下文 `storeId=0` 可开通门店角色、门店下拉不为空）、`TenantIamDomainClientScopeTest`（组织/租户/门店三种上下文的匹配矩阵）。

## 7. API 契约

### 7.1 端点

服务内路径不带 `/api`；网关统一前缀 `/api/v1`（`SAAS_PATH_STANDARD.md:10-15`）。

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/admin/tenant/organizations?status=` | `tenant.organization.view` | 当前租户组织列表；`status` 可选 |
| GET | `/admin/tenant/organizations/{id}` | `tenant.organization.view` | 组织详情 + 只读门店列表 |
| POST | `/admin/tenant/organizations` | `tenant.organization.manage` | 创建组织 |
| PUT | `/admin/tenant/organizations/{id}` | `tenant.organization.manage` | 改名（code 不可改） |
| PATCH | `/admin/tenant/organizations/{id}/status` | `tenant.organization.manage` | 状态变更 |
| — | 无 DELETE | — | 不物理删除 |

### 7.2 响应形状

```json
// GET /admin/tenant/organizations
[
  {
    "id": 100,
    "tenantId": 100,
    "code": "headquarter",
    "name": "总部",
    "status": "ACTIVE",
    "storeCount": 2,
    "createdAt": "2026-10-28T10:00:00Z",
    "updatedAt": "2026-10-28T10:00:00Z"
  }
]

// GET /admin/tenant/organizations/100
{
  "organization": { "id": 100, "code": "headquarter", "name": "总部", "status": "ACTIVE", "storeCount": 2 },
  "stores": [
    { "id": 100, "organizationId": 100, "code": "s929-ktv-001", "name": "SaaS KTV 旗舰店", "businessType": "KTV", "status": "ACTIVE" }
  ]
}
```

- `OrganizationView` 不暴露 `createdBy/updatedBy`（与 `StoreView` 口径一致，G22）。
- 列表不分页：单租户组织数量级小；若未来超过 200，再按既有列表惯例加 `page/pageSize`，不在本期预留伪分页参数。

### 7.3 错误码

| HTTP | code | 场景 |
| --- | --- | --- |
| 401 | `SAAS_CONTEXT_REQUIRED` | 缺少租户上下文 |
| 403 | `PERMISSION_DENIED` | 缺少 view/manage 权限（由 `PermissionGuard` 抛出） |
| 403 | `TENANT_SCOPE_DENIED` | 目标组织不属于当前租户（跨租户） |
| 404 | `ORGANIZATION_NOT_FOUND` | 组织不存在 |
| 400 | `ORGANIZATION_CODE_INVALID` / `ORGANIZATION_NAME_INVALID` / `ORGANIZATION_STATUS_INVALID` | 参数非法 |
| 409 | `ORGANIZATION_CODE_EXISTS` | 同租户 code 重复 |
| 409 | `ORGANIZATION_IN_USE` | 关闭前置条件不满足（返回门店/绑定/成员计数提示） |

错误响应沿用全局 `ApiException` 契约；前端用 `notifyAdminRequestError` 转成人话，禁止透出英文原文。

### 7.4 审计

| 动作码 | 资源类型 | 触发 |
| --- | --- | --- |
| `tenant.organization.create` | `tnt_organization` | 创建成功/失败（**已在 `AuditActions.java:183` 登记**） |
| `tenant.organization.update` | `tnt_organization` | 改名成功/失败（**需新增登记**） |
| `tenant.organization.status.change` | `tnt_organization` | 启停/关闭成功/失败（**需新增登记**） |

- 审计详情只记受控值：`{"from":"ACTIVE","to":"SUSPENDED"}`、`{"before":{"name":"..."},"after":{"name":"..."}}`，不记自由文本以外的大字段；
- 幂等键：`organization-create:<tenantId>:<code>`、`organization-status:<orgId>:<status>:<version>`；
- 失败也留痕（参照 `StoreApplicationService.recordFailure` 的写法），审计只 WARN，业务异常原样抛。
- 前端审计资源词表已含 `tnt_organization: '组织'`（`open-saas-admin/src/constants/terms.js:820`），动作动词表覆盖 `create/update/change`，无需新增词条；新增动作码必须与后端 `AuditActions` 登记保持一致。

### 7.5 幂等与并发

- 创建：`(tenant_id, code)` 唯一键兜底；重复创建返回 409（不做静默幂等，避免把“同名不同意图”吞掉）。
- 改名/状态：`UPDATE ... WHERE id=? AND tenant_id=?` 校验影响行数；并发下以数据库为准，必要时在 `OrganizationPo` 加乐观锁列（本期不加列，采用条件更新 + 读后校验）。
- 状态变更相同值：返回 200 + 当前视图（幂等），不写审计成功记录（或写 `noop`，二选一在实现时固定并测试）。

### 7.6 安全边界

- 租户来自签名 `X-Tenant-Context`，绝不从路径/请求体取租户（`SAAS_PATH_STANDARD.md`、`StoreApplicationService.requireContext` 范式）。
- 跨租户存在性判断必须用 `@InterceptorIgnore` 显式 SQL（照抄 `OrganizationMapper.selectTenantId`），区分 404 与 403。
- 菜单可见性只是体验；接口权限由 `PermissionGuard.require("tenant.organization.view/manage")` 强制。
- 平台运营只持 `view`；即便伪造请求也无法写（服务端 403）。

## 8. 权限与角色设计

### 8.1 一期权限矩阵

| 权限码 | module/resource/action | `scope_level` | `grantable_levels` | 授予角色 |
| --- | --- | --- | --- | --- |
| `tenant.organization.view` | tenant/organization/view | `TENANT` | `TENANT,PLATFORM` | `tenant.owner`（预置）、`platform.operator`（预置，只读） |
| `tenant.organization.manage` | tenant/organization/manage | `TENANT` | `TENANT` | `tenant.owner`（预置） |

说明：

- `view` 的 `grantable_levels` 含 `PLATFORM` 是为了让 `platform.operator`（平台作用域角色）能合法持有只读权限；这是平台运营“受控代操作”的既有模式（`tenant.currency.manage`/`report.view` 等也由平台运营持有）。
- `manage` 只允许 `TENANT` 角色，平台运营即使进入租户上下文也不能写。
- 不授予 `store.manager`、`store.cashier`、`store.finance`：门店人员不应管理组织。
- 自定义租户角色可按需获得 `view`；`manage` 是否允许自定义角色获得由租户管理员决定（同现有 `iam.role.manage` 的授权能力），但只有租户级角色可持有。

### 8.2 一期组织与“角色”的真实关系

一期组织与角色只有**间接关系**：

```text
组织 → 门店（organization_id）
门店 → 上下文（storeId）
角色绑定 → 作用域（TENANT / STORE）
```

- 租户级角色覆盖全租户（含所有组织）；
- 门店级角色只覆盖当前门店；
- 组织级角色（`ORGANIZATION`）是二期能力，一期不产生、不展示、不分配。
- 组织管理页只展示“门店数”和“门店列表”，不展示角色绑定，也不提供“组织管理员”入口；页面文案明确“组织级角色将在后续版本开放”。

### 8.3 二期：ORGANIZATION 作用域启用设计（前置条件 + 白名单）

启用顺序必须是“先数据域、后角色入口”，不得颠倒：

1. **数据域守卫**：
   - `StoreScopeRequestFilter` 之外新增组织边界校验：当上下文 `organizationId>0 && storeId==null` 时，门店相关接口必须校验目标门店 `store.organization_id == context.organizationId`，否则 403 `ORGANIZATION_SCOPE_DENIED`；
   - `GET /admin/tenant/stores`、门店更新、审计、报表、经营总览等按组织收敛的接口补 `organization_id` 条件；
   - 明确“组织上下文不是租户上下文”，禁止 `storeId=null` 退化为租户全量。
2. **角色模型放开**：
   - `Role.create` 允许租户自定义角色使用 `ORGANIZATION`；
   - `RoleMapper.selectAssignableByTenant` 的 `scope_level IN ('TENANT','STORE')` 扩为含 `ORGANIZATION`；
   - `StaffController.create` / `TenantIamDomainClient.isStaffRole` / BFF 允许 `ORGANIZATION` 且必须带 `organizationId`；
   - `RoleBindingApplicationService` 既有组织作用域校验保持（`ORGANIZATION_SCOPE_REQUIRED`、`ORGANIZATION_TENANT_MISMATCH`）。
3. **权限白名单**：只允许“数据域已按组织收敛”的权限码进入 `ORGANIZATION` 的 `grantable_levels`。候选集（需逐项验证）：

   | 权限码 | 是否可组织级 | 前置 |
   | --- | --- | --- |
   | `tenant.overview.view` | 待定 | 经营总览按组织过滤 |
   | `report.view` | 待定 | 报表按组织过滤 |
   | `audit.view` | 待定 | 审计按组织过滤 |
   | `tenant.organization.view` | 否 | 组织管理是租户级治理 |
   | `tenant.organization.manage` | 否 | 同上 |
   | `tenant.store.manage` | 否（一期不开放） | 需先完成按组织门店守卫 |
   | `tenant.tenant.manage` / `iam.role.manage` / `tenant.currency.manage` | 否 | 租户级配置 |

   **反模式**：把 `ORGANIZATION` 批量加进所有权限的 `grantable_levels`，或按“角色复制”绕过白名单。这两种做法都会把组织管理员变成跨组织越权入口。
4. **上下文与 UI**：上下文选择器开放组织项（需先完成 5.4 的语义修复）；经营总览/报表显示“组织范围”；角色与权限页新增 ORGANIZATION 选项与组织选择器；运营人员开通页新增组织选择。
5. **测试**：跨组织门店 403、跨租户 403、组织停用后 403/上下文消失、组织角色只能看到本组织数据。
6. **迁移**：二期如新增预设组织角色或列，须新 ADR + 新迁移；本期不预埋。

### 8.4 历史数据校准（必做）

目标：消除“`tenant.owner` 绑在 ORGANIZATION 作用域”的遗留数据（演示种子 `V33__demo_tenant_membership.sql:16-28` 即此形态）。

规则：

1. 找出 `iam_role_binding` 中 `role.code='tenant.owner' AND scope_type='ORGANIZATION' AND status='ACTIVE'` 的绑定；
2. 为同一 `subject(MEMBERSHIP) + role` 补一条 `scope_type='TENANT'`、`organization_id=0`、`store_id=0` 的绑定（不存在才插入）；
3. 将原组织绑定置 `REVOKED`；
4. 递增受影响 membership 的 `authorization_version`；
5. 输出校准前后核对表（tenant / membership / 绑定变化），作为发布证据。

不允许的做法：直接修改 V33（已执行迁移不可变，G28）；或在校准中改动非 `tenant.owner` 的自定义组织角色。

## 9. 前端设计

### 9.1 路由、菜单、权限映射

| 项 | 值 |
| --- | --- |
| 路由 | `/admin/tenant/organizations`，`name: TenantOrganizations`，`meta.scope: 'TENANT'`，`meta.title: '组织管理'`，图标 `OfficeBuilding` |
| 菜单 | `iam_menu`：`parent_id=100`、`code='organization'`、`name='组织管理'`、`path='/admin/tenant/organizations'`、`scope_level='TENANT'`、`domain_code='core'`、`required_permission='tenant.organization.view'`、`sort_no=5`、`status='ACTIVE'` |
| 页面权限映射 | `utils/menuPermission.js`：`'/admin/tenant/organizations': 'tenant.organization.view'` |
| 页面内写权限 | `hasPermission(contextStore.current?.permissions, 'tenant.organization.manage')` |
| 页面文件 | `open-saas-admin/src/views/tenant/organizations.vue` |
| API 文件 | `open-saas-admin/src/api/organization.js` |

`meta.scope` 必须是 `TENANT`：否则平台/门店路由守卫会拦截（`router/index.js:269-284`）。不得照抄审计页“不新增后端菜单/权限”的例外（`router/index.js:226-234`）——组织页是新增能力，必须走权限码。

### 9.2 页面蓝图

```text
┌ 组织管理 ──────────────────────────────────────────────── [+ 新建组织] ┐
│ 说明：组织用于对门店分组与授权；门店只能归属一个组织。                 │
├────────────────────────────────────────────────────────────┤
│ [状态筛选：全部 / 启用 / 停用 / 已关闭]                         │
│ ┌──────────┬──────────┬────────┬────────┬────────────┬────────┐ │
│ │ 组织名称  │ 组织编码  │ 门店数 │ 状态   │ 更新时间    │ 操作   │ │
│ ├──────────┼──────────┼────────┼────────┼────────────┼────────┤ │
│ │ 总部      │ headquarter│ 2     │ 启用   │ 2026-10-28 │ 查看门店 编辑 停用 │ │
│ └──────────┴──────────┴────────┴────────┴────────────┴────────┘ │
│ 空态：暂无组织 + [创建第一个组织]                                  │
└────────────────────────────────────────────────────────────┘
```

- **列表**：`el-table border stripe`；状态列用 `el-tag` + 词表函数；门店数为只读统计；操作按 `manage` 显示/禁用。
- **新建/改名**：`el-dialog width="min(520px, 94vw)"` + `el-form` + 行内校验；创建后刷新列表并提示；重复 code 显示 409 对应人话。
- **查看门店**：`el-drawer` 或对话框，展示只读门店表（名称/编码/业态/状态），并提供跳转门店页的链接；不提供改归属。
- **状态变更**：`ElMessageBox.confirm` 二次确认；停用确认文案包含“该组织下 N 家门店将无法再选择该组织范围；门店状态不变”；关闭确认文案列出前置条件；危险按钮 `type="danger"`。
- **错误处理**：统一 `notifyAdminRequestError(e, '加载/创建/保存组织失败')`；禁止 `window.confirm`、禁止裸英文错误。
- **权限只读态**：平台运营进入时 `canManage=false`，顶部显示 `el-alert type="info"`：“当前账号仅可查看组织信息；组织维护由租户管理员完成。”
- **空态**：`<el-empty description="暂无组织" />` + 主按钮；不出现空白页。
- **响应式**：沿用 `@media (max-width: 800px/640px)`，表格容器可横向滚动。

### 9.3 术语（`constants/terms.js`）

新增并纳入 `terms.test.js` 守卫：

```js
export const ORGANIZATION_STATUS_TEXT = { ACTIVE: '启用', SUSPENDED: '停用', CLOSED: '已关闭' }
export const ORGANIZATION_STATUS_TYPE = { ACTIVE: 'success', SUSPENDED: 'warning', CLOSED: 'info' }
export const ORGANIZATION_STATUS_ACTION_TEXT = { ACTIVE: '停用', SUSPENDED: '启用', CLOSED: '已关闭' }
// TERMS.organization = '组织'
```

- 已存在 `SCOPE_TYPE_TEXT.ORGANIZATION='组织级'`、`SCOPE_TYPE_HINT_TEXT.ORGANIZATION='组织级（所选组织）'`（`terms.js:477-490`），页面复用，不新增同义词。
- 页面不得出现“房间/包间/商家/待收/房台”等禁用词（`terms.test.js` 已守卫）。

### 9.4 上下文与布局改动（一期最小集）

| 文件 | 改动 | 原因 |
| --- | --- | --- |
| `utils/context.js` | `chooseSingleContext` 的“总部”判定要求 `organizationId` 为空；组织上下文不默认选中 | 防止组织范围被当成租户总部 |
| `components/TenantContextSelector.vue` | 组织级上下文项加“组织”标识并禁用（提示二期开放）；`itemLabel` 保持层级路径 | 不提供语义未实现的“工作范围” |
| `layout/AdminLayout.vue` | 上下文面板三态：租户总部 / 组织（只读范围）/ 门店；不再把组织叫总部 | 与后端上下文语义对齐 |
| `layout/workspaceShell.js` | 一期不动；二期再加组织工作区 | 避免扩散 |
| `router/index.js` | 新增组织页路由；其余不动 | 页面入口 |
| `utils/menuPermission.js` | 新增映射 | 菜单兜底门禁 |

> 数据校准完成后，正常租户管理员只有“租户总部/门店”候选；上述禁用逻辑是防御遗留脏数据，不改变正常路径。

### 9.5 UI 规范符合性

- 页面骨架：`admin-page` + `page-header` + `admin-card`（`32_FRONTEND_UI_STYLE.md:30-58`）；
- 主操作 `primary`、状态 `el-tag`、危险操作 `danger`；禁止手写页面级卡片、禁止自定义主色/圆角/阴影；
- 弹层只用 Element Plus `el-dialog` / `ElMessageBox`（`:199-206`）；
- 不新增全局样式变量，复用 `--brand-primary` / `--admin-radius`；
- 视觉回归按既有 360×800 / 393×851 / 412×915 视口执行（`:208-213`）。

## 10. 迁移与发布

### 10.1 迁移清单

| 仓库/服务 | 文件 | 内容 |
| --- | --- | --- |
| `open-im-server` / platform-tenant-service | `V36__organization_permissions.sql` | 2 个 `iam_permission`；授予 `tenant.owner`（view+manage）与 `platform.operator`（view）；不新建表 |
| `open-im-server` / platform-tenant-service | `V37__normalize_tenant_owner_scope.sql` | 校准 `tenant.owner` 组织级绑定 → 租户级（幂等）+ 递增 membership 版本 |
| `open-im-server` / platform-admin-service | `V8__organization_menu.sql` | 插入菜单叶子（id=33, parent=100, code=organization） |

约束：

- 最新版本：tenant `V35`、admin `V7`；不得回改已执行脚本（G28）。
- **空库部署顺序**：`platform-admin-service` 先于 `platform-tenant-service`（tenant 的 `V19/V26/V27` 依赖 admin 建的 `iam_menu/iam_role_profile`；见 `docs/50-services/platform/platform-tenant-service/README.md` 部署约束）。因此菜单先落库、权限后落库：窗口期菜单会被 `required_permission` 剪掉（fail-closed），不会出现“无权限入口”。
- 若先跑 tenant（仅本地单测/局部环境），菜单行不存在，页面仍可通过路由直达但接口 403；不作为发布顺序。
- 迁移必须同时满足：`V{n}__desc.sql` 命名、幂等 `ON DUPLICATE KEY UPDATE`/`NOT EXISTS`、不新增无 `tenant_id` 表、不改表结构。

### 10.2 迁移示意（V36）

```sql
INSERT INTO `iam_permission`
  (`code`, `module`, `resource`, `action`, `description`, `status`,
   `scope_level`, `domain_code`, `grantable_levels`, `menu_code`,
   `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('tenant.organization.view', 'tenant', 'organization', 'view', '查看组织', 'ACTIVE',
   'TENANT', 'core', 'TENANT,PLATFORM', 'organization', 0, NOW(3), 0, NOW(3)),
  ('tenant.organization.manage', 'tenant', 'organization', 'manage', '管理组织', 'ACTIVE',
   'TENANT', 'core', 'TENANT', 'organization', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `description` = VALUES(`description`), `scope_level` = VALUES(`scope_level`),
  `domain_code` = VALUES(`domain_code`), `grantable_levels` = VALUES(`grantable_levels`),
  `menu_code` = VALUES(`menu_code`), `updated_at` = NOW(3);

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `r`.`id`, `p`.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` `r`
JOIN `iam_permission` `p` ON `p`.`code` IN ('tenant.organization.view', 'tenant.organization.manage')
WHERE `r`.`code` = 'tenant.owner' AND `r`.`status` = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM `iam_role_permission` `rp`
                  WHERE `rp`.`role_id` = `r`.`id` AND `rp`.`permission_id` = `p`.`id`);

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `r`.`id`, `p`.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` `r`
JOIN `iam_permission` `p` ON `p`.`code` = 'tenant.organization.view'
WHERE `r`.`code` = 'platform.operator' AND `r`.`tenant_id` IS NULL AND `r`.`status` = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM `iam_role_permission` `rp`
                  WHERE `rp`.`role_id` = `r`.`id` AND `rp`.`permission_id` = `p`.`id`);
```

### 10.3 回滚

- 代码回滚：删除 Controller/路由/页面即可；接口消失，旧版本前端不会调用。
- 菜单回滚：`UPDATE iam_menu SET status='INACTIVE' WHERE code='organization' AND scope_level='TENANT'`（等价于下线入口）。
- 权限回滚：撤销 `iam_role_permission` 中两码的授权；权限行置 `DISABLED`（不物理删除，保留审计）。
- 数据校准回滚：保留校准前快照；如需恢复，按快照重建组织级绑定并撤销租户级绑定（高风险，须审批）。
- 零 DDL 意味着无表结构回滚。

### 10.4 分阶段路线

| 阶段 | 内容 | 出口条件 |
| --- | --- | --- |
| M0 前置修复 | 组织状态参与上下文/权限；owner 绑定校准；上下文语义修复 | 现有页面无回归；安全测试通过 |
| M1 后端 | Organization 聚合 + CRUD + 审计 + 权限迁移 | 单测/控制器测试、Flyway 空库+增量、403 矩阵 |
| M2 前端 | 菜单迁移 + 页面 + 路由 + 术语 + 守卫测试 | `npm test`/`npm run build`、UI 校验、E2E 主路径 |
| M3 发布验收 | 全量门禁、证据归档、文档同步 | release evidence + 验收签字 |
| P2（另立） | 组织级角色 + 组织工作上下文 | 数据域守卫 + 白名单 + 新 ADR/设计 |

## 11. 测试与验收

### 11.1 后端

| 测试 | 覆盖 |
| --- | --- |
| `OrganizationApplicationServiceTest` | 创建/重复 code 409/改名/状态机/关闭前置 409/跨租户 403/无权限 403/审计成功与失败/快照逐出 |
| `OrganizationControllerTest`（照抄 `StoreControllerTest` 范式） | 401 无上下文、403 无权限、200 列表/详情、404、400、409 |
| `IamSnapshotMapper` / `PermissionSnapshotProviderTest` 扩展 | 停用组织的组织级绑定不产出上下文/权限；门店展开只含 ACTIVE 组织 |
| `RoleBindingApplicationServiceTest` | 保持组织作用域校验；校准后的 TENANT 绑定合法 |
| Flyway 校验 | 空库全量 + V35→V36/V37 增量；`validate-flyway-conventions.ps1` / `validate-flyway-immutability.ps1` |
| 安全矩阵 | 跨租户读写、平台只读、无权限、组织停用后旧上下文失效 |

### 11.2 前端

| 测试 | 覆盖 |
| --- | --- |
| `utils/organization-guard.test.js`（新增） | 路由存在、页面用 `admin-page/page-header/admin-card`、错误走 `notifyAdminRequestError`、空态 `el-empty`、枚举走 terms |
| `utils/menuPermission.test.js`（改） | 新映射存在；现有“只登记币种一项”的断言需同步更新 |
| `utils/context.test.js`（改） | “总部”优先候选必须 `organizationId` 为空；组织上下文不默认选中 |
| `terms.test.js`（改） | 组织状态词表 / `SCOPE_TYPE_TEXT.ORGANIZATION` 不回退 |
| `stores/context.test.js`（改） | 组织上下文不触发整页刷新/不进入错误工作区（按一期禁用策略） |

### 11.3 E2E 主路径

1. 租户管理员登录 → 组织与门店 → 组织管理可见 → 创建组织 → 列表出现；
2. 重复 `code` → 409 人话提示；
3. 平台运营进入同一租户 → 组织菜单可见 → 无“新建/编辑/停用”按钮 → 直接调用写接口 403；
4. 停用组织（先确保其下无启用门店）→ 该组织级上下文候选消失 → 重新选择失败给出明确提示；
5. 关闭组织 → 前置条件不满足时 409 并显示门店数；
6. 审计日志出现 `tenant.organization.*` 记录。

### 11.4 证据与命令

```powershell
# 文档门禁
.\scripts\validate\validate-architecture-decisions.ps1
.\scripts\validate\validate-documentation-governance.ps1
.\scripts\validate\validate-documentation-reachability.ps1
.\scripts\validate\validate-documentation-links.ps1

# 代码阶段（提交前）
.\scripts\validate\invoke-engineering-validation.ps1 -Scope Changed
# 迁移专项
.\scripts\validate\validate-flyway-conventions.ps1
.\scripts\validate\validate-flyway-immutability.ps1
.\scripts\validate\validate-tenant-sql.ps1

# 后端测试（Windows）
.\mvnw.ps1 -pl platform-services/tenant/platform-tenant-service -am test

# 前端
cd open-saas-admin
npm test
npm run build
```

证据目录按发布规范保存：`.outputs/verify/release-<版本>/`（`70-operations/RELEASE_RUNBOOK.md:370-382`）。

## 12. 风险与缓解

| 编号 | 风险 | 缓解 |
| --- | --- | --- |
| R1 | 组织级角色被误解为已支持 | 页面文案显式说明“二期开放”；接口不提供 ORGANIZATION 角色；ADR/设计文档写明边界 |
| R2 | 停用组织导致运营中断 | 二次确认展示门店数；停用可恢复；审计记录影响面 |
| R3 | 关闭组织产生孤儿引用 | CLOSED 前置检查（门店/绑定/主组织 membership）+ 409；不物理删除 |
| R4 | 跨租户越权 | 签名上下文 + `@InterceptorIgnore` 归属校验 + 403 矩阵测试 |
| R5 | 菜单/权限迁移顺序导致入口短时不可见 | fail-closed 设计；admin 先于 tenant；验证 SQL 与发布检查 |
| R6 | 组织状态修复影响现有上下文 | 先出影响清单；灰度验证；快照逐出可观测 |
| R7 | owner 绑定校准被误扩大到自定义角色 | 校准 SQL 限定 `role.code='tenant.owner'`；校准前后核对表 |
| R8 | 文档/OpenAPI 漂移 | G52：同步 service README、权限模型、OpenAPI 快照 |

## 13. 备选方案与否决理由

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| A. 平台管理组织 | 否决 | 与“组织是租户下一级”冲突；双写入口；平台不掌握品牌/事业部划分 |
| B. 平台 + 租户双入口 | 否决 | 两套权限/审计/校验难一致，维护成本高 |
| C. 组织树（`parent_id`） | 本期否决 | 无业务需求；牵动上下文、权限继承、报表与门店归属；需新 ADR |
| D. 一期直接开放组织级角色 | 否决 | 缺少按组织收敛的数据域，直接产生跨组织越权 |
| E. 只做展示（D-3） | 否决 | 当前问题来源；可见即需治理 |
| F. 物理删除组织 | 否决 | 引用多、审计不可追溯、易产生孤儿数据 |

## 14. 待决策项

| 编号 | 问题 | 建议 | 影响 |
| --- | --- | --- | --- |
| D-1 | 平台运营是否应获得 `tenant.organization.manage` | 否（只读） | 若需要平台直接修数据，需 break-glass 流程与额外 ADR |
| D-2 | 租户开通是否自动创建默认组织 | 本期否；二期与开通模板一起做 | 影响新租户首次登录的空态体验 |
| D-3 | `CLOSED` 是否允许重新激活 | 否（终态） | 允许重开需定义 code 复用与审计规则 |
| D-4 | 二期组织级角色白名单 | 待数据域守卫完成后评审 | 决定组织管理员能做什么 |
| D-5 | 是否扩展 G55 覆盖 `platform-tenant-service` | 建议单独立项 | 当前脚本只扫 admin 服务，tenant 控制器靠测试兜底 |
| D-6 | 组织是否作为报表/账单归属维度 | 不在本期 | 涉及跨域口径，需业务确认 |

## 15. 实施任务拆分

| 编号 | 任务 | 主要文件 | 测试/门禁 | 依赖 |
| --- | --- | --- | --- | --- |
| T0-1 | 组织状态进入上下文与权限解析 | `IamSnapshotMapper.java`、`PermissionSnapshotProvider.java`、相关 SQL | `PermissionSnapshotProviderTest`、安全矩阵 | — |
| T0-2 | `tenant.owner` 组织级绑定校准 | `V37__normalize_tenant_owner_scope.sql` + 核对脚本 | Flyway 增量、前后核对表 | T0-1 |
| T0-3 | 前端上下文语义修复 | `utils/context.js`、`TenantContextSelector.vue`、`AdminLayout.vue` | `utils/context.test.js`、`stores/context.test.js` | T0-2 |
| T0-4 | 签名上下文 `0 → null` 归一（运营人员门店下拉/开通） | `StaffController.requireContext`、`TenantIamDomainClient.matchesScope` | `StaffControllerTest`、`TenantIamDomainClientScopeTest` | — |
| T1-1 | 组织领域模型/端口/PO/Adapter | `domain/organization/**`、`infra/persistence/**` | 领域单测 | — |
| T1-2 | 组织应用服务与 Controller | `application/OrganizationApplicationService.java`、`api/**` | `OrganizationApplicationServiceTest`、`OrganizationControllerTest` | T1-1 |
| T1-3 | 审计动作码登记 | `AuditActions.java` | 审计单测/词表测试 | T1-2 |
| T1-4 | 权限迁移 V36 | `V36__organization_permissions.sql` | Flyway 校验 | T1-2 |
| T2-1 | 菜单迁移 V8 | `V8__organization_menu.sql` | 菜单 BFF 测试/空库 | T1-4（权限先声明更稳） |
| T2-2 | 前端页面/路由/API/术语 | `views/tenant/organizations.vue`、`api/organization.js`、`router/index.js`、`utils/menuPermission.js`、`constants/terms.js` | 前端单测/构建/UI 校验 | T1-2 |
| T2-3 | 前端守卫测试 | `organization-guard.test.js` 等 | `npm test` | T2-2 |
| T3-1 | 文档同步 | 本文件、ADR-0016、`40-domain/README.md`、service README、`ACCOUNT_PERMISSION_MODEL.md`、OpenAPI 快照 | G47/G50/G52 | 全部 |
| T3-2 | 全量门禁与发布证据 | `.outputs/verify/release-<版本>/` | `invoke-engineering-validation.ps1 -Scope Full` | T3-1 |

> 实现时把本表拆成 PR 级任务，每个 PR 只覆盖一个写作用域；文档与迁移同批提交（G52）。

## 16. 工程规范符合性对照

| 门禁 | 本方案如何满足 |
| --- | --- |
| G47 architecture-decisions | 新增 ADR-0016（4 章节、`proposed`、登记 `adr/README.md`） |
| G52 docs-impact | Controller 变更同步 `50-services/platform/platform-tenant-service/README.md`；迁移同步 `40-domain/` 本文件与 service README |
| G55 permission-access-control | ApplicationService `PermissionGuard.require`；权限码先落 Flyway；403 测试。**注：脚本当前只扫 platform-admin-service，tenant 控制器需由测试与 PR 证据显式覆盖（D-5）** |
| G18/G19/G20/G43/G45/G46 | Controller 只做协议转换、无事务、无 Repository；路径与既有 `/admin/tenant/**` 不冲突 |
| G27/G28 | 新迁移 `V36/V37/V8`；不改 V1–V35/V1–V7 |
| G32 / tenant-sql | `tnt_organization` 已有 `tenant_id`；无新表；显式租户谓词与拦截器并用 |
| G22/G23 | 领域 record 不含审计字段；不新增保留标识符 |
| G54 frontend-ui-standard | 页面使用 `admin-page/page-header/admin-card`；无自定义主题；Element Plus 弹层 |
| G29/G44/G50 | 本文件登记到 `40-domain/README.md` 与 `identity/README.md`；链接可解析；ADR 登记 |
| G39 verify-inventory | 若新增校准核对脚本放 `scripts/verify/` 并登记 |
| G14/G15/G42 | 本方案不发 MQ 事件；状态失活用同步逐出 + 授权版本 |
| G38/G41 | 不新增指标；沿用既有审计/日志 |

## 17. 参考

- [ADR-0016 组织管理的归属与一期形态](../../20-architecture/adr/0016-organization-management-boundary.md)
- [ADR-0014 工作区与角色治理](../../20-architecture/adr/0014-workspace-role-model.md)
- [ADR-0015 身份联邦与租户 IAM](../../20-architecture/adr/0015-identity-federation-tenant-iam.md)
- [身份与访问模型](IDENTITY_ACCESS_MODEL.md) / [身份数据模型](IDENTITY_SCHEMA.md) / [租户开通现状](TENANT_ONBOARDING.md)
- [权限与数据域规范](../../80-development/standards/50_PERMISSION_AND_DATA_SCOPE.md)
- [数据库迁移标准](../../80-development/standards/23_DATABASE_MIGRATION_STANDARD.md)
- [前端 UI 风格标准](../../80-development/standards/32_FRONTEND_UI_STYLE.md)
- [platform-tenant-service 服务说明](../../50-services/platform/platform-tenant-service/README.md)
