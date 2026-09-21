# 审计日志存储改造：独立 schema + 月分区 + 冷归档

> 方案集：`AUDIT_STORAGE`；序号：`01`；实施边界：`common-audit-service`（表结构 / 迁移 / 仓储 / 归档任务）、
> `gv_saas_admin`（列表分页契约）、`k8s/local`（本地 Kind，验证与运维主路径）/ `k8s/ack`（建库授权与归档投递）；
> 状态：**实施中**（编号进度见 §9）；不变量依据：[21 持久层技术栈标准](../standards/21_PERSISTENCE_STACK.md)、
> [数据库迁移脚本规范](../DATABASE_MIGRATION_STANDARD.md)、[CHINA_COMPLIANCE](../compliance/CHINA_COMPLIANCE.md)、
> [PLATFORM_SECURITY_01_GOVERNANCE](../renovation/PLATFORM_SECURITY_01_GOVERNANCE.md)。

## 0. 决策记录（已确认）

| 项 | 决策 |
| --- | --- |
| 存储归属 | 审计表迁到**独立 schema `gv_audit`** |
| 切分机制 | **原生月分区**（不采用物理月表，理由见 §3） |
| 分区键 | `occurred_at`（业务发生时间），`pmin` / `pmax` 兜底 |
| 幂等 | 迁到独立台账表 `iam_audit_idempotency`（分区表无法保留现唯一键） |
| 同批优化 | 真实多行批插、列表 count 收敛、`occurred_at` NOT NULL + 索引收敛 |
| 保留 | 热存 **24 个月**，超期冷归档后才 `DROP PARTITION` |
| 冷归档 | **本阶段只做归档清单**：标记 `ARCHIVED` + 从可查范围排除，**不投递、不删分区**；投递与删除待后续安排 |
| 合规底线 | 网络日志留存 ≥ 6 个月（24 个月远高于底线）；归档与删除动作本身写审计 |

## 1. 现状事实（改造前基线）

`iam_audit_log`（库 `gv_saas`，Flyway history `flyway_schema_history_audit`）：

- 24 列，含 `detail_json`（JSON，脱敏后 ≤ 8192 字符）与 `user_agent varchar(512)`；**8 个索引**。
- 幂等：`UNIQUE (tenant_id, idempotency_key)` + 捕获 `DuplicateKeyException` 反查既有记录
  （`AuditLogRepositoryImpl.saveIfAbsent`）；幂等键三档派生见 `AuditLogApplicationService.idempotencyKey`。
- 写：只增不改。`writeBatch` 实为**逐条单行 INSERT**（同一事务内循环），200 条 = 200 次往返。
- 读：单表 `LambdaQueryWrapper`；**每次列表查询先 `COUNT(*)`**；时间过滤按 `occurred_at`（NULL 回退 `created_at`）；
  排序 `ORDER BY COALESCE(occurred_at, created_at) DESC, id DESC`；分页用 `LIMIT offset,size`（无分页插件）。
- 运维：无清理、无归档、无预建分区；audit 服务内无任何 `@Scheduled`。
- 单测约束：`AuditOccurredAtMigrationTest` 会把迁移 SQL 放进 **H2（MODE=MySQL）真跑**；
  `AuditLogRepositoryH2QueryTest` 手工建表跑真 SQL。**H2 不支持分区 DDL**，因此：
  ① 分区/保留类 MySQL 专有 DDL 只能存在于 Flyway 迁移中，不进 H2 用例；
  ② 仓储层生成的查询 SQL 必须保持 H2/MySQL 双端可执行（只用普通比较谓词，不用 MySQL 专有函数）。

## 2. 目标结构

### 2.1 `gv_audit.iam_audit_log`（唯一逻辑表，代码侧表名不变）

列与现状一致，仅两处语义收紧：

- `occurred_at datetime(3) NOT NULL COMMENT '业务发生时间（服务端收敛，不接受空值）'`
  —— V3 已回填历史 NULL、写路径已保证非空，此后排序与时间过滤收敛到**单列**，索引可用、不再需要 `COALESCE`。
- `idempotency_key` 保留（用于排障与人工对账），但**不再承担唯一性**（唯一性在台账表）。

主键与分区：

```sql
ALTER TABLE iam_audit_log
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (id, occurred_at);          -- 分区表要求：唯一索引（含主键）必须包含分区列
ALTER TABLE iam_audit_log
  PARTITION BY RANGE COLUMNS(occurred_at) (
    PARTITION pmin    VALUES LESS THAN ('2025-01-01'),   -- 兜底：异常早的时间
    PARTITION p202601 VALUES LESS THAN ('2026-02-01'),
    ...
    PARTITION pmax    VALUES LESS THAN (MAXVALUE)        -- 兜底：任何时间都写得进去
  );
```

> `id` 仍是 `AUTO_INCREMENT`，全局唯一；`selectById` 仍按 `WHERE id = ?` 命中（复合主键只是让 MySQL 接受分区）。

### 2.2 `gv_audit.iam_audit_idempotency`（幂等台账）

```sql
CREATE TABLE iam_audit_idempotency (
  tenant_id       bigint unsigned NOT NULL COMMENT '租户ID（0=平台级）',
  idempotency_key varchar(128)    NOT NULL COMMENT '幂等键（口径同 AuditLogApplicationService）',
  audit_id        bigint unsigned NOT NULL COMMENT '命中的审计记录ID',
  created_at      datetime(3)     NOT NULL COMMENT '首次写入时间（清理依据）',
  PRIMARY KEY (tenant_id, idempotency_key),
  KEY idx_iam_audit_idem_created (created_at) COMMENT '按时间清理（幂等只需短窗口）'
) COMMENT='审计写入幂等台账：唯一性职责从分区主表迁出';
```

- 写入顺序：`INSERT IGNORE` 台账 → 受影响行数 0 判为重复（不再写主表，返回既有 `audit_id`）→ 1 才写主表。
- 清理：保留窗口（建议 7 天，可配）后批量删除；幂等只对「重试窗口内的重复上报」负责。

### 2.3 `gv_audit.iam_audit_archive_manifest`（归档清单，合规证据）

