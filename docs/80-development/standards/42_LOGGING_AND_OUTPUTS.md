# 日志输出与诊断规范

[中文](42_LOGGING_AND_OUTPUTS.md) | [English](42_LOGGING_AND_OUTPUTS.en.md)

## 目标与适用范围

**目标**：让构建、校验、发布与运行产生的日志和诊断产物**各归其位、可被脚本消费、不污染仓库根目录**，并让排障时有确定的查找路径。

**适用范围**：构建、验证、打包、部署与运行期间的日志文件、诊断产物及其目录归档。

**非目标**：不规定代码里日志的声明方式、文案与级别（见 [41_JAVA_LOGGING_CONVENTIONS.md](41_JAVA_LOGGING_CONVENTIONS.md)）；不规定指标、追踪、告警（见 [40_OBSERVABILITY_CONVENTIONS.md](40_OBSERVABILITY_CONVENTIONS.md)）。

## 术语与前置假设

| 术语 | 定义 |
| --- | --- |
| 诊断产物 | 构建/校验/排障过程生成的日志、报告、清单等非源码文件。 |
| 任务日志 | 一次构建或部署执行的完整输出，按 `<task>-<timestamp>.log` 命名并落日期目录。 |
| 服务运行日志 | 服务进程自身的 `application.log`，按 `spring.application.name` 归档。 |
| 制品目录 | Maven 各模块的 `target/`，由构建工具管理，不手工搬运。 |
| 根目录污染 | 在仓库根直接产生 `build_*.log`、`deploy_*.log` 一类临时文件。 |

**前置假设**：`.outputs/` 不在版本控制内（由 `.gitignore` 覆盖）；日志采集按目录约定抓取；服务名与 `spring.application.name` 一致。

## 目录规范

项目生成的日志和临时诊断产物不得写入仓库根目录。

在项目根目录下使用以下结构：

```text
.outputs/
 logs/
  build/
   YYYYMMDD/
    <task>-<timestamp>.log
  im-services/
   <service-name>/
    application.log
    application.YYYY-MM-DD.<index>.log.gz
```

## 规则

1. 构建、校验、打包和部署命令日志写入 `.outputs/logs/build/`。
2. 服务运行日志写入 `.outputs/logs/im-services/<service-name>/`。
3. Maven 构建产物保留在各模块的 `target/` 目录，除非发布流程明确复制到其他位置。
4. 静态校验报告写入 `.outputs/quality/`；发布清单与访问凭据清单写入 `.outputs/releases/`。
5. 新增工作不得在根目录产生临时的 `build_*.log`、`deploy_*.log` 等文件。

## 适用边界

本规范只约束构建、验证、发布和运行期间的日志文件、诊断产物及其目录归档。代码中的日志声明、文案、级别和异常处理遵循 [41 Java 日志规范](41_JAVA_LOGGING_CONVENTIONS.md)；日志、指标、追踪、告警和审计的共同原则遵循 [40 可观测性规范](40_OBSERVABILITY_CONVENTIONS.md)。

## 工具入口

- `run-dev.ps1` 是本地启动服务的标准入口，并预先配置每个服务的日志文件。
- `scripts/validate/invoke-engineering-validation.ps1` 是工程校验和 Maven 命令执行的标准入口，会持久化构建日志。

## 排障指引

1. 先在 `.outputs/logs/build/<date>/` 定位对应构建或部署任务的日志。
2. 运行期问题检查 `.outputs/logs/im-services/<service-name>/application.log`。
3. 服务名必须与 `spring.application.name` 保持一致，以便将日志准确映射到模块和部署单元。

## 归档模板

```text
.outputs/
├── logs/
│   ├── build/<YYYYMMDD>/<task>-<timestamp>.log     一次构建/部署/校验的全量输出
│   └── im-services/<service-name>/application.log  服务运行日志（含按日滚动与 .gz 归档）
├── quality/                                        静态校验报告与 worklist
└── releases/                                       发布清单与凭据清单
```

**命名示例（不构成强制）**：`build/20261014/flyway-conventions-20261014-114037.log`。

## 门禁与人工检查项

**可自动化**：

| 门禁 | 判据 |
| --- | --- |
| 根目录整洁 | 仓库根不存在 `*_dump.txt`、`*.log`、`cursor.mark`、`*.orig`、`*.rej`、`*.bak` |
| 诊断产物不入库 | `.outputs/` 全部路径均被 `.gitignore` 覆盖（无受控文件） |
| 目录归属 | 校验报告只落 `.outputs/quality/`，发布清单只落 `.outputs/releases/`，构建日志只落 `.outputs/logs/build/` |

**人工检查项**（评审时确认）：

1. 新增工具是否把自己的输出写进了约定目录，而不是当前工作目录。
2. 服务名目录是否与 `spring.application.name` 一致，避免排障时找不到日志。
3. 滚动/归档策略是否避免单文件无限增长。

## 可验证的验收标准

| # | 判据 | 证据形式 |
| --- | --- | --- |
| 1 | 仓库根与各仓根无临时日志或 dump 残留 | 仓库整洁门禁通过 |
| 2 | `.outputs/` 下无受版本控制文件 | `git ls-files .outputs` 无输出 |
| 3 | 一次构建可在 `.outputs/logs/build/<date>/` 找到完整输出 | 该次任务的日志路径 |
| 4 | 一个服务的运行日志可在 `.outputs/logs/im-services/<service-name>/` 找到 | 目录清单与日志片段 |
| 5 | 校验报告与发布清单各在 `quality/` 与 `releases/` | 目录清单 |

## 与其他规范的关系

| 关系 | 文档 | 说明 |
| --- | --- | --- |
| 引用 | [41_JAVA_LOGGING_CONVENTIONS.md](41_JAVA_LOGGING_CONVENTIONS.md) | 代码内日志的声明、文案与级别 |
| 引用 | [40_OBSERVABILITY_CONVENTIONS.md](40_OBSERVABILITY_CONVENTIONS.md) | 日志、指标、追踪、告警与审计的共同原则 |
| 依赖 | [01_TEXT_ENCODING_AND_BATCH_EDITING.md](01_TEXT_ENCODING_AND_BATCH_EDITING.md) | 写出日志与报告时的 UTF-8 无 BOM 口径 |
| 关联 | [ENGINEERING_RULES.md](../governance/ENGINEERING_RULES.md) | 统一工程校验入口与仓库整洁断言 |
