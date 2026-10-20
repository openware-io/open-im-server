> Status: historical
> Archived: 2026-10-19
> Superseded-by:
> Original-location: docs/renovation/MIGRATION_BASELINE_01_DATA.md

# 迁移基线合并重建（批次 2）

> 方案集：`MIGRATION_BASELINE`；序号：`01`；边界：`DATA`；前置：[02 审查报告](ENGINEERING_GOVERNANCE_02_AUDIT.md)、[03 整改方案](ENGINEERING_GOVERNANCE_03_REMEDIATION.md) §5
> 状态：**执行中（第 1 步已完成：留档点与清单）**
> 决策依据：DC-09（合并重建）、DC-10（冻结点）、PR-01~PR-03（未上线、无数据、可销毁重建）

## 1. 留档点（已完成）

| 项 | 值 |
| --- | --- |
| 前置 tag | `pre-baseline-consolidation-20261013`（注释 tag，指向 HEAD） |
| 迁移文件数 | **199** |
| 文件清单 | `.outputs/quality/migration-inventory-before.txt`（格式：`<mode> blob <sha> <path>`，可逐条用 `git show <tag>:<path>` 复核） |
| 取回方式 | `git show pre-baseline-consolidation-20261013:<path>`；整目录导出 `git archive <tag> -- <dir>`（外部制品库，不入仓库） |

> 注意：该 tag 指向 HEAD，**不含**工作区未提交的批次 0/1 改动（45 改 + 29 删 + 15 新增）。迁移文件在批次 0/1 中**未被改动**，因此本留档对迁移是准确的；但若需要完整的代码基线追溯，建议先提交批次 0/1 再打第二个 tag。

## 2. 旧结构分布（按模块，迁移文件数）

| 模块 | 文件数 |
| --- | --- |
| platform-services/order/platform-order-service | 32 |
| platform-services/tenant/platform-tenant-service | 32 |
| im-services/user/im-user-service | 24 |
| im-services/conversation/im-conversation-service | 16 |
| common-services/payment/common-payment-service | 15 |
| im-services/message/im-message-service | 13 |
| platform-services/customer/platform-customer-service | 13 |
| im-services/admin/im-admin-service | 12 |
| platform-services/admin/platform-admin-service | 11 |
| platform-services/resource/platform-resource-service | 8 |
| common-services/audit/common-audit-service | 7（含 `migration-audit` 第二根） |
| platform-services/identity / marketing / payment-channel / idaas / media / sms / mail | 5 / 4 / 2 / 2 / 1 / 1 / 1 |

## 3. 重建范围（worklist 驱动，不靠人工回忆）

本次一次性修正的输入是门禁 worklist，**418 条**（`.outputs/quality/flyway-conventions-worklist.txt`），分布：

| 类别 | 条数 |
| --- | --- |
| 缺审计字段（DC-11 全表强制） | 241 |
| 主键非 `id bigint unsigned AUTO_INCREMENT` | 44 |
| 索引名不符合 `idx_<table>_<semantic>` / `uk_<table>_<semantic>` | 70 |
| 主键不是 `id`（业务键/复合键） | 19 |
| 未登记前缀 | 15 |
| 缺列注释 / 缺表注释 | 8 |
| `CREATE TABLE IF NOT EXISTS` | 6 |
| `datetime(6)` 应为 `datetime(3)` | 4 |
| 缺 `COLLATE=utf8mb4_0900_ai_ci` | 4 |
| 动态 DDL（`PREPARE/EXECUTE`） | 2 |
| `DROP TABLE` | 1 |
| 缺 `ENGINE=InnoDB` / `DEFAULT CHARSET=utf8mb4` | 1 / 1 |
| 登记表与文档不一致（`mail`/`sms`） | 2 |

同时一并处理（不在该 worklist 内）：

- **前缀改登记/改名**：`support_media_*` → `media_*`（DC-04）；`mail_*`/`sms_`/`open_*`/`iam_menu`/`saa_admin_account`→`adm_`/`tenant_payment_method`→`tnt_`（§7 登记结果）
- **集团业务领域登记**（DC-02）：`group_*`/`idaas_*` 表与目录/文档一并登记
- **租户查询级 41 条**（`tenant-sql` worklist）：为平台级扫表补 `platform-wide` / `tenant-agnostic` 显式标注，其余补租户条件
- **4 处历史凭据明文**（`secret-exposure`）：迁移注释中的口令与应用密钥哈希随基线重建清除
- **合并 `common-audit-service` 的双迁移根**为单根 + 分区表基线（DC-17 保留分区）
  > ⚠️ **本条前提有误，已废弃**：`db/migration`（老库 `open_im` 历史）与 `db/migration-audit`（新库 `open_audit` 基线）由 profile `audit-schema` 原子切换、共用历史表且**同名不同库**，已有 `AuditPartitionInvariantTest` 固化"必须物理隔离"。两根本轮各自规范化、**不合并**。详见 [交接书 §3.1.1](MIGRATION_BASELINE_01_HANDOFF.md)。
