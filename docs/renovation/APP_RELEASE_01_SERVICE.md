# 客户端版本发布治理重构方案

> 方案集：`APP_RELEASE`；序号：`01`；实施边界：`im-admin-service`、Flyway、Gateway、OpenAPI 与发布任务；状态：设计待实施；后续方案：[App 实施方案](APP_RELEASE_02_APP.md)、[管理后台实施方案](APP_RELEASE_03_ADMIN.md)；长期规则：[客户端发布与版本治理规范](../standards/14_CLIENT_RELEASE_GOVERNANCE.md)。

## 1. 结论与边界

客户端版本发布按**具体操作系统**管理，不按“App”或“PC”这种产品大类管理。首发受管平台固定为：

| 产品分组 | 发布平台 `platform` | 本次启用范围 |
| --- | --- | --- |
| 移动端 | `android`、`ios` | 启用发布、App release-check 与升级链路 |
| PC 客户端 | `windows`、`macos`、`linux` | 仅预留平台枚举、表约束、Admin 展示、API 与 CI manifest；不启用发布或客户端检查，待三个 PC 客户端分别交付后逐个开启 |
| 浏览器、HarmonyOS | `web`、`harmonyos` | 不在本次范围；不得创建发布记录 |

`windows`、`macos`、`linux` 虽可在管理后台归类展示为“PC 客户端”，但每个系统必须拥有独立的版本序列、构建制品、最低支持版本、更新策略、灰度范围和发布记录。业务 REST、WebSocket 与消息同步协议仍保持统一 v1；版本管理只决定客户端是否可继续访问、是否提示/强制升级，以及可使用哪些已定义能力。

后端以 `client-release.enabled-platforms` 管理启用状态，初始值严格为 `android,ios`。五个平台均可解析为 `ReleasePlatform`，但服务端只允许启用平台创建、提交或检查 Release；PC 平台在各自客户端、安装升级演练和分发流水线完成后，按平台独立把开关加入该配置。Admin 必须展示五个平台和“未启用”状态，且服务端（不是前端）拒绝未启用平台的创建/提交/策略变更。

当前 `adm_app_release` 仅记录单一 `versionCode`、发布布尔值和下载地址，不能表达渠道、发布状态、制品完整性、分阶段灰度、撤回、最小兼容版本或审计；并且当前能力尚未正式使用。本方案采用一次性替换，**不保留旧表、旧字段、旧接口或旧响应兼容层**。

## 2. 目标与非目标

### 2.1 目标

1. 将客户端发布从后台 CRUD 升级为可审计的发布治理能力。
2. 同一平台按渠道独立发布 `internal`、`beta`、`stable`，客户端只能命中所属渠道的有效发布。
3. 用单调递增的 `buildNumber` 作机器判定，用 SemVer `version` 作展示；服务端不解析或比较版本字符串。
4. 支持草稿、已排期、灰度发布、全量发布、暂停和撤回；所有状态变化留存不可变审计记录。
5. 在版本检查中同时返回更新决策、强更拦截、制品下载信息、协议兼容快照和服务端时间。
6. 对每一个二进制制品记录 SHA-256、大小、下载地址、签名/公证摘要和有效平台架构，客户端可先校验再安装。

### 2.2 非目标

1. 不按平台拆分聊天、用户、消息、媒体或 RTC 等业务后端。
2. 不把操作系统版本、CPU 架构、设备型号或用户 ID 作为发布平台。它们是发布规则的附加条件。
3. 不提供服务端对客户端代码的热更新，也不下发可执行脚本。
4. 不支持同一 `platform + channel` 的多条重叠灰度规则；复杂实验应由受控 Feature Flag 平台承担。
5. 不将浏览器和 HarmonyOS 偷偷映射到 `linux` 或 `android`；新增平台须走独立评审。

## 3. 统一术语与不可变规则

| 名称 | 含义 | 规则 |
| --- | --- | --- |
| `platform` | 客户端运行操作系统 | 仅 `android/ios/windows/macos/linux` |
| `channel` | 发行渠道 | `internal`、`beta`、`stable`；一台客户端固定一个渠道 |
| `version` | 面向用户的 SemVer | 正式版本为 `MAJOR.MINOR.PATCH`，禁止 `v` 前缀和构建元数据 |
| `buildNumber` | 同一平台、渠道内可排序构建号 | 正整数，发布后不可变，服务端只用它判断新旧 |
| `release` | 某平台、渠道的一个候选发布 | 可关联一个或多个制品，例如 Windows x64/arm64 |
| `artifact` | 可安装的不可变二进制制品 | 以 SHA-256 为身份证，公开分发地址和内容均不可变 |
| `minimumBuildNumber` | 当前仍允许访问业务的最低构建号 | 由独立 `platform + channel` 策略生效，不能高于当前全量发布构建号 |
| `mandatory` | 命中该发布时必须更新 | 只影响比目标版本旧的客户端；不替代最低支持版本 |
| `rolloutPercent` | 灰度比例 | 0~100，按稳定哈希命中；100 才称“全量” |
| `compatibilitySnapshot` | 此 Release 经验证的协议/服务端兼容基线 | 仅表达 `protocolVersion` 和最低服务端能力版本，不能承载动态 Feature Flag |
| `platformEnabled` | 后端允许发布和检查的平台开关 | 初始仅 Android/iOS；PC 三端交付后独立开启，浏览器/HarmonyOS 永不属于该开关 |

同一 `platform + channel` 的 `buildNumber` 必须严格递增，且每个 `buildNumber` 唯一。允许 Android 与 Windows 恰好使用相同数字，但含义彼此独立。桌面架构不是版本号：Windows/macOS 可在同一 Release 下分别挂 `x64`、`arm64` 制品；Linux 首发只允许一个明确的包格式/架构组合，建议 `x64 + Flatpak` 或 `x64 + AppImage` 二选一。

## 4. 领域模型与状态机

### 4.1 聚合

`ClientRelease` 是唯一发布聚合，拥有 Release 基本信息、制品清单、协议兼容快照和状态流转。`ReleaseAuditLog` 是不可变审计实体，只追加，不能通过管理接口修改或删除。

```text
ClientRelease
├── platform + channel + version + buildNumber
├── releaseStatus
├── updatePolicy (mandatory, rolloutPercent, rolloutSalt)
├── distribution (storeUrl?, releaseNotes, publishedAt?, scheduledAt?)
├── artifacts[]
│   ├── targetArchitecture
│   ├── packageType
│   ├── downloadUrl
│   ├── sha256 / sizeBytes
│   └── signingMetadata
└── compatibilitySnapshot

ReleaseAuditLog
├── releaseId
├── action / beforeStatus / afterStatus
├── operatorId / requestId / reason
└── occurredAt / immutable payload digest
```

### 4.2 状态机

```text
DRAFT --submit--> SCHEDULED --publish time reached--> ROLLING_OUT --100%--> RELEASED
  |                   |                                     |                 |
  +--archive----------+--cancel------------------------------+--pause--> PAUSED
                                                              |                 |
                                                              +--withdraw-------+--resume--> ROLLING_OUT
RELEASED --withdraw--> WITHDRAWN
```

排期提交会同时固化初始灰度比例；到点后 `1..99` 进入 `rolling_out`，`100` 直接进入 `released`。图中主线仅展示低于 100 的通常路径。

状态定义：