```sql
CREATE TABLE iam_audit_archive_manifest (
  id            bigint unsigned NOT NULL AUTO_INCREMENT,
  period        char(7)         NOT NULL COMMENT '归档月份 YYYY-MM',
  partition_name varchar(64)    NOT NULL COMMENT '被归档/删除的分区名',
  object_path   varchar(512)    NOT NULL COMMENT '归档对象路径（目录或对象键）',
  row_count     bigint unsigned NOT NULL COMMENT '归档行数（导出时统计）',
  checksum_sha256 char(64)      NOT NULL COMMENT '归档文件校验和（校验通过才允许删除）',
  state         varchar(16)     NOT NULL COMMENT 'ARCHIVED/DROPPED/HELD',
  operator_id   bigint unsigned NULL,
  created_at    datetime(3)     NOT NULL,
  dropped_at    datetime(3)     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_iam_audit_archive_period (period, partition_name)
) COMMENT='审计冷归档清单：归档校验与删除的可追溯证据';
```

## 3. 取舍记录（为什么是「原生月分区」而不是「物理月表」）

| 维度 | 原生 RANGE 分区（采用） | 物理月表 `iam_audit_log_YYYYMM`（未采用） |
| --- | --- | --- |
| 查询代码 | **零改动**：表名不变，`LambdaQueryWrapper` / `count` / `LIMIT` 全部照用 | 跨月需 UNION + 跨月 count/分页；或引入动态表名 |
| 依赖边界 | 无新依赖 | 需 `DynamicTableNameInnerInterceptor`（jsqlparser），与 21_PERSISTENCE_STACK「业务模块禁止直接声明 MP 底层组件」冲突 |
| 写入失败风险 | `pmax` 兜底，**任何时间都写得进去** | 月表不存在即写入失败 → **审计丢失**（合规事故） |
| 清理 | `DROP PARTITION` 秒级 | `DROP TABLE` 秒级（同等） |
| 冷月单独压缩/迁移 | 需 per-partition tablespace 才等价 | 天然支持 |
| 迁移成本 | 需重建表（见 §5 批次 3） | 不需动现有表 |

结论：在本仓「查询链路不动 + 绝不出现写不进审计的时刻」这两条硬约束下，原生分区胜出；物理月表唯一的优势（冷月单独处理）用
`innodb_file_per_table` + per-partition tablespace 与归档清单补回。

## 4. 三个同批优化

1. **真实多行批插**：`writeBatch` 由「循环单插」改为一条 `INSERT INTO ... VALUES (),(),...`（分片 ≤ 200 行/批）。
   台账先按已知 ID 批量抢占，再对未重复的记录批量插入主表。连接串追加 `rewriteBatchedStatements=true`。
   （依赖批次 2b 的应用侧 ID：插入前就要知道主键，否则回不了逐条 ID、也没法回填台账。）
2. **列表 count 收敛**（**已实现**）：列表接口默认仍返回精确 `total`（兼容现状）；新增 `skipCount` 契约——第 2 页起由前端回传，
   后端跳过 `COUNT(*)` 并回 `total = -1`，前端沿用上一页的 total 展示。另加 `countCap`（默认 100000）：
   超过上限时返回 `total = countCap` + `totalCapped = true`，前端显示「10万+」。
   `COUNT(*)` 的实现改为 `SELECT COUNT(*) FROM (SELECT 1 ... LIMIT cap) t`——LIMIT 必须在**聚合之前**，
   才能让超大结果集提前停止扫描（`last("LIMIT n")` 会被拼到聚合结果之后，对 COUNT 无效）。
3. **`occurred_at` NOT NULL + 索引收敛**（**已实现**，落在批次 2c 的建表 DDL，不在旧表上重复动 DDL）：
   - `occurred_at` 收紧为 NOT NULL，排序与时间过滤收敛到单列，去掉 `COALESCE` 表达式（它无法走索引）；
   - 索引的时间列由 `created_at` 对齐到 `occurred_at`——这是**功能性修复**而非单纯清理：
     改造前的索引建在 created_at 上，而真实查询谓词、排序都用 occurred_at，这些索引基本用不上；
   - 只删可证明的重复项 `idx_iam_audit_query(tenant_id, action, created_at)`（与
     `(tenant_id, action, occurred_at)` 完全同义）；
   - `idx_iam_audit_action_time` / `idx_iam_audit_result_time` 暂时保留（分别服务「操作类型筛选」
     与「只看失败」两条真实查询路径），**是否进一步删减以真实慢日志 + EXPLAIN 复核为准**，
     需要时另开迁移（已执行脚本不可改）。

## 5. 分批次实施（每批可独立发布、可独立回退）

### 批次 0：容量与查询画像（不改代码）

先跑（结果决定分区粒度与保留窗口是否需调整、以及索引收敛幅度）：

```sql
SELECT table_rows, ROUND(data_length/1024/1024,1) data_mb, ROUND(index_length/1024/1024,1) index_mb
FROM information_schema.tables WHERE table_schema='gv_saas' AND table_name='iam_audit_log';

SELECT DATE_FORMAT(COALESCE(occurred_at,created_at),'%Y-%m') m, COUNT(*) rows_cnt,
       ROUND(AVG(LENGTH(detail_json))) avg_detail, ROUND(AVG(LENGTH(user_agent))) avg_ua
FROM gv_saas.iam_audit_log GROUP BY m ORDER BY m DESC LIMIT 24;
```

验收：拿到「行/月、detail 均值、索引占比」三个数。**若行/月 > 2000 万，分区粒度降为半月或周**（本文其余部分不变）。

### 批次 1：查询成本（后端 + 前端，不动表）

- 后端：`AuditQueryRequest` 增 `skipCount` / `countCap`；`AuditPageView` 增 `totalCapped`；仓储 `count` 改带上界。
- 前端：`gv_saas_admin/src/views/tenant/audits.vue` 翻页时回传 `skipCount` 并沿用上一页总数；`totalCapped` 显示「10万+」。
- 回退：纯增量参数，缺省行为与现状完全一致，回退只需回滚镜像。

### 批次 2a：幂等台账（**已实现**，不动表结构以外的东西）

先解掉「分区表唯一键」这个阻塞项，且这一步本身就能独立验证（H2 真 SQL 覆盖）：

- `V4__audit_idempotency_ledger.sql`：`iam_audit_idempotency(tenant_id, idempotency_key, audit_id, created_at)`，
  主键即幂等唯一键；`created_at` 单列索引供按时间清理。
- `AuditLogRepositoryImpl.saveIfAbsent` 改为**台账优先**：`INSERT ... SELECT ... WHERE NOT EXISTS` + 受影响行数判定
  （顺序重试走「插 0 行」，不依赖异常；并发抢同键仍由主键兜底抛 `DuplicateKeyException`）。
  抢到才写主表并回填 `audit_id`；台账有键但 `audit_id` 为空时**按未写入补写**（审计宁可重复不可丢失）。
