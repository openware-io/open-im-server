# 工程仓库与分支矩阵

[中文](ENGINEERING_REPOSITORIES.md) | [English](ENGINEERING_REPOSITORIES.en.md)

> 状态：`current`（2026-10-21 快照）  
> 作用：定义 im-saas 工程的产品仓库范围、版本分支命名和跨仓库一致性检查。  
> 原则：**一个版本主题对应全部产品仓库的同一分支名**，不允许只切后端或只切某个客户端。

## 1. 产品仓库

`D:\projects\cnb-oss` 下参与 im-saas 产品交付的仓库：

| 仓库 | 角色 | 当前版本分支 |
| --- | --- | --- |
| `open-im-server` | 后端、网关、平台服务、工程文档 | `develop/2.4.0-hotel` |
| `open-chat-app` | C 端 App / H5 | `develop/2.4.0-hotel` |
| `open-chat-desktop` | 桌面端 | `develop/2.4.0-hotel` |
| `open-chat-admin` | IM 管理后台 | `develop/2.4.0-hotel` |
| `open-saas-admin` | SaaS 运营后台 | `develop/2.4.0-hotel` |
| `open-saas-mobile` | SaaS C 端移动端 | `develop/2.4.0-hotel` |
| `open-chat-turn` | TURN / WebRTC 接入 | `develop/2.4.0-hotel` |
| `open-website` | 官网 / 运营站点 | `develop/2.4.0-hotel` |

## 2. 不纳入版本分支的仓库

| 仓库 | 原因 |
| --- | --- |
| `.github` | 组织级社区健康文件，不随产品版本发布 |
| `open-green-pass` | 独立软件工程测试治理平台，不属于 im-saas 产品交付链 |

如果后续把 GreenPass 纳入发布链，必须先在本文件登记，再按同一规则建分支。

## 3. 分支规则

```text
main                         只用于发布/同步，不直接开发
develop/<version>-<theme>    版本开发分支，全部产品仓库同名
feature/<topic>              可选，从版本分支切出，合并回版本分支
```

规则：

1. 新版本开始时，先在**全部产品仓库**创建同名 `develop/<version>-<theme>`；
2. 基线必须是各仓库已推送且与 `main` 同步的上一版本分支；
3. 任何一个产品仓库缺分支，都视为版本未准备完成；
4. 不允许 `open-im-server` 在 `2.4.0-hotel`，而客户端仍在 `2.3.0`；
5. `main` 只做发布同步，不承载酒店半成品；
6. 分支删除必须确认已合并或明确废弃。

## 4. 2.4.0-hotel 基线

2026-10-21 创建快照：

| 仓库 | `develop/2.3.0` 基线 | `develop/2.4.0-hotel` 远端 |
| --- | --- | --- |
| `open-im-server` | `86d7bd9` | 已推送 |
| `open-chat-app` | `1397dd2` | 已推送 |
| `open-chat-desktop` | `7786ff8` | 已推送 |
| `open-chat-admin` | `7eee9ec` | 已推送 |
| `open-saas-admin` | `4af7263` | 已推送 |
| `open-saas-mobile` | `10bc2b8` | 已推送 |
| `open-chat-turn` | `100293d` | 已推送 |
| `open-website` | `c30dcab` | 已推送 |

## 5. 一致性检查

在 `D:\projects\cnb-oss` 同级目录执行：

```powershell
pwsh -File open-im-server/scripts/check-engineering-branches.ps1
```

检查项：

- 每个产品仓库是否存在目标分支；
- 当前分支是否为目标分支；
- 工作区是否干净；
- 远端是否存在同名分支；
- 本地 HEAD 与远端是否一致。

## 6. 发布前检查

- 全部产品仓库在同一版本分支；
- 每个仓库工作区干净；
- 版本分支已推送；
- 门禁、构建、客户端出包通过；
- 发布清单记录各仓库 commit；
- 合并 `main` 只在发布窗口执行。

## 7. 相关文档

- [工程规范](ENGINEERING_RULES.md)
- [门禁治理规范](../standards/04_GATE_GOVERNANCE.md)
- [酒店业务](../../40-domain/hotel/README.md)