| 状态 | 是否可被客户端命中 | 可修改内容 | 说明 |
| --- | --- | --- | --- |
| `draft` | 否 | 除 ID 外全部 | 编辑、上传制品元数据、配置协议兼容快照 |
| `scheduled` | 否 | 仅排期时间、说明、尚未发布的制品 | 必须晚于当前时间 |
| `rolling_out` | 是 | 灰度比例只能上调；发布说明可追加 | 1~99% 灰度 |
| `released` | 是 | 仅发布说明追加、最低支持策略单独变更 | 固定为 100% |
| `paused` | 否 | 可恢复为 `rolling_out` 或撤回 | 新检查不命中；已下载制品不删除 |
| `withdrawn` | 否 | 不可恢复 | 安全事故或严重缺陷；必须填写原因 |
| `archived` | 否 | 不可恢复 | 从未发布的草稿/排期取消 |

禁止直接 `DELETE` Release。草稿取消进入 `archived`；已经对任何客户端可见的 Release 只能 `withdrawn`。`published` 布尔字段彻底移除。

### 4.3 发布前置条件

发布到 `rolling_out` 或 `released` 前，服务端必须原子校验：

1. `platform` 已在五平台枚举中且 `platformEnabled=true`，`channel` 合法，`buildNumber` 大于该平台渠道所有既有构建号。
2. `version` 满足 SemVer，发布说明非空，`rolloutPercent` 合法。
3. 至少存在一个有效 Artifact；商店 Artifact 使用 Release 级 `storeUrl`，并须有商店版本/构建号与审核状态证明。
4. 直装 Artifact 的 URL 必须为 HTTPS、域名属于允许的分发域名、`sha256` 为 64 位小写十六进制、`sizeBytes > 0`；`google-play` 与 `app-store` 只允许使用经过校验的 `storeUrl`，不接收直装下载字段。
5. 平台制品规则：Android 为直装 `apk` 或商店 `google-play`；iOS 仅 `app-store`；Windows 为 `msix` 或 `exe`；macOS 为已签名并公证的 `dmg/pkg`；Linux 为首发约定的 `flatpak/appimage`。`aab` 只作为 CI/商店上传产物，不作为客户端直装 Artifact。
6. 发布所需的 `compatibilitySnapshot` 已由服务端能力注册表校验，且其要求的 REST/WS 协议版本均为当前受支持版本。
7. 同一个 `platform + channel` 最多一条处于 `scheduled` 或 `rolling_out` 的活动发布；历史 `released` 记录必须保留。创建新排期、立即发布、恢复灰度前必须先处理旧活动发布，避免定时任务与新发布竞争。
8. 首发阶段所有发布写操作仅允许现有 `ROLE_ADMIN`；所有状态变更携带 `Idempotency-Key` 和变更原因。双人审批/职责分离须在用户权限模型具备细粒度角色后单独设计，不能在当前系统中用前端按钮假装实现。

## 5. 数据设计

不得修改已进入 Flyway 历史的 V1 基线。完整的迁移策略、V2 SQL、前置检查、回滚边界与退役表清理见本节 5.7。依据仓库迁移规范，V2 不得使用 `DROP TABLE`：它将旧 `adm_app_release` 重命名为退役表，并在同一后端提交中创建新表、移除所有旧代码。任何环境执行前必须由部署脚本确认旧表不存在正式发布数据并完成可恢复备份；检查失败时迁移必须中止，不能编辑 V1 或无条件清库绕过 Flyway 校验。

### 5.1 枚举字段与值映射

版本发布的 `platform`、`channel`、`status`、`architecture`、`package_type` 和写操作 `action` 都是本领域拥有的封闭集合，必须使用 MySQL 8 `ENUM`，不能以 `VARCHAR + CHECK` 代替。`mandatory` 是二值开关，使用 `TINYINT(1)`；其余版本号、地址、摘要、时间和说明字段按语义使用普通类型。

| Java 枚举 | 数据表与列 | MySQL 8 类型 | 数据库值 |
| --- | --- | --- | --- |
| `ReleasePlatform` | `adm_client_release.platform`、`adm_client_release_policy.platform` | `ENUM('android','ios','windows','macos','linux')` | `android`、`ios`、`windows`、`macos`、`linux` |
| `ReleaseChannel` | `adm_client_release.channel`、`adm_client_release_policy.channel` | `ENUM('internal','beta','stable')` | `internal`、`beta`、`stable` |
| `ReleaseStatus` | `adm_client_release.status`、审计前后状态 | `ENUM('draft','scheduled','rolling_out','released','paused','withdrawn','archived')` | 与状态机同名小写值 |
| `TargetArchitecture` | `adm_client_release_artifact.architecture` | `ENUM('universal','x64','arm64')` | `universal`、`x64`、`arm64` |
| `PackageType` | `adm_client_release_artifact.package_type` | `ENUM('apk','google-play','app-store','msix','exe','dmg','pkg','flatpak','appimage')` | 与制品规则同名小写值 |
| `ReleaseOperationAction` | `adm_client_release_audit_log.action`、`adm_client_release_operation.action` | `ENUM('create','update','submit','rollout','pause','resume','withdraw','upsert_policy','scheduled_publish')` | 与应用用例/调度动作同名小写值 |

每个 Java 枚举必须携带显式 `databaseValue`，例如 `DRAFT("draft")`，由 TypeHandler 或持久化转换器按该值读写；绝不使用 `ordinal()` 或 MySQL ENUM 内部序号。PO、领域、DTO/OpenAPI 的转换必须显式映射，读取到未知值立即失败并记录脱敏诊断。以后扩展任一枚举时，必须新增前向 Flyway `ALTER TABLE ... MODIFY ... ENUM(...)`，同时提交 Java 枚举、TypeHandler/转换器、OpenAPI、App/Admin 常量和测试；禁止重排或改写已有字面量。

### 5.2 `adm_client_release`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `platform` | enum | 操作系统平台，值见 5.1 |
| `channel` | enum | `internal/beta/stable` |
| `version` | varchar(32) | SemVer 展示版本 |
| `build_number` | bigint | 机器排序版本 |
| `status` | enum | 状态机状态，值见 5.1 |
| `mandatory` | tinyint(1) | 命中该更新时是否强更 |
| `rollout_percent` | tinyint unsigned | 0~100 |
| `rollout_salt` | varchar(64) | 灰度稳定哈希盐 |
| `scheduled_at` | datetime(3) null | 定时发布点 |
| `published_at` | datetime(3) null | 首次对客户端可见时间 |
| `release_notes` | text | 发布说明 |
| `store_url` | varchar(1024) null | 商店安装/更新页 |
| `compatibility_json` | json | 已校验协议/服务端兼容快照 |
| `created_by` / `updated_by` | bigint unsigned | 管理员 |
| `created_at` / `updated_at` | datetime(3) | 审计时间 |
| `row_version` | bigint unsigned | 乐观锁 |

约束与索引：

- `UNIQUE(platform, channel, build_number)`；
- `UNIQUE(platform, channel, version)`；
- `CHECK(build_number > 0)`、`CHECK(rollout_percent BETWEEN 0 AND 100)`；
- 索引 `(platform, channel, status, build_number DESC)`、`(status, scheduled_at)`；
- 最低版本不能依靠数据库 CHECK 关联其他行，由独立策略聚合在事务内验证。
- `active_delivery_guard` 生成列与唯一键保证同一 `platform + channel` 最多一条 `scheduled` 或 `rolling_out` 的活动发布；应用层仍须在事务中先做状态校验。

