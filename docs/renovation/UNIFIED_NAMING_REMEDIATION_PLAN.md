# 统一规范整改总纲 v2（含你的裁决与我的补充建议）

> 你的裁决已录入：**D1 不改**｜**D2 改（收敛到 `idaas`）**｜**D3 改**｜**D4 统一**｜**D5 我列建议清单**｜**D6 不改**｜**D7 下一级建 `saas` 目录后收敛**｜**D8 已实现的→工程实现文档；规范更新的→工程规范文档；其余标历史文档**
> 另：`console` 改短为 **`cons`**（你提出）。
> 本文件**仍未改动任何文件**（除自身），待你确认 `cons` 与下面几处补充建议后执行。

---

## 一、`console` → `cons`：**同意**，但要补一条防止自伤

`cons` 比 `console` 短，符合 `ENGINEERING_RULES.md:156`"缩写的目的在于控制表名长度"，且与全表 2–4 字母的风格一致。

**唯一风险**：`cons` 也容易联想 `constructor` / `const`。为防歧义，建议：
1. 登记表**写明域义**："运营控制台（Console）"，前缀 `cons`；
2. 长度取 **4 字母**（`cons`），比 `csl`/`cnsl` 更可读；
3. 表名 `cons_admin_account`、`cons_menu` 语义自明。

> 若你更认可 `csl`，请说；否则按 `cons` 执行。

---

## 二、⚠️ D3/D4 落实时发现的**真错位**（需你定处理方式）

按你说的"**im 要对应 im 的表、saas 要对应 saas 的表，不要错位**"，实测发现两处：

| 服务 | 现在连的库 | 表归属 | 问题 |
| --- | --- | --- | --- |
| **`common-audit-service`** | 默认 `open_im`（未启 profile 时） | 审计域（**独立域**，IM 与 SaaS 共用） | ❌ 审计表默认落进 **SaaS 库**；应为独立审计库 `open_audit` |
| **`group-idaas-service`** | `open_im` | **集团层**（IDaaS） | ❌ 集团域的表落进 **SaaS 库**；应按业务线分设 |
| `common-media-service` | `im_server` | `media_*`（IM 拥有） | ✅ **不算错位**（表随 IM 业务线） |

**另发现一处碰撞风险**：`common-media-service` 的 Flyway history 用**默认表名** `flyway_schema_history`，而其他服务都用专属名（`flyway_schema_history_*`、`*_schema_history`）。若与别的服务共库会造成 history 表冲突。

### 补充建议 A：库名统一方案（D4"统一"的落地）

现有：IM 库 `im_server`、SaaS 库 `open_im`、审计库 `open_audit`。**风格不一致**（一个中性名、一个产品名）。两个方案：

| 方案 | 结果 | 代价 |
| --- | --- | --- |
| **A1（建议）** | `open_im`（IM 业务线）/ `open_saas`（SaaS 业务线）/ `open_audit`（审计）/ `open_idaas`（集团） | **需迁移 IM 库名**（`im_server`→`open_im`），且现 `open_im`（SaaS）要改名为 `open_saas` → **两个库都要迁** |
| **A2（零迁移）** | 仅**修错位**：审计显式 `open_audit`、集团新建 `open_idaas`；`im_server`/`open_im` 保持 | **零数据迁移**，但不满足"风格统一" |

> ⚠️ A1 会**同时改动 IM 与 SaaS 两个库名**，涉及全部服务的连接配置、k8s init job、既有数据卷，且**必须在清库重建窗口内做**（本地用 `emptyDir`，重建即清空，见 03 方案 N-23）。**请你选 A1 或 A2。**

---

## 三、D5：旧客户端名 → 新名**建议清单**（请确认或纠正）

依据 = k8s 应用清单（`k8s/local/applications.yaml` 的 `__APP_IMAGE_*__` + `k8s/ack/*.yaml`）。

| 文档里的旧名 | **建议新名** | 依据 | 置信度 |
| --- | --- | --- | --- |
| `gv_saas_admin` | **`saas-admin`** | `__APP_IMAGE_SAAS_ADMIN__`、`k8s/ack/saas-admin.yaml` | 高 |
| `gv_saas_mobile` | **`saas-mobile`** | `__APP_IMAGE_SAAS_MOBILE__`、`k8s/ack/miniservice.yaml` 的 `saas-mobile:1.1.1` | 高 |
| `gv_chat_app` | **`saas-mobile`**（移动端） | 旧名 `chat_app` ≈ App；当前移动端只有 `saas-mobile` | **中——需你确认**（也可能是独立 App 仓库） |
| `gv_chat_desktop` | **`pc-admin`** | 旧名 `chat_desktop` ≈ PC 客户端；当前 PC 端只有 `pc-admin` | **中——需你确认** |
| `gv_chat`（单用） | **`saas-mobile`** 或整体称 **"IM/SaaS 客户端"** | 语境多指"客户端总体" | **低——需你定** |
| `gv_im_server` | **`im-services/` + `platform-services/`（服务端）**；库见补充建议 A | 旧名即"服务端总体" | 高 |
| （无旧名） | `open-website`、`unified-portal` | `__APP_IMAGE_OPEN_WEBSITE__`、`__APP_IMAGE_UNIFIED_PORTAL__` | — |

> ⚠️ **本仓只有 `portal/`**，`saas-admin`/`saas-mobile`/`pc-admin` 的实现**不在本仓**。所以改名只能改**文档里的称呼**；若那些前端在别的仓库，需另行统一。