- 这一步对现网是**纯增量**：主表原唯一键仍在，语义与改造前一致，可单独发布、可单独回退。

### 批次 2b：真实多行批插（**已实现**）

`writeBatch` 原本是「循环单插」，200 条 = 200 次往返；审计主表是高频写入大表，批量路径的成本几乎全在这里。
改成一条多行 `INSERT` 需要**插入前就知道 ID**（否则回不了逐条 ID、也没法在抢占台账时写记录 ID）：

- 主键改为应用侧生成：`AuditIdGenerator`（复用 `sdk/common` 的 `SnowflakeIdGenerator`，不新增依赖），
  `@TableId(type = IdType.INPUT)`；`CommonAuditApplication` 显式 `@Import(SnowflakeIdGenerator.class)`
  （它在 `sdk/common`，不在本服务扫描包内）。
- **台账在抢占时就写入 audit_id**（主键抢占前已知），因此去掉了原来「先插主表再回填台账」的第二步——
  每条记录少一次 UPDATE；`audit_id` 仍允许 NULL，只为历史残留兜底（恢复路径补写主表后回填，只补空值）。
- 批内：台账逐条抢占（小表，且需要逐条的首次/重复判定），**主表一次多行插入**；
  超过 `MAX_INSERT_CHUNK=200` 分片，避免超长 SQL。
- 不引入 JDBC 批处理（`rewriteBatchedStatements`）：那需要 BatchExecutor，与 MyBatis-Plus 默认 SIMPLE 执行器
  混用会绕过 Spring 事务的会话管理；多行字面量更直接。
- **顺带修掉一个真实缺陷**：多行 INSERT 会把所有列显式写出来，而数据库默认值只在「不指定该列」时生效——
  显式写 NULL 会直接 1048。单条路径过去靠 MyBatis-Plus 跳过 null 字段间接吃到默认值，
  于是会出现「单条能写、批量报错」。现在 `toPo` 统一补齐 NOT NULL 列的缺省值（与迁移脚本的列默认值一一对应），
  两条路径口径一致。

### 批次 2c：独立 schema + 分区表（**已实现**）

- 新增库 `gv_audit`；**用 profile 做原子切换**：默认（无 profile）连老库 `gv_saas` + 老迁移目录；
  加 `SPRING_PROFILES_ACTIVE=audit-schema` 后 `application-audit-schema.yml` 同时把库名换成 `gv_audit`
  （可用 `AUDIT_DATABASE` 覆盖）与迁移目录换成 `classpath:db/migration-audit`。**去掉 profile 即回退**，
  代码与镜像都不用动。
  为什么库名与迁移目录必须一起切：新库物理设计已变（老库是普通表 + 唯一键幂等，新库是分区主表 + 幂等台账），
  两个目录的版本号互不复用——混用会出现两种都失败的状态（详见下一条）。
- **两个迁移目录物理隔离**（迁移规范允许「确认不存在历史库时重建 V1 基线」，新库正是这种情况）：
  - `db/migration`：老库历史，冻结在 V1–V4（V1 建表、V2 加列/索引、V3 回填、V4 幂等台账）；
  - `db/migration-audit`：**新库的全新基线** —— V1 幂等台账、V2 分区主表、V3 归档清单。
  - 若把分区主表 DDL 放进老目录，老库部署会对着已存在的普通表再 `CREATE TABLE`（1050）直接失败；
    新库首次迁移则会被老库 V1 先建成普通表、随后被分区 DDL 撞上。这条已用静态守卫用例钉住。
- 分区主表（`migration-audit/V2`）：`PARTITION BY RANGE COLUMNS(occurred_at)`，
  `pmin` + 2024-09…2026-12 月分区 + `pmax`；`occurred_at` 收紧为 `NOT NULL`；主键改 `(id, occurred_at)`；
  索引的时间列由 `created_at` **对齐到 `occurred_at`**（改造前的索引建在 created_at 上，
  而真实查询谓词与排序都用 occurred_at，等于用不上），并删掉与 `(tenant_id, action, occurred_at)` 同义的
  `idx_iam_audit_query`（8 → 7；`result`/`action` 上是否还要独立索引，留待 EXPLAIN 复核后另开迁移删）。
- 归档清单（`migration-audit/V3`）：`iam_audit_archive_manifest`（§2.3）。
- **发布顺序硬约束**：先跑 `scripts/migration/audit-schema-bootstrap.sql` 建库并授权（含 `gv_saas.tnt_tenant`
  只读），再带 `audit-schema` profile 发布；否则服务连不上 `gv_audit`（启动失败）。
- 切换后不立即回填的窗口：列表只显示切库后的新记录，批次 3 在分钟级内补齐（见下）。
- DDL 无法在 H2 验证（H2 不支持分区），因此两条腿都要有：
  ① `scripts/verify/audit-partition-verify.sql`（真实 MySQL 上跑：分区清单、唯一索引不含分区列的行数=0、
     时间范围 `EXPLAIN` 只命中目标分区、`pmax` 可写并可回收探针行、台账唯一键在位）；
  ② `AuditPartitionInvariantTest`（静态守卫，进单测）：断言按 `occurred_at` 分区、`pmin`/`pmax` 存在、
     **每个唯一索引都含分区列**、幂等唯一键只在台账表上、两个迁移目录不得混用。
     已用「注入违规唯一键」反向验证过它会失败。
- 跨库读取：`TenantNameMapper` 的 `tnt_tenant` 显式限定为 `gv_saas.tnt_tenant`（不能再依赖连接默认库）。
- 建库授权落地位置：**本地 Kind 零手工**（`k8s/local/mysql-saas-init-job.yaml` 已在建库阶段
  一并创建 `gv_audit` 并授权，该 Job 可重复执行）；
  **ACK 走运维受控步骤**（§8-5，执行 `scripts/migration/audit-schema-bootstrap.sql`，不新增 k8s 资源）；
  其它环境（原生 MySQL 等）同样手工执行该脚本一次。
- 回退：新库与新表独立存在，旧库旧表不动；去掉 `audit-schema` profile（库名与迁移目录同时切回）即回到现状。

### 批次 3：回填（**已实现**，脚本 `scripts/migration/audit-backfill-to-gv-audit.sql`）

脚本按仓内既有迁移脚本约定组织（`[Backfill]` / `[Verify]` / `[Switch]` / `[Rollback]` + `__占位变量__`）：

