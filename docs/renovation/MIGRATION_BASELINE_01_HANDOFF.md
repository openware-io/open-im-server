# 迁移基线重建交接（批次 2）

> 方案集：`MIGRATION_BASELINE`；序号：`01`；前置：[01 数据重建方案](./MIGRATION_BASELINE_01_DATA.md)
> 状态：**第 2 步（19 个迁移根合并）已全部完成并提交**；worklist 415 → **7**，且这 7 条全是已挂起的前缀决策
> 目标读者：**新会话**（上一个会话上下文已接近上限，故在此交接）
> 交接时间：2026-10-13；分支 `develop/2.2.0-auth`

## 0. 30 秒现状

- **19/19 迁移根已合并为单 V1 基线**，全部已提交，工作区干净。
- **✅ 清库重建的迁移链已在真实 MySQL 8.0.46 上验证通过**（不只是静态检查）：19 个根的全部基线按序应用到全新库，**0 失败**，产出 `open_im` **152 张表** + `open_audit` **3 张表**；跨服务**无表名冲突**（唯一同名对是已登记的 `iam_audit_log` / `iam_audit_idempotency`——两个库各一份、物理设计不同，正是 §3.1.1 记录的审计双根设计）；`iam_audit_log` 的 **30 个月分区**在合并后完好。**这证明"删掉 V2..Vn 后从 V1 重建"这条路真的能跑通。**
- **批次 2 的四个门禁里已有三个清零并转阻断**（`tenant-sql` 43→0、`secret-exposure` 4→0、`mq-registry` 44→0）；**报告模式门禁从 8 个降到 5 个**，其中批次 2 只剩 `flyway-conventions`：

  | 门禁 | 剩余 | 卡在哪 |
  | --- | --- | --- |
  | `flyway-conventions` | **7** | **唯一剩下的批次 2 门禁**，7 条全是**前缀决策**：`open_*`×3（user）、`saa_`×1（platform-admin）、`group_*`/`idaas_*`×3（idaas）。**你已明确挂起**（"我自己后续定"），所以这条不作弊就清不掉 |
  | `tenant-sql` | **0** ✅ | 已转**阻断**（`8f87209`） |
  | `secret-exposure` | **0** ✅ | 已转**阻断**（`84879c6`）。改写证据行、门禁零改动；顺带修掉门禁漏报的一处真泄漏 |
  | `mq-registry` | **0** ✅ | 已转**阻断**（`05fc409`）。34 条台账 + 3 个生产者组常量；门禁改为**语义判定**后暴露并修掉 3 处真问题 |
  | `standards-governance` / `java-silent-catch` / `java-fully-qualified-names` / `service-api-boundaries` | 107 / 103 / 192 / 137 | 属**批次 3/4**，不在批次 2 范围 |

- **⚠️ 门禁工作流自身的一个坑（本轮已修 4 个脚本）**：`validate-*.ps1` 的**成功分支原先不删除自己的 worklist/report 文件**，所以**门禁通过后仍会留下一个写着旧违规的清单**——本轮多次据此误判"还有 N 条"，实际早已清零。已给 `tenant-sql`、`secret-exposure`、`flyway-conventions`、`mq-registry` 四个脚本加上"成功即删除输出"。**已核对其他门禁**：仍留文件的都是**真的失败**的门禁（`java-silent-catch` 等），属正确行为。**判据改为看门禁 exit code 与实时运行输出，不要把 `.outputs/quality/*-worklist.txt` 的存在当真理。**

- **可以开工**：冻结门禁实测 `not frozen ... baseline rebuild is allowed (DC-10)`，清库重建路径畅通。
- **19/19 迁移根已合并为单 V1 基线并提交**（每根 worklist 归零；下表括号内为"合并前文件数 → 现文件数"）：

  | 模块 | 提交 | 合并前→后 |
  | --- | --- | --- |
  | `platform-resource-service` | `903033b` | 8 文件 → 1 |
  | `platform-identity-service` | `b423d1b` | 5 文件 → 1 |
  | `common-payment-channel-service` | `f797263` | 2 文件 → 1 |
  | `platform-marketing-service` | `d295e0c` | 4 文件 → 1 |
  | `common-mail-service`（样板，补反引号） | `d6969fe` | 1 文件 |
  | `common-sms-service` | `aafc774` | 1 文件 |
  | `common-media-service`（`support_`→`media_` + `id` 主键 + Java 侧） | `b7377be` | 1 文件 + 5 PO + 6 服务类 |
  | `platform-admin-service`（**未改表名**） | `e4114c4` | 11 文件 → 1 |
  | `platform-customer-service` | `d62ba0d` | 13 文件 → 1 |
  | `common-audit-service`（两个根**分别**处理，不合并） | `a1c892a`+`a1de6c1` | 4→2 与 3 文件 |
  | `platform-tenant-service` | `4a3da4a` | 32 文件 → 1 |
  | `im-message-service` | `0bd3953` | 13 文件 → 1（15 表 / 38 索引） |
  | `im-conversation-service` | `e228da3` | 16 文件 → 1（9 表） |
  | `platform-order-service` | `0b252c3` | **32 文件 → 1**（17 表 / 63 索引） |
  | `im-user-service` | `67e4901` | 24 文件 → 1（23 表） |
  | `im-admin-service` | `7fb4736` | 12 文件 → 1（15 表） |
  | `common-payment-service`（含 `tenant_payment_method`→`tnt_` 跨服务改名） | `e2be171` | 15 文件 → 1（12 表） |
  | `group-idaas-service`（**未改表名**） | `e2be171` | 2 文件 → 1（3 表） |

- **与迁移无关的门禁自伤项已闭合**：新增 `validate-powershell-ascii-only.ps1`，门禁数 23 → **24**，`scripts/validate` 内非 ASCII 脚本清零。详见 [03 整改方案](./ENGINEERING_GOVERNANCE_03_REMEDIATION.md) §19 与 N-24。

## 1. 开工前的 4 条硬约束（踩过，不要再踩）

