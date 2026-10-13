# 迁移基线重建交接（批次 2）

> 方案集：`MIGRATION_BASELINE`；序号：`01`；前置：[01 数据重建方案](./MIGRATION_BASELINE_01_DATA.md)
> 状态：**第 0 步完成；第 2 步进行中——19 个迁移根已完成 6 个，worklist 415 → 378，SQL 文件 199 → 184**
> 目标读者：**新会话**（上一个会话上下文已接近上限，故在此交接）
> 交接时间：2026-10-13；分支 `develop/2.2.0-auth`

## 0. 30 秒现状

- **可以开工**：冻结门禁实测输出 `not frozen (no baseline tag with a freeze statement); baseline rebuild is allowed (DC-10)`，迁移重写路径畅通。
- **第 0 步已完成**：`validate-flyway-conventions.ps1` 已补上第 11 项"标识符必须加反引号"检查（`0e10d21`），并修掉三处会让门禁读错的缺陷（见 §3 坑 4 与 §2 步骤 0）。
- **已完成 6 个模块**（各自合并为单 V1，worklist 归零并已提交）：
  | 模块 | 提交 | 合并前→后 |
  | --- | --- | --- |
  | `platform-resource-service` | `903033b` | 8 文件 → 1 |
  | `platform-identity-service` | `b423d1b` | 5 文件 → 1 |
  | `common-payment-channel-service` | `f797263` | 2 文件 → 1 |
  | `platform-marketing-service` | `d295e0c` | 4 文件 → 1 |
  | `common-mail-service`（样板，补反引号） | `d6969fe` | 1 文件 |
  | `common-sms-service`（补审计列 + 索引名） | `aafc774` | 1 文件 |
- **剩余**：13 个迁移根、**378 条** worklist、184 个 SQL 文件。最大三块 `message 57`、`user 44`、`admin(IM) 44`。
- 与迁移无关的门禁自伤项本轮已闭合：新增 `validate-powershell-ascii-only.ps1`，门禁数 23 → **24**，`scripts/validate` 内 2 个非 ASCII 脚本已清零。详见 [03 整改方案](./ENGINEERING_GOVERNANCE_03_REMEDIATION.md) §19 与 N-24。

## 1. 开工前的 4 条硬约束（踩过，不要再踩）

| # | 约束 | 原因与正确做法 |
| --- | --- | --- |
| 1 | **不要用 `git reset` 重置索引来"分组提交"** | 上一轮因此把 `mvnw` 的 `100755` 一起回退；Windows 上 `git add` **无法**从文件系统恢复执行位 → commit 声称"补了 100755"、实际是 `100644`，Linux CI 上 `./mvnw` 直接 Permission denied。分组提交请用 `git add <path>` / `git commit -- <path>`，动过索引后必须 `git ls-files -s mvnw` 复核为 `100755` |
| 2 | **冻结语义是结构化的**：tag 名以 `baseline-` 开头，或注释含独立行 `freeze: true` | 不要写"本 tag 不承担冻结语义"这类**松散词**描述。旧版门禁对注释做 `frozen` 词匹配，一句声明"自己不是冻结点"的话反而把它判成冻结点，错误阻断了批次 2。打 tag 用 `git tag -a`，命名 `baseline-*` |
| 3 | **全仓 `.ps1` 都是无 BOM 文件，且 Windows CI 用 `powershell.exe` 执行** | 脚本内任何非 ASCII（中文注释、正则、文案）在 PS 5.1 下按 ANSI 解析，会被静默损坏。校验脚本一律英文注释。已延期到批次 4 的 6 个文件见 `scripts/validate/powershell-ascii-allowlist.txt`，**不要往该清单新增条目**（陈旧条目本身会让门禁失败） |
| 4 | **每次提交/每轮只做一个模块** | 378 条的上下文开销是本次交接的直接原因。按 §2 步骤 1 的模块清单逐块推进，一块一轮，做完即验证并提交 |
| 5 | **合并基线必然删除 V2..Vn，且不接受折中** | 删除后旧库无法平滑升级（Flyway 认为 V2..Vn 从未执行、校验和不一致）。这是 PR-01/PR-03 明确接受的代价（未上线、无数据），由第 4 步"清库重建"兜底。不要为兼容旧库保留 V2..Vn，也不要写补偿语句 |
| 6 | **一份 worklist 文件是并发写目标** | `flyway-conventions-worklist.txt` 每次跑门禁都被整体重写。若并行跑多个模块（多 agent），先把"跑门禁 → 统计本模块条数"放在同一条命令里，并接受偶发的互相覆盖；判据是"某次门禁运行中本模块贡献 0 条"，不是文件某一刻的样子 |

