# 工程标准版图

[中文](STANDARDS_MAP.md) | [English](STANDARDS_MAP.en.md)

> 状态：`current`（`develop/2.3.0`）  
> 定位：标准体系的**总图**，不是另一份标准正文。  
> 原则：标准不是按时间堆叠的文件，而是“原则 → 领域标准 → 实践标准 → 门禁证据”的 assurance stack。

## 1. 两个轴

标准版图不是一棵树，而是两个轴：

```text
纵轴：作用层级
  L0 原则与治理      为什么做、冲突时怎么取舍
  L1 领域标准        某个工程领域必须满足什么
  L2 实践标准        在具体技术栈上怎么做
  L3 门禁与证据      怎么证明它真的做到了

横轴：工程关注面
  foundation / architecture / build / data / delivery / quality / security / collaboration / domain
```

编号只是**分类索引**，不表达依赖关系；依赖关系看“支撑原则”和“门禁证据”。

## 2. 标准版图

| ID | 标准 | 层级 | 领域 | 门禁 / 证据 | 成熟度 |
| --- | --- | --- | --- | --- | --- |
| 00 | [DDD 微服务软件工程规范纲要](00_DDD_MICROSERVICE_SOFTWARE_ENGINEERING_OUTLINE.md) | L0 | foundation | G48, G49 | active |
| 01 | [文本编码与批量改写规范](01_TEXT_ENCODING_AND_BATCH_EDITING.md) | L2 | collaboration | G01, G29 | active |
| 02 | [架构决策记录（ADR）规范](02_ARCHITECTURE_DECISION_RECORDS.md) | L0 | governance | G47 | active |
| 03 | [工程原则](03_ENGINEERING_PRINCIPLES.md) | L0 | foundation | G48, G49 | active |
| 04 | [门禁治理规范](04_GATE_GOVERNANCE.md) | L0 | governance | G33, G48, G49, G51, G52 | active |
| 10 | [DDD 服务工程规范](10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) | L1 | architecture | G18, G19, G20, G21, G24, G25, G43 | active |
| 12 | [RocketMQ 治理规范](12_ROCKETMQ_CONVENTIONS.md) | L2 | messaging | G14, G15, G42 | active |
| 13 | [平台媒体规范](13_PLATFORM_MEDIA_CONVENTIONS.md) | L2 | media | G36, G40 | active |
| 20 | [Maven 工程规范](20_MAVEN_ENGINEERING_CONVENTIONS.md) | L1 | build | G11, G26 | active |
| 21 | [持久层技术栈标准](21_PERSISTENCE_STACK.md) | L1 | data | G12, G24, G25, G27 | active |
| 22 | [技术栈版本矩阵](22_TECHNOLOGY_STACK_VERSION_MATRIX.md) | L1 | build | G11（部分） | active |
| 23 | [数据库迁移脚本规范](23_DATABASE_MIGRATION_STANDARD.md) | L2 | data | G27, G28 | active |
| 24 | [币种规范](24_CURRENCY_CONVENTIONS.md) | L1 | domain | G13, G34, G35 | active |
| 30 | [客户端发布与版本治理规范](30_CLIENT_RELEASE_GOVERNANCE.md) | L1 | delivery | 人工发布检查 | active + manual |
| 31 | [客户端发布操作规范](31_CLIENT_RELEASE_SKILL.md) | L2 | delivery | 人工发布检查 | active + manual |
| 40 | [可观测性规范](40_OBSERVABILITY_CONVENTIONS.md) | L1 | quality | G37, G38, G41 | active |
| 41 | [Java 日志规范](41_JAVA_LOGGING_CONVENTIONS.md) | L2 | quality | G06, G07, G08, G37 | active |
| 42 | [日志输出与诊断规范](42_LOGGING_AND_OUTPUTS.md) | L2 | quality | G07, G37 | active |
| 43 | [E2E 测试与验收规范](43_E2E_TESTING_CONVENTIONS.md) | L2 | quality | G39 | active |

## 3. 覆盖矩阵

| 关注面 | L1 领域标准 | L2 实践标准 | 状态 |
| --- | --- | --- | --- |
| foundation | 00 | 03, 04 | ✅ |
| architecture | 10 | 12, 13 | ✅ |
| build | 20, 22 | — | ✅ |
| data | 21, 24 | 23 | ✅ |
| delivery | 30 | 31 | ⚠️ 只有人工检查 |
| quality | 40 | 41, 42, 43 | ✅ |
| security | **缺失** | 仅 G16/G17 门禁 | ❌ 缺口 |
| collaboration | — | 01, 02 | ⚠️ 文档治理有门禁，贡献流程未成标准 |
| domain | 24 | — | ⚠️ 仅币种，其他领域规则仍在 `40-domain/` |

## 4. 当前缺口

这些不是“还没写文档”，而是**标准版图上没有覆盖的格子**：

1. **安全标准**：只有 G16/G17 检查密钥和配置，没有威胁模型、认证授权、密钥轮换、供应链安全标准。
2. **隐私与数据生命周期**：缺少数据分类、保留期限、删除/导出、跨境传输标准。
3. **性能与容量**：缺少 SLO、延迟预算、压测、容量规划标准。
4. **API 兼容性与弃用**：缺少版本策略、兼容窗口、弃用流程标准。
5. **可靠性与灾备**：缺少 RTO/RPO、备份恢复、故障演练标准。
6. **供应链与许可证**：缺少第三方依赖、License、SBOM 标准。
7. **客户端发布门禁**：30/31 目前靠人工检查，没有自动化证据。
8. **贡献与社区治理**：贡献流程、代码所有权、评审 SLA 还没有标准。
9. **国际化与可访问性**：面向 OpenChat 多语言/多端，尚未形成标准。

## 5. 新增标准流程

1. **先定位格子**：选择层级（L0/L1/L2）和关注面；
2. **写清支撑原则**：引用 `03` 的 P1–P10；
3. **写清门禁或证据**：能自动化就加 G 门禁；不能自动化就定义人工证据；
4. **登记版图**：更新本表、`standards/README.md` 和根 README；
5. **登记缺口**：如果只补了一部分，明确剩余缺口，不假装完整；
6. **跑门禁**：G48/G49 必须通过。

## 6. 门禁

- **G48 standards-catalog**：编号、英文摘要、索引完整性。
- **G49 standards-map**：每个标准必须出现在版图中，版图不能引用不存在的标准；每行必须有层级、领域、门禁/证据。
- 领域门禁见 [门禁注册表](../governance/GATE_REGISTRY.md)。

## 7. 与其他文档的关系

- [工程原则](03_ENGINEERING_PRINCIPLES.md)：L0 的判断依据；
- [标准目录](README.md)：按分类块浏览；
- [工程规范](../governance/ENGINEERING_RULES.md)：流程与责任；
- [门禁注册表](../governance/GATE_REGISTRY.md)：L3 的可执行实现；
- [架构决策记录](../../20-architecture/adr/README.md)：原则冲突时的决策历史。
