# common-audit-service

> E0 服务文档基线。职责/端口/入口按当前脚手架实现填写，指标/告警阈值待可观测体系接入后校准。

## 职责

- 审计日志写入（安全敏感 / 合规关键操作留痕）
- 审计查询（管理端）
- 为各领域服务提供审计客户端（`AuditClient`）

## 端口

- 服务直连：`4190`（`server.port`）
- 网关公开入口：`/api/v1/admin/audits/**`

## 入口（controller 路径，不带 /api）

- `/internal/audit/records`（POST 写入审计记录）
- `/admin/audits`（GET 查询）

## 依赖

- MySQL：库 `gv_audit`（**独立 schema**，见下文「存储与保留」），Flyway history 表 `flyway_schema_history_audit`
  - 回退/迁移期间仍可能连旧库 `gv_saas`（同一张 `iam_audit_log`，普通表）
  - 只读跨库：`gv_saas.tnt_tenant`（审计列表回填租户名；单独授予 SELECT）
- Redis：无
- MQ：无

## 存储与保留（方案：`docs/renovation/AUDIT_STORAGE_01_SERVICE.md`）

### 表与分区

| 表 | 作用 | 关键点 |
| --- | --- | --- |
| `iam_audit_log` | 审计主表（只增不改） | **按 `occurred_at` 月分区**（`pmin` + 月分区 + `pmax`），主键 `(id, occurred_at)`，**无唯一键** |
| `iam_audit_idempotency` | 写入幂等台账 | 唯一键 `(tenant_id, idempotency_key)` 在这里；只覆盖重试窗口，按天清理 |
| `iam_audit_archive_manifest` | 冷归档清单 | 归档/删除的**可追溯证据**；本阶段只登记 `ARCHIVED`/`HELD` |

为什么唯一键不在主表：MySQL 要求分区表的每个唯一索引都包含分区列，而幂等键一旦补上时间列，
重试晚 1ms 就不再冲突、**幂等会静默失效**（BFF 拦截器与领域服务双写同一次操作会变两条痕迹）。
分区列选 `occurred_at` 而不是 `created_at`：前端的时间筛选与排序都用它，按 created_at 分区等于无法裁剪分区。

### 保留策略（`audit.retention.*`）

| 配置键 | 默认 | 含义 |
| --- | --- | --- |
| `enabled` | true | 是否启用保留任务 |
| `interval-ms` | 3600000 | 调度间隔（任务幂等，可重复运行） |
| `hot-months` | 24 | 热存月数（含当前月）；更早的月份登记归档并移出可查范围 |
| `pre-create-months` | 2 | 预建未来月数（分区缺失时数据落 `pmax` 而不是写入失败） |
| `idempotency-days` | 7 | 幂等台账保留天数 |
| `hold-periods` | 空 | 法律保留月份（逗号分隔 `YYYY-MM`）：不归档、不删除、仍可查 |

任务每轮：预建分区 → 巡检 `pmin`/`pmax`（非空即 WARN，落在这两个分区的记录不参与保留策略）
→ 登记归档清单并移出可查范围（列表响应回执 `retentionFloor`）→ 清理过期幂等键。
归档登记与分区预建**自留审计**（`audit.retention.archive` / `audit.retention.partition.add`）。

> 本阶段**不投递对象存储、不 `DROP PARTITION`**：清单里 `objectPath`/`checksumSha256` 恒空。
> 投递与删除落地时的硬约束是「清单存在 + SHA-256 复核通过」。

### 操作人姓名的展示口径（读时关联，不写时冗余）

审计表里的操作人身份是三段：`operator_id`（**冗余的关联键**）+ `operator_name` + `operator_account`。
列表与详情返回前按 `operator_id` 到 `gv_saas.saa_admin_account`（关联键 `platform_account_id`）补出姓名与登录名，
所以历史数据即使没存姓名，界面也显示「谁做的」而不是一行 `#1`。

| 规则 | 说明 |
| --- | --- |
| 已存值优先 | 记录里存下的姓名是**动作发生当时的快照**，账号改名不追改历史记录；只在为空时补当前值 |
| 读时关联，不写时冗余 | 与租户名称同一口径：写入路径是高频大表，不为展示再查一次账号表 |
| 失败只降级不失败 | 账号表跨域只读不可用（缺授权/抖动）时按空值返回并 WARN，列表照常返回 |
| 检索同样走关联 | `operatorKeyword` 先在账号表解析成候选 `platform_account_id`（上限 200），再并到 `operator_id` 上，因此「按姓名搜索」能查到没存姓名的历史记录 |