## 2. 执行顺序

### 步骤 0（已完成）：给 `flyway-conventions` 门禁补"反引号"检查

已完成于 `0e10d21`。修复内容与**为什么必须修**（两处静默失效）：

1. 门禁原本**完全没有**反引号检查，且审计列检查把反引号写成可选 → 违反 `docs/DATABASE_MIGRATION_STANDARD.md:8`（"标识符使用反引号"）的表**静默通过**。现新增第 11 项：表名与列名必须加反引号，每张表最多报 2 条（表名 + 首个违规列），保证 worklist 可读。
2. 索引正则原本**硬编码了反引号** → 未加反引号的索引**完全不匹配**，索引命名规则静默失效。改为反引号可选后，原本被漏掉的索引问题暴露出来（索引命名类 70 → 81 条）。
3. 新增的裸标识符分支必须排除"列定义"：`name VARCHAR(255)` 里的 `VARCHAR` 会被误读成索引名。已加"裸标识符后不得紧跟 `(`"约束修掉（净减 5 条误报）。
4. **不要用 .NET 条件组** `(?(name)yes|no)`：它解析的是 **balancing group**、不是命名组，因此永远不匹配、会静默关掉检查。改用普通交替 `(?:`a`|b)`。

**当前 worklist = 378 条**，分类：审计列 192、索引命名 73、id 类型 37、PK 非 id 19、前缀 15、其他 15、反引号 14、注释 7、datetime 6。

> ⚠️ **前缀类 15 条已决定暂不处理**（用户决定，待后续单独定夺）：`channel_`、`secret_chat`、`saa_`、`support_`、`open_`、`group_`、`idaas_`。它们指向同一类问题——**表用了别的域的前缀**（例如 `channel`/`secret_chat` 属会话域却未用 `conversation_`；`saa_admin_account` 属平台后台却未用 `adm_`）。**不要**简单地把这些前缀登记进登记表了事（那会让同一域出现两个前缀），也不要擅自改表名。已核实的事实：`iam_` 域的表分散在 `im-admin-service`（`adm_*` 16 张）与 `platform-admin-service`（`iam_menu`、`saa_admin_account`），`common-audit-service` 用 `iam_audit_*`，`paych_`/`audit_` 在 `table-prefixes.txt` 里已登记但**无任何表使用**。

### 步骤 1：按模块重写基线（worklist 驱动，378 条）

输入清单：`.outputs/quality/flyway-conventions-worklist.txt`（**415 行**，格式 `<文件路径>:<表名>: <问题>`，其中 2 行属于 `docs/ENGINEERING_RULES.md` 前缀登记问题）。

**本步的真正形态：把 199 个 SQL 文件合并为 19 个 V1 基线**（已完成 6 个模块，现存 184 个文件）。逐模块做法：

1. 先跑门禁看该模块的条目；
2. 把该模块 V1..Vn **全部读一遍**（用显式 UTF-8 读，否则 PS 5.1 会显示乱码），合并成**唯一的 `V1__<domain>_baseline.sql`**：
   - 表结构取**累计最终态**（后版本加的列直接写进 V1 的建表语句，不再用 `ALTER TABLE`）；
   - 播种与数据订正语句保留在 V1 末尾，并保持幂等；
3. **删除 V2..Vn**；
4. 重跑门禁，确认该模块条目归零。

> ⚠️ **删除 V2..Vn 会使已存在的数据库无法再平滑升级**（Flyway 会认为 V2..Vn 从未执行、校验和不一致）。这是**本方案明确接受的代价**：PR-01/PR-03 已确认项目未上线、无生产数据，第 4 步"清库重建"就是配套动作。不要为了兼容旧库而保留 V2..Vn，也不要新增补偿语句。