- **history 表名统一** `<service>_schema_history`（media 由 `support_schema_history` 改）
  > ⚠️ **本条无规则依据，本轮不做**：`ENGINEERING_RULES.md` / `DATABASE_MIGRATION_STANDARD.md` / `standards/21` 均未规定 history 表命名，也无门禁检查。现状为多数服务 `flyway_schema_history_<service>`、IM 四服务与 media 用 `<service>_schema_history`。若要统一，应先立规范＋门禁再一次性改。
- 删除"先建后删"迁移与孤儿 `deploy/database/init.sql`

## 4. 执行顺序

1. **（已完成）** 打 tag + 生成 199 文件清单 ← 本文件
2. 逐模块重写基线（每服务合并为 `V1__<domain>_baseline.sql`，后续从 `V2` 起）
3. 登记表与规范同步（`ENGINEERING_RULES` 前缀登记表补 `mail_`/`sms_`/`open_`/集团域/`media_`）
4. 清库重建：销毁 Kind 命名空间与 Compose 数据卷（PR-03）→ 全新 Flyway 迁移 → 全服务启动
5. 回归：`scripts/verify/*` 全量 + 关键链路（登录/会话/消息/媒体/KTV 预约）
6. 门禁复验：`flyway-conventions` → 0、`tenant-sql` → 0、`secret-exposure` → 0，三个门禁从报告模式**转为阻断**（删除 `report-only-gates.txt` 中对应行）
7. tag 收尾（DC-15/DC-10）：删除 `v2.0.0`（本地 + 远端）→ 打 `v2.2.0` 注释 tag（tag 名以 `baseline-` 开头，或注释含独立行 `freeze: true`）

## 4.1 样板模块（已完成）：common-mail-service

`common-services/mail/common-mail-service/src/main/resources/db/migration/V1__mail_baseline.sql`（1 文件 / 3 表）已按新规范重写，**该模块 worklist 归零**。实际改动：

- 3 张表补齐 `created_by` / `updated_by`（原表只有 `created_at` / `updated_at`）——DC-11 要求 4 个审计字段齐全
- 索引名从缩写表名改为**完整表名 + 语义**：`uk_mail_channel_tenant_host` → `uk_mail_channel_config_tenant_host`、`uk_mail_send_idempotency` → `uk_mail_send_log_tenant_idempotency`、`idx_mail_send_query` → `idx_mail_send_log_query`
- 原本已合规、无需改动：`id bigint unsigned AUTO_INCREMENT`、`ENGINE=InnoDB` + `CHARSET=utf8mb4` + `COLLATE=utf8mb4_0900_ai_ci`、`datetime(3)`、表/列中文注释、无 `IF NOT EXISTS`

**同时修正了门禁的一处误报（重要）**：原 `validate-flyway-conventions.ps1` 的字段检查要求标识符**带反引号**（`` `id` ``、`` `created_by` ``），导致"定义正确但未加反引号"的表被误报为"缺字段"。改为反引号可选后，worklist **418 → 382**（36 条误报被清除），邮件模块归零。

**遗留待办（下一轮必须处理）**：`DATABASE_MIGRATION_STANDARD.md:8` 要求"标识符使用反引号"，而当前门禁**尚未单独校验反引号**——本轮样板文件仍使用无反引号写法。因此：
1. 门禁需新增一条独立检查："标识符未使用反引号"（与"缺字段"分开报，避免再次混为一谈）；
2. 后续模块改写时应统一加反引号，并回头补上样板模块的反引号。

## 5. 验收标准

- `powershell -File scripts/validate/invoke-engineering-validation.ps1 -Scope Full` 退出码 0
- `flyway-conventions` / `tenant-sql` / `secret-exposure` 三个门禁 worklist 归零并转为阻断
- 清库重建后 31/31 deployment 就绪、`scripts/verify/*` 回归通过
- `.outputs/quality/migration-inventory-before.txt` 的 199 条可逐条复核（tag 存在且可解析）

## 6. 回滚

- 骨架层面：`git checkout pre-baseline-consolidation-20261013 -- <path>` 取回任一旧迁移文件
- 环境层面：清库重建（无生产数据，PR-01/PR-03），旧结构可从 tag 完整恢复
