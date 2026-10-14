# 统一规范整改总纲 v3（工作区级）

> **v3 的关键修正**：v1/v2 只扫了 `open-im-server` **一个仓库**，遗漏了工作区另外 9 个仓库。用户指出后重做，**范围改为整个工作区 `D:\projects\cnb-oss`（10 个 git 仓库）**。
>
> 你的裁决：**D1 不改**｜**D2 改（收敛到 `idaas`）**｜**D3 改**｜**D4 统一**｜**D5 我列清单**｜**D6 不改**｜**D7 下一级建 `saas` 目录后收敛**｜**D8 三分法**｜`console` 缩为 **`cons`**｜**A1 全面统一库名**

---

## 一、工作区全貌（此前遗漏的部分）

`D:\projects\cnb-oss` 下 **10 个 git 仓库**，按**两条业务线 + 集团 + 支撑**分族：

| 仓库 | 定位（自述） | 归属 |
| --- | --- | --- |
| `open-im-server` | 服务端（含 `portal/`） | IM |
| `open-chat-app` | **Open IM 客户端（Flutter）** | IM |
| `open-chat-desktop` | **IM 跨平台桌面客户端（Electron + Vue 3）**；自述仍写 "WV Chat" | IM |
| `open-chat-admin` | **Open IM 平台 PC 管理后台（Vue 3 + Element Plus）** | IM |
| `open-chat-turn` | TURN 服务（WebRTC） | IM（支撑） |
| `open-saas-admin` | **SaaS 管理后台（平台运营后台 / 租户后台）** | SaaS |
| `open-saas-mobile` | **SaaS 接入 App 的前端（Vue3 双入口 H5）** | SaaS |
| `open-green-pass` | **GreenPass · 软件工程测试治理平台** | **独立产品**（与 IM/SaaS 无关） |
| `open-website` | IM 官网及静态内容站 | IM |
| `.github` | 组织级配置 | — |

> **命名族规律已形成**：`open-chat-*` = IM 客户端族；`open-saas-*` = SaaS 前端族。**旧前缀 `gv`/`wv` 正是这族名的前身。**

---

## 二、⚠️ 修正我上一轮的错误：客户端映射

我上一轮把 `gv_chat_desktop` 猜成 `pc-admin`——**错**。经代码核查，**权威映射**（出自 `open-im-server/scripts/deploy/build-saas-release.ps1:21-28` 的 `$projects` 与 `$projectSources`）：

| k8s 应用名 | 来源仓库 | 说明 |
| --- | --- | --- |
| `pc-admin` | **`open-chat-admin`** | IM 平台 PC **管理后台**（注意在 `k8s/local/gateway-admin.yaml`，**不连库**） |
| `saas-admin` | `open-saas-admin` | SaaS 管理后台 |
| `saas-mobile` | `open-saas-mobile` | SaaS 接入 App 前端 |
| `unified-portal` | **`open-im-server`（本仓 `portal/`）** | 统一门户 |
| `open-website` | `open-website` | IM 官网 |

**旧名 → 新名 建议清单（已按代码核实，请你确认）**：

