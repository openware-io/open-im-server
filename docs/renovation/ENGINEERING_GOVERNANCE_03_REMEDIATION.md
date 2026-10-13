# im-saas 工程规范整改方案（v10 · 批次 0 已执行）

> 方案集：`ENGINEERING_GOVERNANCE`；序号：`03`；前置：[01 治理计划](./ENGINEERING_GOVERNANCE_01_CONFORMANCE.md)、[02 审查报告](./ENGINEERING_GOVERNANCE_02_AUDIT.md)
> 配套审查报告：[ENGINEERING_GOVERNANCE_02_AUDIT.md](./ENGINEERING_GOVERNANCE_02_AUDIT.md)（同目录）
> v1→v2 决策 → v3 无上线前提 → v4 例外归零+留档 → v5 纯开源开发阶段 → v6 全案自审 → v7 执行移出+深查 11 项 → v8 附录 C 确认 + 根目录治理 + 报告脱敏归档 → v9 附录 A.8 全部确认（无待决项）→ **v10 批次 0 执行完成（含 Kind 运行环境轮换）**
> 定位：按 `standards/00:28`，属**改造方案**，不重写规范正文。本文件已按其自身建议归档至 `docs/renovation/`。

---

## 0. 变更记录（v8 执行项 + v9 确认）

### 0.1 已执行

| 动作 | 结果 | 验证 |
| --- | --- | --- |
| **附录 C（N-01~N-11）全部确认** | 11 项均带默认方案并入批次 | 见 §16 |
| 删除工作区根目录 `.vscode` | 已删除；根目录现仅剩 8 个产品仓 + `.github` + `open-green-pass` | `Get-ChildItem` 确认 |
| 两份报告脱敏 | 6 类凭据字面量替换为掩码（管理员初始口令、ACK JWT 密钥、4 类默认密钥字面量） | `git grep` 确认归档文档已不在命中列表 |
| 报告归档 | 审查报告 → `docs/renovation/ENGINEERING_GOVERNANCE_02_AUDIT.md`；整改方案 → `docs/renovation/ENGINEERING_GOVERNANCE_03_REMEDIATION.md`（原名带日期，已按 `docs/renovation/README.md:9-19` 改名并登记索引） | 根目录已无 `IM_SAAS_*` 文件；`git grep IM_SAAS_ENGINEERING` 无残留 |
| 归档后门禁复验 | 文本编码门禁 **exit 0**；密钥门禁 **exit 0** | 两份新文档未引入门禁失败 |
| `release_manager` 移出（v7） | 27 文件 / 21.2 MB → `D:\tools\release_manager`；统一门禁 `-Scope Changed` **exit 0** | 见 v7 记录 |

> 关于 `.vscode`：它**不是本次审查产生的**，创建时间为 2026-10-12 08:11（本会话 16:35 才开始），内容仅一行 `java.jdt.ls.vmargs` —— 是 VS Code Java 扩展为多仓工作区自动生成的 JDT 语言服务器 JVM 参数。已删除；若 IDE 再次提示生成，应把该设置写入**用户级 settings**，避免落回工作区根。

### 0.2 本轮新发现（N-12~N-18，详见 §16）

`CL-B1` 仓名歧义修正（实为 `open-chat-admin`/`open-chat-turn` 两仓，`open-saas-admin`/`open-saas-mobile` 合规）、`open-saas-mobile` 契约测试位于仓库根、`open-chat-app/.pubrc` 非标准文件、**历史凭据值的存放机制**（仓库文档不得写明文）、**PowerShell 读取 UTF-8 显示陷阱**（本轮实测再次触发）、**两个仓未忽略编辑器目录**、**归档命名与索引不合规（自审发现并已修正）**。

---

## 1. 前提与决策

### 1.1 前提（PR）

| # | 内容 |
| --- | --- |
| PR-01 | 工程**未正式上线**，无生产数据，无外部消费方 |
| PR-02 | **无需兼容**：一次性改完再验证，不设过渡期、不做双写/双读 |
| PR-03 | 允许销毁并重建本地/Kind 数据卷与命名空间作为验收路径 |
| PR-04 | **不存在真实发布环境**：开源开发阶段，所有部署均为本机 Kind；ACK 清单属未来目标（未部署） |

### 1.2 决策（DC）

| # | 决策项 | 内容 |
| --- | --- | --- |
| DC-01 | 交付口令 | 初始化部署时**动态生成**；部署后输出"访问与账号清单"（§3） |
| DC-02 | `group-services` | 登记为**集团业务领域** |
| DC-03 | `platform-services` 门禁 | **立刻硬拦**，一次性改造 |
| DC-04 | `support_media_*` | **改名成 `media_`**（直接改基线） |
| DC-05 | `.env` | **以安全整改为准修订规范**，不进版本管理 |
| DC-06 | 非 `id` 主键表 | **不开例外，严格按规范执行**（受 DC-17 约束） |
| DC-07 | `*-api` 依赖 | **不带 Spring 运行时** |
| DC-08 | 规范自更正 | 消除自相矛盾；**新增规范必须审查以避免冲突** |
| DC-09 | 迁移基线 | **合并重建**；文档同步；旧内容历史档留存，分支保持整洁（§5） |
| DC-10 | 冻结点 | 以**显式基线 tag**为准；两次 tag 之间允许重建基线 |
| DC-11 | 审计字段 | **全表强制 4 个字段，零例外** |
| DC-12 | 清单落盘 | 允许 `.outputs/releases/` + 终端打印（标注阅后删除） |
| DC-13 | 废弃资产 | 允许删除孤儿目录/日志/cookie/dump 等 |
| DC-14 | 例外机制 | **全面取消例外**；门禁零豁免 |
| DC-15 | tag 治理 | 删除旧 tag `v2.0.0`（含远端），改由显式基线 tag 承担冻结语义 |
| DC-16 | `k8s/ack/*` | **保留并修正** |
| DC-17 | 分区表主键 | **采纳 (a)**：保留分区；规范写明"MySQL 分区表主键必须包含分区列，属引擎约束" |
| DC-18 | `release_manager` | **已移出至 `D:\tools\release_manager`** |
| **DC-19** | **根目录治理** | **工作区根目录只允许仓库目录**；`.vscode` 已删除；审查/方案文档归档到 `docs/renovation/`；IDE 工作区级设置改放用户级，避免再生成 |
| **DC-20** | **历史凭据值存放** | **仓库内任何文档不得写明文凭据**；门禁所需的历史值放入 gitignored 的 `.outputs/quality/rotated-credentials.txt`，由 `validate-secret-exposure.ps1 -RotatedCredentialFile <path>` 读取；本方案与审查报告已按此脱敏 |

---

## 2. 已关闭的原议题

| 原议题 | 结论 |
| --- | --- |
| Q1 棘轮 vs 停线 | 关闭：一次性改造，零豁免硬拦 |
| Q4 `media_` 改名方式 / Q5 加 `id` 窗口 | 关闭：直接改基线（DC-17 例外） |
| Q6 `IF NOT EXISTS` 是否放开 | 关闭：脚本一律删除，规范保持严格 |
| H-5 V8 checksum 断裂 | 关闭：随基线重建消失，留档即可 |
| EX-01~EX-04 豁免表 | 关闭：全部取消（§4） |
| 第二门禁入口（`verify.bat`） | 关闭：随 DC-18 移出消失 |
| 附录 C N-01~N-11 | 关闭：**本轮全部确认** |

---

## 3. 工作流 A：部署凭据动态生成与交付清单（DC-01/DC-12）

| 类别 | 凭据 | Kind / Compose | ACK |
| --- | --- | --- | --- |
| 数据存储 | `DB_PASSWORD`、`REDIS_PASSWORD`、`MONGODB_PASSWORD` | 部署脚本生成 | Secret Manager |
| 消息 | `ROCKETMQ_ACCESS_KEY`/`SECRET_KEY` | 部署脚本生成 | Secret Manager |
| 签名 | `JWT_SECRET`、`INTERNAL_SERVICE_AUTH_SECRET` | 部署脚本生成 | Secret Manager |
| 对象存储 | `MINIO_ROOT_PASSWORD`、`MEDIA_ACCESS_KEY`/`SECRET_KEY` | 部署脚本生成 | Secret Manager |
| 后台账号 | SaaS 后台 admin 初始口令 | 部署脚本生成并注入 | 同左 |
| 开放平台 | 每环境 OAuth client secret | 部署脚本生成 | Secret Manager |

1. **生成点唯一**：复用 `scripts/initialize-local-env.ps1:25` 的 `RandomNumberGenerator`。
2. **幂等**：已存在即不重新生成；轮换需显式 `-Rotate`。
3. **fail-fast**：删除 5 处 `secret: ${VAR:默认值}`；G-13 门禁拦住该类写法。
4. **管理员初始化**：Flyway 只建"未激活"记录（占位 hash + `PENDING_ACTIVATION`）→ 部署脚本注入 `ADMIN_BOOTSTRAP_PASSWORD` → 服务端消费一次转 `ACTIVE`。
5. **禁令**：口令不入 `log.*`、迁移脚本与注释、镜像构建参数、git 跟踪文件。
6. **清单**：终端打印 + `.outputs/releases/<mode>-<ts>-access.md`；结构沿用样例（统一门户 / SaaS 后台 / A380 / B 端 / IM 后台 / 健康检查 / 官网 / MinIO API+Console / K8s 内部端口说明 + 账号区），HTTP 端点探活；`docs/deployment/LOCAL.md`、`k8s/README.md` 改为由生成器产出或自动校验。
7. `scripts/verify/*.ps1` 的硬编码地址改为从清单/环境读取（N-08）。

