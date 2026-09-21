# 数据库规范全量审计报告

审计日期：2026-09-03  
审计范围：所有服务 `src/main/resources/db/migration/` 下的生产 Flyway SQL（排除测试 H2 迁移、`target/` 产物和手工运维脚本）。

## 结论

- 共检查 18 个服务、107 个生产迁移文件。
- 文件命名、版本号重复、UTF-8/BOM、Flyway 模块边界、Flyway 配置、持久化依赖边界和租户隔离专项校验通过。
- 106 个建表语句满足 `InnoDB + utf8mb4 + utf8mb4_0900_ai_ci`；1 个历史建表语句缺少排序规则和表注释。
- 未发现物理外键、跨库 `REFERENCES`、触发器、存储过程或连接级 `SET NAMES`。
- 发现的历史问题不能通过修改已执行迁移解决，必须使用新的 Flyway 版本迁移或记录为基线例外。

## 分级问题

### P0：凭据和密钥进入迁移源代码

涉及 `group-idaas-service/V2__seed_default_admin.sql`、`platform-admin-service/V2__saa_admin_account_seed.sql`、`platform-identity-service/V4__seed_admin_identity.sql` 和 `im-user-service/V1__init.sql`。

- 管理员初始密码出现在注释或可推导的种子上下文中；客户端 `client_secret` 以明文占位值写入种子。
- 已执行脚本不能直接删改，否则会造成 Flyway checksum 漂移。
- 处理方案：立即轮换开发/测试环境管理员密码和客户端密钥；生产环境只从 Secret Manager/部署密钥注入；后续基线只写不可逆哈希或一次性初始化标记，不写明文凭据；在 CI 增加凭据扫描门禁。

### P1：历史迁移中的隐式幂等或破坏性语句

- `CREATE TABLE IF NOT EXISTS`：5 处，集中在支付方式和用户账户注销/设备会话迁移。
- `DROP TABLE IF EXISTS`：4 处，见 `im-user-service/V12__drop_coin_points.sql`。
- `INSERT IGNORE`：13 行。
- `ON DUPLICATE KEY UPDATE`：23 行。

这些语句会掩盖环境漂移或静默覆盖数据。历史版本保持不变；新版本必须使用明确前置条件、唯一键和可审计失败。对仍需执行的数据修复，先增加环境检查和备份/回滚预案，再单独发布修复迁移。

### P1：表结构元数据不完整

`common-payment-service/V9__tenant_payment_method.sql` 缺少表级 `COLLATE=utf8mb4_0900_ai_ci` 和表 `COMMENT`，字段及唯一索引也缺少用途注释。

建议新增下一版本迁移，使用 `ALTER TABLE` 补齐排序规则、表注释、字段注释和索引注释；上线前在脱敏副本验证锁表时间与字符集兼容性。

### P1：租户审批表缺少隔离字段

静态复核发现 `platform-tenant-service/V5__tnt_iam_approval.sql` 的 `iam_approval` 未声明 `tenant_id`。现有租户专项脚本未将该表纳入检查，因此不能据此判定安全。

必须先确认该表是否只保存平台级审批：

- 若为租户级审批，新增迁移补充非空 `tenant_id`、数据回填、租户索引，并同步 Repository 查询条件。
- 若确为平台级审批，在表注释、领域登记和校验器中明确平台级例外，防止后续误用。

### P2：字段注释覆盖不足

按字段级规则扫描，历史基线及后续迁移合计约 355 个字段缺少 `COMMENT`，主要集中在客户、订单、支付、资源、租户 IAM 基线和发布审计表。该问题不影响当前执行，但降低数据字典、审计和运维可读性。

建议按服务拆分 comment-only 迁移，优先处理支付、订单、租户 IAM、发布审计等核心域；每批迁移先在副本执行并检查 DDL 锁等待。不能为追求一次性通过而修改既有 V1/V2 文件。

### P2：布尔字段类型历史不统一

发现 8 处 `TINYINT`（未显式为 `TINYINT(1)`），涉及支付渠道开关、租户支付方式、发布灰度比例、用户隐私开关和营销授权字段。灰度比例不是布尔值，不应机械改成 `TINYINT(1)`；其余字段需按代码语义确认后再新增 `MODIFY COLUMN` 迁移。

## 已执行验证

以下校验均通过：

- `validate-flyway-module-boundaries.ps1`
- `validate-security-and-flyway-configuration.ps1`
- `validate-tenant-sql.ps1`
- `validate-persistence-dependency-boundaries.ps1`
- `validate-text-encoding.ps1`
- 后端 Maven 全量 `clean verify -DskipTests=false`（此前已完成）

统一工程校验仍有一个既有误报：`scripts/deploy/k8s.ps1` 被 secret 暴露扫描器误判为敏感值读取；该误报与数据库迁移无关。

## 后续门禁

1. 新增数据库规范校验：禁止 `IF NOT EXISTS`、`DROP TABLE`、`INSERT IGNORE`，强制表级引擎/字符集/排序规则及表注释。
2. 将 `iam_approval` 纳入租户隔离规则，并补充平台级例外白名单机制。
3. 将敏感种子迁移改为部署期初始化任务，凭据只来自 Secret Manager，并在 CI 阻断明文密码、token、secret。
4. 每个结构修复使用新 Flyway 版本；执行前在空库和脱敏生产副本各跑一次，执行后运行 `flyway validate` 和服务回归测试。