| 旧名 | **建议新名（仓库名）** | 依据 | 置信度 |
| --- | --- | --- | --- |
| `gv_chat_app`（115 处） | **`open-chat-app`** | `open-chat-desktop/docs/architecture.md:7` 明确"移动端 App（`gv_chat_app`，Flutter）1:1 复刻" | **高（已核实）** |
| `gv_chat_desktop`（33 处） | **`open-chat-desktop`** | 同上文档首行标题即 `# gv_chat_desktop 架构总览` | **高（已核实）** |
| `gv_chat_admin`（31 处） | **`open-chat-admin`** | IM PC 管理后台，与 `pc-admin` 应用对应 | **高** |
| `gv_saas_admin` | **`open-saas-admin`** | `open-saas-admin/docs/i18n-migration-plan.md:1` 标题即 `# gv_saas_admin 多语言落地` | **高（已核实）** |
| `gv_saas_mobile` | **`open-saas-mobile`** | `open-saas-mobile/README.md:10` 自称"本仓库" | **高（已核实）** |
| `gv_im_server` | **`open-im-server`** | 服务端；`open-chat-desktop/docs/architecture.md:53` 称"唯一后端为 gv_im_server" | **高（已核实）** |
| `gv_chat`（6 处，单独出现） | **应统一为上述具体名**；当指"客户端总体"时写"IM 客户端" | 与 `gv_chat_app` 语义重叠，属**同一事物的省略写法** | **建议合并，不再单独使用** |
| `wv_chat`（`WV Chat`） | **`Open Chat`**（IM 客户端族） | `open-chat-desktop` 自述仍写 "WV Chat"，是 `wv` 的唯一集中残留 | 需你定中文/英文名 |
| `GV_API_BASE`、`GV_JPUSH_APPKEY`、`GV_TRUST_SELF_SIGNED` 等环境变量 | **`OPEN_API_BASE`、`OPEN_JPUSH_APPKEY`、`OPEN_TRUST_SELF_SIGNED`** | `open-chat-app/BUILD.md` 里的构建参数 | 需你定前缀词 |

> **回答你的问题**：`gv_chat` **与 `gv_chat_app` 不是两样东西**——`gv_chat` 是同一客户端族的**省写**（6 处），`gv_chat_app` 是完整写法（115 处）。**建议统一为仓库名，`gv_chat` 不再单独出现。**

---

## 三、`gv`/`wv` 残留全工作区实测（v3 修正后的范围）

| 仓库 | `gv` | `wv` | 说明 |
| --- | --- | --- | --- |
| `open-im-server` | 705 | 2 | 绝大多数是**日期快照与 renovation 稿**（历史留档）；生产代码只剩 `.env` 1 处 |
| **`open-chat-app`** | **68** | 0 | 集中在 `BUILD.md`(9)、`docs/multi_environment_build_commands.md`(16)、`docs/architecture.md`(9)、`INTEGRATION_TESTING.md`(7) |
| **`open-chat-desktop`** | **57** | **5** | `docs/contracts.md`(19)、`src/renderer/src/stores/chat.ts`(**6 处代码**)、`docs/README.md`、`docs/architecture.md` |
| `open-saas-mobile` | 37 | 0 | `c-end/ktv-room-type-booking.test.js`(5)、`README.md`(4)、`c-end/app.js`(3) |
| `open-saas-admin` | 22 | 0 | `docs/remote-dev-integration.md`(6)、`docs/i18n-migration-plan.md`(3)、`src/utils/format.js`(2) |
| `open-chat-admin` | 1 | 2 | `src/views/audit/index.vue`、`src/views/config/client-releases.vue` |
| `open-website` | 2 | 0 | `html/developer.html` |
| `open-chat-turn` / `open-green-pass` / `.github` | 0 | 0 | 干净 |

**总计约 180 处（不含 `open-im-server` 的历史文档）**，且**含前端生产代码**（如 `open-chat-desktop/src/renderer/src/stores/chat.ts` 6 处、`open-saas-admin/src/utils/format.js` 2 处）。

### 跨仓库绝对路径残留（既有工作区路径，也有旧路径）

| 文件 | 内容 | 判定 |
| --- | --- | --- |
| `open-saas-mobile/README.md:103` | `D:\projects\cnb\gv_im_server\scripts\deploy\sync-oidc-client-registry.ps1` | ❌ **旧路径 + 旧名，必须改** |
| `open-chat-desktop/docs/architecture.md:53` | 提到 `gv_im_server` / `gv_chat_app` 的 worktree 约定 | ❌ 改 |
| `open-im-server/docs/RELEASE_RUNBOOK.md` | 15 处绝对路径 | ❌ 改（活文档） |
| `open-im-server/docs/standards/15` | 4 处绝对路径 | ❌ 改（规范禁止） |

---

## 四、`cons` 与 schema 命名（A1 全面统一）

### 4.1 前缀