1. [Backfill] 按月循环执行 `INSERT INTO gv_audit... SELECT ... FROM gv_saas...`（跨库同实例），
   每次一条语句、一份可控大小的事务——**不要一条语句搬完所有历史**（长事务顶爆 undo 与主从延迟）；
2. 幂等靠新表主键 `(id, occurred_at)`：重复执行同一月份不会重复落库；
   冲突处理用 `ON DUPLICATE KEY UPDATE id = id`（显式无操作，只忽略主键重复），
   **不用 `INSERT IGNORE`**——那会把 NOT NULL 之类的真实错误一起吞掉；
3. `occurred_at` 用 `COALESCE(occurred_at, created_at)` 兜底（新表该列 NOT NULL 且是分区键），
   其余 NOT NULL 列同样显式 COALESCE，避免历史上被写入过 NULL 的行搬不过去；
4. **只回填 ≥ 保留下限（热存 24 个月）的月份**：更早的记录留在旧表，不搬进新表——
   否则它们会落进 `pmin` 而永远无法随保留策略清理；
5. [Verify] 逐月对账（两库同区间行数一致）+ 抽样比对关键列 + `pmin`/`pmax` 非空体检；
6. [Switch] 不是本脚本的动作：单写切换由 `audit-schema` profile 一次性完成，
   因此**不需要写入冻结窗口**（回填只读旧表）；
7. [Rollback] 去掉 profile 即回退；旧表在回退窗口内不得改动；
   回退前落在新表的写入会被记录在变更单上，必要时按 id 反向补回旧表。

### 批次 4：保留与归档清单（**已实现**；投递与删除刻意留到后续）

`AuditRetentionJob`（`@EnableScheduling` + `@Scheduled(fixedDelayString = "${audit.retention.interval-ms:3600000}")`，
沿用仓内既有的调度范式）每轮执行四步，全部幂等、可重复运行：

1. **预建分区**：当前月 + 未来 `pre-create-months`（默认 2）个月，缺则 `REORGANIZE PARTITION pmax INTO (...)`
   —— 保留 `pmax` 是为了**任何时间都写得进去**（新月份没预建时数据落 pmax，而不是写入失败丢审计）；
   预建失败只记 ERROR 不抛异常：保留任务绝不能反过来影响审计写入。
2. **兜底分区巡检**：`pmin`/`pmax` 非空即 WARN —— 落在兜底分区的记录**不会参与保留策略**，
   属于审计口径问题，必须让人看见而不是悄悄堆积。
3. **归档清单登记**：早于热存窗口（默认 24 个月）的**已有月分区**登记 `ARCHIVED` 并移出可查范围
   （列表查询的左边界收敛到 `retentionFloor`，并在响应里显式回执，见 §2.1 后的查询侧说明）；
   本阶段**不投递对象存储、不 DROP PARTITION**。
   候选集由「现有分区」推导而不是穷举历史月份：没建过分区的月份不可能有数据，穷举只会产生空清单行。
4. **幂等台账清理**：按 `idempotency-days`（默认 7 天）删除过期幂等键——幂等只需覆盖重试窗口。
   刻意不加 `LIMIT`（MySQL/H2 行为不一致），靠「按小时运行」让单次删除量自然有界。

**法律保留**：`audit.retention.hold-periods`（逗号分隔 `YYYY-MM`）命中的月份只登记 `HELD`，不归档、不删除、仍可查。

**自留审计**：归档登记与分区预建各写一条审计（`audit.retention.archive` / `audit.retention.partition.add`），
走与其它服务**同一条** `AuditClient` 上报通道（因此 `common-audit-service` 自己也要声明
`app.audit-service.base-url`——这一条是被仓内守护用例 `AuditBaseUrlConfiguredTest` 拦出来才发现的，
否则审计服务自身的留痕会静默发往 `localhost` 而丢失）。

**本阶段刻意不做（后续单独批次）**：
- 冷归档**投递**（导出 `jsonl.gz` + SHA-256 到归档目录/对象存储）与清单 `object_path`/`checksum_sha256` 回填；
- `DROP PARTITION` 与清单置 `DROPPED`：硬约束是「清单存在 + 校验和复核通过」，且需要投递先落地；
- 因此清单里 `objectPath`/`checksumSha256` 目前恒为空、`state` 只会是 `ARCHIVED`/`HELD`。

**实现要点（可回归）**：
- 决策与执行分离：`AuditRetentionPolicy` 是纯逻辑（窗口/保留/预建/下限），
  `AuditRetentionPort` 承担 MySQL 专有副作用（`REORGANIZE`、`information_schema` 读分区、台账清理）——
  H2 跑不了分区 DDL，这样保留策略的回归不依赖数据库方言。
- 分区名与边界由 `AuditPartitionSpec` 生成并**严格校验**（只可能是 `p\d{6}` 与合法日期字面量）：
  DDL 无法使用参数占位符，因此从源头排除拼接注入。
- 全部按 **UTC** 计算自然月：JDBC 连接固定 `serverTimezone=UTC`，分区边界也用 UTC 字面量，三者同口径。

### 批次 5：基础设施与文档（**已实现**）

- **k8s/local（本地主路径，Kind）**：`mysql-saas-init-job.yaml` 已扩展建库授权（创建 `gv_audit` + 授权，
  可重复执行），**本地无需任何手工建库步骤**。按决策不再以 Compose 作为本地路径。
- **k8s/ack**：按决策 B 走**运维受控步骤**（执行 `audit-schema-bootstrap.sql`，不新增 k8s 资源，见 §8-5）。
- **文档**：`docs/im-services/audit/README.md` 增「存储与保留 / 部署与切换 / 告警」；
  `.env.example` 增 `[1.1]` 段（`audit-schema` profile、保留策略键、自留审计上报地址）。
- **验证入口**：`scripts/validate/invoke-engineering-validation.ps1 -Scope Changed` 全绿（16 项门禁）；
  分区 DDL 用 `scripts/verify/AuditPartitionDdlCheck.java` 在真实 MySQL 上跑（H2 不覆盖分区 DDL），
  配套 `verify-audit-storage.ps1`（9 组断言）与 `audit-partition-verify.sql`。

