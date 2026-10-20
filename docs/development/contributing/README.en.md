# Contributing

[中文](README.md) | [English](README.en.md)

## Before you start

- Read the [engineering rules](../governance/ENGINEERING_RULES.md) and the [gate registry](../governance/GATE_REGISTRY.md).
- Read the [DDD service engineering conventions](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md).
- Configure your [IDE](../tooling/IDE_SETUP.md).

## Before committing

```powershell
.\scripts\validate\invoke-engineering-validation.ps1 -Scope Changed
```

For documentation changes, also run:

```powershell
.\scripts\validate\validate-documentation-links.ps1
.\scripts\validate\validate-documentation-governance.ps1
.\scripts\validate\validate-standards-governance.ps1
```