`console` → **`cons`**：同意（更短，符合 `:156`）。登记表须写明域义"运营控制台（Console）"。表名：`cons_admin_account`、`cons_menu`。

### 4.2 Schema 命名（A1）— **还需你定一项**

**权威来源**：`build-saas-release.ps1` 用 `$saasImages.Keys`（应用名）映射到 `__APP_IMAGE_<NAME>__`。未来若加 schema 变量，命名须与之同源。

各服务**现存库/schema 变量**（实测）：

| 服务 | 变量 | 现值 |
| --- | --- | --- |
| IM 服务（user/message/conversation/admin/media） | `DB_DATABASE` → `DB_NAME` | `im_server` |
| 多数 SaaS 服务 | `MYSQL_DATABASE` | `open_im` |
| 审计（`audit-schema` profile） | `AUDIT_DATABASE` | `open_audit` |
| 集团 | `MYSQL_DATABASE` | `open_im`（**错位，待改**） |

**A1 建议目标**（需你确认第 3 列）：

| 业务线/域 | 建议库名 | 变量名建议 | 备注 |
| --- | --- | --- | --- |
| IM 业务线 | `open_im` | `IM_DATABASE` | 需从 `im_server` 迁名 |
| SaaS 业务线 | `open_saas` | `SAAS_DATABASE` | 现 `open_im` 迁名 |
| 审计域 | `open_audit` | `AUDIT_DATABASE` | 不变，但**须改为默认**（现仅 profile 生效） |
| 集团层 | **`open_idaas` / `open_group` / 其他？** | `IDAAS_DATABASE` | **⬅ 需你定（E4）** |
| 通用支撑（media） | `open_media`？ | `MEDIA_DATABASE`？ | 现阶段在 `im_server`，是否独立 |

> **我的建议**：集团用 **`open_group`**——因为该域在登记表里的域名是"集团认证服务/集团"，且 `group` 是业务线之上的**层级名**；`open_idaas` 也可以，但 `idaas` 是**技术词**（身份即服务），作为库名不如 `group` 直观。**两个都可行，请你定。**

---

## 五、D7 / D8（沿用 v2，含工作区级调整）

### D7 `docs/` 目录收敛

`open-im-server/docs/im-services/` 现有 **10 个目录，9 个不属于 IM**（identity/customer/marketing/payment/payment-channel/resource/sms/tenant 属 SaaS，audit 属通用支撑）。目标：

```text
docs/im-services/            ← 只放 IM（并补现缺的 user/message/conversation/admin 文档）
docs/platform-services/saas/ ← 你要求的 saas 目录，收敛 SaaS 服务文档
docs/common-services/        ← audit / media / mail / sms
```

（`docs/platform-services`、`docs/common-services` 现均**不存在**，需新建。）

### D8 三分法（**工作区级**）

| 类别 | 判据 | 去向 |
| --- | --- | --- |
| ① 已实现 → **工程实现文档** | 描述现状事实、长期有效 | 各仓 `docs/business/`、`docs/services/`、`docs/im-services/`、`docs/deployment/`；实现文档需**跟随代码所在仓库**（如 IM 客户端架构文档应在 `open-chat-*/docs/`） |
| ② 规范更新 → **工程规范文档** | 描述应当如何 | `open-im-server/docs/standards/*` + `docs/ENGINEERING_RULES.md` |
| ③ 其余 → **标历史文档** | 过时方案稿/盘点/快照 | 留原处 + 统一标注 |

⚠️ **跨仓库课题**：`open-im-server/docs/` 里现在也放着**前端仓库的文档**（如 `CLIENT_INTEGRATION_01_APP.md` 讲 App）。三分法需**同时决定"文档是否该跟着它描述的仓库走"**——这是本轮新发现的治理问题，**需你定口径**。

---

## 六、⚠️ 阻塞项：工作区事实清单应放哪

我在 v2 里把事实清单放进了 `open-im-server/docs/business/`，但**它现在必须覆盖 10 个仓库**，放在服务端仓库里不合适。三个选项：