## 6. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 直接在旧表上 `ALTER ... PARTITION BY` | 表重建期审计写入中断 = 合规风险 | 新建独立 schema 的分区表，靠环境变量切流量；**不做原地 DDL** |
| 发布顺序颠倒（先切库名后建库/授权） | 服务起不来，或 Flyway 把分区表建到 `gv_saas`（与旧表同名直接失败） | 建库授权作为显式前置步骤（`audit-schema-bootstrap.sql`），并在方案与脚本注释里写明顺序 |
| 唯一键限制被忽略 | 幂等静默失效，双写变两条 | 幂等迁台账（§2.2）+「重复上报只留一条」回归用例 + 静态守卫用例（已反向验证会失败） |
| `occurred_at` 脏值（未来/极早） | 记录钉在 `pmax`/`pmin` 永不清理 | 服务端收敛（未来 ≤ now+5min 否则 clamp 至 now；早于保留窗口按原值但记告警）+ `pmax` 监控告警 |
| 跨库读 `tnt_tenant` 授权失败 | 列表租户名为空 | 名称只影响展示（现有代码已容错 WARN），授权含在 bootstrap 脚本里 |
| 切换瞬间历史记录暂不可见 | 列表只显示切库后的新记录 | 切库与回填同一发布窗口内完成（分钟级）；回填保留原 `id`，主键天然幂等、可重跑 |
| 归档未完成即删除 | 数据永久丢失 | 本阶段只写清单不删分区；将来删除前强制校验清单 + SHA-256 复核；有 `HELD` 机制 |
| H2 单测无法覆盖分区 DDL | 迁移回归盲区 | 静态守卫用例（不可执行部分的**不变量**）+ `scripts/verify` 真实 MySQL 脚本（可执行部分） |

## 7. 验收标准

1. 点菜单/写操作产生的审计记录在**新库新表**可见，`action/label/resource/result/操作人/租户` 字段与改造前一致。
2. 重复上报（同 `requestId` 双写）在新库仍只留一条（台账幂等回归用例，已实现）。
3. 列表时间过滤跨月查询走**分区裁剪**（`EXPLAIN` 的 `partitions` 列只命中目标分区）——
   由 `scripts/verify/audit-partition-verify.sql` 在真实 MySQL 上验收。
4. 200 条批量上报的 SQL 往返为 O(1) 批（不再是 200 次单插）——批次 2b。
5. 第 2 页起的列表查询不再执行 `COUNT(*)`（已实现）。
6. 超过 24 个月的月份：**本阶段**在归档清单里登记 `ARCHIVED`（`objectPath`/`checksum` 为空）并被移出可查范围
   （列表响应回执 `retentionFloor`）；法律保留月份只登记 `HELD` 且仍可查。
   投递与 `DROP PARTITION` 落地后，再要求「SHA-256 校验通过才删 + 归档/删除动作各有一条审计记录」。
7. `pmin`/`pmax` 非空能被巡检发现（WARN），且保留任务失败不影响审计写入。
8. `invoke-engineering-validation.ps1 -Scope Changed` 全绿。

## 8. 决策与待确认项

已定（本次评审）：

1. **新库名**：`gv_audit`。
2. **冷归档**：本阶段只做归档清单（标记 `ARCHIVED` + 移出可查范围），不投递对象存储、不删分区。
3. **切换方式**：改用 profile 切库（`audit-schema`），不再需要 `RENAME` 与写入冻结窗口。
4. **本地环境统一用 Kind，不再用 Compose**：验证与运维步骤都以 Kind（`k8s/local`）为准
   （Kind 的 `mysql-saas-init` Job 已在建库阶段一并创建 `gv_audit`，因此 Kind **不需要**手工建库）。
5. **ACK 建库路径选 B：运维受控步骤**（不新增 k8s 资源）。
   * ACK 现状：MySQL 是集群内 `mysql` Service（非 RDS），服务用 `MYSQL_HOST=mysql`、`im_user`，
     密码取自 Secret **`gv-im-secret`**（与 `k8s/local` 的 `gv-im-env` **不同名**）；
     ACK 的 MySQL 靠官方 entrypoint 的 `MYSQL_DATABASE` 建库授权，**只在空数据卷首次初始化时执行一次**，
     所以**已有 ACK 环境里 `gv_audit` 一定不存在**；
   * 落地步骤：把 `scripts/migration/audit-schema-bootstrap.sql` 里的 `__DB_USERNAME__` 换成 `im_user`，
     在 ACK 的 MySQL 上执行一次（第二条 `GRANT SELECT ON gv_saas.tnt_tenant` 在 ACK 可跳过，
     `im_user` 对 `gv_saas.*` 已是全量授权），**再**带 `audit-schema` profile 发布；
   * 备选（本次未采用）：新增一次性 init Job——需要在 `k8s/ack/` 新增资源，按决策不做。

## 9. 实施进度（编号跟踪）

状态图例：✅ 已完成并验证 ｜ ⏸ 按你的决定不做 ｜ ⛔ 待生产数据（本地库已能跑通口径，真实体量需生产/准生产）

**总进度：15 / 17 完成**；另 1 项按决定关闭（第 13 项，冷归档）；
仅剩 1 项待生产数据（第 14 项容量实测——口径与脚本已就绪，Kind 全量回归与 ACK 发布均已完成，见 §9.4）。

