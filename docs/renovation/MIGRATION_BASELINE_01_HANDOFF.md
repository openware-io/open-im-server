# 迁移基线重建交接（批次 2）

> 方案集：`MIGRATION_BASELINE`；序号：`01`；前置：[01 数据重建方案](./MIGRATION_BASELINE_01_DATA.md)
> 状态：**批次 2 第 1 步已完成，第 2 步（382 条模块重写）未开始**
> 目标读者：**新会话**（上一个会话上下文已接近上限，故在此交接）
> 交接时间：2026-10-13；分支 `develop/2.2.0-auth`；HEAD `d2beb48`

## 0. 30 秒现状

- **可以开工**：冻结门禁实测输出 `not frozen (no baseline tag with a freeze statement); baseline rebuild is allowed (DC-10)`，`-ReportOnly` 不拦截、迁移重写路径畅通。
- **主工作量的数量已定**：`flyway-conventions` worklist = **382 条**（不是文档里写的 418，见 §3 的坑 4）。
- **唯一必须在重写之前做的事**：先给 `flyway-conventions` 门禁补"反引号"检查（§2 步骤 0）。当前基线样板就没加反引号，若先铺开重写，382 条会被重写两遍。
- 与迁移无关的门禁自伤项本轮已闭合：新增 `validate-powershell-ascii-only.ps1`，门禁数 23 → **24**，`scripts/validate` 内 2 个非 ASCII 脚本已清零。详见 [03 整改方案](./ENGINEERING_GOVERNANCE_03_REMEDIATION.md) §19 与 N-24。

## 1. 开工前的 4 条硬约束（踩过，不要再踩）

| # | 约束 | 原因与正确做法 |
| --- | --- | --- |
| 1 | **不要用 `git reset` 重置索引来"分组提交"** | 上一轮因此把 `mvnw` 的 `100755` 一起回退；Windows 上 `git add` **无法**从文件系统恢复执行位 → commit 声称"补了 100755"、实际是 `100644`，Linux CI 上 `./mvnw` 直接 Permission denied。分组提交请用 `git add <path>` / `git commit -- <path>`，动过索引后必须 `git ls-files -s mvnw` 复核为 `100755` |
| 2 | **冻结语义是结构化的**：tag 名以 `baseline-` 开头，或注释含独立行 `freeze: true` | 不要写"本 tag 不承担冻结语义"这类**松散词**描述。旧版门禁对注释做 `frozen` 词匹配，一句声明"自己不是冻结点"的话反而把它判成冻结点，错误阻断了批次 2。打 tag 用 `git tag -a`，命名 `baseline-*` |
| 3 | **全仓 `.ps1` 都是无 BOM 文件，且 Windows CI 用 `powershell.exe` 执行** | 脚本内任何非 ASCII（中文注释、正则、文案）在 PS 5.1 下按 ANSI 解析，会被静默损坏。校验脚本一律英文注释。已延期到批次 4 的 6 个文件见 `scripts/validate/powershell-ascii-allowlist.txt`，**不要往该清单新增条目**（陈旧条目本身会让门禁失败） |
| 4 | **每次提交/每轮只做一个模块** | 382 条的上下文开销是本次交接的直接原因。按 §2 步骤 1 的模块清单逐块推进，一块一轮，做完即验证并提交 |

## 2. 执行顺序

### 步骤 0（必须先做）：给 `flyway-conventions` 门禁补"反引号"检查

现状与证据：