**逐模块清单（按条目数降序；已完成 6 个，剩余 13 个）**：条目数会随进度变化，权威值请读 `.outputs/quality/flyway-conventions-worklist.txt`。

| 状态 | 模块（迁移根） | 条目 | 迁移文件数 | 备注 |
| --- | --- | --- | --- | --- |
| ⬜ | `im-services/message/im-message-service` | 57 | 13 | 最大块 |
| ⬜ | `im-services/user/im-user-service` | 44 | 24 | 含 `open_*`（前缀待定） |
| ⬜ | `im-services/admin/im-admin-service` | 44 | 12 | `adm_*` 16 张表 |
| ⬜ | `common-services/media/common-media-service` | 39 | 1 | 前缀 `support_media_*`→`media_*`（DC-04）；含 10 条反引号 |
| ⬜ | `im-services/conversation/im-conversation-service` | 39 | 16 | 含 `channel_*`/`secret_chat`（前缀待定） |
| ⬜ | `platform-services/order/platform-order-service` | 38 | 32 | |
| ⬜ | `common-services/payment/common-payment-service` | 23 | 15 | |
| ⬜ | `common-services/audit/common-audit-service`（`migration-audit`） | 22 | 3 | **不得与 `migration` 合并**（见下方警示）；新库 `open_audit` 基线 |
| ⬜ | `platform-services/tenant/platform-tenant-service` | 18 | 32 | 含 `iam_approval`（前缀待定） |
| ⬜ | `platform-services/customer/platform-customer-service` | 17 | 13 | |
| ⬜ | `platform-services/admin/platform-admin-service` | 15 | 11 | `iam_menu`（前缀待定）、`saa_admin_account`（前缀待定） |
| ⬜ | `group-services/idaas/group-idaas-service` | 11 | 2 | 集团业务领域登记（DC-02）；`group_*`/`idaas_*` 前缀待定 |
| ⬜ | `common-services/audit/common-audit-service`（`migration`） | 11 | 4 | **不得与 `migration-audit` 合并**；老库 `open_im` 历史目录 |
| ✅ | `common-services/payment-channel/common-payment-channel-service` | 0 | 1 | `f797263` |
| ✅ | `platform-services/marketing/platform-marketing-service` | 0 | 1 | `d295e0c`；另修了 `MktEventConsumedPo` 缺审计字段的运行期缺陷 |
| ✅ | `common-services/mail/common-mail-service` | 0 | 1 | `d6969fe`（样板补反引号） |
| ✅ | `platform-services/identity/platform-identity-service` | 0 | 1 | `b423d1b` |
| ✅ | `platform-services/resource/platform-resource-service` | 0 | 1 | `903033b` |
| ✅ | `common-services/sms/common-sms-service` | 0 | 1 | `aafc774` |

**分类口径（378 条）**：审计列 192、索引命名 73、id 类型 37、PK 非 id 19、前缀 15、其他 15、反引号 14、注释 7、datetime 6。

**history 表名统一**（`<service>_schema_history`）：`im-services/admin`（`admin_schema_history`）、`user`（`user_schema_history`）、`message`（`msg_schema_history`）、`conversation`（`conversation_schema_history`）四个 IM 服务仍用旧口径；`media` 的 `support_schema_history` 需改为 `media_schema_history`。改 `application.yml` 的 `spring.flyway.table` 即可，与清库重建配套。

**每模块的重写配方**（已由邮件模块钉死，照抄即可）：

