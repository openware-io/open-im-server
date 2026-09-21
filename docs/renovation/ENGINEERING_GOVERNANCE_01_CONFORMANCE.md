# 工程规范一致性治理计划

> 状态：执行中
>
> 适用范围：工程文档、Maven 构建、静态校验脚本与 CI 入口
>
> 最后核查：2026-07-31

## 规则来源

- `docs/standards/` 定义可复用工程规则。
- `docs/ENGINEERING_RULES.md` 定义本项目的适配规则，必须与根 `pom.xml` 和校验脚本一致。
- `docs/renovation/` 记录项目改造背景、决策、证据与未完成事项，不覆盖工程规则。
- 根 `pom.xml`、`scripts/validate/` 与 CI 命令是可执行事实；文档变更必须同时说明对应实现和验收命令。

## 一致性清单

| 编号 | 事项 | 现状 | 验收标准 |
| --- | --- | --- | --- |
| G-01 | Maven 版本所有权 | 已由 `validate-maven-version-ownership.ps1` 校验 | 平台、服务域、叶子模块和网关的版本模型校验通过 |
| G-02 | Reactor 收敛规则 | 不采用 `reactorModuleConvergence`，其要求全部 Reactor 制品同版本，与独立服务域版本模型冲突 | Maven 规范和工程规则不再要求该 Enforcer 规则 |
| G-03 | 静态门禁入口 | `invoke-engineering-validation.ps1` 串行执行全部现有校验脚本与 Maven Wrapper | `Changed` 和 `Full` 均输出 `.outputs/logs/build/<日期>/` 日志并传播失败退出码 |
| G-04 | 传递依赖例外 | `javassist` 已集中升级并加 Maven 禁令 | 依赖树为 `3.32.0-GA`，低版本解析在 `validate` 失败 |
| G-05 | 本地开发配置 | `.env` 是共享的本地开发基线，密钥校验不扫描该已批准配置；禁止无工程决策改为忽略或模板策略 | 生产和共享环境凭据只通过受管环境变量注入，其他受版本控制配置仍受密钥校验 |
| G-06 | CI 接入 | 仓库未发现可见流水线定义 | CNB 流水线调用统一入口的 `-Scope Full` 并以非零退出码阻断 |

## 执行顺序

1. 合并统一校验入口、根 POM 门禁与文档修订。
2. 在 CNB 流水线执行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\invoke-engineering-validation.ps1 -Scope Full`。
3. 使用独立 Compose 项目名和临时卷执行 RocketMQ 与服务启动冒烟，避免复用开发者本机数据卷。
4. 每次新增依赖、模块、门禁或发布流程时，更新本清单中的实现证据与验收命令。