---

## 4. 工作流 B：例外取消与"合法化 / 改造"二分（DC-14）

### 4.1 规则冲突 → 合法化

| 对象 | 证据 | 处置 |
| --- | --- | --- |
| `sdk/infrastructure` 的 `Currency`/`CurrencyResolver` | `standards/16:30` | 修订 `ENGINEERING_RULES:16`：sdk 允许放**经专项规范批准的跨域语义组件**，注明依据 |
| `sdk/infrastructure` 的 `StoreTimeService` 等 | `MULTI_TIMEZONE_DESIGN:1036` | 同上；删除 `SAAS_PLATFORM_07_EXECUTION:15` 的冲突表述 |
| `iam_audit_log` 分区表主键（DC-17） | `migration-audit/V2:42,53` | 在 `ENGINEERING_RULES:188` 写明引擎约束条款 |

### 4.2 实现未遵循 → 改造

| 对象 | 证据 | 处置 |
| --- | --- | --- |
| admin 公共图片 multipart 代理 | `standards/13:18/:37` | 改造为 `upload-sessions` 直传；修正 `docs/services/common-media-service/README.md:19` |
| 追加型表缺审计字段 / 非 `id` 主键 | `ENGINEERING_RULES:189/188` | 按 DC-11/DC-06 全量修正 |

### 4.3 对审查报告 B-2 的严重级别修正（按 PR-04）

密钥值全仓仅 1 处；本机 Kind 的 `JWT_SECRET` 由 `initialize-local-env.ps1:61` 每机随机生成；ACK 未部署 → **今天实际影响为 0**，非"已发生的泄漏利用"；但仓库公开可读，按该清单部署即鉴权旁路 → 修复动作不变、优先级仍为**高**。

---

## 5. 工作流 C：迁移基线合并 + 文档更新 + git 留档（DC-09/DC-10）

### 5.1 重建内容

| 动作 | 内容 |
| --- | --- |
| 合并 | 每服务合并为 `V1__<domain>_baseline.sql`，后续改 `V2` 起 |
| 一次性修正 | ① 前缀按 §7 ② 主键统一 `id bigint unsigned NOT NULL AUTO_INCREMENT`（DC-17 例外）③ 全表 4 个审计字段 ④ 字符集/排序规则全覆盖 ⑤ `datetime(3)` ⑥ 索引命名 + 删重复索引 ⑦ 表/列 `COMMENT` 全覆盖 ⑧ 删除 `IF NOT EXISTS`/`DROP TABLE`/动态 DDL |
| 范围 | 18 个迁移服务 + `common-audit-service` 双根（合并为单根 + 分区表基线）；`db/test-migration`（payment/customer/order）同步结构 |
| 清理 | 删除"先建后删"迁移（`im-user-service/V12__drop_coin_points.sql` 及建表）、孤儿 `deploy/database/init.sql` |
| history 表名 | 统一 `<service>_schema_history` |
| 文档同步 | `ARCHITECTURE.md`、`DATABASE_MIGRATION_STANDARD.md`（补冻结点）、服务 README、`mq/REGISTRY.md`、`openapi/*`（如受影响）、`renovation/README.md` 索引、重做 `DATABASE_AUDIT_REPORT` |
| 验证 | 清库 → 全新 Flyway → 全服务启动 → 关键链路回归 → `scripts/verify/*` 回归 |

### 5.2 git 留档（已实测）

| 步骤 | 做法 |
| --- | --- |
| 1. 前置 tag | `git tag -a pre-baseline-consolidation-20261013 -m "合并迁移基线前的完整状态（199 个迁移）"` |
| 2. 单提交 | 删除旧文件 + 新增基线**同一提交**，message 引用 tag 与本文件 |
| 3. 清单 | `git ls-tree -r <tag> \| Select-String 'src/main/resources/db/migration(-audit)?/.*\.sql$'` → **path + blob SHA** 贴入 `MIGRATION_BASELINE_01_DATA.md`（实测 199 行） |
| 4. 库内只留文档 | 记录 tag + SHA、旧文件清单、表级映射、结构差异、验证证据、冻结声明 |
| 5. 外部交付 | `git archive -o xxx.zip <tag> -- common-services im-services platform-services group-services`，存外部制品库 |
| 6. 禁止 | 不重写历史；不长期挂分支/stash；不把旧 `.sql` 留在 `src/main/resources/db/**`；不把归档二进制提交进仓库 |

> 坑位实测：`git ls-tree -r <tag> -- "*db/migration/*.sql"` 返回 **0 行**（ls-tree 不做 glob）；`git ls-files` 读索引/工作树，不能用于历史 tag。

### 5.3 冻结与门禁

- 冻结以**显式基线 tag**为准，不与"任意 `v*` tag"耦合（`publish-images.yml:13-14` 以 `tags: ['v*']` 触发镜像发布）。
- 处置顺序：① 批次 2 重建基线 → ② §5.4 版本归位 → ③ 批次 5 总闸全绿 → ④ 删 `v2.0.0`（本地+远端）→ ⑤ 打 `v2.2.0` 注释 tag 并推送。

```powershell
git tag -d v2.0.0
git push origin :refs/tags/v2.0.0
git tag -a v2.2.0 -m "开发阶段基线：本 tag 内 Flyway 迁移冻结，只增不改（见 docs/renovation/MIGRATION_BASELINE_01_DATA.md）"
git push origin v2.2.0
```

> 提示：① 删 `v2.0.0` 会使指向它的 Release/外部链接失效；② 推 `v2.2.0` 会**触发 `publish-images` 工作流**构建推送全部业务镜像。

### 5.4 版本基线归位（须在打基线 tag **之前**）

- 4 个结构聚合 POM（现 2.0.1）、`sdk` 版本与 `standards/22:19`（写 1.0.5，实际 2.2.0）统一到同一基线。
- `ENGINEERING_RULES:24` 与 `standards/20:45`、门禁脚本的冲突按 DC-08 修订。
- `minio`/`aliyun-sdk-oss` 版本上提根 `dependencyManagement`；矩阵补 `jetbrains.annotations`、`error-prone-annotations`。

---

## 6. 工作流 D：规范治理与冲突审查（DC-08）

1. **文本自更正清单（11 处）**：`ENGINEERING_RULES:16`（sdk 允许项）、`:24`、`:29-50`、`:132-133`（.env）、`:188`（DC-17）；`standards/20:44`、`:154`、`:163`（"发布管理器"→"发布流程"）、`standards/12:17`、`standards/22:19`、`standards/30`（补"读取同样必须显式 UTF-8"，见 N-16）；`DATABASE_MIGRATION_STANDARD:11`。**已完成**：`standards/30:77`、`standards/41:28`、`standards/42:31` 的失效引用与目录口径。
2. **新增规范必须过冲突审查**：每份规范带"适用范围 / 与其他规范的关系 / 最后核查 / 负责人"；`validate-standards-governance.ps1` 检查 ① 七项模板 ② 无项目模块名/绝对路径/TODO ③ 链接有效 ④ 索引覆盖 ⑤ 同主题不重复定义。
3. **权威索引**：`standards/00` 增"权威索引"节，收敛 `standards/21`、`DATABASE_MIGRATION_STANDARD`、`RELEASE_SPECIFICATION`、`RELEASE_RUNBOOK` 的重叠。

---

## 7. 前缀与命名登记

| 表 | 现前缀 | 目标 | 备注 |
| --- | --- | --- | --- |
| `support_media_*`（5 张） | `support_` | **`media_`** | 索引/PO/Mapper/实体/文档同步 |
| `group_account`/`group_org` | `group_` | **保留**（DC-02 登记集团域） | 域父 POM/目录同步 |
| `idaas_client` | `idaas_` | 随集团域登记 | 不改名 |
| `open_*`（3 张） | `open_` | 登记为 IM 开放平台子域 | 表名保留 |
| `mail_*`/`sms_*`（6 张） | `mail_`/`sms_` | 登记为通用支撑子域 | 表名保留 |
| `saa_admin_account` | `saa_` | **`adm_admin_account`** | 同步 PO/Mapper/后台代码 |
| `iam_menu` | — | 补登记 | — |
| `tenant_payment_method` | `tenant_` | **`tnt_payment_method`** | 同步 PO/Mapper |

---

## 8. 新增门禁清单