| 选项 | 说明 |
| --- | --- |
| **① `.github` 仓库** | 组织级，天然跨仓库（该仓库已存在且是 git 仓库） |
| ② `open-im-server/docs/` 保留 | 但需明确"这是工作区级文档，非本仓专属" |
| ③ 新建工作区级文档仓库 | 最规范但需新建仓库 |

**建议 ①**（`.github` 是组织级配置仓库，正合"跨仓库规范与事实清单"之用）。**请你在 E5 定。**

---

## 七、执行清单（v3，工作区级）

| 步 | 动作 | 范围 | 状态 |
| --- | --- | --- | --- |
| 1 | `ENGINEERING_RULES.md`：登记表增列 + 命名条文 + 新增 `open`/`idaas`/`cons` + 库名对齐 | `open-im-server` | 待执行 |
| 2 | 同步 `table-prefixes.txt`（两处同改） | `open-im-server` | 待执行 |
| 3 | **A** `iam_audit_*` → `audit_*`（含索引名、两迁移根、文件名） | `open-im-server` | 已批准 |
| 4 | **C** `saa_admin_account` → `cons_admin_account`（含 PO 类名） | `open-im-server` | 待 `cons` 确认 |
| 5 | **D2** `group_*` → `idaas_*` | `open-im-server` | 已批准 |
| 6 | **错位修正 + A1 库名统一**（IM/SaaS/审计/集团四库 + 全部连接配置 + k8s init job） | 全工作区 | 待 E2/E4 |
| 7 | **旧名批量改名**（`gv_*`/`wv_*`/`GV_*`/`WV_*` → `open-*`）**含前端生产代码** | **7 个前端仓库 + open-im-server** | 待 D5 确认 |
| 8 | 文档三分法 + `docs/` 目录收敛 + 绝对路径清理 | 全工作区 | 待 E5 |
| 9 | `.env` 桶名 `gv-im-local-private` → `open-im-local-private` | `open-im-server` | 待确认 |
| 10 | 门禁 **7 → 0** 并转阻断；每步跑受影响模块测试 | `open-im-server` | 待执行 |

---

## 八、决策已全部确认（执行口径）

| # | 决策 | **结论** |
| --- | --- | --- |
| **E1** | 控制台前缀 | ✅ **`cons`** |
| **E2** | 库名统一（A1） | ✅ **`open_im`（IM）+ `IM_DATABASE`／`open_saas`（SaaS）+ `SAAS_DATABASE`／`open_audit` + `AUDIT_DATABASE`** |
| **E3** | 客户端名映射 | ✅ `gv_chat_app`→`open-chat-app`、`gv_chat_desktop`→`open-chat-desktop`、`gv_chat_admin`→`open-chat-admin`、`gv_saas_admin`→`open-saas-admin`、`gv_saas_mobile`→`open-saas-mobile`、`gv_im_server`→`open-im-server`、`GV_*`→`OPEN_*`；**`WV Chat` → 直接写 `Open Chat`**（不再保留"WV"这一阶段关系） |
| **E4** | 集团库名 | ✅ **`open_group`** |
| **E5** | 事实清单归属 | ✅ **就在后端总仓 `open-im-server`**（即工程详细文档），**并且它就是"工程详细文档"本身**——不再另建位置 |
| **E6** | 前端文档位置 | ✅ **留在后端总仓 `open-im-server/docs/`**——后端总仓是**全局视角**，关联前端文档放这里**合适**；此结论**写入文档规范，不再重复询问** |
| **E7** | 运行期标识 | ✅ **分步骤做**（与文档/注释改名分开推进） |

### 由此确定的两条文档规范（写入规范文档）

1. **`open-im-server` 是后端总仓库，也是全工程的全局视角所在**；关联前端仓库（`open-chat-*`、`open-saas-*`、`open-website`）的文档**集中放在 `open-im-server/docs/`**，不迁往各自仓库。
2. **事实清单（`docs/business/PROJECT_FACTS_INVENTORY.md`）= 工程详细文档**，负责登记**全工程事实**（跨 10 个仓库），与规范（应当如何）、改造方案（如何达标）三分。