### 5.3 `adm_client_release_policy`

每个启用最低版本限制的 `platform + channel` 最多一条策略记录，字段为 `platform`、`channel`、`minimum_build_number`、`row_version`、完整审计字段。`UNIQUE(platform, channel)`；`minimum_build_number >= 1`。缺少策略表示尚未设置最低受支持构建号，不能用 `0` 伪造。策略变更与 Release 状态变更一样写入 `adm_client_release_audit_log`，并且只能由独立管理接口提高或在明确紧急回退审批下下调。

### 5.4 `adm_client_release_artifact`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` / `release_id` | bigint | 主键与所属 Release |
| `architecture` | enum | `universal/x64/arm64` |
| `package_type` | enum | `apk/google-play/app-store/msix/exe/dmg/pkg/flatpak/appimage` |
| `download_url` | varchar(1024) | HTTPS 制品地址；商店型制品可为空 |
| `sha256` | char(64) null | 直装包必填 |
| `size_bytes` | bigint null | 直装包必填 |
| `signature_metadata_json` | json null | 签名证书指纹、公证票据等不可执行元数据 |
| `created_at` | datetime(3) | 创建时间 |

`UNIQUE(release_id, architecture, package_type)`；`CHECK(size_bytes IS NULL OR size_bytes > 0)`。Artifact 在 Release 发布后不可修改；首发使用内容不可覆盖的稳定分发地址，CDN 内部迁移不得改变该地址返回的内容摘要。需要更换二进制、摘要、架构或包类型时必须创建新的 Release，不能修改或“替换”已发布 Artifact。

### 5.5 `adm_client_release_audit_log`

记录 `release_id` 或 `policy_id`、`action`、状态前后值、操作人、请求 ID、幂等键、原因、经脱敏的请求快照摘要与发生时间。此表无 UPDATE/DELETE Repository 或管理接口，保留期遵循平台审计规范。

### 5.6 `adm_client_release_operation`

所有写用例都写入幂等操作记录，字段为全局唯一 `idempotency_key`、动作、规范化请求摘要、结果 `release_id` 或 `policy_id` 与完整审计字段。重复键且摘要相同必须返回原资源；同一键配不同摘要返回 `409`，绝不能执行第二次状态流转。幂等记录不是审计日志的替代品：一次成功写入同时产生一条操作记录和一条审计日志。

### 5.7 Flyway V2 与迁移执行

本次无需保留历史接口或业务数据兼容，但必须遵守 Flyway “已执行迁移只增不改、迁移中不使用 `DROP TABLE`”规则。因此旧能力的运行时移除与旧物理表的不可逆清理分两步执行：同一个后端提交新增 `V2__replace_app_release_governance.sql`，将 `adm_app_release` 重命名为 `adm_app_release_retired_v1` 并创建五张新表；一个完整发布周期且备份验证后，DBA 按变更单物理清理退役表。禁止把 `DROP TABLE` 写入 V2。

V2 必须与本方案中的领域、Repository、Mapper、Controller、Gateway 和测试同一提交，不能单独发布。实施提交还必须新增 `scripts/release/Test-ClientReleaseCutoverPreflight.ps1`，它只读连接目标库、输出至 `.outputs/logs/`、失败即非零退出；凭据仅从受管环境变量读取。

Flyway 前执行以下只读检查；任一结果不符合即停止部署：

```sql
SELECT COUNT(*) AS release_count
FROM `adm_app_release`;

SELECT COUNT(*) AS unexpected_schema_count
FROM `information_schema`.`tables`
WHERE `table_schema` = DATABASE()
  AND `table_name` IN (
    'adm_client_release', 'adm_client_release_artifact',
    'adm_client_release_policy', 'adm_client_release_audit_log',
    'adm_client_release_operation', 'adm_app_release_retired_v1'
  );
```

`release_count` 必须为 `0`，`unexpected_schema_count` 必须为 `0`，且必须已有可恢复备份。发布单记录备份标识、操作者、目标环境和查询结果。部署时先摘除旧服务实例；在空库验证 V1+V2，在结构一致演练库验证前置检查和 V2，再部署新后端/Gateway，最后切换 App 与 Admin。V2 执行后不能回滚到读取 `adm_app_release` 的旧二进制，只能回滚新应用/Gateway 并按发布单处置。

以下内容为 `V2__replace_app_release_governance.sql` 的完整脚本；实施时原样创建为 UTF-8 文件，使用两空格缩进、反引号标识符和大写 SQL 关键字，执行后不可修改。