| # | 事项 | 对应批次 | 状态 | 证据 / 产物 |
| --- | --- | --- | --- | --- |
| 1 | 列表 count 收敛（`skipCount` + `countCap`，后端 + 前端） | 1 | ✅ | `AuditQueryApplicationService` / `AuditPageView.totalCapped` / `gv_saas_admin` 审计页；用例 17 条 |
| 2 | 幂等台账 `iam_audit_idempotency` + 台账优先写入 | 2a | ✅ | `db/migration/V4` + `AuditLogRepositoryImpl.saveIfAbsent`；`AuditIdempotencyLedgerH2Test` 5 条（真 SQL） |
| 3 | 真实多行批插（应用侧雪花 ID、`IdType.INPUT`、多行 `INSERT`、NOT NULL 缺省值） | 2b | ✅ | `AuditIdGenerator` / `IamAuditLogMapper.insertBatch`；`AuditBatchInsertTest` 6 条（**数 mapper 调用次数**） |
| 4 | 独立 schema `gv_audit`：建库授权 + profile 原子切换 + 两个迁移目录隔离 | 2c | ✅ | `application-audit-schema.yml` / `scripts/migration/audit-schema-bootstrap.sql` / `k8s/local/mysql-saas-init-job.yaml` |
| 5 | 分区主表（`RANGE COLUMNS(occurred_at)` + `pmin`/`pmax` + `occurred_at NOT NULL` + 索引时间列对齐） | 2c | ✅ | `db/migration-audit/V2__audit_partitioned_table.sql`（8 → 7 索引，删同义重复项） |
| 6 | 分区不变量静态守卫 + 真实 MySQL 验收脚本（H2 不支持分区 DDL） | 2c | ✅ | `AuditPartitionInvariantTest` 4 条（**已反向验证会失败**）+ `scripts/verify/audit-partition-verify.sql` |
| 7 | 回填脚本（按月切片、保留原 `id` 幂等、只填 ≥ 保留下限、对账 + 回退） | 3 | ✅ | `scripts/migration/audit-backfill-to-gv-audit.sql`（`[Backfill]/[Verify]/[Switch]/[Rollback]`） |
| 8 | 保留与归档清单（预建分区、`pmin`/`pmax` 巡检、24 月可查下限、法律保留、自留审计） | 4 | ✅ | `AuditRetentionPolicy` / `AuditRetentionApplicationService` / `AuditRetentionJob`；用例 16 条 |
| 9 | 文档与部署说明收尾（审计服务 README、`.env.example`、profile 与自留审计上报地址） | 5 | ✅ | `docs/im-services/audit/README.md`、`.env.example` §1.1 |
| 10 | 归档清单**真库**幂等用例（清单是删除前唯一证据，重复登记不得覆盖行数快照） | 4 | ✅ | `AuditArchiveManifestH2Test` 3 条（H2 真 SQL） |
| 11 | 前端消费 `retentionFloor`（归档月份不能表现为「查不到」）+ 一键验收脚本 | 4/5 | ✅ | `gv_saas_admin` 审计页提示 + `parseAuditListResponse.retentionFloor`（用例 26 条）；`scripts/verify/verify-audit-storage.ps1`（9 组断言） |
| 12 | ACK 建库授权路径：**决策 B（运维受控步骤）** | 5 | ✅ | 决策与落地步骤见 §8-5（`__DB_USERNAME__` 换 `im_user` 后执行 `audit-schema-bootstrap.sql`，再切 profile）；不新增 k8s 资源 |
| 13 | 冷归档投递 + `DROP PARTITION` + 清单置 `DROPPED` | —— | ⏸ 按决定不做 | 你已明确「冷归档先不管」：本阶段清单只登记 `ARCHIVED`/`HELD`，`objectPath`/`checksumSha256` 恒空、不删任何分区 |
| 14 | 上线前容量实测（行/月、detail 均值、索引占比 → 复核分区粒度与窗口） | 0 | ⛔ 待生产数据 | SQL 见 §5 批次 0；**本地 Kind 已能执行该口径**（本轮已在 kind 上跑通全部真库校验），但真实体量只能取生产/准生产库的数，未实测前不改分区粒度 |
| 15 | 索引定稿复核（`action`/`result` 上是否还留独立索引） | 2c 后续 | ✅ | 已在 kind（MySQL 8.0.46，6 万行合成数据/3 个月）跑完 8 种真实查询形态的 `EXPLAIN`：**7 个索引全部被选中、无闲置索引**，见 §9.2；结论是保持现状，进一步删减需生产慢日志 |
| 16 | Kind 全量回归（同一份发布清单）→ 暴露并修复两处真实缺陷 | 5 | ✅ | `deploy-k8s.ps1` 全量 24 服务 + `k8s-scoped.ps1` 5 目标增量，均按 digest 校验通过；修复启动即 CrashLoop 的 Bean 装配缺陷与自留审计 hairpin 缺陷，见 §9.4 |
| 17 | ACK 发布：建库授权 → 切 `audit-schema` → 回填历史 | 5 | ✅ | ACK 真库 Flyway v3、30 分区、清单与自留审计落库；旧表 21 行回填新表并逐 `id` 对账一致，见 §9.4 |

### 9.1 验证证据（每轮回归）

```text
common-audit-service  90 tests  BUILD SUCCESS
  AuditPartitionInvariantTest 4       分区列必在唯一索引内 + 两个迁移目录不得混用
  AuditBatchInsertTest 6              多行批插语句级契约（防回退成循环单插）
  AuditIdempotencyLedgerH2Test 5      重复只留一条 / 跨租户不误合并 / 中断残留补写（H2 真 SQL）
  AuditArchiveManifestH2Test 3        清单幂等且不覆盖首次行数快照 / HELD 不进可查下限（H2 真 SQL）
  AuditRetentionPolicyTest 8          窗口边界 / 预建范围 / 保留豁免 / 下限推导
  AuditRetentionApplicationServiceTest 8  不重复建 / 补缺 / pmin·pmax 巡检 / 归档 / 保留 / 幂等清理
  AuditQueryApplicationServiceTest 17 含归档月份被下限排除、区间不被扩大、无归档不设下限
  AuditLogRepositoryH2QueryTest 4     时间过滤与带上界 count（H2 真 SQL）
gv_saas_admin         audit utils 26 tests  passed（含 retentionFloor 归一化）
工程验证 -Scope Changed  16/16 门禁全绿
反向验证               注入违规唯一键 → 分区不变量用例如期失败 → 已还原
```

### 9.1.1 环境相关的验收入口（本地统一 Kind）

| 脚本 | 覆盖 | 用法（**本地统一 Kind**） |
| --- | --- | --- |
| `scripts/verify/AuditPartitionDdlCheck.java` | **真库跑 DDL**（22 项断言，本轮已在 MySQL 8.0.46 上执行通过）：三份迁移脚本能在 MySQL 8 执行、`RANGE COLUMNS(occurred_at)` 与 `pmin`/`pmax`、主键与唯一索引含分区列、台账唯一键、`occurred_at NOT NULL`（写 NULL 被拒）、`REORGANIZE pmax` 追加月分区、远未来值落 `pmax` 不报 1526、单月区间 `EXPLAIN` 只命中该分区、多行 INSERT 与两处「不存在才插入」SQL 的真实语义 | `kubectl -n gv-im-local port-forward svc/mysql 13306:3306` 后按文件头命令跑；只在**带后缀的临时表**上跑，结束自动清理，绝不触碰真实表 |
| `scripts/verify/verify-audit-storage.ps1` | 9 组断言：schema 存在、`RANGE COLUMNS(occurred_at)`、`pmin`/`pmax` 存在、**唯一索引都含分区列**、`occurred_at NOT NULL`、台账唯一键、清单表存在、兜底分区为空、当月分区已预建 | 同样先 `port-forward`，再 `powershell -File scripts/verify/verify-audit-storage.ps1 -User root -Password <DB_PASSWORD>` |
| `scripts/verify/audit-partition-verify.sql` | 分区清单、唯一索引违规行数、时间范围 `EXPLAIN` 的 `partitions` 列、`pmax` 可写探针、台账唯一键 | 手工执行：`mysql -h 127.0.0.1 -P 13306 -uroot -p < scripts/verify/audit-partition-verify.sql` |