---

## 四、D7：`docs/im-services/` 目录归属（按你的方案）

**你的方案**：下一级建 `saas` 目录后收敛进去。建议落地为：

```text
docs/
├── im-services/            # 只放 IM 业务线服务文档
│   ├── user/ message/ conversation/ admin/        ← 现缺，需补
│   └── (audit? media? 见下)
├── platform-services/      # 新建：SaaS 业务线服务文档
│   └── saas/               # 你要求的 saas 目录
│       ├── identity/ tenant/ customer/ marketing/
│       ├── order/ resource/ payment/ payment-channel/ admin/
└── common-services/        # 新建：通用支撑（audit / media / mail / sms）
```

**现状错位**（实测 `docs/im-services/` 现有 10 个目录）：

| 目录 | 实际业务线 | 应迁往 |
| --- | --- | --- |
| `im-services/admin` | IM | 留 `im-services/` |
| `im-services/audit` | 通用支撑 | → `common-services/audit` |
| `im-services/identity`、`customer`、`marketing`、`payment`、`payment-channel`、`resource`、`sms`、`tenant` | **SaaS** | → `platform-services/saas/` |

> 即：**10 个目录里 9 个放错了业务线**。这也是"文档归属"层面最该整改的一处。

---

## 五、D8：文档三分法（按你的口径）

你要求：**已实现的 → 工程实现文档；规范更新的 → 工程规范文档；其余标历史**。

| 类别 | 判据 | 去向 | 本轮实测归类 |
| --- | --- | --- | --- |
| **① 已实现 → 工程实现文档** | 描述**现状事实**、长期有效 | `docs/business/`、`docs/services/`、`docs/im-services/`、`docs/deployment/` | `ACCOUNT_PERMISSION_MODEL.md`（已在此）、新建的 `PROJECT_FACTS_INVENTORY.md`（已在此）；`docs/renovation/` 下**已落地**的方案（如 `IM_OPEN_PLATFORM_01~06`、`AUDIT_STORAGE_01`）**需甄别后迁入** |
| **② 规范更新 → 工程规范文档** | 描述**应当如何** | `docs/standards/*`、`docs/ENGINEERING_RULES.md` | 前缀命名条文本轮已写入 `ENGINEERING_RULES.md`；`domains/prefix` 相关待补 |
| **③ 其余 → 标为历史文档** | 已过时的方案稿/盘点/审计快照 | 留在 `docs/renovation/` 或日期化文件，**加统一标注** | `DOMAIN_PREFIX_SPEC_REVIEW/V2/V3`、`NAMING_REMNANT_AUDIT`、`audit-2026-09-17-*`、`currency-scope-inventory-2026-09-18` 等 |

**建议的统一历史标注**（加在文件首行下方）：

```markdown
> ⚠️ **历史文档（归档于 <日期>）**：本文记录当时的状态与判断，**命名与结论以 [项目事实清单](../business/PROJECT_FACTS_INVENTORY.md) 与现行规范为准**，不再更新。
```

> ⚠️ **甄别 `docs/renovation/` 里"已实现"的方案需要逐个核对**（不只看文档自称）。本轮未做，列为执行项——**这是 D8 的主要工作量**。

---

## 六、执行清单（全部）

| 步 | 动作 | 状态 |
| --- | --- | --- |
| 1 | `ENGINEERING_RULES.md`：登记表增列（业务线/限界上下文/权威服务）+ 命名条文 + 新增 `open`/`idaas`/`cons` 行 + 库名对齐 | 待执行 |
| 2 | 同步 `scripts/validate/table-prefixes.txt`（**两处同改**；新增 `open`/`idaas`/`cons`） | 待执行 |
| 3 | **A**：`iam_audit_*` → `audit_*`（DDL 含索引名 + 两个迁移根 + 文件名） | 已批准 |
| 4 | **C**：`saa_admin_account` → `cons_admin_account`（表名 + PO 类名 + 索引名 + 注解） | 待 `cons` 确认 |
| 5 | **D2**：`group_account`/`group_org` → `idaas_account`/`idaas_org`；`group_` 留档 | 已批准 |
| 6 | **错位修正**：审计显式 `open_audit`；集团建独立库；media history 表名补专属名 | 待方案 A1/A2 |
| 7 | 文档三分法（① 迁实现文档 ② 规范更新 ③ 标历史）+ `docs/im-services` 目录收敛 + 旧产品名改名 + 绝对路径清理 | 待执行 |
| 8 | `.env` 桶名 `gv-im-local-private` → `open-im-local-private` | 待确认 |
| 9 | 门禁 **7 → 0** 并转阻断；每步跑受影响模块测试 | 待执行 |

---

## 七、还需你定的 4 点

| # | 决策 | 选项 |
| --- | --- | --- |
| **E1** | 前缀长度 | **`cons`（同意）** / `csl` / 仍用 `console` |
| **E2** | 库名统一方案 | **A1 全面统一**（含改 IM 库名，需迁移）/ **A2 只修错位**（零迁移） |
| **E3** | D5 清单里的中/低置信项 | `gv_chat_app`→`saas-mobile`？`gv_chat_desktop`→`pc-admin`？`gv_chat`→？ |
| **E4** | 集团库名 | `open_idaas` / `idaas` / 其他 |