| ID | 门禁 | 覆盖规则 |
| --- | --- | --- |
| G-01 | CI 调用 `-Scope Full`（PowerShell 镜像 + Maven ≥3.9.16） | `ENGINEERING_RULES:123`、G-06 |
| G-02 | 入口跨平台；修正 `publish-images.yml:54` 的裸 `mvn -T 1C clean verify` 与 tag 解析 | `standards/20:95/98` |
| G-03 | 修复 5 个空转/浅层门禁 + 健壮性（跟踪文件缺失须跳过而非崩溃，N-10） | `ENGINEERING_RULES:131` |
| G-04 | 边界门禁零豁免硬拦（含 platform-services） | `standards/10:141-145` |
| G-05 | 全路径引用门禁 | `ENGINEERING_RULES:88` |
| G-06 | catch 块门禁 | `ENGINEERING_RULES:112` |
| G-07 | `validate-flyway-immutability.ps1` | `ENGINEERING_RULES:221` + DC-10 |
| G-08 | `validate-standards-governance.ps1` | `standards/00:32-37/101-111` |
| G-09 | MQ 台账双向比对 + Relay 检查 + 组名字面量比对（N-02） | `standards/12:7/24/33` |
| G-10 | 文档相对链接 + 索引完整性 | `standards/00:37` |
| G-11 | 租户门禁重设计（白名单反转 + 原生 SQL 查询级断言） | `ENGINEERING_RULES:155` |
| G-12 | DDL 规范门禁（前缀/主键/审计字段/字符集/索引/注释；DC-17 放行） | `ENGINEERING_RULES:156-201` |
| G-13 | 禁止密钥默认值 `${VAR:默认值}`；**（v8）** 支持 `-RotatedCredentialFile` 读取 gitignored 历史值清单（DC-20）。**该参数必须可选**：文件缺失时只打印警告并跳过"历史值比对"，不让 CI 依赖本地文件 | DC-01/DC-20 |
| G-14 | 断言仓库内无第二校验入口 | `standards/20:141` |
| **G-15** | **根目录整洁断言**（DC-19）：工作区根只允许仓库目录；各仓根目录无 dump/cursor 等残留。**注意**：工作区根不是 git 仓库，该断言**无法在 CI 执行** → 定位为本地/发布前检查脚本（`scripts/validate/validate-workspace-root.ps1`），可选纳入 `-Scope Full` | 用户要求 + `standards/42:5` |

---

## 9. 客户端分仓整改清单

### 9.1 open-chat-app（Flutter）

CL-A1 清 `_sticker_panel_dump.txt`/`cursor.mark` + 补 `.gitignore`｜CL-A2 修 `pubspec.yaml:37,41,111` 乱码｜CL-A3 文档 `gv_core`/`gv_ui`→`open_core`/`open_ui`｜CL-A4 65 处硬编码中文入 l10n｜CL-A5 修 Bundle ID、FCM 矛盾、桌面章节｜CL-A6 补 release-check 触发点｜CL-A7 CI 加签名门禁 + 补丁包豁免登记｜CL-A8 Flutter 版本基线统一｜**（v8）CL-A9 `.pubrc` 处置：它实为一段不可执行的批处理片段（`set PUB_HOSTED_URL=… / set FLUTTER_STORAGE_BASE_URL=… / flutter pub get`，121 字节），非标准 `.pubrc` 配置、全仓无引用；已核实该镜像设置**未在任何文档中记录**——因此先迁移到 `BUILD.md` 的"国内镜像"一节（或 `scripts/flutter-env.ps1`），**再**删除该文件，避免丢失这条对国内开发者有用的信息**

### 9.2 其余仓

| ID | 动作 | 状态修正 |
| --- | --- | --- |
| CL-B1 | **仅 `open-chat-admin`（`.gitignore:9`）与 `open-chat-turn`（`.gitignore:10`）缺 lockfile 且 CI 跑 `npm ci` → 必须提交 lockfile 并移除忽略规则** | **（v8 修正）** `open-saas-admin`/`open-saas-mobile` 的 `package-lock.json` **已被跟踪、无忽略规则，属合规，不得改动** |
| CL-B2 | 六仓 CI 触发分支均只监听 `main`；实际分支为 `develop/2.2.0-auth` → 改为 `develop/**`+`main` | — |
| CL-B3 | `open-chat-admin` 版本单一事实来源（去 `1.0.5` 与死的 `VITE_APP_VERSION`） | — |
| CL-B4 | `open-chat-admin` 发布治理补齐 | — |
| CL-B5 | `open-chat-admin` 文档与 env 纠错（端口、`VITE_API_BASE_URL`、`.env.local.example`、`!.env.example`） | — |
| CL-B6 | `open-saas-mobile` README/结构/构建命令对齐；`VERSION` 真正被读取 | — |
| CL-B7 | `open-chat-desktop` 版本/链接/`repositories/` 层/Prettier+ESLint/测试/`strict`/签名与公证/bump 脚本 | — |
| CL-B8 | `open-website` 部署指南改 manifest 流程；CI 加 `scripts/validate.ps1`；CSP 与 `add_header` 覆盖 | — |
| CL-B9 | `open-chat-turn` 发布脚本文档化 + 最小单测 | — |
| CL-B10 | 客户端文档反引号路径改链接（app 26 / desktop 19 / admin 1） | — |
| CL-B11 | 代币品牌回退值统一（`储值币` vs `A380币`） | `open-saas-mobile` ↔ `open-saas-admin` |
| CL-B12 | `open-saas-admin` 媒体上传改直传 | — |
| **CL-B13** | **（v8 新增）** `open-saas-mobile` 三个契约测试文件位于仓库根（`currency-contract.test.js`、`media-url-contract.test.js`、`token-display-contract.test.js`）→ 移入 `test/`（已核实：无 `vitest.config`、vite 配置内无 `test` 段，`vitest run` 用默认 include 覆盖 `test/**`，**无需改 `package.json`**） | N-13 |
| **CL-B14** | **（v8 审查新增）** 编辑器目录忽略补齐：**`open-chat-desktop`、`open-saas-mobile` 完全无编辑器忽略**（`.vscode`/`.idea`/`.trae` 命中数 0）；**`open-chat-turn` 只忽略 `.idea`，缺 `.vscode`**。合计 3 仓需补，并统一 8 仓模板 | N-17 |

---

## 10. 验证后自动关闭项

| 项 | 验证方式 | 关闭条件 |
| --- | --- | --- |
| RocketMQ ACL 状态 | 查集群配置 | 未启用 → L-7 关闭；启用 → 实现 `AclClientRPCHook` |
| ACK 两服务是否缺密钥注入 | `kubectl get deploy -o yaml` | 按 DC-16 补齐后关闭 |
| `.env` 密钥是否外传 | 询问开发者 | 未外传 → 治理违规；已外传 → 泄漏事件 |
| javassist 实际版本 | `dependency:tree` | 3.32.0-GA → 成立 |
| `@electron-toolkit/tsconfig` strict | `tsc --showConfig` | strict 开 → 关闭 |
| `mkt_consent` 唯一键风险 | 追 `account_id` 唯一性 | 全局唯一 → 关闭 |
| `oidc-hybrid-e2e` 外部仓 | 确认是否纳管 | 纳管 → `standards/43` 外部路径改链接 |

---

## 11. 批次

| 批次 | 内容 | 验收 |
| --- | --- | --- |
| **0 安全止血** | 按 §11.1 的 **15 处凭据站点**逐一清理；轮换全部固定凭据；`k8s/ack/*` 改 `secretKeyRef` 并补齐注入；`V11` 种子密钥环境化；验收脚本口令与 LAN IP 参数化；删 cookie/dump 文件；建立 `.outputs/quality/rotated-credentials.txt`（DC-20） | 历史值清单扫描零命中；服务缺变量即启动失败 |
| **1 门禁与规范** | G-01~G-15；DC-01 清单生成器；DC-05 `.env` 修订；DC-08 的 11 处更正；`publish-images.yml` 硬化；`MqProperties` 死配置清理 + 生产者组常量集中；无第二入口断言 | 各门禁对已知样本能报错；故意违反即失败 |
| **2 数据一次性重建** | §5.1 合并重建（含 §7 前缀）；MQ 台账补 34 项；库名口径统一；补重建脚本；DC-17 条款落地 | 清库重建 → 全新迁移 → 全服务启动 → 回归通过；G-12 零违规 |
| **3 代码一次性收敛** | 481 处全路径；103 处静默 catch；统一入口日志；关键类注释；`@Data`；`sdk/common` 枚举与 `AppConstants` 业务常量下沉；28 个旧 `controller/` 包归位；`im.common.dto` DTO 归位；延迟销毁改 Outbox；DC-07 清理 `*-api` Spring 运行时；platform-services 分层收敛；admin 图片改直传；`Currency` 字面量收敛；common-services 分层补建；弃用 API 实际修复；`run-dev.ps1` 扩展到 20 服务 | 批次 1 门禁绿；架构测试覆盖 platform/common |
| **4 文档与客户端** | 服务文档统一 + 补 3 个 + 去重 + 补 `portal/`；修 14 条死链；`standards/15` 移出与项目内容下沉；更新 G-05/G-06 与"最后核查"；索引补全；重做 `DATABASE_AUDIT_REPORT`；文档分层收敛（docs 根 12 份、superpowers 11 份、根 `RELEASE-NOTES-*.md`，以及 `docs/renovation/README.md` 这份"目录命名规范"的归属与其过期的 v2.0.0 版本/分支节）；一次性运维脚本与游离 Java 处置；§9 客户端清单 | 链接与索引门禁绿；客户端 CI 在 `develop/**` 通过 |
| **5 一次性总验证** | 清库 → 部署 → 清单产出 → `scripts/verify/*` 回归 → §13 总闸；**之后**删 `v2.0.0` 并打 `v2.2.0` | 总闸全绿；`validate-flyway-immutability` 报"已冻结" |