跨 schema 只读需要授权：`GRANT SELECT ON gv_saas.saa_admin_account`（见 `scripts/migration/audit-schema-bootstrap.sql`）。

## 关键指标

- 审计写入吞吐与错误率
- 审计查询时延
- 审计丢弃 / 失败次数（不可抵赖性风险）
- **兜底分区行数**（`pmin`/`pmax`，期望恒为 0：非 0 说明有记录不参与保留策略）

## 告警

- 审计写入失败率升高（合规留痕缺口）
- 审计表增长异常
- 管理端审计查询不可用
- **`pmax` 非空**（月分区预建没跟上）／**`pmin` 非空**（出现异常早的时间）

## 关联 ID

- `X-Tenant-Context`：租户上下文（`tenantId`）
- `requestId`：审计记录请求 ID
- `operatorId` / `resourceType` / `resourceId`：审计主体与对象
- `idempotencyKey`：审计幂等键

## 部署与切换

- **独立 schema 由 profile 原子切换**：默认（无 profile）连 `gv_saas` + `classpath:db/migration`（老库历史）；
  加 `SPRING_PROFILES_ACTIVE=audit-schema` 后连 `gv_audit`（可用 `AUDIT_DATABASE` 覆盖）+ `classpath:db/migration-audit`
  （新库全新基线 V1 台账 / V2 分区主表 / V3 归档清单）。**去掉 profile 即回退**，代码与镜像不用动。
  两个迁移目录**不得混用**（老库 V1 建的是普通表、新库 V2 建的是分区主表，同一版本序列混用必然冲突）。
- **建库授权必须先于切 profile 的发布**，否则服务连不上 `gv_audit` 启动失败：
  - 本地 Kind（`k8s/local`，本地唯一路径，不再用 Compose）：`mysql-saas-init` Job 已在建库阶段
    一并创建 `gv_audit` 并授权，**零手工**；
  - ACK：走**运维受控步骤**——把 `scripts/migration/audit-schema-bootstrap.sql` 里的 `__DB_USERNAME__`
    换成 `im_user`，在 ACK 的 MySQL 上执行一次（ACK 的 Secret 名是 `gv-im-secret`；
    `GRANT SELECT ON gv_saas.tnt_tenant` 可跳过，ACK 的 `im_user` 对 `gv_saas.*` 已是全量授权）。
- **回填**：`scripts/migration/audit-backfill-to-gv-audit.sql`（按月切片、保留原 `id` 幂等、只填 ≥ 保留下限的月份、
  含对账与回退段）。Kind 里从宿主机访问库：`kubectl -n gv-im-local port-forward svc/mysql 13306:3306`。
- **自留审计上报地址**：审计服务自身也用 `AuditClient` 留痕，必须声明
  `app.audit-service.base-url`（本地/集群都可指向自身 `http://localhost:4190`，即在 4190 上监听的自己；
  也可用 `INTERNAL_AUDIT_SERVICE_BASE_URL` 覆盖）。缺这个键会被守护用例 `AuditBaseUrlConfiguredTest` 拦下——
  否则自身留痕会静默发往 localhost 而丢失。

## 故障处置

- 写入失败：核对当前 profile 指向的库、审计表结构与幂等台账
- 查询异常：核对索引与保留策略（`retentionFloor` 是否把区间收敛掉了）
- 保留任务异常：核对 `pmin`/`pmax` 行数与分区清单；任务失败不会影响审计写入
- 丢失风险：核对各服务 `AuditClient` 调用链

## 状态

- 骨架已落库：审计写入 / 查询已实现；未接 springdoc（无 OpenAPI 快照）（见 `SAAS_PLATFORM_07_EXECUTION.md`）
- 存储改造：独立 schema + `occurred_at` 月分区 + 幂等台账 + 多行批插 + 保留/归档清单已实现；
  冷归档投递与 `DROP PARTITION` 待后续批次（进度见 `AUDIT_STORAGE_01_SERVICE.md` §9）