- 每服务合并为**单根** `V1__<domain>_baseline.sql`，后续脚本从 `V2` 起。
- 必备要素：
  - 4 个审计字段齐全 `created_by` / `updated_by` / `created_at` / `updated_at`（缺 `created_by`/`updated_by` 是 241 条的主要来源）。
  - 主键 `id bigint unsigned NOT NULL AUTO_INCREMENT`（44 条）；**业务键/复合键作主键的 19 条**需改为 `id` 主键 + 唯一索引。
  - `ENGINE=InnoDB` + `DEFAULT CHARSET=utf8mb4` + `COLLATE=utf8mb4_0900_ai_ci`。
  - 时间列 `datetime(3)`（不是 `datetime(6)`）。
  - 索引名 `idx_<完整表名>_<语义>` / `uk_<完整表名>_<语义>`（**完整表名**，不要用缩写表名——样板里 `uk_mail_channel_tenant_host` → `uk_mail_channel_config_tenant_host` 就是这条）。
  - 表与列 `COMMENT` 齐全（表注释用 `COMMENT=`）。
  - 禁止：`CREATE TABLE IF NOT EXISTS`、外键、`DROP TABLE`、动态 DDL（`PREPARE`/`EXECUTE`）。
  - **反引号**（步骤 0 完成后为强制）。