### 11.1 批次 0 的 15 处凭据站点（精确清单，已实测）

| # | 位置 | 内容 |
| --- | --- | --- |
| 1 | `platform-services/admin/platform-admin-service/src/main/resources/db/migration/V2__saa_admin_account_seed.sql:1` | 注释含管理员初始口令明文 |
| 2 | `.../V3__seed_a380_admin.sql:1` | 同上（`a380-admin`） |
| 3 | `group-services/idaas/group-idaas-service/src/main/resources/db/migration/V2__seed_default_admin.sql:1` | 同上 |
| 4 | `platform-services/admin/.../src/test/java/.../AdminLoginApplicationServiceTest.java:25-26` | `SEED_PASSWORD` 常量 + 注释 |
| 5 | `docs/audit-2026-09-17-exceptions-and-i18n.md:196,203,715,736,990` | 5 处明文 + 可执行 curl 请求体 |
| 6-10 | `scripts/verify/verify-audit-platform.ps1:16`、`verify-currency-e2e.ps1:21`、`verify-reservation-room-type.ps1:20`、`verify-wallet-token-display.ps1:25`、`verify-a380-ktv-closure.ps1:91` | 默认登录口令 |
| 11 | `k8s/ack/platform-identity-service.yaml:30`（另有 `:34`） | JWT 签名密钥 / OAuth 客户端密钥明文 |

---

## 12. 覆盖回归表（审计发现 → 动作）

| 审计发现 | 覆盖动作 | 状态 |
| --- | --- | --- |
| B-1 明文管理员口令 | 批次 0（15 处清单）+ DC-01 | 已覆盖 |
| B-2 ACK 密钥 | 批次 0 + §4.3 | 已覆盖 |
| B-3 密钥门禁空转 | G-03 + G-13 + DC-20 | 已覆盖 |
| B-4 CI 未接入/镜像 Maven 版本 | G-01 + G-02 | 已覆盖 |
| B-5 platform-services 被排除 | G-04 + 批次 3 | 已覆盖 |
| B-6 客户端 CI 必然失败 | CL-B1（**已修正为两仓**）+ CL-B2 | 已覆盖 |
| B-7 `.env` 规范相反 | DC-05 + 批次 1 | 已覆盖 |
| H-1 `group-services` | DC-02 + 批次 2 + §7 | 已覆盖 |
| H-2 前缀未登记 | 批次 2 + §7 + G-12 | 已覆盖 |
| H-3 其它凭据/大二进制 | 批次 0 + DC-13 + DC-18 | 已覆盖 |
| H-4 MQ 台账 34 项 | 批次 2 + G-09 | 已覆盖 |
| H-5 历史迁移被改写 | §5 + G-07 | 已覆盖 |
| H-6 事务内 best-effort 发 MQ | 批次 3 + G-09 | 已覆盖 |
| H-7 481 处全路径 | 批次 3 + G-05 | 已覆盖 |
| H-8 103 处静默 catch | 批次 3 + G-06 | 已覆盖 |
| H-9 入口日志缺失 | 批次 3 | 已覆盖 |
| H-10 服务文档覆盖/路径 | 批次 4 + G-10 | 已覆盖 |
| H-11 5 个门禁名不副实 | G-03 + G-11 + G-12 | 已覆盖 |
| H-12 DDL 规范缺口 | 批次 2 + DC-06/11 + G-12 | 已覆盖 |
| H-13 文档失真 | 批次 4 + §6.1 | 已覆盖 |
| H-14 客户端分仓问题 | §9 CL-A* / CL-B* | 已覆盖 |
| 中低·架构与模块 / 构建 / 数据 / 输出治理 / 其它 | 批次 1/2/3 + G-11/G-12 + DC-17 | 已覆盖 |
| 客户端 `portal` 文档、域父 POM 命名、`im-access-ws` 版本属性 | 批次 1/2/4 | 已覆盖 |
| v7 N-01~N-11（已确认） | 批次 0/1/3/4 | 已覆盖 |
| **v8 N-12~N-18** | 见 §16.2 | 已覆盖 |

**结论：审查报告全部发现 + v7/v8 新增 18 项在 §11 均有唯一归属动作，无孤立项。**

---

## 13. 验收总闸

```powershell
# 0. 清库重建（PR-03）
# 1. 唯一工程门禁
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\invoke-engineering-validation.ps1 -Scope Full

# 2. 凭据清零（DC-20：历史值只存本地 gitignored 清单，仓库内无明文）
git grep -n -f .outputs/quality/rotated-credentials.txt        # 期望：无输出
git grep -n -e '192\.168\.31\.'                                # 期望：无输出

# 3. 迁移冻结与规范治理（DC-10/DC-08）
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\validate-flyway-immutability.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\validate-standards-governance.ps1

# 4. 留档可追溯性（DC-09）——实测 199 行
git ls-tree -r pre-baseline-consolidation-20261013 |
  Select-String 'src/main/resources/db/migration(-audit)?/.*\.sql$'

# 5. 无第二校验入口（原 G-14）
git grep -n -i 'release_manager\|verify.bat'                   # 期望：无输出

# 6. 根目录整洁（DC-19 / G-15）
Get-ChildItem 'D:\projects\cnb-oss' -Force |
  Where-Object { $_.Name -notin @('.github','open-green-pass','open-im-server','open-chat-app',
    'open-chat-admin','open-chat-desktop','open-chat-turn','open-saas-admin','open-saas-mobile','open-website') }
# 期望：无输出

# 7. 部署与交付清单（DC-01）
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\k8s.ps1

# 8. 业务回归：scripts/verify/*.ps1 全量
```

---

## 14. 附录 A：逐条确认清单

| 组 | 内容 | 状态 |
| --- | --- | --- |
| A.1 前提 | PR-01~PR-04 | ✅ 已确认 |
| A.2 决策 | DC-01~DC-18 | ✅ 已确认（DC-17 采纳 a；DC-18 已执行） |
| A.3 前缀与命名 | NR-01~NR-08 | ✅ 已确认 |
| A.4 门禁 | G-01~G-14 | ✅ 已确认 |
| A.5 客户端 | CL-A1~A8、CL-B1~B12 | ✅ 已确认（CL-B1 仓名已在 v8 修正） |
| A.6 批次与顺序 | BA-0~BA-5 | ✅ 已确认 |
| A.7 v7 新增 | N-01~N-11 | ✅ **本轮全部确认** |
| **A.8 v8 新增** | DC-19、DC-20、G-15、CL-A9、CL-B13、CL-B14、N-12~N-18 | ✅ **全部确认（按建议执行）** |

---

## 15. 附录 B：确认记录与仍未闭环

### 15.1 A.8 确认记录（2026-10-13，按建议执行）

| 项 | 采纳内容 |
| --- | --- |
| DC-19 | 采纳（已执行）：工作区根只允许仓库目录；`.vscode` 已删除，IDE 工作区级设置改放用户级；归档必须遵循目标目录既有命名与索引规范 |
| DC-20 | 采纳 **+ 两条约束**：`-RotatedCredentialFile` 参数**可选**（文件缺失只警告、不阻断，CI 不依赖本地文件）；仓库文档一律掩码，真值仅存 gitignored 的 `.outputs/quality/rotated-credentials.txt` |
| G-15 | 采纳断言内容，但**降级为本地/发布前脚本**（`scripts/validate/validate-workspace-root.ps1`，可选纳入 `-Scope Full`）——工作区根不是 git 仓库，进 CI 会形成永久空转门禁 |
| CL-A9 | 采纳 **"先迁移、后删除"**：先把 pub 国内镜像设置写入 `BUILD.md`（或 `scripts/flutter-env.ps1`），再删除 `.pubrc` |
| CL-B13 | 采纳：三个契约测试移入 `test/`；**无需改 `package.json`**（无 vitest 配置，默认 include 覆盖） |
| CL-B14 | 采纳，**范围修正为 3 仓**：`open-chat-desktop`、`open-saas-mobile` 补 `.vscode/`+`.idea/`；`open-chat-turn` 补 `.vscode/` |
| N-12 / N-15 / N-16 / N-18 | 采纳：N-12 仓名已修正；N-15/N-18 已执行；N-16 在 `standards/30` 补"读取也必须显式 UTF-8"并在 `IDE_SETUP` 加提示 |

> **至此方案无待决项**：PR-01~04、DC-01~20、NR-01~08、G-01~15、CL-A1~A9、CL-B1~B14、BA-0~5、N-01~N-18 全部确认。

### 15.2 仍未闭环

- 未给出人力与日期（需项目管理输入）。
- 客户端 8 仓未逐仓单独排期，并入批次 1/4。
- §10 的 7 项需仓库外信息验证后方可关闭。
- `open-im-server` 工作树当前有 **8 改 + 28 删（已暂存）+ 2 个新归档文档（未跟踪）**，提交时机由你决定。

---

## 16. 附录 C：深查新增发现

### 16.1 v7 已确认（N-01~N-11）