| # | 约束 | 原因与正确做法 |
| --- | --- | --- |
| 1 | **不要用 `git reset` 重置索引来"分组提交"** | 上一轮因此把 `mvnw` 的 `100755` 一起回退；Windows 上 `git add` **无法**从文件系统恢复执行位 → commit 声称"补了 100755"、实际是 `100644`，Linux CI 上 `./mvnw` 直接 Permission denied。分组提交请用 `git add <path>` / `git commit -- <path>`，动过索引后必须 `git ls-files -s mvnw` 复核为 `100755` |
| 2 | **冻结语义是结构化的**：tag 名以 `baseline-` 开头，或注释含独立行 `freeze: true` | 不要写"本 tag 不承担冻结语义"这类**松散词**描述。旧版门禁对注释做 `frozen` 词匹配，一句声明"自己不是冻结点"的话反而把它判成冻结点，错误阻断了批次 2。打 tag 用 `git tag -a`，命名 `baseline-*` |
| 3 | **全仓 `.ps1` 都是无 BOM 文件，且 Windows CI 用 `powershell.exe` 执行** | 脚本内任何非 ASCII（中文注释、正则、文案）在 PS 5.1 下按 ANSI 解析，会被静默损坏。校验脚本一律英文注释。已延期到批次 4 的 6 个文件见 `scripts/validate/powershell-ascii-allowlist.txt`，**不要往该清单新增条目**（陈旧条目本身会让门禁失败） |
| 4 | **每个模块一轮，做完即验证并提交** | 早期 378 条的上下文开销是本次交接的直接原因。按模块清单逐块推进 |
| 5 | **合并基线必然删除 V2..Vn，且不接受折中** | 删除后旧库无法平滑升级（Flyway 认为 V2..Vn 从未执行、校验和不一致）。这是 PR-01/PR-03 明确接受的代价（未上线、无数据），由第 4 步"清库重建"兜底。不要为兼容旧库保留 V2..Vn，也不要写补偿语句 |
| 6 | **一份 worklist 文件是并发写目标** | `flyway-conventions-worklist.txt` 每次跑门禁都被整体重写。若并行跑多个模块（多 agent），先把"跑门禁 → 统计本模块条数"放在同一条命令里，并接受偶发的互相覆盖；判据是"某次门禁运行中本模块贡献 0 条"，不是文件某一刻的样子 |
| 7 | **子 agent 必须显式给出工作目录** | `validate-repository-hygiene.ps1` 的 G-15 会扫描**工作区根**（`D:\projects\cnb-oss`）的每一个条目，只允许 11 个仓库目录 + `.github`。某个子 agent 用了错误的 CWD，在 `D:\projects\cnb-oss\.outputs\` 写下验证产物，**直接让这条阻断门禁失败**（此后每次 `-Scope Changed` 都在 repository-hygiene 处报错）。给子 agent 的 prompt 里要写明绝对工作目录，收尾时检查工作区根 |
| 8 | **不要用 shell 循环做全仓引用扫描** | 两次自研 PowerShell 扫描都报了"0 处失效引用"，而 `grep` 工具实际找到 11 处。判据要选一个**已知存在**的引用做交叉验证，不要相信 0；全仓扫描用 grep 工具 |
| 9 | **门禁报错时先问"这条语句/这个值是否本来就不该由它承担"** | 本轮两次因此避免了错误修改：`tenant-sql` 的 11 条误报（谓词由拦截器运行时注入，源码里本就没有）、`mq-registry` 的 10 条生产者组（值已经在 `sdk/protocol-mq` 里定义，YAML 里的是 `${ENV:默认值}` 的**默认值**）。**直接加标记/删默认值都会把"正确的写法"改坏。** |

## 2. 执行顺序

### 步骤 0（已完成）：给 `flyway-conventions` 门禁补"反引号"检查

已完成于 `0e10d21`。修复内容与**为什么必须修**（两处静默失效）：

1. 门禁原本**完全没有**反引号检查，且审计列检查把反引号写成可选 → 违反 `docs/DATABASE_MIGRATION_STANDARD.md:8`（"标识符使用反引号"）的表**静默通过**。现新增第 11 项：表名与列名必须加反引号，每张表最多报 2 条（表名 + 首个违规列），保证 worklist 可读。
2. 索引正则原本**硬编码了反引号** → 未加反引号的索引**完全不匹配**，索引命名规则静默失效。改为反引号可选后，原本被漏掉的索引问题暴露出来（索引命名类 70 → 81 条）。
3. 新增的裸标识符分支必须排除"列定义"：`name VARCHAR(255)` 里的 `VARCHAR` 会被误读成索引名。已加"裸标识符后不得紧跟 `(`"约束修掉（净减 5 条误报）。
4. **不要用 .NET 条件组** `(?(name)yes|no)`：它解析的是 **balancing group**、不是命名组，因此永远不匹配、会静默关掉检查。改用普通交替 `(?:`a`|b)`。

**当前 worklist = 378 条**，分类：审计列 192、索引命名 73、id 类型 37、PK 非 id 19、前缀 15、其他 15、反引号 14、注释 7、datetime 6。

> ⚠️ **前缀类条目已决定暂不处理**（用户决定，待后续单独定夺）：`channel_`、`secret_chat`、`saa_`、`support_`、`open_`、`group_`、`idaas_`。它们指向同一类问题——**表用了别的域的前缀**。

> ✅ **但先查一遍，有的已经不是问题了**：`conversation` 模块的后继版本 `V6__fix_channel_secret_chat_schema_conventions.sql` **早就把** `channel`→`conversation_channel`、`channel_subscription`→`conversation_channel_subscription`、`secret_chat`→`conversation_secret_chat` 重命名了，Java PO 与审计报告都按新名。worklist 里那几条 `channel`/`secret` 前缀只是**旧版本文件**贡献的——把 RENAME 折进基线后自然消失，**不需要重命名、不需要登记**。`support_media_*`→`media_*`（media 模块）同理且已完成。**判据：先看该模块最新版本里是否已经 RENAME 过，再看 worklist 行来自哪个版本文件。**

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

**逐模块清单（全部为 ✅；下面的表保留每根的条目数与文件数作为历史记录）**：权威口径请读 `.outputs/quality/flyway-conventions-worklist.txt`。

| 状态 | 模块（迁移根） | 条目 | 迁移文件数 | 备注 |
| --- | --- | --- | --- | --- |
| ⬜ | `im-services/message/im-message-service` | 57 | 13 | 最大块 |
| ⬜ | `im-services/user/im-user-service` | 44 | 24 | 含 `open_*`（前缀待定） |
| ⬜ | `im-services/admin/im-admin-service` | 44 | 12 | `adm_*` 16 张表 |
| ⬜ | `im-services/conversation/im-conversation-service` | 39 | 16 | 含 `channel_*`/`secret_chat`（前缀待定） |
| ⬜ | `platform-services/order/platform-order-service` | 38 | 32 | |
| ⬜ | `common-services/payment/common-payment-service` | 23 | 15 | **`tenant_payment_method` 属本模块**：需改 `tnt_` 前缀，并同步 payment 与 admin 两处的 `@TableName`（见 §4 第 6 条） |
| ⬜ | `group-services/idaas/group-idaas-service` | 11 | 2 | 集团业务领域登记（DC-02）；`group_*`/`idaas_*` 前缀待定 |
| 🔸 | `platform-services/admin/platform-admin-service` | 1 | 1 | 仅剩 `saa` 前缀（**已决定挂起**） |
| ✅ | `platform-tenant-service` | 0 | 1 | `4a3da4a`（32 文件 → 1；执行验证发现 3 个静态审查漏掉的缺陷） |
| ✅ | `common-audit-service`：`migration` 根 | 0 | 2 | `a1c892a`（V1+V3；V3 因 `AuditOccurredAtMigrationTest` 钉住路径而保留） |
| ✅ | `common-audit-service`：`migration-audit` 根 | 0 | 3 | `a1c892a` + `a1de6c1`（**两根不合并**） |
| ✅ | `common-media-service` | 0 | 1 + 5 PO | `b7377be`（`support_`→`media_` + `id` 主键 + Java 侧改写，19 测试通过） |
| ✅ | `platform-customer-service` | 0 | 1 | `d62ba0d` |
| ✅ | `platform-admin-service` | 0 | 1 | `e4114c4`（**未改表名**） |
| ✅ | `common-sms-service` | 0 | 1 | `aafc774` |
| ✅ | `common-mail-service` | 0 | 1 | `d6969fe`（样板补反引号） |
| ✅ | `platform-marketing-service` | 0 | 1 | `d295e0c` |
| ✅ | `platform-identity-service` | 0 | 1 | `b423d1b` |
| ✅ | `platform-resource-service` | 0 | 1 | `903033b` |
| ✅ | `common-payment-channel-service` | 0 | 1 | `f797263` |

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
- `tenant-sql` worklist **43 → 18**（`.outputs/quality/tenant-sql-worklist.txt`）：本轮已清 `customer`(8)、`audit`(5)、`resource`(4)、`tenant`(8)；剩 `order`16、`payment`1、`admin`1。**两类修法要分清**：

  | 情形 | 正确做法 |
  | --- | --- |
  | 语句**确实带** `tenant_id`，但门禁按行判定、谓词落在**下一行**（`SELECT ... FROM t` 换行后 `WHERE tenant_id = ...`） | **把 `FROM` 与谓词放同一行**（reflow）。不要为了过门禁加 `tenant-agnostic`——那是谎报，也丢了"这行本来就带租户谓词"的信息 |
  | 语句**确实没有**租户维度（分区 DDL、按全局唯一主键/账号定位、平台级字典或台账、按时间窗口的保留清理、跨租户定时扫描） | 加门禁认识的 `platform-wide` / `tenant-agnostic` 行尾标记，**并在标记里写清属于哪一类理由** |

  另外：若表已在 `scripts/validate/tenant-platform-tables.txt` 登记为平台级（如 `tnt_business_type`、`tnt_app_user_authorization`），那它就是平台级表，标记用 `platform-wide` 而不是 `tenant-agnostic`。

  **order 模块的 18 条已全部处理完（worklist 归零）**，方法本身值得复用：

  - **3 条**是真正的跨租户扫描（`KtvSessionMapper:65,79`、`ReservationMapper:26`，均带 `@InterceptorIgnore`，javadoc 明写"跨租户一次扫出…每行自带 tenantId，逐行设上下文再写，绝不跨租户写"）→ 加 `platform-wide` 标记。
  - **5 条**其实**带** `tenant_id`，只是谓词落在续行（`DailySerialMapper`、`InventoryStockMapper`、`PayIntentMapper`）→ **reflow**，不加标记。
  - **11 条是门禁误报**，根因已修：`tenant-sql` 原先对**每一行**SQL 都要求租户谓词，但 `TenantLineInnerInterceptor` 会给**没有 `@InterceptorIgnore`** 的语句在运行时注入谓词——那个谓词在源码里根本不存在。门禁改为**只判定 opt-out（带 `@InterceptorIgnore`）的语句**后，这批误报消失（见 `8f87209`）。**教训：门禁报错先问"这条语句是否需要自己写谓词"，而不是直接加标记——加标记会把"租户内查询"错写成"与租户无关"。**
- `secret-exposure` **6 → 4 → 0**（`.outputs/quality/secret-exposure-report.txt`）：user/idaas 合并后两处迁移文件里的历史凭据消失（6→4），剩余 4 条全在 `docs/renovation/ENGINEERING_GOVERNANCE_02_AUDIT.md`（`:61`、`:70` 报 Inline secret，`:72`、`:74` 报 Secret config default），本轮清零。

  ✅ **已清零并转为阻断（`84879c6`）——门禁脚本一行未改（diff=0）**。做法是**改写证据行**，而不是放宽检测：

  1. **先发现了一个门禁没报的真泄漏**：审计报告 `:62` 曾把 `IM_OAUTH_APP_SECRET` 的**真实值**原样写在文档里，而门禁没报它——因为该键在 YAML 行内流式映射中、不在行首。已脱敏（该值现已不在仓库任何位置）。
  2. **剩余 4 条按"保留事实、不复现值"改写**：仍写清**哪个文件、哪一行、哪个变量名**曾有明文，但**不再复现值的字面量或前缀，也不再用 `value: …` / `${VAR:…}` 这种"凭据形状"的语法**——门禁正是被这种形状触发。审计事实一条没丢，重复的密钥没了。
  3. **为什么"照抄凭据形状 + 打码"过不去**：门禁只用一个 **ASCII 安全值表**判断"已脱敏"，而本仓惯用的 `«…»` 书名号是**非 ASCII**，永远匹配不上。上一轮两次尝试放宽该门禁（安全值表认 `«…»`；取值在说明文字前停下）**都因无法验证而回滚**——注入合成密钥后没能复现出独立违规条目，无法证明放宽后仍抓得到真密钥。**最终选择"不动门禁"这条路。**
  4. 新增文末「凭据泄漏值的脱敏约定」（`ENGINEERING_GOVERNANCE_02_AUDIT.md` §9）：明确"**只省略后缀 ≠ 脱敏**，前缀本身就是凭据的一部分"。
  5. **实测**：4 个泄漏前缀在文档中出现次数均 **0**；门禁**不带 `-ReportOnly` 也 exit 0**；`secret-exposure` 已从 `report-only-gates.txt` **删除**，统一入口运行时**不再打印 REPORT 横幅**（阻断已生效）。
- 删除"先建后删"迁移与孤儿 `deploy/database/init.sql`。
- ~~合并 `common-audit-service` 的双迁移根为单根~~ **（已核实为错误前提，废弃）**；~~history 表名统一 `<service>_schema_history`~~ **（无规则依据，本轮不做）**——两条详见 §3.1.1。

### 步骤 2：登记表与规范同步

- `docs/ENGINEERING_RULES.md` 前缀登记表补 `mail_` / `sms_` / `open_` / 集团域 / `media_`（worklist 开头 2 条就是这里）。
- 同步 [01 方案](./MIGRATION_BASELINE_01_DATA.md) §3 的前缀登记结果。

### 步骤 3：三个门禁转阻断（**只在 worklist 归零后**）

对每个门禁：`-ReportOnly` 跑 → worklist 归零 → 删除 `scripts/validate/report-only-gates.txt` 中对应行 → 再跑一次统一入口确认阻断模式通过。

⚠️ **不要顺手删 `mq-registry`**：它在 `report-only-gates.txt` 里登记的是"批次 2（台账补登记）**+** 批次 1（常量集中）"。台账部分 34 条已完成，**当前剩 10 条**（全是 `application.yml` 的 `${ENV:默认值}` 生产者组默认值）。两部分都完成才可移除该行。

### 步骤 4：清库重建（PR-03）

> **✅ 运行期已实测通过（本轮）——不需要重建集群就验证了"全新库 + 当前代码 + Flyway"这条链路。**
>
> 做法：起一个空 `mysql:8.0.46`（建 `open_im` / `im_server` / `open_audit` 三个库，**0 张表**），然后**用当前 HEAD 构建的 19 个服务 jar 逐个真启动**（jar 已确认只含合并后的 `V1__*_baseline.sql`），让 **Flyway 自己去建表**。
>
> **结果：19/19 服务全部成功，142 个迁移全部 `success=1`，0 失败**（`TODO`：各服务 history 表见下），服务本身也都启动起来了（`Started ...Application`）。典型日志：
> ```
> Schema history table `open_im`.`flyway_schema_history_sms` does not exist yet
> All configured schemas are empty; baseline operation skipped
> Migrating schema `open_im` to version "1 - sms baseline"
> Successfully applied 1 migration to schema `open_im`, now at version v1
> ```
> **关键确认**：空库上 Flyway 会**跳过 baseline 动作、直接执行 V1**——所以 `baseline-on-migrate` + `baseline-version=0` 在全新库上是安全且正确的。
>
> **最终 schema 与现网逐表一致**：新建库 152 张应用表、现网 150 张，**差值恰好只有 `open_im.iam_audit_log` / `iam_audit_idempotency` 两份冗余副本**——因为本轮没带 `audit-schema` profile，审计服务用老根在 `open_im` 里也建了一份；带该 profile 时它写 `open_audit`（现网即如此）。**没有"现网有、新库没有"的表。**
>
> ⚠️ **两个必须知道的事实**：
> 1. **多版本号是正常的**：各服务历史表里能看到 33（order/tenant）、24（user）等**多个版本**，因为**老库仍然可以从旧版本平滑升级**（V2..Vn 未删）；而**空库只执行 V1 一条**。所以"合并成单 V1"没有破坏老库升级路径。
> 2. **本轮实测的产物里表名是老名**（`support_media_object`、`tenant_payment_method`、`adm_app_release_retired_v1`），因为**生产迁移文件今天用的就是这些名字**；而**合并基线里写的是新名**（`media_*`、`tnt_payment_method`、`adm_app_release`）。两者在真实建库时**并不冲突**（改名只在基线文件里体现）。**这点仍需你确认是否有意为之**——见 §4 第 12 条。
>
> **⚠️ 本轮运行期实测的有效性有限，不要当作完整验收（下轮须用 `mvn package` 重做）：**
>
> 我事后才发现一个问题：**`target/classes` 里残留着合并前的迁移文件**（`mvn test` 不做 clean）。因此**合并前打过包的 jar 里同时含有"旧的全部 V2..Vn + 新的 V1 基线"**。例如 `common-payment-service` 的 jar 里**旧 15 个文件全在**，运行时实际跑的是"新 V1 + 旧 V2..V15"这套**混合历史**，而不是"只有一条 V1 基线"。
>
> - ✅ **仍然可信的部分**：`common-sms-service`（jar 里**只有** `V1__sms_baseline.sql`，因为它在合并后才被测过）——它的日志是干净证据：空库上 `baseline operation skipped` → `Migrating to version "1 - sms baseline"` → `Successfully applied 1 migration`。**这已经证明了核心语义**：空库 + `baseline-on-migrate` + 单 V1 基线 = 正确建表。
> - ✅ **仍然可信**：`mvn test` 会重新编译 `src/main/resources`，所以**合并后跑过测试的模块**（message / user / order / customer / payment / audit 等）其 `target/classes` 已是新单文件。
> - ❌ **不可信**：**合并前打过包的模块**（payment、tenant、admin 等）那次"OK / applied=15/32/33"是**混合历史**跑出来的，**不能证明"单 V1 基线"在运行期可用**。
> - **下轮修正**：`mvnw --offline -DskipTests package` 做一次**干净打包**（`package` 不清理 target，所以**要么先 `clean`，要么确认完成后 jar 里只剩一个文件**），逐服务校验 `jar tf | grep db/migration` **只列出 V1 基线**，再重跑本验证。
>
> 可复现命令：
> ```powershell
> docker run -d --name dsh-flyway -e MYSQL_ROOT_PASSWORD=rootpw -p 3306:3306 mysql:8.0.46
> # 等真正可查询后建三库；注意 mysqladmin ping 会在初始化期间就返回成功，必须用 information_schema 判断
> docker exec dsh-flyway mysql -uroot -prootpw -e "CREATE DATABASE open_im; CREATE DATABASE im_server; CREATE DATABASE open_audit;"
> # 逐个 java -jar <service>/target/*-2.2.0-SNAPSHOT.jar --server.port=<free>
> # 必须设 JWT_SECRET 与 INTERNAL_SERVICE_AUTH_SECRET，否则多个服务会「拒绝启动」
> ```

> **⚠️ 先读这条：现存的 `open-im-local` 集群是"重建前"的旧状态，不能拿它当验收基线。**
>
> 本轮实测确认：
> - 集群 31/31 deployment 就绪，但**创建时间是 `2026-10-12T01:37Z`**，而**第一个合并基线的提交是 `903033b`（`2026-10-13 17:53 +0800` = `09:53Z`）**——**集群比合并基线早约 32 小时**。
> - 因此现网库仍是**改名前的表名**：`im_server.support_media_*`（5 张，代码里已 0 引用）、`open_im.tenant_payment_method`（代码里现在是 `tnt_payment_method`，16 处引用）、`im_server.adm_app_release_retired_v1`（基线里是 `adm_app_release`，10 处引用）。
> - 现网 `open_audit` 只有 3 张表（`iam_audit_log` / `iam_audit_idempotency` / `iam_audit_archive_manifest`），**没有** `open_im` 里的同名旧表——印证审计双根按 profile 单库生效。
> - 现网表数：`open_im` 94 + `im_server` 73（含 5 张 flyway 历史表）→ **实际应用表 150**；**新基线洁净重建 = 155 张**（`open_im` 152 + `open_audit` 3）。差额恰好就是上面那批改名，**方向正确**。
>
> **结论**：清库重建**确实必须做**，且**不能用当前集群验证迁移结果**——它跑的是旧代码+旧 schema。重建需要：重建集群 → 用**当前 HEAD** 构建镜像 → 全新库跑 Flyway。这是破坏性操作（会销毁一个已运行 34h 的可用环境），**需要你确认后再动**。

> **迁移链部分已验证（本轮）**：19 个根的全部基线在全新 MySQL 8.0.46 上按序应用 **0 失败**，152 + 3 张表、无跨服务冲突、审计分区完好。**剩余未验证的是运行期部分**：`kind` 命名空间销毁/重建、各服务带 Flyway 启动（`flyway_schema_history_*` 表、`baseline-on-migrate`、服务间启动顺序）、31/31 deployment 就绪。验证方法（可复现）：
> ```powershell
> docker run -d --name dsh-rebuild-check -e MYSQL_ROOT_PASSWORD=rootpw -p 13306:3306 mysql:8.0.46
> # 等 mysqladmin ping 通过；建 open_im 与 open_audit 两个库
> # 逐个服务 docker cp 其 db/migration 下的 .sql 再 mysql <db> < file（PowerShell 没有 '<' 重定向）
> # 审计服务用 db/migration-audit 那个目录 + open_audit 库；老根用 db/migration + open_im
> ```
> 注意：`docker run` 可能因 registry TLS 超时失败，重试即可（本轮第一次失败、第二次成功）。

销毁 Kind 命名空间与 Compose 数据卷（**注意 N-23：本地 mysql/minio 用 `emptyDir`、mongodb 无卷，重建即清空数据，这是预期行为**）→ 全新 Flyway 迁移 → 全服务启动（31/31 deployment）。

### 步骤 5：回归

`scripts/verify/*` 全量 + 关键链路（登录 / 会话 / 消息 / 媒体 / KTV 预约）+ 统一入口 `-Scope Full` 退出码 0 + `.outputs/quality/migration-inventory-before.txt` 的 199 条可逐条复核（tag 可解析）。

**✅ 已完成的"无需活环境"部分（本轮）**：`scripts/verify/` 共 15 个脚本，全部需要活服务或集群（网关 URL、管理员口令、kubectl 上下文、MySQL 直连），**本地跑不了**；但"合并基线有没有把它们的假设改坏"已核查完：

1. **迁移文件名引用未过期**（最易藏雷处）：`AuditPartitionDdlCheck.java` 硬编码引用的三个审计迁移文件名 **全部仍然存在**（`db/migration-audit/` 的 `V1__audit_idempotency_ledger.sql` / `V2__audit_partitioned_table.sql` / `V3__audit_archive_manifest.sql`），**无失效引用**。
2. **ASCII 门禁覆盖面**：`scripts/verify/*.ps1` 中 3 个含非 ASCII（`e2e-ktv-smoke.ps1` 3813 B、`verify-unread-secret-toggle.ps1` 393 B、`verify-a380-ktv-closure.ps1` 6 B）**都已在 `powershell-ascii-allowlist.txt` 正确延期到批次 4**，门禁实测通过。
3. **审计模块的语义断言仍成立**（基线注释把 `occurred_at` 口径写成了断言，必须验证它不是空话）：
   - 写入侧 `AuditLogApplicationService#toLog`：`occurredAt == null` 时取 `createdAt`（L139-140）✅
   - 查询侧 `AuditLogRepositoryImpl#applyTimeRange`：`occurredAt IS NULL → createdAt` 兜底（L325-327），排序 `COALESCE(occurred_at, created_at)`（L187）✅
   - 有测试守卫（`AuditOccurredAtMigrationTest` 等 8 个测试类涉及）；**审计模块实测 115 测试 / 0 失败** ✅
   - `V1__iam_audit_baseline.sql` 把 `occurred_at` 定为 **NULL 是刻意的**（结构性修法在应用层，V3 只做幂等回填、空库上是空操作），三处口径一致。

**真正跑一遍仍需活集群**，与步骤 4 的运行期部分同一前置条件。

### 步骤 6：冻结 tag 收尾（DC-15 / DC-10）

删除 `v2.0.0`（本地 + 远端）→ 打 `v2.2.0` 注释 tag，**名字用 `baseline-` 前缀**（或注释含独立行 `freeze: true`）。

## 3. 已实测确认的事实（可直接引用，不必重测）

| 项 | 实测结果 |
| --- | --- |
| 冻结门禁 | `validate-flyway-immutability.ps1` 输出 `not frozen ... baseline rebuild is allowed (DC-10)`，**exit 0** |
| 现有 tag | `pre-migration-rewrite-20261013`、`pre-baseline-consolidation-20261013`、`v2.0.0`——**都不含 `baseline-` 前缀、也不含 `freeze: true`**，故当前不冻结 |
| `mvnw` 执行位 | `git ls-files -s mvnw` → `100755` ✅（上一轮修好了，别再动索引） |
| 门禁总数 | **24**（`invoke-engineering-validation.ps1` 的 `$validators`） |
| 报告模式门禁（批次 2 相关） | 批次 2 只剩 `flyway-conventions` **7**（前缀决策）。已转**阻断**且归零：`tenant-sql`、`secret-exposure`；`mq-registry` 由批次 2 的 34 条台账 + 批次 1 常量集中共管，现 **2** |
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

### 3.4 ⚠️ `target/classes` 会残留合并前的迁移文件（本轮踩到，务必先读）

`mvn test` **不会**清理 `target/classes`。合并基线时删掉了源码里的 V2..Vn，但**旧的 `.sql` 仍留在 `target/classes/db/migration/` 里**，于是：

- `jar tf <service>.jar | grep db/migration` 会**同时列出**新的 `V1__*_baseline.sql` **和**一堆旧文件（实测 `common-payment-service` 有旧 15 个、`platform-tenant-service` 旧 32 个、`im-admin-service` 旧 12 个）。
- 谁在合并前打过包、合并后没重新打包，那个 jar 跑起来就是**"新 V1 + 旧 V2..Vn"的混合历史**——**看起来能跑通，但不代表"单 V1 基线"可用**。
- `target/` 已被 `.gitignore:1` 忽略，**所以它永远不会进提交，也不会影响源码正确性**；它只影响**构建/验证结论**。

**做运行期验证前的检查清单**：
1. 先 `mvnw --offline -DskipTests package`（或在受影响模块上 `clean package`）；
2. 对**每个**服务列出 jar 里全部迁移，确认**只有一条** `V1__*_baseline.sql`（审计服务例外：老根 2 条、`migration-audit` 3 条）；
3. **不要用 `Select-Object -First 1` 判断**——那正是本轮误判的来源（只看到 V1，漏看了后面的旧文件）。

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

1. **旧迁移文件名失效引用（已部分处理）**：本轮已清零"已删除迁移文件名"的全部引用（含 `currency-scope-inventory` 7 处、`MULTI_TIMEZONE_DESIGN`、`RoomTypePo` javadoc，见 `9ef4708`）。**但每个新合并的模块都会再产生同类引用**，收尾时再扫一遍即可：判据是"仓库内不得出现已删除迁移文件名"（`git log --diff-filter=D --name-only` 与全仓 grep 对比）。

1b. **media 表名改动会连带 5 个 Java 映射 + 1 个门禁数据文件**（`support_media_*` → `media_*`）：`MediaObjectPo`/`MediaUploadSessionPo`/`MediaUploadPartPo`/`MediaReferencePo`/`MediaAuditEventPo` 的 `@TableName`，以及 `scripts/validate/tenant-platform-tables.txt` 里登记为"平台级表"的 `support_media_*` 5 行（否则 `validate-tenant-sql` 会因表名对不上而失去豁免）。合并 media 时必须一并改，否则运行期 MyBatis 会找不到表。

1c. **改表名/主键时，先查 Java 侧怎么取行**（本轮 media 的实际教训，值得所有模块照做）：

- 原来的 media 表**没有 `id`**：`media_object` 用 `object_id`、`media_upload_session` 用 `upload_session_id` 作主键。按 `ENGINEERING_RULES.md:192`（"所有核心表必须使用 `bigint unsigned` 自增 `id` 作主键"）它们**本就违规**，门禁报 `PK must be exactly 'id'` 是对的，所以要加 `id`。
- 但加 `id` 后，**`@TableId` 必须从业务键改成 `id`**，而多处代码是 `selectById(<业务键>)`（`MediaActivationService:30,32`、`MediaExpiryCleanupJob:35`、`MediaReferenceService:34,52,81`、`MediaUploadSessionService:88,110`、`MediaMultipartUploadService:37`）。MyBatis-Plus 的 `selectById` 走的是 `@TableId`，**主键一改，这些调用会把 varchar 业务键当数值主键去匹配**——纯改表名不能解决。
- 正确做法：PO 加 `private Long id` 作 `@TableId(type=IdType.AUTO)`，业务键降为普通字段（**保留字段名**，如 `objectId`），并把上述 `selectById` 改为 `selectOne(lambdaQuery().eq(Po::getObjectId, key))`；有测试兜底（media 模块 6 个测试类）。
- 通用判据：**只要某张表的主键要从业务键换成 `id`，就必须先 grep 全部 `selectById(` 调用点**，否则改动只是"看起来通过了门禁"。
2. **前缀类 7 条**已按用户决定挂起（见 §2 步骤 1 表后的警示），不在本轮处理：`open_*`×3、`saa_`×1、`group_*`/`idaas_*`×3。
3. `MktEventConsumedPo` 的运行期缺陷已在本轮修掉（`d295e0c`）——这是"补审计列"引发的连带改动，**后续模块若给 PO 未覆盖的列补 `NOT NULL` 审计字段，必须同样检查 Java 侧是否写入**，否则插入会报 MySQL 1364。
4. `standards/30` 已新增第 4 条门禁说明；批次 4 清理 6 个延期的非 ASCII 脚本前，需先定"脚本输出语言"策略。
5. `.outputs/quality/powershell-ascii-only-worklist.txt` 是 ASCII 门禁自检留下的旧快照（gitignored，无影响），可随手删。
6. **`tenant_payment_method` 的归属已核实，归属 `common-payment-service`**：该表由 `common-services/payment/common-payment-service/.../V9__tenant_payment_method.sql` 创建，`@TableName("tenant_payment_method")` 同时出现在 payment 与 admin 两个服务的 PO 中（admin 用它做菜单过滤）。因此 `tnt_` 改名要放在 payment 模块的合并里，并**同步两个服务的 PO**；tenant 模块只在已删除的 V12 注释里提过它，不拥有该表。
7. **跨域数据语句不要折进 V1 基线**：customer 模块的 V7（`UPDATE cst_member JOIN idt_login_identity`）被有意丢弃，因为把它折进 V1 会让"哪个服务先迁移"变成整个重建成功的前置条件。正确做法是移到 `scripts/migration/*.sql` 作为独立脚本（identity 模块已有先例 `scripts/migration/backfill-account-type.sql`）。**order / tenant / message 等模块若遇同类语句，照此处理。**
8. **强烈建议每个模块都用"建两个库再 diff"验证，而不是只读 SQL**：tenant 模块（32 文件、826 行）用这种方式跑出了 **3 个静态审查完全没有发现的缺陷**——折叠进 CREATE TABLE 的 NOT NULL 列缺 `DEFAULT` 导致早期 INSERT 直接失败、我加的 `WHERE` 守卫让某次回填静默失效、以及回填语句位置变化把后续显式 `scope_level='STORE'` 覆盖掉。门禁只验形状，验不了语义；**能跑真实 MySQL 时必须跑**。user 模块同理（OIDC 回填顺序导致 A380 行复活、`user_oidc_scope` 成员变化）。

9. **`mq-registry` 的 10 条"生产者组字面量"其实是正确写法，需要决策而非机械修**：

   - 现状：`application.yml` 里是 `producer-group: ${PLATFORM_ORDER_SERVICE_MQ_PRODUCER_GROUP:platform-order-service-producer}` —— **环境变量优先、字面量是默认值**，正是 `standards/12` 要求的"服务配置中的 Producer Group 必须使用服务专属变量，变量名遵循 `<SERVICE>_MQ_PRODUCER_GROUP`"。
   - 而同一个值**已经在 `sdk/protocol-mq` 里定义了常量**（如 `ResourceMqProducerGroups.PLATFORM_RESOURCE_SERVICE`），两个模块的注册表类（`CouponMqRegistry`、`ResourceMqRegistry`）已直接引用常量。
   - **已实测的决定性证据（本轮）**：这 10 个环境变量**在仓库的部署配置里一个都没有设置**——`k8s/`、`deploy/`、`.cnb.yml`、`docker-compose.yml` 全查过，10 个 `<SERVICE>_MQ_PRODUCER_GROUP` 均为 `NOT SET ANYWHERE`。也就是说 **YAML 里的字面量默认值是生产实际生效的值**。
   - **已按①实现并验证（`ef29e52`）**：门禁改为**语义判定**——配置里的组名只有**偏离** `protocol-mq` 已声明的常量名才算违规（比对 `*Group*.java` 的常量值）。结果 **10 → 2**，剩余 2 条是**真问题**：
     - ✅ `im-conversation-service-producer`：**确实在生产**（有 `ConversationAuthorizationOutboxRelay` + 3 处生产者调用）却从未声明 → 已补进 `ImMqProducerGroups` 并登记台账。
     - ⬜ `im-admin-service-producer`、`platform-identity-service-producer`：**两个服务根本不生产**（无 relay、无生产者调用、代码里连 `producerGroup`/`MqProperties` 都不引用，只消费）。但 `MqInfrastructureConfig.mqProducer` 是**每个 MQ 服务都创建的 Bean**，且 `mqConsumerFactory` 依赖它，所以这个 group 会被 RocketMQ 注册、只是**永不发布**。
   - **这 2 条的处置需要你选**（都会改变运行期接线）：
     - **A. 声明常量**：给这两个"无生产者"服务也在 `protocol-mq` 里登记 group，值就用现名（保留现状语义，门禁清零）。缺点：为不发布的组留下常量。
     - **B. 删掉配置项**：让共享的 `MqProducer` Bean 走 `MqProperties` 自带默认值 `open_im_default_producer`。缺点：该值**本身违反**命名规范（应为 `<服务名>-producer`），且在 broker 上多出一个无意义的 producer group——所以 B 其实要先决定"给 MqProperties 换成什么默认值"。

10. **H2 测试夹具与生产基线已漂移（真实存在，未修，需单独一轮）**：三个模块的 `src/test/resources/db/test-migration/` 是自己的 DDL 副本（不读生产迁移），所以**夹具会悄悄落后于基线**。实测漂移：

| 模块 | 夹具 | 与生产基线相比缺什么 |
| --- | --- | --- |
| `platform-order-service` | `V1__ord_h2_schema.sql` | `ord_event_consumed` 缺 4 个审计列、`ord_inventory_transaction` 缺 `updated_by`/`updated_at`、`ord_event_outbox` 缺 `created_by`/`updated_by`（共 8 列） |
| `platform-customer-service` | `V1__cst_h2_schema.sql` + `V2__cst_member_h2.sql` | 只建 5/10 张表；**表是子集属正常**（夹具只建被测路径需要的表），需逐表核**列** |
| `common-payment-service` | `V1__pay_h2_schema.sql` | 建 7/13 张表；另外夹带了 `ord_order`/`ord_order_item`（跨域夹具，非本模块表） |

**为什么现在测试仍绿**：夹具缺的列，恰好是那些测试路径不写入的列（例如 `ord_event_consumed` 的审计列由 `DEFAULT` 兜底，而夹具里该表的 INSERT 由 PO 驱动、PO 不映射这些列）。所以这**不是回归，而是"测试对着比生产更旧的 schema 跑"**，会长期掩盖问题。
**修的时候注意**：① 表是子集没问题，**只需对齐列**；② 加列会让夹具里的位置式 `INSERT ... VALUES (...)` 失效——payment 模块已因此改成显式列名，照做；③ 改完必须真跑该模块测试（H2 与 MySQL 的 DDL 行为不同，例如 `DEFAULT CURRENT_TIMESTAMP(3)` 在 H2 上的支持）。

**已完成（`14e9af5` + `1075dd2`）**：三个夹具全部对齐完毕，共补 18 个审计列——`order` 8 列（573 测试 / 0 失败）、`customer` 7 列（125 测试 / 0 失败）、`payment` 2 列（130 测试 / 0 失败）。H2 里用 `TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP`（H2 不认 `datetime(3)`）。**这三块已完成，无需再动。**

**附带发现（未修，非失败）**：该模块测试日志里有 `Table "ord_reservation" not found` 的报错噪声——夹具只建 13 张表，缺 `ord_reservation`／`ord_ktv_server_session`／`ord_order_void_approval`／`ord_reservation_rule_config`，而**生产定时任务在测试上下文里真的会去查它们**（`ReservationMapper.selectNotArrivedOverdue`）。它不影响测试结果（573/0/0，后台调度线程只记日志），但会在每次测试里刷错误栈。修法二选一：把 4 张表补进夹具，或在测试 profile 里关掉这些定时任务；**不要**为了让日志干净而删掉生产查询。

11. **`im-admin` 的守卫测试是正确做法，值得抄**：合并后它把断言从"V2 存在"改成 **`assertFalse(exists(V2))`**，并加"迁移目录只允许一个文件"，而不是删掉守卫（`AdminBatchZeroArchitectureGateTest:202-207`）。message 与 user 同样处理。**遇到钉住已删文件的测试，改断言方向并加强，不要删除。**

12. **✅ 已核实为我的误报（本轮更正）：仓库自身是一致的，不存在"新代码 + 旧表名"问题。**
    - 我此前据"运行期实测产物用的是旧表名（`support_media_object`/`tenant_payment_method`/`adm_app_release_retired_v1`）"推断仓库里代码与基线不一致。**这个推断是错的**，根因是**`target/classes` 里的陈旧构建产物**（见 §3.4）：那次运行跑的是"新 V1 基线 + 旧的 V2..Vn"，**旧迁移文件把表名又改回去了**。
    - **正确事实**：合并后的基线文件**只创建新名**（`media_object` 等 5 张、`tnt_payment_method`、`adm_app_release`；**源码目录里没有任何一处 `CREATE TABLE` 用旧名**），而当前 Java 代码引用的也是新名——**两者一致**。旧名只出现在 `target/` 下的历史产物里（`target/` 已被 `.gitignore:1` 忽略，不会进提交）。
    - **教训**：判断"某个服务的 jar 是不是用当前源码构建的"，**不能只看第一个匹配**——`jar tf | Select-String 'db/migration' | Select-Object -First 1` 会漏掉同目录下的旧文件。要**列出全部**并核对是否只有 V1。
   - **台账 34 条已完成（`83ee042`）**：`docs/mq/REGISTRY.md` 已补齐，门禁 44 → **10**，且反向漂移 0（台账里每个名字都能对上 protocol-mq 常量）。做法固化在两个提交过的工具里，**不需要再靠人工回忆**：
     1. `scripts/validate/extract-mq-semantics.ps1`（只读）→ `.outputs/quality/mq-semantics-extract.txt`：对每个引用 protocol-mq 组的类输出**类路径 / 消费组常量 / Topic 常量 / 类级 javadoc**。本仓 javadoc 会写明消费目的，事件消费者还会直接写出幂等键。
     2. `scripts/validate/update-mq-registry-ledger.ps1` 读 `scripts/validate/mq-registry-ledger-entries.txt`（UTF-8）幂等追加行。**脚本与数据分离是刻意的**：脚本必须 ASCII-only（无 BOM 的 `.ps1` 被按 ANSI 解析——我第一版把中文写进脚本，直接解析失败），中文台账文本因此放数据文件。
     - **留痕的诚实口径**：`消费目的` 取自实现；`幂等键` 只在代码写明时填写；**无证据的一律标 `待核`**，不填看似合理的值——standards/12 要求这些列表达真实业务语义，**编造的顺序键比空着更糟**。目前 `顺序键` 多为 `待核`，下一步应从生产方 Outbox 写入处或事件载荷类补齐。
   - 写 `REGISTRY.md` 时注意：该文件是中文无 BOM，**必须用 `[System.IO.File]::WriteAllText($p,$t,[System.Text.UTF8Encoding]::new($false))` 写**；用 `Set-Content`/`Out-File` 会按 ANSI 写坏中文（这正是 standards/30 与本次 media/docs 事件同一个坑）。新脚本 `extract-mq-semantics.ps1` 本身也必须 **ASCII-only**——我第一版在脚本里写了一个 `…`，PS 5.1 立刻按 ANSI 解析失败。

1d. **"补审计列"是有代价的：先查 INSERT 有没有写这些列**（本轮 audit / marketing 各踩一次）。

- 门禁要求每张表都有 `created_by`/`created_at`/`updated_by`/`updated_at` 且 `NOT NULL`。若某表的写入方（mapper 的 `@Insert`、`scripts/verify/*` 探针 SQL）**没有把这些列写进 INSERT**，MySQL 8 严格模式下会以 **1364 拒插**，表现是"审计写入静默丢失"，比门禁报警严重得多。
- 已遇两例：`mkt_event_consumed`（PO 未写 → 已补 PO 字段与消费者赋值，`d295e0c`）；audit 三张表（mapper 只写 `created_at`，未写 `updated_by`/`updated_at` → 本轮在 `updated_at` 上加 `DEFAULT CURRENT_TIMESTAMP(3)` 兜底）。
- **两种正解的取舍**：①把列加进 INSERT（语义最明确，但要改每个写入点）；②给列加 `DEFAULT`（改动最小，但审计语义依赖默认值）。本轮用 ② 兜底、并另派任务用 ① 补齐 mapper。**后续模块务必二选一并留痕**，不要只加列不查写入方。
- 同理检查**分区表**：分区表的每个唯一键/主键都必须包含分区列，所以主键只能是 `(id, occurred_at)`；门禁已按 DC-17 放行该形态。

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