- 规范要求：`docs/DATABASE_MIGRATION_STANDARD.md:8` —— "标识符使用反引号"。
- 门禁现状：`validate-flyway-conventions.ps1` 的 10 项检查（见该文件 9-20 行注释）**没有**反引号检查。第 113 行用 `` `? `` 让审计列的反引号可选，因此**未加反引号的表反而不会报错**。
- 隐性风险：第 122 行的索引正则写成 ``KEY\s+`(?<name>...)` ``，未加反引号的索引**完全不匹配** → 索引命名规则静默失效，不会报"缺反引号"，只会漏检。
- 后果：样板模块 `common-services/mail/common-mail-service/.../V1__mail_baseline.sql` 已经用了无反引号写法（`CREATE TABLE mail_channel_config (`、`id bigint unsigned...`）。

动作：

1. 在门禁中新增**独立检查**："标识符未使用反引号"（与"缺字段"分开报，避免又混为一谈）；同时把索引正则改成反引号可选，防止漏检。
2. 用该检查跑一次现状 → 得到"反引号待补清单"（预期不会小）。
3. 把**样板模块** `V1__mail_baseline.sql` 也补上反引号（它是唯一已改写的模块）。

> 先做这一步的意义：反引号是纯机械改写，先让门禁能报出来，后续 382 条才是一次成型，不会返工。

### 步骤 1：按模块重写基线（worklist 驱动，382 条）

输入清单：`.outputs/quality/flyway-conventions-worklist.txt`（**382 行**，格式 `<文件路径>:<表名>: <问题>`，另有 2 行属于 `docs/ENGINEERING_RULES.md` 前缀登记问题）。

**建议按"条数升序"推进**（先小模块，尽早暴露模式问题，再打大块）：

| 序 | 模块（迁移根） | 条数 | 累计 |
| --- | --- | --- | --- |
| — | `docs/ENGINEERING_RULES.md`（前缀登记，非迁移文件） | 2 | 2 |
| 1 | `platform-services/resource/platform-resource-service` | 3 | 5 |
| 2 | `common-services/payment-channel/common-payment-channel-service` | 5 | 10 |
| 3 | `platform-services/identity/platform-identity-service` | 5 | 15 |
| 4 | `platform-services/marketing/platform-marketing-service` | 6 | 21 |
| 5 | `common-services/sms/common-sms-service` | 9 | 30 |
| 6 | `platform-services/admin/platform-admin-service` | 11 | 41 |
| 7 | `group-services/idaas/group-idaas-service` | 11 | 52 |
| 8 | `common-services/audit/common-audit-service/migration` | 11 | 63 |
| 9 | `platform-services/tenant/platform-tenant-service` | 16 | 79 |
| 10 | `platform-services/customer/platform-customer-service` | 17 | 96 |
| 11 | `common-services/media/common-media-service` | 19 | 115 |
| 12 | `common-services/audit/common-audit-service/migration-audit` | 22 | 137 |
| 13 | `common-services/payment/common-payment-service` | 23 | 160 |
| 14 | `platform-services/order/platform-order-service` | 38 | 198 |
| 15 | `im-services/conversation/im-conversation-service` | 39 | 237 |
| 16 | `im-services/admin/im-admin-service` | 44 | 281 |
| 17 | `im-services/user/im-user-service` | 44 | 325 |
| 18 | `im-services/message/im-message-service` | 57 | 382 |

> 注：用户侧记忆里的"order 32 文件、tenant 32 文件是最大两块"指的是**旧结构文件数**（[01 方案](./MIGRATION_BASELINE_01_DATA.md) §2 表）；真正的工作量口径是上表的 worklist 条数，最大三块是 **message 57 / user 44 / admin 44**。

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

- 前缀改登记/改名：`support_media_*` → `media_*`（DC-04）；`mail_*` / `sms_` / `open_*` / `iam_menu` / `saa_admin_account` → `adm_` / `tenant_payment_method` → `tnt_`。
- 集团业务领域登记（DC-02）：`group_*` / `idaas_*` 表与目录/文档一并登记。
- `tenant-sql` worklist **41 条**（`.outputs/quality/tenant-sql-worklist.txt`）：平台级扫表补 `platform-wide` / `tenant-agnostic` 显式标注，其余补租户条件。
- `secret-exposure` **4 处**（`.outputs/quality/secret-exposure-report.txt`）：迁移注释中的口令与应用密钥哈希随基线重建清除。
- 合并 `common-audit-service` 的双迁移根为单根 + 分区表基线（DC-17 保留分区）；history 表名统一 `<service>_schema_history`（media 由 `support_schema_history` 改）。
- 删除"先建后删"迁移与孤儿 `deploy/database/init.sql`。

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
| 报告模式门禁 | 8 个：`flyway-conventions` 382、`java-fully-qualified-names` 192、`service-api-boundaries` 137、`standards-governance` 107、`java-silent-catch` 103、`mq-registry` 44、`tenant-sql` 41、`secret-exposure` 4 |
| ASCII 门禁 | 新增 `validate-powershell-ascii-only.ps1`（阻断模式，扫描 `scripts/**/*.ps1`）；2 个在册违规已清零；6 个既存延期至批次 4 |
| 工作区 | 干净（除本轮新增改动）；`.outputs/` 被 `.gitignore:13` 忽略，worklist 不会误提交 |

## 4. 本会话遗留（下次顺手清理）

- `.outputs/quality/powershell-ascii-only-worklist.txt`（ASCII 门禁自检时生成的旧快照，无影响）。
- `standards/30` 已新增第 4 条门禁说明，批注里明确"扫描范围比规范原文更宽"的原因；批次 4 清理 6 个延期文件时需先定"脚本输出语言"策略（中文输出 + BOM，或英文输出 + 保持无 BOM）。

## 5. 关键文件索引

| 用途 | 路径 |
| --- | --- |
| 数据重建方案（步骤、验收、回滚） | [MIGRATION_BASELINE_01_DATA.md](./MIGRATION_BASELINE_01_DATA.md) |
| 整改方案（批次、决策、门禁清单） | [ENGINEERING_GOVERNANCE_03_REMEDIATION.md](./ENGINEERING_GOVERNANCE_03_REMEDIATION.md) |
| 审查报告（问题出处） | [ENGINEERING_GOVERNANCE_02_AUDIT.md](./ENGINEERING_GOVERNANCE_02_AUDIT.md) |
| 主 worklist（382 条） | `.outputs/quality/flyway-conventions-worklist.txt` |
| 租户 SQL worklist（41 条） | `.outputs/quality/tenant-sql-worklist.txt` |
| 密钥暴露报告（4 条） | `.outputs/quality/secret-exposure-report.txt` |
| 基线样板（照抄对象） | `common-services/mail/common-mail-service/src/main/resources/db/migration/V1__mail_baseline.sql` |
| 统一门禁入口 | `scripts/validate/invoke-engineering-validation.ps1` |
| 报告模式登记 | `scripts/validate/report-only-gates.txt` |
| ASCII 延期清单 | `scripts/validate/powershell-ascii-allowlist.txt` |
| 规范原文 | `docs/standards/30_TEXT_ENCODING_AND_BATCH_EDITING.md`、`docs/DATABASE_MIGRATION_STANDARD.md` |