```sql
-- 管理域客户端发布治理重构；依赖已执行的 V1 基线。
CREATE TABLE `adm_client_release`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `platform` ENUM('android', 'ios', 'windows', 'macos', 'linux') NOT NULL COMMENT '运行操作系统平台',
  `channel` ENUM('internal', 'beta', 'stable') NOT NULL COMMENT '发行渠道',
  `version` varchar(32) NOT NULL COMMENT '展示版本号',
  `build_number` bigint unsigned NOT NULL COMMENT '平台渠道内递增构建号',
  `status` ENUM('draft', 'scheduled', 'rolling_out', 'released', 'paused', 'withdrawn', 'archived') NOT NULL COMMENT '发布状态',
  `mandatory` tinyint(1) NOT NULL DEFAULT 0 COMMENT '命中更新是否强制升级',
  `rollout_percent` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '灰度比例',
  `rollout_salt` varchar(64) NOT NULL COMMENT '稳定灰度哈希盐',
  `scheduled_at` datetime(3) NULL COMMENT '计划发布时间',
  `published_at` datetime(3) NULL COMMENT '首次对客户端可见时间',
  `release_notes` text NOT NULL COMMENT '发布说明',
  `store_url` varchar(1024) NULL COMMENT '受控商店更新地址',
  `compatibility_json` json NOT NULL COMMENT '已校验的协议兼容快照',
  `active_delivery_guard` varchar(24) GENERATED ALWAYS AS (
    IF(`status` IN ('scheduled', 'rolling_out'), 'active', NULL)
  ) STORED COMMENT '用于保证同平台渠道仅一个活动发布',
  `row_version` bigint unsigned NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_client_release_platform_channel_build` (`platform`, `channel`, `build_number`) COMMENT '平台渠道构建号唯一约束',
  UNIQUE KEY `uk_adm_client_release_platform_channel_version` (`platform`, `channel`, `version`) COMMENT '平台渠道展示版本唯一约束',
  UNIQUE KEY `uk_adm_client_release_active_delivery` (`platform`, `channel`, `active_delivery_guard`) COMMENT '平台渠道活动发布唯一约束',
  KEY `idx_adm_client_release_lookup` (`platform`, `channel`, `status`, `build_number` DESC) COMMENT '客户端版本检查查询',
  KEY `idx_adm_client_release_schedule` (`status`, `scheduled_at`) COMMENT '定时发布扫描',
  CONSTRAINT `ck_adm_client_release_build_number` CHECK (`build_number` > 0),
  CONSTRAINT `ck_adm_client_release_rollout_percent` CHECK (`rollout_percent` <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端发布聚合';

CREATE TABLE `adm_client_release_artifact`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `release_id` bigint unsigned NOT NULL COMMENT '所属客户端发布 ID',
  `architecture` ENUM('universal', 'x64', 'arm64') NOT NULL COMMENT '目标架构',
  `package_type` ENUM('apk', 'google-play', 'app-store', 'msix', 'exe', 'dmg', 'pkg', 'flatpak', 'appimage') NOT NULL COMMENT '安装包类型',
  `download_url` varchar(1024) NULL COMMENT '受控 HTTPS 制品地址',
  `sha256` char(64) NULL COMMENT '直装制品 SHA-256 摘要',
  `size_bytes` bigint unsigned NULL COMMENT '直装制品字节数',
  `signing_metadata_json` json NULL COMMENT '签名或公证元数据',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_client_release_artifact_target` (`release_id`, `architecture`, `package_type`) COMMENT '单发布目标制品唯一约束',
  CONSTRAINT `fk_adm_client_release_artifact_release` FOREIGN KEY (`release_id`) REFERENCES `adm_client_release` (`id`),
  CONSTRAINT `ck_adm_client_release_artifact_size` CHECK (`size_bytes` IS NULL OR `size_bytes` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端发布制品';

CREATE TABLE `adm_client_release_policy`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `platform` ENUM('android', 'ios', 'windows', 'macos', 'linux') NOT NULL COMMENT '运行操作系统平台',
  `channel` ENUM('internal', 'beta', 'stable') NOT NULL COMMENT '发行渠道',
  `minimum_build_number` bigint unsigned NOT NULL COMMENT '最低受支持构建号',
  `row_version` bigint unsigned NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_client_release_policy_platform_channel` (`platform`, `channel`) COMMENT '平台渠道策略唯一约束',
  CONSTRAINT `ck_adm_client_release_policy_minimum_build` CHECK (`minimum_build_number` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端最低支持版本策略';

CREATE TABLE `adm_client_release_audit_log`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `release_id` bigint unsigned NULL COMMENT '关联客户端发布 ID',
  `policy_id` bigint unsigned NULL COMMENT '关联最低支持版本策略 ID',
  `action` ENUM('create', 'update', 'submit', 'rollout', 'pause', 'resume', 'withdraw', 'upsert_policy', 'scheduled_publish') NOT NULL COMMENT '变更动作',
  `before_status` ENUM('draft', 'scheduled', 'rolling_out', 'released', 'paused', 'withdrawn', 'archived') NULL COMMENT '变更前发布状态',
  `after_status` ENUM('draft', 'scheduled', 'rolling_out', 'released', 'paused', 'withdrawn', 'archived') NULL COMMENT '变更后发布状态',
  `request_id` varchar(64) NOT NULL COMMENT '请求关联标识',
  `idempotency_key` char(36) NOT NULL COMMENT '写操作幂等键',
  `reason` varchar(512) NOT NULL COMMENT '变更原因',
  `payload_digest` char(64) NOT NULL COMMENT '脱敏请求快照摘要',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '操作人',
  `created_at` datetime(3) NOT NULL COMMENT '发生时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建时操作人，审计记录不更新',
  `updated_at` datetime(3) NOT NULL COMMENT '创建时间，审计记录不更新',
  PRIMARY KEY (`id`),
  KEY `idx_adm_client_release_audit_release_created` (`release_id`, `created_at` DESC) COMMENT '发布审计时间线查询',
  KEY `idx_adm_client_release_audit_policy_created` (`policy_id`, `created_at` DESC) COMMENT '策略审计时间线查询',
  UNIQUE KEY `uk_adm_client_release_audit_idempotency` (`idempotency_key`) COMMENT '审计动作幂等唯一约束',
  CONSTRAINT `fk_adm_client_release_audit_release` FOREIGN KEY (`release_id`) REFERENCES `adm_client_release` (`id`),
  CONSTRAINT `fk_adm_client_release_audit_policy` FOREIGN KEY (`policy_id`) REFERENCES `adm_client_release_policy` (`id`),
  CONSTRAINT `ck_adm_client_release_audit_subject` CHECK (`release_id` IS NOT NULL OR `policy_id` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端发布不可变审计日志';

CREATE TABLE `adm_client_release_operation`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `idempotency_key` char(36) NOT NULL COMMENT '请求幂等键',
  `action` ENUM('create', 'update', 'submit', 'rollout', 'pause', 'resume', 'withdraw', 'upsert_policy', 'scheduled_publish') NOT NULL COMMENT '用例动作',
  `request_digest` char(64) NOT NULL COMMENT '规范化请求摘要',
  `release_id` bigint unsigned NULL COMMENT '结果客户端发布 ID',
  `policy_id` bigint unsigned NULL COMMENT '结果最低支持版本策略 ID',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '操作人',
  `created_at` datetime(3) NOT NULL COMMENT '发生时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建时操作人，操作记录不更新',
  `updated_at` datetime(3) NOT NULL COMMENT '创建时间，操作记录不更新',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_client_release_operation_idempotency` (`idempotency_key`) COMMENT '写操作幂等唯一约束',
  CONSTRAINT `fk_adm_client_release_operation_release` FOREIGN KEY (`release_id`) REFERENCES `adm_client_release` (`id`),
  CONSTRAINT `fk_adm_client_release_operation_policy` FOREIGN KEY (`policy_id`) REFERENCES `adm_client_release_policy` (`id`),
  CONSTRAINT `ck_adm_client_release_operation_subject` CHECK (`release_id` IS NOT NULL OR `policy_id` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端发布写操作幂等记录';

RENAME TABLE `adm_app_release` TO `adm_app_release_retired_v1`;
```

先创建新表、最后退役旧表，能让新表 DDL 错误保留旧运行时表名以便诊断。`active_delivery_guard` 是数据库层并发底线；应用层仍须在事务中锁定平台渠道活动 Release 并校验状态机，不能用唯一键异常代替业务错误。新表不预置 Release 或 Policy；首次设置最低版本时创建对应 `platform + channel` Policy，缺少 Policy 表示未设置最低受支持构建号。

持久化与迁移测试至少验证：V1+V2 在空 MySQL 顺序执行；旧表名消失且退役表及五张新表的字符集、索引和约束正确；平台渠道构建号/版本重复及第二条灰度发布被拒绝；审计和幂等 Repository 无更新/删除方法；相同幂等键同摘要返回原资源、不同摘要返回冲突；发布后 Artifact、平台、渠道和构建号不可修改；带 `row_version` 的乐观更新影响零行时返回业务冲突。实施完成后运行 `invoke-engineering-validation.ps1 -Scope Changed` 和 `-Scope Full`，并留存 Flyway、前置检查和演练测试证据。

### 5.8 后端运行配置与平台开关

`im-admin-service` 的受管环境配置必须显式声明以下键；`ReleaseDistributionProperties`、`ReleaseProtocolCompatibilityProperties`、`ReleasePlatformAvailabilityProperties` 和 `ClientReleaseScheduledPublicationJob` 只能读取这些配置，领域层不直接读取 YAML：

```yaml
client-release:
  enabled-platforms: [android, ios]
  allowed-download-hosts: [downloads.example.com]
  supported-protocol-versions: [v1]
  minimum-server-capability-version: 1
  scheduled-publication:
    fixed-delay: PT1M
    batch-size: 50
```

生产环境必须通过受管配置提供真实分发域名，禁止把开发地址、签名下载 URL 或凭据提交到仓库。Job 每次在独立事务中锁定最多 `batch-size` 个到期 Release；由于 `active_delivery_guard` 与提交前置条件已阻止同平台渠道活动发布重叠，到期任务不会同另一排期/灰度竞争。若发现结构漂移或不变量被破坏，Job 记录含 `releaseId/sdk/channel` 的 ERROR 并停止处理该条，不得自动改写排期或重复写审计。

PC 平台接入顺序固定为：完成该 OS 客户端、安装升级 E2E 与 CI 制品流水线 → 将该平台加入 `enabled-platforms` → 在 `internal` 创建并演练 Release、验证公开检查与 Admin 状态 → 才允许 beta/stable。由于创建草稿也受平台开关保护，开启前的制品演练只能在 CI 本地验证和非生产隔离环境完成；生产配置开关只在受管部署变量变更，不以 Admin 前端按钮代替。

## 6. 北向 API 契约

所有接口通过 Gateway 暴露，外部前缀固定为 `/api/v1`。本次不保留现有 `GET /config/app-update` 和旧 `/admin/app-releases` 的字段兼容。

### 6.1 客户端公开接口

```http
POST /api/v1/client/release-check
```

请求：

```json
{
  "platform": "android",
  "channel": "stable",
  "version": "1.3.0",
  "buildNumber": 10300,
  "architecture": "universal",
  "protocolVersion": "v1",
  "installationId": "stable-client-installation-id"
}
```

`installationId` 是客户端首次安装时生成并安全保存的随机 UUID，只用于稳定灰度哈希与诊断，不得使用用户 ID、设备硬件 ID 或可识别个人信息。未登录时也必须能调用此接口。

此公开接口只接受五个受管平台枚举；`web`、`harmonyos`、未知值或不完整请求统一返回 `400 CLIENT_RELEASE_INVALID_REQUEST`，绝不映射为其它平台。当前 App 对 Web/HarmonyOS 不发起该请求；已知但未启用的 PC 平台请求则按决策对象返回 `unsupported_client/platform_not_enabled`，使未来误配置可被明确诊断。

成功响应遵循现有跨端契约，非列表统一使用 `{ "data": {}, "requestId": "uuid" }`；`requestId` 来自有效 `X-Request-Id` 或由服务端生成。响应中的决策对象为：

```json
{
  "data": {
    "decision": "optional_update",
    "serverTime": "2026-08-18T08:00:00Z",
    "current": { "version": "1.3.0", "buildNumber": 10300 },
    "target": {
      "version": "1.4.0",
      "buildNumber": 10400,
      "mandatory": false,
      "releaseNotes": "修复消息同步与通知",
      "publishedAt": "2026-08-18T07:00:00Z",
      "artifacts": [{
        "architecture": "universal",
        "packageType": "apk",
        "downloadUrl": "https://downloads.example.com/gv/android/stable/1.4.0/10400/app.apk",
        "sha256": "...",
        "sizeBytes": 123456789,
        "signatureMetadata": { "certificateSha256": "..." }
      }]
    },
    "compatibility": { "protocolVersion": "v1", "minimumServerCapabilityVersion": 1 }
  },
  "requestId": "6f15e8f0-a4c8-43d9-8a89-9d0c304889e6"
}
```

`decision` 只能是：

| 值 | 客户端动作 |
| --- | --- |
| `up_to_date` | 正常进入业务 |
| `optional_update` | 允许跳过，按客户端策略稍后提醒 |
| `mandatory_update` | 阻断业务入口，只显示安全更新页 |
| `unsupported_client` | 阻断业务；当前构建低于 `minimumBuildNumber`、协议不支持、平台未启用或 stable 缺少有效基线 |
| `no_release` | 仅 `internal/beta` 无发布记录时返回；允许研发诊断但不表示“已是最新” |

`unsupported_client` 必须包含 `blockReason`，其值固定为 `minimum_build_not_supported`、`protocol_not_supported`、`platform_not_enabled`、`release_baseline_missing`、`artifact_not_available` 之一。若存在适用于该架构的更新目标，响应仍必须带 `target`，使被拦截客户端可以安装；最低版本拦截的 target 只能是已 `released` 的完整发布。`no_release` 的 `target` 为 `null`。当当前构建低于已生效的 `minimumBuildNumber` 时，始终返回 `unsupported_client`，优先级高于灰度命中与 `mandatory`；客户端不能因为没被灰度命中而绕过最低版本限制。

平台元数据接口的响应固定为 `{data,requestId}`，其中 `data.items[]` 至少包含 `platform`、`enabled`、`supportedPackageTypes`、`onboardingState`（`active`/`reserved`）和 `onboardingNote`。`enabled` 由 `ReleasePlatformAvailabilityPort` 从受管配置读取；`onboardingState` 只能作 Admin 展示，不能由 Admin 写入或覆盖。初始响应中 Android/iOS 为 `active`，Windows/macOS/Linux 为 `reserved`。

### 6.2 管理端接口

| 接口 | 用途与成功响应 |
| --- | --- |
| `GET /admin/client-releases` | 按 sdk/channel/status 分页查询；固定分页响应 |
| `POST /admin/client-releases` | 创建草稿；`201` + `{data,requestId}` |
| `GET /admin/client-releases/{id}` | 查看 Release、制品与审计摘要；`{data,requestId}` |
| `PUT /admin/client-releases/{id}` | 修改草稿；必须带 `expectedRowVersion`；`{data,requestId}` |
| `POST /admin/client-releases/{id}/submit` | 提交排期或立即灰度；`{data,requestId}` |
| `POST /admin/client-releases/{id}/rollout` | 将灰度比例单调上调；`{data,requestId}` |
| `POST /admin/client-releases/{id}/pause` | 暂停灰度；`{data,requestId}` |
| `POST /admin/client-releases/{id}/resume` | 恢复灰度；`{data,requestId}` |
| `POST /admin/client-releases/{id}/withdraw` | 撤回，原因必填；`{data,requestId}` |
| `PUT /admin/client-release-policies/{platform}/{channel}` | 创建或提高最低支持构建号；`{data,requestId}` |
| `GET /admin/client-release-policies` | 查询各平台渠道基线；固定分页响应 |
| `GET /admin/client-releases/{id}/audit-logs` | 查询不可变审计时间线；固定分页响应 |
| `GET /admin/client-release-platforms` | 返回五个平台、`enabled`、可用制品类型和当前接入说明；Admin 用于显示 PC 预留状态 |

所有写接口：`ROLE_ADMIN`、UUID 格式 `Idempotency-Key`、可选 `X-Request-Id`，以及请求体 `expectedRowVersion`（新建除外）。状态流转与策略变更的请求体必须含非空 `reason`；`submit` 必须含 `initialRolloutPercent`，可选 `scheduledAt`。列表固定为 `{items,page,pageSize,total,updatedAt}`，其他成功响应固定 `{data,requestId}`，失败响应固定 `{code,message,requestId,retryable,fieldErrors}`。删除接口不提供。

写请求字段按下表冻结，具体 Schema 以导出的 `docs/contracts/openapi/admin.json` 为准：

| 用例 | 请求体字段 | 服务端必须拒绝 |
| --- | --- | --- |
| 创建草稿（CI） | `platform, channel, version, buildNumber, mandatory, releaseNotes, storeUrl?, compatibility, artifacts[]` | Web/未知平台、未启用平台、重复版本/构建号、无效 Artifact、非受控下载域名 |
| 编辑草稿（CI） | `expectedRowVersion, releaseNotes, storeUrl?, compatibility, artifacts[]` | 非 `draft`、修改平台/渠道/buildNumber、删除全部 Artifact |
| 提交 | `expectedRowVersion, reason, scheduledAt?, initialRolloutPercent` | 缺少初始比例、过去排期、比例不在 `1..100` |
| 上调灰度 | `expectedRowVersion, reason, rolloutPercent` | 非 `rolling_out`、比例不在 `1..100`、小于等于当前比例 |
| 暂停/恢复/撤回 | `expectedRowVersion, reason` | 与当前状态不匹配；撤回后任何恢复 |
| 设置最低版本 | `expectedRowVersion? , reason, minimumBuildNumber` | 小于当前策略、目标构建号不存在或不是该平台渠道 `released` 版本 |

`artifacts[]` 的每项固定为 `architecture, packageType, downloadUrl?, sha256?, sizeBytes?, signingMetadata?`。直装包必须同时有 HTTPS `downloadUrl`、小写 64 位 `sha256` 和正数 `sizeBytes`；`google-play` 只能用于 Android、`app-store` 只能用于 iOS，二者依赖 Release 的 `storeUrl` 且不接收直装下载信息。`compatibility` 固定为 `protocolVersion` 与 `minimumServerCapabilityVersion`；它不是自由 JSON 配置入口，DTO 字段、领域值对象和 OpenAPI 都应为显式强类型。

### 6.2.1 错误码与 HTTP 映射

所有错误仍使用 `{code,message,requestId,retryable,fieldErrors}`。本领域错误码冻结如下，客户端和 Admin 不得根据 `message` 分支：

| HTTP | 错误码 | 语义 |
| --- | --- | --- |
| 400 | `CLIENT_RELEASE_INVALID_REQUEST` | 字段、枚举、SemVer、installationId 或 Artifact 不合法 |
| 400 | `CLIENT_RELEASE_PLATFORM_NOT_ENABLED` | 管理端对已知五平台的创建、提交或策略写入，但当前平台未开启；公开检查则返回 `unsupported_client/platform_not_enabled` |
| 401/403 | `CLIENT_RELEASE_UNAUTHORIZED` / `CLIENT_RELEASE_FORBIDDEN` | 管理写操作缺少认证或 `ROLE_ADMIN` |
| 404 | `CLIENT_RELEASE_NOT_FOUND` | Release、Policy 或审计主体不存在 |
| 409 | `CLIENT_RELEASE_CONFLICT` | 构建号/版本/活动发布冲突 |
| 409 | `CLIENT_RELEASE_IDEMPOTENCY_REUSED` | 同一幂等键对应不同规范化请求 |
| 409 | `CLIENT_RELEASE_ROW_VERSION_CONFLICT` | 乐观锁版本过期 |
| 409 | `CLIENT_RELEASE_INVALID_STATE` | 当前状态不允许该动作 |
| 422 | `CLIENT_RELEASE_ARTIFACT_INVALID` | 分发域名、摘要、签名元数据或平台制品组合不合法 |
| 429 | `CLIENT_RELEASE_RATE_LIMITED` | 公开检查超过 Gateway 限流 |
| 503 | `CLIENT_RELEASE_TEMPORARILY_UNAVAILABLE` | 公开检查依赖暂时不可用；客户端按离线策略处理 |

公开 `release-check` 的 POST 即使被现有 App `ApiClient` 自动附带 `Idempotency-Key` 也必须忽略该 Header，不创建幂等记录；管理写接口才校验和持久化幂等键。

### 6.3 更新决策算法

1. 验证 `sdk/channel/architecture/protocolVersion/buildNumber/installationId`；未知平台、错误枚举、非 UUID installationId 或非法构建号返回 `400`，绝不默认映射到其它平台。
2. 通过 `ReleasePlatformAvailabilityPort` 读取平台启用配置。该 Port 必须由 Create/Update/Submit/Rollout/Resume/UpsertPolicy 和 ReleaseCheck 用例共同调用：管理写操作对未启用平台返回 `400 CLIENT_RELEASE_PLATFORM_NOT_ENABLED`，公开检查则返回 `unsupported_client/platform_not_enabled`；App 不会对当前未启用的 PC 平台发送请求，此分支只保护误配置或未来接入错误。`Pause` 与 `Withdraw` 不受平台开关限制，确保平台被紧急停用后仍可安全处置既有发布。
3. 先查询该 `platform + channel` 最高的、架构匹配且可安装的 `released` Release，作为完整基线 target；再计算唯一 `rolling_out` Release 的稳定灰度命中。命中且构建号更高时，以它替换 target；未命中灰度时保留完整基线 target。
4. 灰度命中以 `SHA-256(release.rolloutSalt + ":" + installationId)` 的前 8 字节转换为无符号数，再对 100 取模；同一 Release 的同一安装 ID 结果固定。不得用随机数、用户 ID、IP 或时间作为灰度依据。
5. 若没有完整基线：`internal/beta` 返回 `no_release` 且 `target=null`；`stable` 返回 `unsupported_client/release_baseline_missing`。若有最低版本策略且当前构建低于阈值，返回 `unsupported_client/minimum_build_not_supported` 并携带完整基线 target。若当前协议不受支持，返回 `unsupported_client/protocol_not_supported` 并尽可能携带可安装 target。
6. 目标 `buildNumber <= 当前 buildNumber` 返回 `up_to_date`；否则按 `mandatory` 产生 `mandatory_update` 或 `optional_update`。任一决策都返回服务端时间和已校验的兼容快照；客户端只按响应的 Artifact 下载，不自行拼接 URL。

## 7. 与动态客户端配置、鉴权和业务协议的边界

`GET /config/client` 是动态业务 Feature Flag/配置接口，仍由独立客户端配置治理，不得被 `release-check` 取代，也不得把 `rtc`、推送开关等动态开关冻结进 Release。Release 只保存当时已验证的协议兼容基线；客户端在通过版本检查后，仍按实时客户端配置决定是否显示新增入口。

版本检查不是安全边界。首发只要求 Gateway 记录 `X-Client-Platform`、`X-Client-Channel`、`X-Client-Build-Number`、`X-Client-Protocol-Version` 以便观测；强更界面由客户端执行。若发生必须服务端阻断的安全事故，另行在 Gateway 实施统一版本准入，且需同时覆盖 HTTP 业务路由、`/auth/ws-ticket` 与 WebSocket ticket 的平台版本绑定；禁止在各业务 Controller 分散判断，也禁止把可伪造的客户端 Header 当成权限证明。

客户端版本与 API/WS 协议版本分离：Release 可以只修 UI 或安装包；只有协议发生破坏性变化时才提升 `protocolVersion` 并新增 REST/WS 契约版本。不得以提高 `minimumBuildNumber` 替代协议兼容设计。

## 8. 重构实施计划

### 8.0 服务端目录、文件与职责清单

新实现按管理域的 `clientrelease` 子域分层，不继续把发布业务塞进 `AdminManagementApplicationService` 或 `AdminManagementPersistenceAdapter`。下列文件是实现提交的最小清单；名称可因既有代码生成约定微调，但不得改变层次和依赖方向。

```text
im-services/admin/im-admin-service/src/main/java/io/openware/im/admin/
  api/clientrelease/
    ClientReleaseController.java                 # 公开检查与管理端 HTTP 入口
    ClientReleaseDtos.java                       # Request/Response DTO，不泄露领域对象
    ClientReleaseApiConverter.java               # DTO <-> Command/Result
  application/clientrelease/
    ClientReleaseApplicationService.java         # 草稿、状态流转、策略、幂等事务边界
    ClientReleaseCheckApplicationService.java    # 只读更新决策用例
    command/{CreateReleaseCommand,UpdateReleaseCommand,SubmitReleaseCommand,
      RolloutReleaseCommand,PauseReleaseCommand,ResumeReleaseCommand,
      WithdrawReleaseCommand,UpsertPolicyCommand}.java
    query/{ReleaseListQuery,ReleaseAuditLogQuery,ReleaseCheckQuery}.java
    result/{ClientReleaseResult,ReleaseCheckResult,ReleasePolicyResult,
      ReleaseAuditLogResult}.java
    port/{ReleaseDistributionPolicyPort,ReleaseProtocolCompatibilityPort,
      ReleasePlatformAvailabilityPort,ClientReleaseClock}.java
  domain/clientrelease/
    model/{ClientRelease,ReleaseArtifact,ClientReleasePolicy,ReleaseAuditLog,
      ReleasePlatform,ReleaseChannel,ReleaseStatus,TargetArchitecture,PackageType,
      ReleaseOperationAction,CompatibilitySnapshot}.java
    policy/{ReleaseEligibilityPolicy,RolloutAssignmentPolicy,
      ReleaseStateTransitionPolicy}.java
    repository/{ClientReleaseRepository,ClientReleasePolicyRepository,
      ClientReleaseAuditLogRepository,ClientReleaseOperationRepository}.java
  infra/clientrelease/
    config/{ReleaseDistributionProperties,ReleaseProtocolCompatibilityProperties,
      ReleasePlatformAvailabilityProperties}.java
    persistence/
      po/{ClientReleasePo,ClientReleaseArtifactPo,ClientReleasePolicyPo,
        ClientReleaseAuditLogPo,ClientReleaseOperationPo}.java
      mapper/{ClientReleaseMapper,ClientReleaseArtifactMapper,ClientReleasePolicyMapper,
        ClientReleaseAuditLogMapper,ClientReleaseOperationMapper}.java
      typehandler/{ReleasePlatformTypeHandler,ReleaseChannelTypeHandler,
        ReleaseStatusTypeHandler,TargetArchitectureTypeHandler,PackageTypeTypeHandler,
        ReleaseOperationActionTypeHandler}.java
      converter/ClientReleasePersistenceConverter.java
      repository/ClientReleasePersistenceAdapter.java
    schedule/ClientReleaseScheduledPublicationJob.java
```

- `domain` 不引入 Spring、MyBatis、PO、JSON 序列化或 `AppPlatform`；聚合通过受限方法维护状态、构建号不可变性和发布后不可变性，禁止 `@Data`。
- `application` 只依赖上述领域 Repository/Port。它在一个事务中锁定平台渠道活动发布、写 Release/Policy、Operation 和 Audit；不能导入 Mapper、PO、Wrapper 或 SQL 字段名。
- `infra` 实现 Repository 和配置 Port。PO 中的受控枚举通过 5.1 所列 TypeHandler 使用显式 `databaseValue` 持久化，禁止依赖 MyBatis 默认 ordinal；乐观更新必须使用 `WHERE id = ? AND row_version = ?`，影响行数为零转换为业务冲突；Mapper 只面对 PO。
- Controller 只做 `jakarta.validation`、鉴权上下文提取、Header 校验、DTO 转换和响应封装。`release-check` 不读取登录用户，也不调用管理写用例。

当前旧实现的删除/改造清单是确定的：

```text
删除
  api/AdminManagementDtos.java 中 ReleaseCreateRequest、ReleaseUpdateRequest、ReleaseResponse
  domain/release/AppRelease.java
  domain/repository/AppReleaseRepository.java
  infra/persistence/po/AppReleasePo.java
  infra/persistence/mapper/AppReleaseMapper.java

从现有类移除
  api/AdminManagementController.java 中 /admin/app-releases 与 /config/app-update 端点、转换器和请求 record
  application/AdminManagementApplicationService.java 中 releaseRepository、releases/create/update/delete/updateCheck 及 ReleaseChange/ReleasePatch
  infra/persistence/repository/AdminManagementPersistenceAdapter.java 中 AppReleaseRepository 实现、Mapper 注入和 PO 转换

同步修改
  AdminBatchZeroArchitectureGateTest.java 中旧表/路由/旧类断言
  gateways/gateway/src/main/resources/application.yml
  sdk/infrastructure/.../SecurityConfig.java
  docs/im-services/im-admin-service/README.md、docs/im-services/gateway/README.md、根 README.md
  docs/contracts/openapi/admin.json（由脚本重新导出，不手工拼接）
```

`AppPlatform` 仍可继续服务于设备推送等既有领域；本次只消除发布子域对它的依赖，绝不为删除发布逻辑而影响其它业务。

### 8.1 变更清单

| 范围 | 必须同步的变更 |
| --- | --- |
| 服务端 | 管理域 Release 聚合、状态机、应用服务、Repository/Mapper/PO、权限和审计 |
| 数据 | 本方案 5.7：`adm_app_release` 退役迁移、新 Release/Artifact/Policy/Audit/Operation 表、索引与受控测试数据清理 |
| 契约 | Admin OpenAPI 快照、客户端 release-check OpenAPI、错误码和协议兼容快照 Schema |
| Gateway | 删除旧 app-update 路由；新增 `/api/v1/client/release-check` 路由与公开限流；管理端新路径继续命中既有 `/api/v1/admin/**` 路由 |
| 管理后台 | 见 `APP_RELEASE_03_ADMIN.md`：工作流、制品/摘要录入、灰度/暂停/撤回、最低版本策略和审计查看 |
| 客户端 | 见 `APP_RELEASE_02_APP.md`：本轮 Android/iOS 的检查、安全 installationId、包校验与安装跳转；PC 三端只预留，待各自方案启用 |
| CI/CD 与部署 | 构建号分配、制品上传、SHA-256/签名元数据生成、分发域名白名单、定时发布任务和监控告警 |
| 测试 | 服务端领域、数据库、接口、契约；端侧升级 E2E 见对应端侧方案 |

### Phase 0：冻结与清理

1. 执行本方案 5.7 的只读前置检查，确认所有环境没有正式依赖 `adm_app_release` 的数据；记录并清理仅用于开发的测试记录。
2. 固化新 OpenAPI DTO、错误码、请求/响应封装和 Admin 的路由矩阵；不得让前后端以字段猜测互相联调。
3. 删除管理域发布子域对通用 `AppPlatform` 的依赖，新增管理域自有 `ReleasePlatform`（只含五个受管平台）和 `ReleaseChannel`；`web` 不得进入发布聚合。

### Phase 1：领域与持久化

1. 按 8.0 的目录落地 `ClientRelease`、`ReleaseArtifact`、`ClientReleasePolicy`、`ReleaseAuditLog` 和 `ClientReleaseOperation`；删除 `AppRelease` 和旧 Repository/PO/Mapper，同时更新架构门禁中列举的旧类和表名。
2. 将 V2 与代码一起实施，加入乐观锁、唯一约束与状态/索引；补充 Mapper、Repository、领域状态转移、幂等和事务测试。
3. `ClientReleaseScheduledPublicationJob` 以固定短周期调用应用层用例；Repository 使用 `status='scheduled' AND scheduled_at <= now` 的加锁分页查询。每条记录在同一事务内二次校验仍为 `scheduled` 后，按已保存的 `rolloutPercent` 转为 `rolling_out`（`1..99`）或 `released`（`100`）；重复扫描、重启、并发节点和时钟边界均不能产生第二次审计或第二次状态变更。

### Phase 2：北向契约、Gateway 与调度

1. 重建公开 release-check 和管理端 Release API；Controller 只做 DTO 校验与转换，发布规则归 Application/Domain。公开检查只返回 `{data,requestId}`；它不要求登录、不产生审计写入，也不接受 `Idempotency-Key`。
2. Gateway 将 `/api/v1/client/release-check` 转发给管理域，保留 `/api/v1/admin/**` 统一管理路由；移除 `/api/v1/config/app-update` 路由、README 与服务文档声明；安全配置只对新公开检查端点放行。
3. 在 Controller/API 测试稳定后，运行 `scripts/export-openapi-snapshots.ps1` 生成并提交 `admin.json`；所有响应使用强类型 DTO，禁止 `Map<String,Object>`。
4. `SecurityConfig` 移除 `/config/app-update` 的 `permitAll`，仅新增精确的 `/client/release-check` 放行；其它 `/admin/**` 路径继续由既有 `ROLE_ADMIN` 鉴权。Gateway 新公开路由也只匹配该精确 POST 路径，不能将 `/client/**` 全部公开。

### Phase 3：客户端、管理后台与分发

1. 按 `APP_RELEASE_02_APP.md` 完成 App 端一次性切换，并按 `APP_RELEASE_03_ADMIN.md` 重建后台发布页面；两端均删除旧 DTO、API 调用与 UI。禁止先让任一端发布到只存在于另一端的路径。
2. 建立制品上传流程：CI 先生成包、计算 SHA-256、签名/公证，再用管理 API 创建草稿和 Artifact；禁止人工填写摘要或替换已发布二进制。

### Phase 3.1：CI 制品清单与受控发布机器人

当前 `gv_chat_app/.cnb.yml` 只执行 Flutter 校验和 Android debug 附件，不是发布流水线。本次必须新增受控 release job 与 `gv_chat_app/tools/create_client_release_manifest.dart`：输入 `platform,channel,version,buildNumber,architecture,packageType,mandatory,protocolVersion,minimumServerCapabilityVersion,releaseNotesPath,artifactPath?,storeUrl?`，读取版本库内非空发布说明和已上传制品，输出规范化 JSON；直装包计算 SHA-256、`sizeBytes`，商店包校验其商店上传产物后只输出 `storeUrl`，绝不输出 AAB 下载字段。脚本拒绝空值、非 SemVer、非正构建号和不允许的 package/platform 组合。

固定发布流程为：

1. 仅由受保护的 release 触发器运行；其输入与后端 `enabled-platforms` 交叉校验，初始矩阵只允许 Android/iOS，PC 三端仅保留 manifest schema 和后续矩阵项。
2. 调用 App 的统一 `tools/build.ps1`，显式注入 `APP_ENV`、`GV_RELEASE_CHANNEL`、`GV_RELEASE_ARCHITECTURE`、`GV_PROTOCOL_VERSION`、version 和 buildNumber；构建、签名/公证后先上传到 `allowed-download-hosts` 的内容不可覆盖路径。
3. 运行 manifest 脚本，保存其 JSON 和 SHA-256 到 CI 制品；Android 商店包使用 `google-play + storeUrl`，iOS 使用 `app-store + storeUrl`，不将 AAB 作为客户端下载 Artifact。
4. CI 仅使用专用、受管的 `GV_RELEASE_ADMIN_TOKEN` 调用 `POST/PUT /api/v1/admin/client-releases` 创建或编辑草稿；Token 对应非个人的现有 `ROLE_ADMIN` 发布机器人账户，存于 CNB 加密变量，按发布角色轮换，绝不出现在日志、manifest 或 App 包。
5. 返回的 Release ID、requestId、manifest SHA-256 与 CI 构建号共同归档。管理员在 Admin 工作台只负责查看草稿、提交、灰度、暂停、恢复、撤回和策略变更。

在 IAM 尚未提供细粒度服务账户前，`ROLE_ADMIN` 是首发可执行但权限较宽的过渡方案；它必须使用独立账户、受保护流水线和审计，不能使用任何员工个人 Token。引入 IAM 后再收敛为最小发布权限，不改变 Release API 或制品清单格式。

### Phase 4：灰度与正式启用

1. 首次只发布 Android/iOS `internal`，验证 release-check、商店/直装路径、回退和哈希校验；PC 三端在各自客户端完成后按 5.8 的顺序单独进入 internal，不得阻塞移动端首发。
2. 再发布 `beta`，观察检查成功率、下载成功率、摘要/签名失败率、启动成功率和崩溃率。
3. `stable` 从 1% 开始上调；发生 P0/P1 故障先 `pause`，必要时 `withdraw`，禁止修改已发布制品或覆盖同一个 buildNumber。
4. 首次稳定版本至少运行一个完整发布周期后，才设置 `minimumBuildNumber` 和强更策略；完成数据退役确认后才允许物理删除 `adm_app_release_retired_v1`。

## 9. 测试、监控与验收

### 9.1 必须自动化的测试

- 领域状态机：所有合法转移、非法转移、重复幂等命令、乐观锁冲突。
- 决策算法：Android/iOS 启用路径、三渠道、版本相等/高低、最低版本、灰度边界 0/1/99/100、架构不匹配、暂停和撤回；并验证 Windows/macOS/Linux 未启用返回 `platform_not_enabled`，Web/HarmonyOS 返回 400。
- 数据库：平台渠道构建号唯一、Audit 仅追加、发布后 Artifact 不可改。
- API：401/403、参数非法、`Idempotency-Key` 重放、OpenAPI 快照、旧接口 404。
- 客户端：安全存储 installationId、摘要/签名失败、可选/强制升级界面、断网与恢复、下载取消后可重试。
- Gateway：新公开检查端点可访问、旧端点 404、限流生效、管理端新路径鉴权正确。
- 发布调度：排期重复触发、重启恢复和时钟边界下不会重复发布。

### 9.2 指标与告警

按 `sdk/channel/buildNumber/releaseId/decision` 采集：检查请求数与失败率、命中率、下载开始/完成/摘要失败、安装器启动结果、升级后首次启动、崩溃率、活跃构建分布和被最低版本拦截数量。所有日志只记录 `installationId` 的不可逆短哈希，绝不记录下载签名 URL、认证 token 或硬件标识。

### 9.3 完成定义

本轮完成不是能在后台新增一行版本，而是：五个受管平台均已有独立枚举、数据约束与 Admin 平台元数据，Android/iOS 已具备独立发布序列；管理端可对已启用平台创建、灰度、暂停、撤回并审计；Android/iOS 客户端能正确校验并处理五种决策；发布制品可验证、不可覆盖；OpenAPI、数据库迁移、权限、E2E 与指标面板全部通过。任何 PC 平台未完成其安装/升级回退验收时，不得启用该平台或创建其 Release，更不得进入 `stable`。
