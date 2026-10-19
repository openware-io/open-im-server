# im-saas 文档归档索引

[中文](README.md) | [English](README.en.md)

> 状态：`current`（Phase 1 脚手架，暂未迁移文件）  
> 规则来源：[文档整理与归档方案](../DOCUMENTATION_ORGANIZATION.md)  
> 原则：先登记、后移动；保留原文，不把历史文档改写为现行契约。

## 1. 归档目录

```text
docs/archive/
├─ audits/           一次性审计、数据库审计
├─ inventories/      范围/币种/资源盘点
├─ renovation/       改造过程与历史决策
├─ superpowers/      历史设计与计划快照
└─ release-notes/    旧版本发布与验收记录
```

## 2. 元数据

归档文档顶部统一使用：

```markdown
> Status: historical
> Archived: 2026-10-19
> Superseded-by: <现行文档相对路径，可为空>
> Original-location: <原路径>
```

## 3. 登记表

Phase 2 开始逐条登记；未登记的文件不得移动。

| 原路径 | 归档路径 | 类型 | 替代文档 | 状态 |
| --- | --- | --- | --- | --- |
| （待登记） | `docs/archive/...` | audit / plan / renovation | （可为空） | planned |

## 4. 移动检查

1. `git mv` 保留历史；
2. 加元数据头；
3. 更新入链和高流量旧路径的转发说明；
4. 更新本索引；
5. 运行 `scripts/validate/validate-documentation-links.ps1`。
