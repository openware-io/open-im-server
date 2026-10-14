# 批次 2 基线冻结执行规格（Baseline Freeze Runbook）

> **性质**：工程详细文档。本文定义**目标里"打 `baseline-*` 冻结 tag"这一交付物的确切执行规格**，使其在任何授权时点都可机械复现。
> **当前状态**：**未冻结**（已实测：无基线 tag，迁移可重建）。冻结**未执行**，因其前置经项目自身方案（`ENGINEERING_GOVERNANCE_03` §5.3）排在批次 5 之后。

---

## 1. 冻结语义（权威来源：`scripts/validate/validate-flyway-immutability.ps1`）

门禁 `validate-flyway-immutability.ps1` 对"基线 tag"的判定（脚本 L6-L9）：

基线 tag 是**二者之一**：
1. **名字匹配 `baseline-*`** 的 tag，或
2. 注释中包含**独立一行** `freeze: true` 的 tag。

- **无基线 tag** → 门禁输出"未冻结（无冻结声明），允许重建基线"并 **exit 0**。
- **有基线 tag** → 门禁比对 `git diff --name-status <tag>..HEAD -- '*db/migration/*.sql'`：
  - 状态为 **M（修改）或 D（删除）** → **违规**，exit 1；
  - **新增（A）始终允许**。

> ⚠️ 门禁只匹配**结构化标记** `^\s*freeze:\s*true\s*$`（独立行）。散落的 "freeze" 字样**不**触发，这是刻意的——避免注释里"本 tag 不是冻结点"之类的话被误判为冻结声明。

---

## 2. 执行前置（来自 `ENGINEERING_GOVERNANCE_03` §5.3，按序，不得跳步）

| 序 | 前置 | 当前状态 |
| --- | --- | --- |
| ① | 批次 2 重建基线 | ✅ 已完成（19/19 根，199→22 文件） |
| ② | §5.4 版本归位 | 待确认 |
| ③ | **批次 5 总闸全绿** | ❌ **未达成**——`§13` 第 3 项规范治理 107 项违规（属批次 4） |
| ④ | 删 `v2.0.0`（本地 + 远端） | 待执行（会使指向它的 Release/外部链接失效） |
| ⑤ | 打 `v2.2.0` 注释 tag 并推送 | 待执行 |

> **③ 是唯一硬阻碍**。`§13` 第 1 项（唯一工程门禁 `-Scope Full`）**已达成**（实测 exit 0）；第 4 项留档可追溯**已达成**（199 行精确一致）；第 2、5 项为历史文档引用问题（详见 §5）。

---

## 3. 冻结执行命令（授权后照抄）

```powershell
# 前置：工作区必须干净；迁移集必须自洽（见 §4 自检）
git -C open-im-server status --porcelain          # 期望：空
powershell -NoProfile -File open-im-server/scripts/validate/validate-flyway-immutability.ps1
# 期望：Flyway immutability: not frozen ...（冻结前）

# 按 §5.3 顺序：先删旧 tag（本地+远端）
git -C open-im-server tag -d v2.0.0
git -C open-im-server push origin :refs/tags/v2.0.0

# 打冻结基线 tag（注释 tag；名字与 freeze 标记二者兼有，双保险）
git -C open-im-server tag -a v2.2.0 -m @"
开发阶段基线：本 tag 内 Flyway 迁移冻结，只增不改（见 docs/renovation/MIGRATION_BASELINE_01_DATA.md）
freeze: true
"@

# 验证门禁已识别冻结且无重写
powershell -NoProfile -File open-im-server/scripts/validate/validate-flyway-immutability.ps1
# 期望：Flyway immutability: frozen at v2.2.0, no rewritten migrations detected.

# 推送（⚠️ 会触发 publish-images 工作流：tags: ['v*']）
git -C open-im-server push origin v2.2.0
```

> **命名说明**：上述用 **`v2.2.0`**（项目方案指定的正式基线名，同时靠 `freeze: true` 行被门禁识别）。若要一个**名字即匹配**的 tag（`baseline-*`），可另打 `baseline-v2.2.0`；门禁对二者同样识别，**建议只打一个冻结 tag**，避免两个冻结点造成语义歧义。

---

## 4. 冻结自检（每次冻结前必跑；**当前全部通过**）

| 检查 | 判据 | 实测（HEAD `c6fb388`） |
| --- | --- | --- |
| 工作区干净 | `git status --porcelain` 为空 | ✅ 0 项 |
| 迁移改动为零 | 无 `db/migration` 相关未提交改动 | ✅ 0 项 |
| 版本号唯一 | 同一迁移根内无重复版本号 | ✅ 无重复 |
| 迁移文件可枚举 | `migration` 与 `migration-*` 两类目录分别 `ls-files` | ✅ **22** 个（18 个 `migration` 根 + 1 个 `migration-audit` 根含 3 文件） |

> **统计口径提醒**：`git ls-files` 的 pathspec 必须**分两次**调用（`*/db/migration/*.sql` 与 `*/db/migration-*/*.sql`）。单次模式会漏掉 `migration-audit` 根——本仓审计服务正是这种结构（`migration` + `migration-audit` 双根）。

---

## 5. 冻结后门禁的不变量（不得回退）

- **已冻结后**：修改或删除 tag 内任何迁移 → 门禁 **exit 1**。届时修复只能**新增 V(n+1)**，不得改旧脚本。
- **允许**：新增迁移文件。
- **本次改名的既有事实**：`iam_audit_*` → `audit_*` 等改名**发生在冻结之前**，因此是**合法的基线重写**；冻结后不得再出现同类改名（否则只能新增 `RENAME` 迁移）。

---

## 6. 与 §13 验收总闸的关系（剩余项的性质区分）

| §13 项 | 状态 | 性质 |
| --- | --- | --- |
| 1. 唯一工程门禁 `-Scope Full` | ✅ exit 0 | — |
| 2. 凭据清零（`192.168.31.`） | ❌ 15 处 | **全在 `docs/` 历史快照与方案文档**；`scripts/` 内实测 **0** |
| 3. 规范治理 | ❌ **107 项** | **真实存量**：23 处项目模块名 + 5 处绝对路径 + 11 处 TODO + 68 处缺最简模板节（跨 15 个规范文件）→ **批次 4** |
| 4. 留档可追溯 | ✅ **199 行**（精确一致） | — |
| 5. 无第二校验入口 | ❌ 12 行 | 其中 **4 行是校验器自身禁词表**（假阳性）；其余在历史/方案文档 |
| 6. 根目录整洁（G-15 allowlist 11 项） | ✅ 无输出 | — |
| 7. 部署与交付清单 | ✅ 31/31 deployment | — |
| 8. 业务回归 `scripts/verify/*` | ⚠️ 已全量执行并定性 | 13/13 已执行；失败全部为**外部凭据 / 按环境传参 / 需 DB 直连**，已文档化于 `standards/43 §3.1` |

> **结论**：冻结的硬阻碍是 **§13 第 3 项（批次 4 的 107 项规范治理）**。第 2、5 项主要涉及**历史文档引用口径**与**校验器自身假阳性**，属门禁口径问题，**改动需先经确认**（不得为达标而擅改验收标准）。