| 编号 | 发现 | 归属 |
| --- | --- | --- |
| N-01 | `MqProperties.java:13-16` 死/误导字段（`producerGroup` 默认字面量、`consumerGroupPrefix` 仅日志引用、`accessKey`/`secretKey` 全仓 0 引用） | 批次 1 |
| N-02 | **10 个服务**在 `application.yml` 自持 `producer-group` 默认字面量，违反 `standards/12:24` | 批次 1 + G-09 |
| N-03 | `AppConstants.java:16-17` 含 IM 业务常量（`MSG_DEDUP_TTL_SEC`、`RECALL_WINDOW_MS`） | 批次 3 |
| N-04 | 实际弃用 API 使用（`WebJsonConfig.java:22`、`AuditDetailSanitizer`），门禁只查声明 | 批次 1 + 批次 3 |
| N-05 | `publish-images.yml:54` 裸 `mvn -T 1C clean verify` + tag 解析粗糙 | 批次 1 + G-02 |
| N-06 | 文档分层无归属（docs 根 12 份、superpowers 11 份、根 `RELEASE-NOTES-*.md`） | 批次 4 |
| N-07 | 一次性运维/回填脚本与游离 Java（含 0 引用的 `repair-zombie-…sql`） | 批次 4 |
| N-08 | 4+ 个验收脚本硬编码 `192.168.31.91` | 批次 0 |
| N-09 | `run-dev.ps1:2` 仅覆盖 8/20 个可部署服务 | 批次 3 |
| N-10 | `validate-secret-exposure.ps1:20` 在"已删除未提交"文件上崩溃（本轮实测触发） | 批次 1 + G-03 |
| N-11 | 范围界定：`.github`（组织级仓）、`.vscode`、`open-green-pass` 不属本次范围 | 记录项 |

### 16.2 v8 新增（已确认，按建议执行）

| 编号 | 发现（含证据） | 归属动作 | 默认 |
| --- | --- | --- | --- |
| N-12 | **CL-B1 仓名歧义修正**：实测 `open-chat-admin`（`.gitignore:9` 忽略 lockfile，未跟踪）与 `open-chat-turn`（`.gitignore:10` 同理）才是 `npm ci` 必然失败的仓；`open-saas-admin`/`open-saas-mobile` 的 `package-lock.json` **已被跟踪且无忽略规则**，属合规 | 修正 §9.2 CL-B1 表述，避免误改合规仓 | 确认 |
| N-13 | `open-saas-mobile` 三个契约测试文件位于**仓库根**（`currency-contract.test.js`、`media-url-contract.test.js`、`token-display-contract.test.js`），与其他仓 `test/` 约定不一致。已核实无 `vitest.config`、vite 配置无 `test` 段 → 移动后 `vitest run` 默认 include 即可覆盖，无需改脚本 | 新增 CL-B13：迁入 `test/` | 确认 |
| N-14 | `open-chat-app/.pubrc` **不是配置文件**，而是一段不可执行的批处理片段（`set PUB_HOSTED_URL=https://pub.flutter-io.cn` / `set FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn` / `flutter pub get`，121 字节，2026-09-20 创建），全仓无引用；且该镜像设置**未在任何文档中记录** | 新增 CL-A9：先把镜像设置写进 `BUILD.md`（或 `scripts/flutter-env.ps1`），再删除 `.pubrc` | 确认 |
| N-15 | **根目录污染**：工作区根出现 VS Code Java 扩展自动生成的 `.vscode/settings.json`（创建于 2026-10-12 08:11，早于本次审查；内容仅一行 `java.jdt.ls.vmargs`）。此外两份审查/方案文档原本也落在工作区根 | DC-19：`.vscode` 已删除（防再生成：该设置改放用户级）；两份文档已脱敏归档到 `docs/renovation/`；新增 G-15 根目录整洁断言 | 确认（已完成） |
| N-16 | **读取编码陷阱（本轮实测）**：本机 PowerShell 5.1 用 `Get-Content` 读取 UTF-8 无 BOM 文件时按 GBK 解码，导致归档文档显示为乱码（用 .NET 显式 UTF-8 复核后确认文件完好）。同一陷阱早前在 `.mvn/wrapper/maven-wrapper.properties` 上制造过一次假阳性 | `standards/30` 增加"读取同样必须显式指定 UTF-8"；审查方法固定使用显式 UTF-8 读取 | 确认 |
| N-17 | **编辑器目录忽略覆盖不全（实测修正）**：`open-chat-desktop`、`open-saas-mobile` 的 `.gitignore` 对 `.vscode`/`.idea`/`.trae` 命中数为 **0**；`open-chat-turn` 仅忽略 `.idea`，**缺 `.vscode`** → 合计 **3 仓**存在编辑器目录被误提交的风险（正是工作区根 `.vscode` 事件的同类问题）。其余 5 仓已忽略 | 新增 CL-B14：3 仓补齐 `.vscode/`（desktop/mobile 还需 `.idea/`），统一 8 仓模板 | 确认 |
| N-18 | **归档位置与命名不合规（自审发现，已修正）**：审查报告与整改方案最初落在**工作区根目录**，且文件名带日期（`..._AUDIT_2026-10-12.md`），违反 `docs/renovation/README.md:9-19` 的 `<SERIES>_<NN>_<AREA>.md` 规范（"文件名不包含 PLAN、RENOVATION、日期、版本号…"），并与既有 `ENGINEERING_GOVERNANCE_01_CONFORMANCE.md` 的序号冲突 | 已改名为 `ENGINEERING_GOVERNANCE_02_AUDIT.md` / `ENGINEERING_GOVERNANCE_03_REMEDIATION.md`，补"方案集/序号/前置/后置"声明，并登记进 `docs/renovation/README.md`；DC-19 增加"归档必须遵循目标目录既有的命名与索引规范" | 确认（已完成） |

### 16.3 批次 0 执行期间的新发现（N-19~N-23）

| 编号 | 发现（含证据） | 归属动作 |
| --- | --- | --- |
| N-19 | **本地媒体/MinIO 凭据模型不一致（既存缺陷，非本次轮换引入）**：`.env` 与生效 Secret 中 `MEDIA_ACCESS_KEY` 与 `MINIO_ROOT_USER` **取值相同**（`local-minio-access-key`），但 `MEDIA_SECRET_KEY` **不等于** `MINIO_ROOT_PASSWORD`。而 `k8s/local/minio-init-job.yaml:24`、`k8s/ack/minio-init.yaml:25` 的本意是用 `mc admin user add` 创建**独立 media 服务账号** → 本地媒体服务以 root 用户名 + 不匹配的口令访问 MinIO，上传链路应为失败状态 | 批次 2/3：把 media 改为独立 MinIO 用户（`mc admin user add` + policy），令 `MEDIA_ACCESS_KEY ≠ MINIO_ROOT_USER`；随后再轮换 MinIO 凭据。（本次**未**轮换 MinIO，避免扩大破坏面） |
| N-20 | `scripts/verify/verify-a380-ktv-closure.ps1:99` 的 SQL 字面量含中文（`'E2E 房型 …'`）且文件**无 BOM**；同目录另外 4 个验收脚本都在头部显式声明"必须 ASCII-only"，因为 Windows PowerShell 5.1 会把无 BOM 的 `.ps1` 当 ANSI 读取（解析/乱码风险）。该 6 个非 ASCII 字节在 HEAD 版本即存在 | 批次 4：该文件改为纯 ASCII，或改用 `-Encoding UTF8` 的调用约定并统一加 BOM |
| N-21 | **库名口径分裂（强化 DB-6）**：实际存在 3 个库——`im_server`（开放平台表 `open_application`）、`open_im`（`saa_admin_account`/`group_account` 等主表）、`open_audit`（审计表）；而 `.env` 的 `DB_DATABASE=im_server` 在多数服务中**未被使用**（服务读 `${MYSQL_DATABASE:open_im}`，Secret 中并无 `MYSQL_DATABASE` 键）。与 `ENGINEERING_RULES:154` 的 `gv_im`/`gv_saas` 口径亦不一致 | 批次 2（DB-6）：统一库名口径与配置键名，并同步文档、Compose/K8s 与迁移 |
| N-22 | **移除密钥默认值必须配套测试配置**：删除 5 处 `${INTERNAL_SERVICE_AUTH_SECRET:默认值}` 后，`platform-order-service` 的 12 个 `@SpringBootTest` 类因缺少该变量而 `Failed to load ApplicationContext`（门禁自伤）。已为 payment/customer/order 的 `src/test/resources/application-test.yml` 补**测试专用占位值**（生产仍无默认值，符合 fail-fast 语义；customer 原本已有 `jwt.secret` 测试值的先例） | 已修复；作为规则写入 G-13：**移除默认值必须同时提供测试侧配置** |
| N-23 | **本地 Kind 无持久化卷**：命名空间内 **0 个 PVC**；`mysql`/`minio` 使用 `emptyDir`（`k8s/local/infrastructure.yaml:29,111`），`mongodb` 连数据卷都没有（数据在容器层）。因此重建这三个 Pod 会**清空数据** → 轮换不能"重启一切"，必须按组件区分"服务端存储型（ALTER/changeUserPassword，不重启）"与"启动读取型（改 Secret + 重启）" | 已按此原则执行本轮轮换；批次 2 的基线重建需配合清库重建（PR-03） |

