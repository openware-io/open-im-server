# 门禁治理规范

[中文](04_GATE_GOVERNANCE.md) | [English](04_GATE_GOVERNANCE.en.md)

> 状态：`current`（`develop/2.3.0`）  
> 层级：L0 治理  
> 适用范围：所有 `Gxx` 门禁、门禁脚本、注册表、目录、执行入口和证据。

## 目标与适用范围

本规范规定**门禁如何新增、分类、放置、排序、评审、降级和退役**，确保门禁数量增长时仍然可维护，而不是变成一堆互不关联的脚本。

适用：

- 新增或修改 `scripts/validate/validate-*.ps1`；
- 新增或修改 `invoke-engineering-validation.ps1` 中的执行顺序；
- 新增 `report-only` 门禁或把门禁转为 blocking；
- 新增领域门禁（架构、安全、数据、业务、质量、交付、文档）。

不适用：

- 单个门禁内部的业务判断；
- 具体标准的正文；
- CI 平台自身的运行配置。

## 术语与前置假设

- **门禁（gate）**：对一条可执行规则的自动检查，必须有明确失败条件。
- **注册 ID**：`G01`、`G02`……，按注册顺序递增，**不表示类别**。
- **类别（category）**：门禁所属治理面，如 `architecture`、`security`、`data`。
- **Owner**：对门禁规则和误报负责的角色。
- **阶段（stage）**：`static`、`unit`、`integration`、`docs`、`release`。
- **证据（evidence）**：失败时可定位的 worklist、日志或报告。
- **退役（retired）**：规则不再适用，但保留 ID 和历史，不复用编号。

## 原则与约束

### 1. 一个门禁只回答一个问题

门禁名称必须是 `domain-subject`，例如：

```text
service-api-boundaries
tenant-sql
documentation-reachability
```

禁止：

```text
check-all
misc-rules
new-gate
```

### 2. ID 与类别分离

- ID 是**注册顺序**，一旦分配永不复用；
- 类别写在 `GATE_CATALOG.md`，不靠 ID 猜；
- 现有 G01–G52 不重新编号；
- 新门禁取下一个未使用 ID；
- 类别可以扩展，但必须先在治理规范中登记。

### 3. 分类

| 类别 | 关注面 | 典型 Owner |
| --- | --- | --- |
| `meta` | 门禁、标准、文档治理本身 | governance |
| `code-quality` | 编码、异常、日志、脚本卫生 | backend |
| `architecture` | 分层、边界、依赖、模块、路由 | architecture |
| `security` | 密钥、租户隔离、敏感数据 | security |
| `data` | 持久化、迁移、数据边界 | data |
| `domain` | 业务规则、消息语义、币种 | domain |
| `quality` | 可观测性、测试、验证清单 | quality |
| `media` | 媒体边界与 URL | platform |
| `delivery` | 部署、发布、客户端交付 | delivery |

### 4. 放置位置

```text
scripts/validate/validate-<domain>-<subject>.ps1
```

- 脚本名必须与门禁名一致：`service-api-boundaries` → `validate-service-api-boundaries.ps1`；
- 脚本必须 ASCII-only；
- 不得把多个无关规则塞进一个脚本；
- 不得在门禁脚本里修改业务代码或文档，只读取和报告。

### 5. 登记位置

新增门禁必须同时更新四处：

1. [GATE_REGISTRY.md](../governance/GATE_REGISTRY.md)：ID、名称、脚本、模式、说明；
2. [GATE_CATALOG.md](../governance/GATE_CATALOG.md)：类别、Owner、阶段、证据；
3. [invoke-engineering-validation.ps1](../../../scripts/validate/invoke-engineering-validation.ps1)：执行位置；
4. 相关标准和文档入口。

缺一处，G33/G51 必须失败。

### 6. 执行顺序

```text
static → unit → integration → docs → release
```

- 纯静态规则放在最前，快速失败；
- 需要构建/启动的规则放在 integration；
- 文档和链接检查放在 docs 阶段；
- 发布清单检查放在 release 阶段；
- 同一阶段内按依赖顺序排列，不按作者喜好。

### 7. 模式

- `blocking`：默认；失败阻断；
- `report-only`：只允许在**有限 worklist**、明确 Owner、到期时间和退出条件时使用；
- 禁止永久 report-only；
- worklist 清零后必须删除 `report-only-gates.txt` 条目并转 blocking。

### 8. 新增门禁的最小评审

新增前必须回答：

1. 它支撑哪条原则（`03_ENGINEERING_PRINCIPLES` P1–P10）？
2. 它属于哪个类别、阶段、Owner？
3. 失败时用户看到什么可执行信息？
4. 误报如何排除？有没有 allowlist？allowlist 谁维护？
5. 基线是否为零？如果不是，worklist 和退出条件是什么？
6. 是否会与现有门禁重复？

### 9. 退役

- ID 永不删除、永不复用；
- 在 catalog 标记 `retired`，说明替代门禁或不再适用的原因；
- 从 runner 移除，删除脚本前先归档；
- 更新 registry、catalog、report-only、标准版图。

## 模板或示例

新增门禁提交模板：

```text
Gate: G53
Name: contract-compatibility
Category: architecture
Owner: architecture
Stage: static
Why: 防止已发布 API 的破坏性变更
Principle: P8 兼容性与可演进
Script: scripts/validate/validate-contract-compatibility.ps1
Blocking: yes
Baseline: 0
Docs: docs/80-development/standards/44_API_COMPATIBILITY.md
Registry: updated
Catalog: updated
Runner: after service-api-boundaries
```

## 门禁与验证

- **G33 gate-registry**：registry、脚本、runner、report-only 四方一致；
- **G51 gate-catalog**：每个 registry 门禁在 catalog 中恰好一次，类别/Owner/阶段合法；
- **G52 docs-impact**：高风险代码变更必须伴随对应文档变更；
- **G50 documentation-reachability**：现行文档必须可达。

## 可验证的验收标准

- 新门禁有唯一 ID、类别、Owner、阶段和文档入口；
- 失败消息能直接指出文件和修复方向；
- 没有永久 report-only；
- G33/G51 通过；
- 代码变更涉及 API/迁移/门禁/部署时，G52 有明确文档证据。

## 与其他规范的关系

- [工程原则](03_ENGINEERING_PRINCIPLES.md)：门禁支撑的原则；
- [标准版图](STANDARDS_MAP.md)：标准与门禁的映射；
- [门禁注册表](../governance/GATE_REGISTRY.md)：唯一 ID 台账；
- [门禁目录](../governance/GATE_CATALOG.md)：类别、Owner、阶段；
- [工程规范](../governance/ENGINEERING_RULES.md)：执行流程。