> **环境阻塞已解除（2026-09 记录）**：Docker Desktop 启动后，已按项目自己的 Kind 配置建好 `gv-im-local`
> 并只起 MySQL + 跑初始化 Job，DDL 校验 22/22 通过（见 §9.1.2）。
> 注意本机 `127.0.0.1:3306` 上另有一个**原生 Windows MySQL 8.4 服务**（`MySQL84`），它不是本项目实例、
> 也不接受本项目凭据——本方案的所有真库操作都只走 Kind 的 `port-forward 13306`。

### 9.1.2 真库执行结果（Kind + MySQL 8.0.46，本轮完成）

按项目自己的 Kind 配置建了 `gv-im-local` 集群（`k8s/kind/local-cluster.yaml`：1 control-plane + 1 worker），
只起了 MySQL（从 `k8s/local/infrastructure.yaml` 抽取 mysql 的 Service+Deployment）并跑了
`k8s/local/mysql-saas-init-job.yaml` —— **该 Job 成功建出 `gv_saas` 与 `gv_audit`**，
这也顺带验证了本方案对 Init Job 的扩展（新增建 `gv_audit` + 授权）在真集群上可用。

随后 `kubectl -n gv-im-local port-forward svc/mysql 13306:3306`，用
`scripts/verify/AuditPartitionDdlCheck.java` 对 `gv_audit` 跑完全部断言，**22 passed / 0 failed**：

```text
server version 8.0.46
V1 / V2 / V3 三份迁移脚本          applied on real MySQL 8
partition method / expression      RANGE COLUMNS / `occurred_at`（30 个分区：pmin .. pmax）
pmin / pmax                        both exist
唯一索引含分区列                    offenders: []        主键: id,occurred_at
台账唯一键                         tenant_id,idempotency_key
occurred_at NOT NULL               IS_NULLABLE=NO，且显式写 NULL 被拒绝
REORGANIZE pmax                    p202701 建出且 pmax 保留
多行 INSERT（批量上报路径）          3 行写入成功
远未来值落 pmax                     而不是报 1526
单月区间 EXPLAIN                    partitions=p202609（只命中目标分区）
台账 / 清单「不存在才插入」           均为 1 then 0（幂等抢占语义正确）
```

> 提示：Windows 控制台若显示中文乱码是代码页问题（不是断言失败），可加 `-Dstdout.encoding=UTF-8`；
> `kubectl port-forward` 在本机会偶发 `lost connection to pod`，断了重开即可。
> 该集群与 MySQL **保留未删**（名字正是项目部署脚本会用的 `gv-im-local`）；
> 如需清理：`kind delete cluster --name gv-im-local`。

### 9.2 索引定稿的 EXPLAIN 证据（第 15 项）

在同一 kind MySQL 上造 6 万行合成数据（3 个月 × 2 万行、5 租户、混合 action、1% FAILURE），
`ANALYZE TABLE` 后对仓储层真实生成的 8 种查询形态取 `EXPLAIN`：

| 查询形态 | 分区裁剪 | 选中的索引 | 估算行 |
| --- | --- | --- | --- |
| `tenant_id` + `occurred_at` 区间 + `ORDER BY occurred_at DESC LIMIT 20` | `p202609` | `idx_iam_audit_time` | 491 |
| `tenant_id` + `action` + `occurred_at` 区间 | `p202609` | `idx_iam_audit_tenant_action_time` | 1993 |
| 仅 `occurred_at` 区间（平台视角） | `p202609` | `idx_iam_audit_time` | 180 |
| `action` + `occurred_at` 区间 | `p202609` | `idx_iam_audit_action_time` | 3323 |
| `result` + `occurred_at` 区间 | `p202609` | `idx_iam_audit_result_time` | 200 |
| `operator_id` + `occurred_at` 区间 | `p202609` | `idx_iam_audit_operator_time` | 997 |
| `resource_type` + `resource_id` | 全分区（无时间谓词，预期） | `idx_iam_audit_resource` | 3 |
| `request_id` | 全分区（无时间谓词，预期） | `idx_iam_audit_request` | 3 |

**结论**：7 个索引全部被真实查询形态选中，**没有闲置索引可删**；时间谓词在 6 种形态下都成功裁剪到单个月分区。
`tenant_id + 时间` 这一形态在小租户占比下由 `(occurred_at)` 承担（省掉一次排序），
`(tenant_id, occurred_at)` 仍服务高选择性租户与跨月大范围扫描——两者并列保留是合理的。

> 诚实边界：合成数据是**均匀分布**，反映不了生产的租户/动作倾斜；因此本证据支持「索引集合可用」，
> 但**不足以**判定某个索引可删——那需要生产慢日志（慢查询日志或 `sys.schema_index_statistics`）。
> 本阶段不动索引集合，符合「避免仅为可能查询建立索引」与「已执行的迁移脚本不可改」两条约束。

### 9.3 尚未闭环的两处「已知边界」（已写进代码注释，不是遗漏）

1. **法律保留月份若早于可查下限**：数据仍在库里（本阶段不删分区），但会落在列表范围之外，可按 ID 查详情。
   保留月通常是最新月份，落在下限之上概率极低；保留服务在遇到该组合时会 WARN，供运维发现。
2. **详情按 ID 查询不受可查下限约束**：下限约束的是**批量列表查询**（扫描成本与留存口径），
   按 ID 的定点查询是审计取证的正当需求，且是 O(1) 读。

### 9.4 Kind 全量回归与 ACK 发布（2026-09-19）

**发布清单**（development 增量发布，`build-saas-release.ps1 -SkipPackage -Targets ...`）：

```text
saas-20260919T043340Z-9b375578.json   平台三服务 + saas-admin（9 目标批次的一部分）
saas-20260919T054303Z-a117f298.json   common-audit-service（含自留审计地址修复）
同一份 5 目标清单（Kind 与 ACK 共用）：common-audit-service / platform-order-service /
platform-resource-service / platform-tenant-service / saas-admin
tag：2.0.1-SNAPSHOT / 2.0.16-SNAPSHOT / 2.0.5-SNAPSHOT / 2.0.9-SNAPSHOT / 2.1.21-SNAPSHOT
全部按 digest 固定（`repo@sha256:...`），部署后逐 Pod 校验 imageID。
```