- 每模块完成后立即跑：
  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validate/validate-flyway-conventions.ps1 -ReportOnly
  ```
  然后检查 worklist 中该模块的条目是否**归零**（不要凭印象，读文件）。归零即提交该模块。

**同时一并处理（不在该 worklist 内）**：

- 前缀改登记/改名：`support_media_*` → `media_*`（DC-04）；`mail_*` / `sms_` / `open_*` / `iam_menu` / `saa_admin_account` → `adm_` / `tenant_payment_method` → `tnt_`（后四项属挂起的前缀决策，见上文警示）。
- 集团业务领域登记（DC-02）：`group_*` / `idaas_*` 表与目录/文档一并登记（前缀待定）。
- `tenant-sql` worklist **41 条**（`.outputs/quality/tenant-sql-worklist.txt`）：平台级扫表补 `platform-wide` / `tenant-agnostic` 显式标注，其余补租户条件。
- `secret-exposure` **4 处**（`.outputs/quality/secret-exposure-report.txt`）：迁移注释中的口令与应用密钥哈希随基线重建清除。
- 删除"先建后删"迁移与孤儿 `deploy/database/init.sql`。
- ~~合并 `common-audit-service` 的双迁移根为单根~~ **（已核实为错误前提，废弃）**；~~history 表名统一 `<service>_schema_history`~~ **（无规则依据，本轮不做）**——两条详见 §3.1.1。

### 步骤 2：登记表与规范同步

- `docs/ENGINEERING_RULES.md` 前缀登记表补 `mail_` / `sms_` / `open_` / 集团域 / `media_`（worklist 开头 2 条就是这里）。
- 同步 [01 方案](./MIGRATION_BASELINE_01_DATA.md) §3 的前缀登记结果。

### 步骤 3：三个门禁转阻断（**只在 worklist 归零后**）

对每个门禁：`-ReportOnly` 跑 → worklist 归零 → 删除 `scripts/validate/report-only-gates.txt` 中对应行 → 再跑一次统一入口确认阻断模式通过。

⚠️ **不要顺手删 `mq-registry`**：它在 `report-only-gates.txt` 里登记的是"批次 2（台账补登记）**+** 批次 1（常量集中）"，且当前仍有 **44** 条（34 未登记 + 10 配置字面量）。只有两部分都完成才可移除该行。

### 步骤 4：清库重建（PR-03）

销毁 Kind 命名空间与 Compose 数据卷（**注意 N-23：本地 mysql/minio 用 `emptyDir`、mongodb 无卷，重建即清空数据，这是预期行为**）→ 全新 Flyway 迁移 → 全服务启动（31/31 deployment）。

### 步骤 5：回归

`scripts/verify/*` 全量 + 关键链路（登录 / 会话 / 消息 / 媒体 / KTV 预约）+ 统一入口 `-Scope Full` 退出码 0 + `.outputs/quality/migration-inventory-before.txt` 的 199 条可逐条复核（tag 可解析）。

### 步骤 6：冻结 tag 收尾（DC-15 / DC-10）

删除 `v2.0.0`（本地 + 远端）→ 打 `v2.2.0` 注释 tag，**名字用 `baseline-` 前缀**（或注释含独立行 `freeze: true`）。

## 3. 已实测确认的事实（可直接引用，不必重测）

| 项 | 实测结果 |
| --- | --- |
| 冻结门禁 | `validate-flyway-immutability.ps1` 输出 `not frozen ... baseline rebuild is allowed (DC-10)`，**exit 0** |
| 现有 tag | `pre-migration-rewrite-20261013`、`pre-baseline-consolidation-20261013`、`v2.0.0`——**都不含 `baseline-` 前缀、也不含 `freeze: true`**，故当前不冻结 |
| `mvnw` 执行位 | `git ls-files -s mvnw` → `100755` ✅（上一轮修好了，别再动索引） |
| 门禁总数 | **24**（`invoke-engineering-validation.ps1` 的 `$validators`） |
| 报告模式门禁 | 8 个：`flyway-conventions` **378**、`java-fully-qualified-names` 192、`service-api-boundaries` 137、`standards-governance` 107、`java-silent-catch` 103、`mq-registry` 44、`tenant-sql` 41、`secret-exposure` 4 |
| ASCII 门禁 | 新增 `validate-powershell-ascii-only.ps1`（阻断模式，扫描 `scripts/**/*.ps1`）；2 个在册违规已清零；6 个既存延期至批次 4 |
| 迁移文件总量 | 19 个迁移根 / **184** 个 SQL 文件（合并前为 199，已随 6 个模块减少） |
| 已完成的模块 | 6 个：`resource`、`identity`、`payment-channel`、`marketing`、`mail`、`sms`（各自单 V1，worklist 归零，已提交） |
| 工作区 | 干净；`.outputs/` 被 `.gitignore:13` 忽略，worklist 不会误提交 |

### 3.1 反引号现状分布（决定哪些模块只是机械补反引号）

当前 **14 条**反引号条目分布在 3 个模块（`common-mail-service` 已在 `d6969fe` 清零）：

| 模块 | 反引号条目 | 说明 |
| --- | --- | --- |
| `common-services/media/common-media-service` | 10 | 表名 + 列名（`support_media_object` 等，同时要改前缀） |
| `platform-services/admin/platform-admin-service` | 2 | 表名 + 列名（`iam_menu`） |
| `platform-services/tenant/platform-tenant-service` | 2 | 表名 + 列名（`iam_approval`） |

已完成的模块（`resource`、`identity`、`payment-channel`、`marketing`）**表名与列名本来就已经加了反引号**，它们的条目来自审计列/索引名等问题——所以这类模块的重写主要是"合并 + 补审计列 + 改索引名"，不是补反引号。

## 3.1.1 ⚠️ 两处"看似该合并、实际不能合并"的例外（已核实，务必先读）

**① `common-audit-service` 的两个迁移根绝对不能合并。**

- `src/main/resources/db/migration`（V1~V4）是**老库 `open_im`** 的历史目录；`db/migration-audit`（V1~V3）是**新库 `open_audit`** 的基线目录。
- 由 profile `audit-schema` 原子切换：`application-audit-schema.yml` 同时切库名和 `spring.flyway.locations`。两者共用历史表 `flyway_schema_history_audit`，因此**目录与库必须同进同退**。
- 两边都 `CREATE TABLE iam_audit_log` / `iam_audit_idempotency`，**物理设计不同**（老库普通表+唯一键幂等；新库 `occurred_at` 月分区主表+幂等台账），是**同名不同库**，不是重复。
- 已有测试固化该约束：`AuditPartitionInvariantTest`（"两个迁移目录必须物理隔离"）；`application.yml:15` 原注释亦写明"两个目录**不得混用**"。
- 结论：**分别在各自根内做规范化，保持两个根**。`[01 方案](./MIGRATION_BASELINE_01_DATA.md)` §3 里"合并双迁移根为单根"的写法是**错误前提**，应废弃（DC-17 只要求保留分区，并未要求合根）。

**② history 表名"统一"不具备规则依据，本轮不做。**

- 现状：多数服务用 `flyway_schema_history_<service>`；IM 四服务与 media 用 `<service>_schema_history`（`admin_`/`conversation_`/`msg_`/`user_`/`support_schema_history`）。
- 已核对 `ENGINEERING_RULES.md`、`DATABASE_MIGRATION_STANDARD.md`、`standards/21` —— **都没有规定 history 表命名**，也没有任何门禁检查它。`[01 方案](./MIGRATION_BASELINE_01_DATA.md)` §3 的"统一为 `<service>_schema_history`"是计划意图而非规范要求。
- 结论：**不改**（改名需同步 config、无门禁收益、纯churn）。若将来确有统一诉求，应先写成规范＋门禁，再一次性改。

### 3.2 提交记录（本轮）

```
aafc774 fix(migration): add missing audit columns and full-table index names to sms baseline
d6969fe refactor(migration): add backticked identifiers to mail template baseline
d295e0c refactor(migration): consolidate marketing baseline into one V1
f797263 refactor(migration): consolidate payment-channel baseline into one V1
b423d1b refactor(migration): consolidate identity-service baseline into one V1
903033b refactor(migration): consolidate resource-service baseline into one V1
7aa7a68 fix(validation): stop flyway gate reading comments as DDL
```

## 4. 本会话遗留（下次顺手清理）

1. **旧迁移文件名失效引用（非阻塞但迟早要修）**：合并后 `docs/currency-scope-inventory-2026-09-18.md`（7 处，引用 `V6__res_room_type_and_area.sql` 等）、`docs/renovation/MULTI_TIMEZONE_DESIGN.md:882`（1 处）已指向不存在的迁移文件与行号，需改指合并后的 `V1__*_baseline.sql`；`RoomTypePo.java:14`、`PaymentChannelTransactionRecorder.java:50`、`CouponApplicationService.java:29` 的 javadoc 也引用了旧迁移文件名或已改名的索引名。**每个模块合并后都会新增同类失效引用**，建议在批次 2 收尾时统一扫一遍（判据：仓库内不得出现已删除迁移文件名）。
2. **前缀类 15 条**已按用户决定挂起（见 §2 步骤 1 表后的警示），不在本轮处理。
3. `MktEventConsumedPo` 的运行期缺陷已在本轮修掉（`d295e0c`）——这是"补审计列"引发的连带改动，**后续模块若给 PO 未覆盖的列补 `NOT NULL` 审计字段，必须同样检查 Java 侧是否写入**，否则插入会报 MySQL 1364。
4. `standards/30` 已新增第 4 条门禁说明；批次 4 清理 6 个延期的非 ASCII 脚本前，需先定"脚本输出语言"策略。
5. `.outputs/quality/powershell-ascii-only-worklist.txt` 是 ASCII 门禁自检留下的旧快照（gitignored，无影响），可随手删。

## 5. 关键文件索引

| 用途 | 路径 |
| --- | --- |
| 数据重建方案（步骤、验收、回滚） | [MIGRATION_BASELINE_01_DATA.md](./MIGRATION_BASELINE_01_DATA.md) |
| 整改方案（批次、决策、门禁清单） | [ENGINEERING_GOVERNANCE_03_REMEDIATION.md](./ENGINEERING_GOVERNANCE_03_REMEDIATION.md) |
| 审查报告（问题出处） | [ENGINEERING_GOVERNANCE_02_AUDIT.md](./ENGINEERING_GOVERNANCE_02_AUDIT.md) |
| 主 worklist（378 条，权威口径） | `.outputs/quality/flyway-conventions-worklist.txt` |
| 租户 SQL worklist（41 条） | `.outputs/quality/tenant-sql-worklist.txt` |
| 密钥暴露报告（4 条） | `.outputs/quality/secret-exposure-report.txt` |
| 基线样板（照抄对象） | `common-services/mail/common-mail-service/src/main/resources/db/migration/V1__mail_baseline.sql` |
| 统一门禁入口 | `scripts/validate/invoke-engineering-validation.ps1` |
| 报告模式登记 | `scripts/validate/report-only-gates.txt` |
| ASCII 延期清单 | `scripts/validate/powershell-ascii-allowlist.txt` |
| 规范原文 | `docs/standards/30_TEXT_ENCODING_AND_BATCH_EDITING.md`、`docs/DATABASE_MIGRATION_STANDARD.md` |