---

## 17. 批次 0 执行记录（2026-10-13）

### 17.1 仓库侧（已完成）

| # | 动作 | 验证 |
| --- | --- | --- |
| 1 | `AdminLoginApplicationServiceTest` 的硬编码种子口令与哈希 → 改为运行时生成（`TEST_PASSWORD`/`TEST_HASH`） | 仓库内不再出现该口令或其哈希 |
| 2 | `docs/audit-2026-09-17-exceptions-and-i18n.md` 的 5 处明文（含可执行 curl）脱敏 | 该文件 `e828…` 残留 0 |
| 3 | 5 个验收脚本：口令默认值 → `$env:IM_SAAS_ADMIN_PASSWORD`；个人 LAN IP `192.168.31.91` → `127.0.0.1`；`verify-a380-ktv-closure.ps1` 新增 `-AdminUser/-AdminPassword` 参数替换内联口令 | 5 文件残留 0；ASCII 纯度保持（a380 的 6 个非 ASCII 字节为既存，见 N-20） |
| 4 | `k8s/ack/platform-identity-service.yaml` 的 `JWT_SECRET`、`IM_OAUTH_APP_SECRET` → `secretKeyRef` | 全仓不再出现该 JWT 密钥值 |
| 5 | 5 处 `${VAR:默认密钥}` → `${VAR}`（audit/payment/customer/order/idaas） | 残留 0；配套测试占位值已补（N-22） |
| 6 | 补齐 **10 个** ACK deployment 的 `INTERNAL_SERVICE_AUTH_SECRET` 注入（edge/idaas/saas-common×2/saas-domain×3/saas-support×2） | 5 个清单 `kubectl apply --dry-run=client` 全部 OK |
| 7 | 删除 3 个 `.outputs/idaas-cookie-*.txt` 会话捕获文件；清理 `.outputs` 根目录散落日志 | cookie 文件 0；`.outputs` 根目录已空 |
| 8 | 建立 `.outputs/quality/rotated-credentials.txt`（旧值，gitignored，供门禁比对与回滚） | 已确认被 `.gitignore:13` 覆盖 |
| 9 | 移除 2 处**代码级**审计兜底密钥：`sdk/infrastructure/.../AuditClientConfig.java:20`（`DEV_FALLBACK_SECRET`）与 `im-services/admin/.../AuditQueryClient.java:32`，改为 `@Value("${internal.service-auth.secret}")` 必需属性；其 javadoc 自述的"ACK 未注入"前提已被第 6 项修复，故兜底可安全移除 | 两文件 `DEV_FALLBACK_SECRET` 残留 0；sdk/infrastructure 与 im-admin-service 均无 `@SpringBootTest`，无需补测试值 |
| 10 | `scripts/verify/verify-wallet-token-display.ps1:12` 注释中的旧 IP 一并替换为 `127.0.0.1` | 该文件残留 0 |

### 17.2 运行环境（已完成，Kind `open-im-local`）

| # | 动作 | 验证 |
| --- | --- | --- |
| 9 | MySQL `ALTER USER`（root@localhost/root@%/im_user@%） | 新口令可用；**旧口令 Access denied** |
| 10 | MongoDB `changeUserPassword(im_chat_app)` | 新口令 `ping.ok=1`；旧口令认证失败 |
| 11 | `.env` 11 个密钥更新 + `open-im-env`、`open-chat-turn-credentials` Secret 更新 | Secret 应用成功（configured） |
| 12 | redis + 25 个应用滚动重启（**刻意跳过** mysql/mongodb/minio/rocketmq，避免 emptyDir 丢数据，见 N-23） | 31/31 deployment `1/1`；重启后日志**零凭证错误**；门户 30080 HTTP 200 |
| 13 | 管理员口令轮换为**专用**口令（admin / a380-admin / 集团 `group_account.admin`） | 改密接口 200；新口令登录 200；**泄漏口令 401**、过渡口令 401 |
| 14 | `saas-ktv` 开放平台应用密钥轮换 | `open_application.app_secret_hash` 指纹已变更 |
| 15 | 生成访问与凭据清单 | `.outputs/releases/kind-20261013-150444-access.md`（gitignored，含端口映射与账号） |

### 17.3 推迟到批次 2 的项（附原因）

| 项 | 原因 |
| --- | --- |
| 3 个 Flyway 种子脚本的明文注释（`V2__saa_admin_account_seed.sql:1`、`V3__seed_a380_admin.sql:1`、`group-idaas-service/V2__seed_default_admin.sql:1`） | 这些脚本**已执行**，现在改写会造成 `flyway_schema_history` checksum 失配 → 运行中的服务一旦重启即崩溃。批次 2 的基线合并（DC-09）会将其重写为干净基线，并配合清库重建。**其对应的凭据已在运行环境失效**（17.2 第 13 项），因此不构成现行可利用风险 |
| `V11__init_open_platform.sql:45-50` 的 `saas-ktv` 密钥哈希 | 同上（checksum）；运行库中的密钥已轮换 |

### 17.4 未轮换项

| 项 | 原因 |
| --- | --- |
| `MINIO_ROOT_PASSWORD` / `MEDIA_ACCESS_KEY` / `MEDIA_SECRET_KEY` | N-19：媒体服务账号模型本身不一致，需先修复再轮换 |
| `ROCKETMQ_ACCESS_KEY` / `SECRET_KEY` | 本地未启用 ACL，仅占位（仍已随 Secret 更新，避免残留旧值） |

### 17.5 批次 0 后仍在库的凭据/IP 残留（明确记账，避免"以为清完了"）

| 类别 | 位置 | 处置 |
| --- | --- | --- |
| Flyway 种子明文（推迟） | `platform-admin-service/V2__saa_admin_account_seed.sql:1`、`V3__seed_a380_admin.sql:1`、`group-idaas-service/V2__seed_default_admin.sql:1`、`im-user-service/V11__init_open_platform.sql:45-50` | 批次 2 基线重建（checksum 约束，见 §17.3）；对应凭据已失效 |
| 历史审计文档中的内网 IP（非凭据） | `docs/audit-2026-09-17-exceptions-and-i18n.md`（7 处 `192.168.31.91`） | 批次 4 文档分层收敛时一并处理；`docs/deployment/LOCAL.md:122` 的引用是"旧入口已废弃"的历史说明，**保留** |
| 测试中的内网 IP | `im-services/user/im-user-service/src/test/java/.../OpenPlatformApplicationServiceTest.java:79`（`lanCallback = "http://192.168.31.91:30082/a380/"`） | 批次 3 改为参数化或示例地址 |
| 环境级弱口令 | `.outputs/quality/rotated-credentials.txt` 中的旧值（gitignored，本地留存） | 与仓库无关；如需彻底清理可删除该文件（会失去回滚参考） |

---

## 18. 批次 1 执行记录（门禁与规范，进行中）

### 18.1 已完成

| 门禁 | 动作 | 状态 |
| --- | --- | --- |
| **G-12 DDL 规范** | 新增 `validate-flyway-conventions.ps1`（取代只看单文件的 `validate-flyway-module-boundaries.ps1`）：扫描全部 18 个迁移根的 159 张表，校验前缀登记、单数命名、`ENGINE/CHARSET/COLLATE`、主键（含 DC-17 分区表放行）、4 个审计字段、索引命名、外键禁令、`IF NOT EXISTS`/`DROP TABLE`/动态 DDL、注释、`datetime(3)` | **报告模式**，worklist **418 条 / 72 文件**（批次 2 清零） |
| **G-13 密钥默认值 + B-3 密钥门禁** | 重写 `validate-secret-exposure.ps1`：新增**值位置**检测（K8s inline `value:`）、PowerShell 参数默认值、`${VAR:默认值}` 默认值检测、历史值比对（`-RotatedCredentialFile`，缺文件只告警）、**缺失文件不再崩溃**（N-10）；新增带理由的误报分类清单 `secret-exposure-allowlist.txt`（6 条） | **报告模式**，worklist **4 条**（= 已知的 4 处迁移明文，批次 2 清零）；其余 0 误报 |
| **G-07 迁移冻结** | 新增 `validate-flyway-immutability.ps1`：无基线 tag 时输出"未冻结、允许重建"；有冻结 tag 后任何 `M/D` 迁移即失败（DC-10 语义） | **已阻断**（当前通过） |
| **G-14 单一门禁入口** | 新增 `validate-repository-hygiene.ps1`：断言仓库内无第二校验入口（`release_manager`、游离 `verify.bat`） | **已阻断**（当前通过） |
| **G-15 根目录整洁** | 同一脚本：工作区根白名单断言 + 仓库根 stray 文件（`*_dump.txt`/`cursor.mark`/`*.orig`）断言 | **已阻断**（当前通过） |
| 报告模式机制 | 新增 `scripts/validate/report-only-gates.txt`：逐条记录"门禁 \| 清零批次 \| worklist \| 原因"，统一入口据此决定阻断/报告；**worklist 归零后必须删除条目**（禁止永久豁免） | 已生效 |
| 入口改造 | `invoke-engineering-validation.ps1`：门禁数 16 → **18**，新增 3 个、替换 1 个；报告模式门禁在运行前显式打印提示 | 已生效，`-Scope Changed` 退出码 **0** |
| 引用清理 | 删除 `validate-flyway-module-boundaries.ps1`，并把 `DATABASE_AUDIT_REPORT.md`、`PLATFORM_SECURITY_01_GOVERNANCE.md` 中的引用改指新门禁 | 无残留引用 |