**Kind 全量回归**（`deploy-k8s.ps1` 全量 24 服务 → `k8s-scoped.ps1` 5 目标增量，均按 digest 校验）：

- 审计服务启动日志：`The following 1 profile is active: "audit-schema"`、
  `Database: jdbc:mysql://mysql:3306/gv_audit`、`Successfully applied 3 migrations ... now at version v3`；
- `gv_audit`：4 张表（Flyway 历史 / 幂等台账 / 归档清单 / 分区主表）、`iam_audit_log` 30 个分区、`pmin`/`pmax` 均 0 行；
- 保留任务真实执行：`REORGANIZE pmax` 预建 p202701…p202710；归档清单登记 2024-09 = `ARCHIVED`；
  自留审计 `audit.retention.partition.add` 正常落库。

**回归暴露并修复的两处真实缺陷**（都已补用例/注释，见 §9.4.1）：

1. 审计服务启动即 `CrashLoopBackOff`：`auditRetentionApplicationService` 构造注入 `AuditClient` 失败——
   提供该 Bean 的 `AuditClientConfig` 不在本服务扫描包下，仓内其它上报服务都显式 `@Import`，只有它漏了；
2. 自留审计全部丢失：把审计服务的上报地址改成集群内 `http://common-audit-service:4190` 后，
   它经 Service ClusterIP 回连自己走 hairpin，JDK HttpClient 抛 `ClosedChannelException`，只留一条 WARN。

#### 9.4.1 两处缺陷的修法与口径

- **Bean 装配**：启动类补 `@Import(AuditClientConfig.class)`；新增 `AuditClientWiringTest` 直接读注解断言，
  不需要拉起 Spring 上下文，避免数据库/中间件不可用时守卫失效（`common-audit-service` 91 条用例）。
- **自留审计地址**：审计服务的上报对象就是它自己，**刻意保持缺省 `http://localhost:4190`**
  （直接进本 Pod 端口，最短且不经过 ClusterIP）；`k8s/ack/saas-common.yaml` 与 `k8s/local/saas.yaml`
  只注入 `SPRING_PROFILES_ACTIVE=audit-schema`，不再注入上报地址，并把「为什么不能用集群地址」写进注释。
  其它业务服务上报的是跨 Pod 调用，仍用集群 DNS 默认值，不受影响。
- **可观测性**：`AuditClient.recordAsync` 失败时改为打印异常链最内层（原实现只打包装异常，
  看不到状态码/根因，正是本轮排查变慢的原因）。

**ACK 发布（命名空间 `im-business`）**：

```text
前置    scripts/migration/audit-schema-bootstrap.sql（__DB_USERNAME__ → im_user）在真库执行：
        gv_audit 建成（utf8mb4_0900_ai_ci），im_user 获 gv_audit.* 全库权限 + gv_saas.tnt_tenant 只读
切换    Deployment 注入 SPRING_PROFILES_ACTIVE=audit-schema（不注入上报地址）
发布    scripts/deploy/ack.ps1 -ReleaseManifestPath <同一份 5 目标清单>
        → ACK release deployed by tag and verified by digest
        （5 个 Deployment 的 spec 与 Pod imageID 均等于清单 digest）
真库    Flyway 在 ACK gv_audit 上 Successfully applied 3 migrations ... now at version v3
        4 张表 / 30 个分区 / pmin·pmax 0 行 / 归档清单 2024-09 = ARCHIVED / 自留审计已落库
回填    [Backfill] 单月 2026-09：旧表 21 行 → 新表 21 行、逐 id 匹配 21 行、新表合计 22 行
回退    删除 SPRING_PROFILES_ACTIVE 并重启即回到 gv_saas；旧表在回退窗口内未做任何改动
```

**回填脚本的两处订正**（`scripts/migration/audit-backfill-to-gv-audit.sql`）：

1. 注释声称幂等，但语句里并没有 `ON DUPLICATE KEY UPDATE` —— 重跑同一月份会直接报 Duplicate entry，已补；
2. 补的时候不能裸写 `id = id`：`INSERT ... SELECT` 的源表有同名列，MySQL 会报 `ERROR 1052 ambiguous`，
   必须写成 `gv_audit.iam_audit_log.id = gv_audit.iam_audit_log.id`（本次在真库上就是这样订正的）。

### 9.5 操作人姓名展示：读时按冗余 `operator_id` 关联（2026-09-19 追加）

**问题（真数据暴露）**：ACK 上线后看真记录，审计列表的操作人显示成 `#1` ——
`iam_audit_log.operator_name` / `operator_account` 都是 NULL（上报方拿不到姓名），
前端按「姓名 → 账号 → `#ID`」兜底就落到了 ID。

**口径**（与租户名称补全完全一致，不引入第二套做法）：

| 决策 | 理由 |
| --- | --- |
| 保留 `operator_id` 作为**冗余关联键**，姓名/登录名在查询时按它关联账号表 | 审计表是高频写入的大表，写入路径不再加一次账号表查询；与 `tenantName` 同一模式 |
| 已存值优先、只在为空时补 | 记录里的姓名是**动作发生当时**的快照，账号改名不该追改历史审计 |
| 关联 `gv_saas.saa_admin_account.platform_account_id` | 租户签名上下文里的 `account_id` 与 `iam_user_role.account_id` 就是这个 id 空间（`ContextController.SelectContextResponse` 亦同） |
| 解析失败只降级 | 账号表跨域只读不可用（缺授权/抖动）时按空值返回 + WARN，列表不因补全失败整体失败 |
| 关键字检索同样走关联 | 否则「按姓名搜索」查不到没存姓名的历史记录：先把关键字在账号表解析成候选 id，再并到 `operator_id` 条件上 |

**证据**：`common-audit-service` 95 条用例全绿（新增 4 条：列表补全、详情补全、补全不可用时列表照常、
仓储层降级；`AuditLogRepositoryH2QueryTest` 用真 SQL 覆盖跨 schema 关联与关键字命中）。
反向验证：注释掉补全调用 → 用例报 `expected: <李四> but was: <null>`（正是「显示成 #ID」的现场），已还原。

**授权**：`scripts/migration/audit-schema-bootstrap.sql` 增加 `GRANT SELECT ON gv_saas.saa_admin_account`
（ACK/Kind 的 `im_user` 对 `gv_saas.*` 已是全量授权，这行是给权限更窄的环境准备的）。
