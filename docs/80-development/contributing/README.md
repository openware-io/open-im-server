# 参与贡献

[中文](README.md) | [English](README.en.md)

## 开始之前

- 阅读 [工程规范](../governance/ENGINEERING_RULES.md) 和 [门禁注册表](../governance/GATE_REGISTRY.md)。
- 阅读 [DDD 服务工程规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)。
- 配置 [IDE](../tooling/IDE_SETUP.md)。

## 提交前

```powershell
.\scripts\validate\invoke-engineering-validation.ps1 -Scope Changed
```

涉及文档时再运行：

```powershell
.\scripts\validate\validate-documentation-links.ps1
.\scripts\validate\validate-documentation-governance.ps1
.\scripts\validate\validate-standards-governance.ps1
```