### 18.2 关键发现：校验脚本必须 ASCII-only

新门禁最初用中文正则匹配 `ENGINEERING_RULES.md` 的小节标题，结果 Windows PowerShell 5.1 把**无 BOM 的 .ps1** 按 ANSI 读取，正则被打乱导致脚本抛异常。这与 N-16/N-20 是同一根因，也是仓库现有校验脚本全部 ASCII 的原因——但该约定此前**未成文**。已改为：脚本保持 ASCII，登记表数据放 `scripts/validate/table-prefixes.txt`（并在脚本中断言该表仍出现在规范文档中，防止两处漂移）。

### 18.3 批次 1 剩余（下一轮）

| 项 | 说明 |
| --- | --- |
| `validate-security-and-flyway-configuration.ps1` | 仍指向不存在的 `services` 目录（DDL 段空转，H-11）；需改为扫描四个域根（其 IF NOT EXISTS 检查与 G-12 重叠，可改为只保留 Flyway/密钥配置断言） |
| `validate-service-api-boundaries.ps1` | 仍排除 `platform-services`（G-04）；需纳入 4 个域根并删除豁免注释与 `.agents/notes` 引用 |
| `validate-java-comment-quality.ps1` | 仅查乱码；需补空 javadoc、截断注释、关键类缺类注释 |
| `validate-java-deprecated-api-usage.ps1` | 仅查 `@Deprecated` 声明；需补上游弃用用法检测（并修 2 处实际用法） |
| `validate-tenant-sql.ps1` | 白名单硬编码（G-11）；需改为"默认必须含 tenant_id + 例外显式登记"，并覆盖 `ord_*`/`saa_*`/`iam_*` 与原生 SQL 查询级断言 |
| G-05 全路径门禁 / G-06 catch 门禁 / G-09 MQ 台账比对 / G-10 文档链接与索引 | 尚未实现 |
| G-01/G-02 CI 接入与 `publish-images.yml` 硬化 | 尚未实施 |
| DC-08 的 11 处规范文本更正 | 尚未实施 |
| `MqProperties` 死配置清理 + 生产者组常量集中（N-01/N-02） | 尚未实施 |

### 18.4 批次 1 第二批（本轮完成）

| 项 | 动作 | 验证 |
| --- | --- | --- |
| **G-04 API 边界门禁** | 重写 `validate-service-api-boundaries.ps1`：覆盖 **4 个域根**（含此前被整体排除的 `platform-services`），删除豁免注释与 `.agents/notes` 引用；新增"application 层不得依赖 mapper/po/repository"规则 | 违规数从 **0（假绿）→ 137 条**，worklist 落 `.outputs/quality/service-api-boundaries-worklist.txt`（批次 3 清零），当前报告模式 |
| **H-11 Flyway 配置门禁** | 重写 `validate-security-and-flyway-configuration.ps1`：删除扫描不存在 `services` 目录的**空转 DDL 段**（该职责已由 G-12 承担）；改为**按文件系统发现**全部持有迁移的模块（原为硬编码 5 个服务），校验 Flyway 启用/history 表/pom 依赖/无迁移却声明 Flyway/密钥默认值 | **已阻断且通过**（0 违规）；顺带发现并修正 `${JWT_EXPIRATION_MS:...}` 时长型误报（键名含 `JWT` 被误判） |
| **G-01 CI 接入** | `.cnb.yml` 重写：底座仅需 JDK 25（`eclipse-temurin:25-jdk`），**显式安装 Maven 3.9.16 与 PowerShell 7**，单阶段调用唯一入口 `-Scope Full` 并阻断非零退出 | 消除了"依赖特定 maven+jdk 镜像 tag"与"镜像 Maven 版本低于门禁要求"两类风险（原 B-4 根因） |
| **G-02 跨平台入口** | `invoke-engineering-validation.ps1`：按平台选择 `pwsh`/`powershell`（`-ExecutionPolicy` 仅 Windows 传）与 `mvnw`/`mvnw.cmd`；运行前打印所选入口 | 本地实测 `shell=powershell, mavenWrapper=mvnw.cmd` |
| **G-02 `publish-images.yml`** | `mvn -B -T 1C clean verify`（裸 `mvn` + 并行 `clean`，违反 `standards/20:95/98`）→ `./mvnw -B -ntp clean verify` | 已改 |
| **附带修复：`mvnw` 无可执行位** | 实测 `git ls-files -s mvnw` 为 **100644**（Linux 上 `./mvnw` 会 Permission denied，CI 必失败）→ `git update-index --chmod=+x mvnw` | 现为 **100755**，首行 `#!/bin/sh` |

### 18.5 批次 1 剩余（下一轮）

| 项 | 说明 |
| --- | --- |
| `validate-tenant-sql.ps1`（G-11） | 白名单硬编码；需改为"默认必须含 tenant_id + 例外显式登记"并覆盖 `ord_*`/`saa_*`/`iam_*` 与原生 SQL 查询级断言 |
| `validate-java-comment-quality.ps1` | 仅查乱码；需补空 javadoc、截断注释、关键类缺类注释 |
| `validate-java-deprecated-api-usage.ps1` | 仅查 `@Deprecated` 声明；需补上游弃用用法检测（并修 2 处实际用法） |
| G-05 全路径 / G-06 catch / G-09 MQ 台账比对 / G-10 文档链接与索引 | 尚未实现 |
| DC-08 的 11 处规范文本更正 | 尚未实施 |
| `MqProperties` 死配置清理 + 10 处 producer-group 字面量集中（N-01/N-02） | 尚未实施 |
| CNB 首次实跑校验 | 本环境无法访问 Docker Hub / GitHub，镜像与 PowerShell 下载 URL 需在 CNB 上实跑一次确认（对应 §10 的 V-1：CI 阻断语义亦未经实测） |

### 18.6 批次 1 第三批（本轮完成）：G-10 文档链接与索引门禁

| 项 | 动作 | 结果 |
| --- | --- | --- |
| **G-10 门禁实现** | 新增 `validate-documentation-links.ps1`：① 校验全部受版本控制 `.md` 的相对链接可解析（忽略锚点/绝对 URL/mailto）；② 断言根 `README.md` 文档索引覆盖 `docs/standards/` 全部文件 | 首次运行报 **19 条**（14 条死链 + 5 条索引缺失），与审查报告 H-13 完全一致 |
| **修复死链** | `README.md` 11 处（`docs/im-services/<service>/` → 实际存在的 `docs/services/<service>/`；`im-order-service` 改为实际模块路径 `platform-services/order/platform-order-service/`）、`docs/renovation/USER_DOMAIN_01_DDD.md` 2 处、`docs/standards/15_CLIENT_RELEASE_SKILL.md` 3 处（`../../../gv_chat_app/` → `../../../open-chat-app/`；`../../RELEASE_RUNBOOK.md` → `../RELEASE_RUNBOOK.md`） | 已清零 |
| **补齐索引** | `README.md` 文档索引补 `13 平台媒体规范`、`14 客户端发布与版本治理`、`15 客户端发布操作`、`16 币种规范`、`43 E2E 测试与验收规范` | 已清零 |
| **接入为阻断门禁** | 加入统一入口（门禁数 18 → **19**） | `-Scope Changed` 退出码 **0**，`Documentation link and index validation passed.` |
| 经验 | 上一次用命令行内联替换把脚本改坏；本轮改为把替换对写入 `.outputs/quality/link-fixes.tsv` 再由脚本读取应用（13 条规则、16 处替换） | 无损坏，可复用该做法 |

### 18.7 批次 1 第四批（本轮完成）：DC-08 规范文本自更正（12 处）

| # | 文件 | 更正内容 |
| --- | --- | --- |
| 1 | `ENGINEERING_RULES:16` | `sdk/` 允许项：经专项规范批准的跨域语义组件（`Currency`/`CurrencyResolver`、`StoreTimeService`、`AuditActions`）**合法化**并注明登记要求；其余业务实现依旧禁止 |
| 2 | `ENGINEERING_RULES:24` | 与 `standards/20:45` 及门禁脚本对齐：**API 叶子不声明版本**；可部署 Service 叶子仅可声明 `<域基础版本>-SNAPSHOT`；网关自持版本 |
| 3 | `ENGINEERING_RULES:132` | `.env` 改为"只提交模板、`.env` 本机生成且被 gitignore、不得写入共享凭据"，以 `PLATFORM_SECURITY_01` 安全整改为准（**消除规范与实现相反的缺陷**） |
| 4 | `ENGINEERING_RULES:133` | 密钥豁免机制改为"不受版本控制 / 显式分类清单（每条需理由）"，并说明历史值比对走 gitignored 的 `rotated-credentials.txt` |
| 5 | `ENGINEERING_RULES:188` | 补 DC-17：MySQL 分区表主键必须包含分区列属**引擎约束**而非业务例外 |
| 6 | `ENGINEERING_RULES:221` | 补 **冻结点定义**（未发布前允许重建基线并留档；冻结以显式基线 tag 为准；冻结后 `M`/`D` 由 G-07 阻断） |
| 7 | `standards/22:19` | sdk 版本 `1.0.5` → `2.2.0`，并说明平台 BOM 语义由根 POM 承担 |
| 8 | `standards/12:17` | "数字和点号" → "数字和下划线"（与同页格式表一致） |
| 9 | `standards/20:44` | sdk 描述与实现对齐（纯聚合 POM；平台版本由根 POM `sdk.version` 持有） |
| 10 | `standards/20:154` | 消除自相矛盾：明确正文出现的 `im-access-ws`/`gateway`/`im-services/user` 属**示例** |
| 11 | `standards/20:163` | "发布管理器" → "发布流程"（该工具已移出仓库） |
| 12 | `standards/30:69` | 补"**读取**同样必须显式 UTF-8"，并把"校验脚本必须 ASCII-only"写成明文约定（N-16/N-20 的根因） |

应用方式：12 条单行替换写入 `.outputs/quality/standards-fixes.tsv` 后由脚本应用，**12/12 命中、0 漏配**；统一门禁 `-Scope Changed` 退出码 **0**。

**DC-08 仍未完成的部分**：`ENGINEERING_RULES:29-50` 目录模板补 `gateways/` 与 `group-services/`；`standards/00` 增"权威索引"节；`standards/42` 目录口径补全（`.outputs/logs/debug`、`quality`、`releases`）；`standards/22` 补 `jetbrains.annotations`/`error-prone-annotations` 两行；以及把"新增规范必须过冲突审查"落成 G-08 门禁（`validate-standards-governance.ps1`，尚未实现）。

### 18.8 批次 1 第五批（本轮完成）：G-08 规范治理门禁

| 项 | 动作 | 结果 |
| --- | --- | --- |
| **G-08 门禁实现** | 新增 `validate-standards-governance.ps1` + 数据文件 `standards-governance-rules.txt`（中文关键词与规则数据放数据文件，**脚本保持 ASCII-only**，与 G-08 要教别人的原则一致）：① 七项最小模板章节齐备（按标题匹配）；② 禁止内容（绝对路径、项目模块名、待办/占位标记）；③ 标准文件可达性 | 首次运行 **107 条**：模块名混入规范 **23**、缺模板章节 **68**（`template` 13、`terms` 13、`acceptance` 11、`relations` 9、`gate` 8、`goal` 7、`rules` 7）、待办标记 **11**、绝对路径 **5** |
| **接入** | 加入统一入口（门禁数 19 → **20**），并按机制置于**报告模式**（worklist 有限，批次 4 清零后删除条目转为阻断） | `-Scope Changed` 退出码 **0**，`Gate 'standards-governance' is in REPORT mode` |
| 价值 | 这是唯一能**防止规范自相矛盾再次发生**的机制：模块名/绝对路径/待办一旦写进 `standards/`，门禁立即报出；DC-08 剩余的 5 处也因此被自动盯住 | — |

**当前报告模式门禁（4 个，均有有限 worklist 与清零批次）**：`flyway-conventions`（418，批次 2）、`secret-exposure`（4，批次 2）、`service-api-boundaries`（137，批次 3）、`standards-governance`（107，批次 4）。

### 18.9 批次 1 第六批（本轮完成）：G-05 全路径引用门禁 + G-06 静默 catch 门禁

| 项 | 动作 | 结果 |
| --- | --- | --- |
| **G-06 静默 catch** | 新增 `validate-java-silent-catch.ps1`：对每个 `catch` 块做**花括号配平**提取块体，要求块内含日志调用、`throw`/rethrow 或 `addSuppressed`；空 catch 单独标注 | 命中 **103 处**——与审查报告 J-04（103 个无 log 无 rethrow、22 个空块）**完全一致**，验证了启发式的准确性 |
| **G-05 全路径引用** | 新增 `validate-java-fully-qualified-names.ps1`：代码行（排除 package/import/注释）中出现已知根包（`io.openware`/`java`/`javax`/`jakarta`/`org`/`com`）+ ≥2 个点 + 末段 UpperCamelCase 即报告 | 命中 **192 处**（审查报告的 481 处来自更宽的正则：含 JDK 320/Spring 91 等；本门禁采用保守规则以控制误报，因此**少于**审查计数，未覆盖的短形式需在批次 3 一并处理） |
| 接入 | 两者加入统一入口（门禁数 20 → **22**），并置于**报告模式**（清零批次 3） | `-Scope Changed` 退出码 **0** |

**报告模式门禁现状（6 个，均有有限 worklist 与清零批次）**：

| 门禁 | worklist | 清零批次 |
| --- | --- | --- |
| `flyway-conventions` | 418 | 批次 2（基线重建） |
| `secret-exposure` | 4 | 批次 2（基线重建） |
| `service-api-boundaries` | 137 | 批次 3（分层收敛） |
| `java-silent-catch` | 103 | 批次 3（代码批改） |
| `java-fully-qualified-names` | 192 | 批次 3（代码批改） |
| `standards-governance` | 107 | 批次 4（规范清理） |

其余 **16 个门禁已阻断**。批次 3 的全部工作量现已由 worklist 精确表达（合计 432 条代码项），可按清单推进而无需人工回忆。

### 18.10 批次 1 第七批（本轮完成）：G-09 MQ 台账双向比对

| 项 | 动作 | 结果 |
| --- | --- | --- |
| **G-09 门禁实现** | 新增 `validate-mq-registry.ps1`：① `sdk/protocol-mq` 的 Topic/Producer/Consumer 常量必须出现在 `docs/mq/REGISTRY.md`；② 台账条目必须有对应常量（反向漂移）；③ 配置文件不得自行声明 producer/consumer 组字面量（`${VAR}` 引用除外） | 命中 **44 条**：**34 条未登记资源**（与审查报告 H-4 的"34 of 69 未登记"**完全一致**）、**10 处配置字面量**（与 N-02 的 10 个服务一致）、反向漂移 **0** 条（证明台账不虚构） |
| 排错过程 | 首版路径 glob 写多了一层（`sdk/protocol-mq/*/src/main/java/*.java`），导致协议常量一个都没提取到，于是虚假报出"台账有、代码无"9 条；改为 `sdk/protocol-mq/**/*.java` 后恢复正常 | 教训：门禁自身的路径规则也要用"已知存在的样本"自检（本轮以 34/10 两个已知数字交叉验证） |
| 接入 | 加入统一入口（门禁数 22 → **23**），置于**报告模式**（台账补登记属批次 2、组名集中属批次 1） | `-Scope Changed` 退出码 **0** |

**报告模式门禁现状（7 个）**：`flyway-conventions` 418、`java-fully-qualified-names` 192、`service-api-boundaries` 137、`standards-governance` 107、`java-silent-catch` 103、`mq-registry` 44、`secret-exposure` 4。合计 **1005 条**待清项，全部有明确清零批次与 worklist 文件。

### 18.11 批次 1 第八批（本轮完成）：G-11 租户隔离门禁重设计

| 项 | 动作 | 结果 |
| --- | --- | --- |
| **白名单反转** | 旧版用硬编码前缀白名单，白名单外的表（`ord_*` 除 `ord_order*`、`saa_*`、`iam_*`）**完全不被检查**，且只断言建表正文含 `tenant_id` 字面量。新版改为**默认必须有 `tenant_id`，全局表必须登记**（`tenant-platform-tables.txt`，每条需理由） | 首次运行 **51 条**：表级 10、查询级 41 |
| **表级清零** | 经核实 10 张表确为全局表：`idt_*`（4，身份/账号平台表）、`iam_audit_archive_manifest`、`support_media_*`（5，通用支撑域，归属由 `owner_id` + 引用表维护）→ 全部登记并写明理由 | 表级 **10 → 0** |
| **查询级断言（新增能力）** | 扫描 mapper 的原生 SQL，凡触及租户域表而生疏 `tenant_id` 者报告；并允许**显式标注** `platform-wide` / `tenant-agnostic` 的平台级扫表（采纳审查报告 M-7 的建议） | 剩 **41 条**（集中在审计分区维护、平台级簿记类 SQL），作为批次 2 的 worklist |
| **覆盖范围修正** | 入口原为 `-Path platform-services`（只扫一个根），改为默认扫描 `platform-services` + `common-services` | 入口参数已更新 |
| **排错** | `Get-ChildItem -LiteralPath $dir` 传 `DirectoryInfo` 会被转成相对名 `migration` 导致找不到路径；须用 `.FullName` | 已修 |
| 接入 | 加入**报告模式**（清零批次 2） | `-Scope Changed` 退出码 **0** |

**报告模式门禁现状（8 个）**：`flyway-conventions` 418、`java-fully-qualified-names` 192、`service-api-boundaries` 137、`standards-governance` 107、`java-silent-catch` 103、`mq-registry` 44、`tenant-sql` 41、`secret-exposure` 4 —— 合计 **1046 条**待清项，全部有 worklist 与清零批次。**批次 1 的门禁实现工作到此收尾**（G-01~G-15 全部落地）。

