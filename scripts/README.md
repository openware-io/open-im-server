# 脚本目录说明

脚本按职责分类。请使用仓库根目录的部署入口执行稳定的本地命令；具体实现位于 `scripts/deploy`。

| 目录 | 职责 | 主要入口 |
| --- | --- | --- |
| `deploy/` | 启动、停止和重置本地运行环境。 | `docker.ps1`、`k8s.ps1`、`local.ps1` |
| `verify/` | 验证运行中的环境，并输出已部署版本的追溯信息。 | `local-integration.ps1`、`release-status.ps1` |
| `validate/` | 执行仓库静态检查和构建验证，不部署服务。 | `invoke-engineering-validation.ps1` |

本目录中其余脚本为共享维护工具，例如本地环境初始化和 OpenAPI 快照导出；它们不是部署或运行期验证入口。

## 部署命令

```powershell
.\deploy-docker.ps1
.\deploy-k8s.ps1
.\deploy-local.ps1
```

Docker Compose 与 Kind Kubernetes 是独立部署模式。端口、停止范围和验证命令见 [`docs/deployment/LOCAL.md`](../docs/deployment/LOCAL.md)。

Kubernetes 资源声明不写入部署脚本，统一位于 `k8s/local/`；生成的 Secret 和本地镜像生命周期见 `k8s/README.md`。

## 运行验证

通过命令参数传入凭据，不得写入脚本：

```powershell
.\scripts\verify\local-integration.ps1 -GatewayUrl http://127.0.0.1:3002 -AdminUsername admin -AdminPassword (Read-Host -AsSecureString)
```

验证 Kind Kubernetes 时，将 `-GatewayUrl` 改为 `http://127.0.0.1:30002`。

## Kubernetes 发布版本状态

使用发布状态命令可一次查看实际部署的服务版本、Git 提交、镜像引用和运行中的 Pod。完整 JSON 记录同时包含应用制品 SHA-256 与运行时镜像摘要：

```powershell
.\scripts\verify\release-status.ps1
.\scripts\verify\release-status.ps1 -OutputPath .\.outputs\releases\manual-check.json
```

`deploy-k8s.ps1` 在每次部署成功后自动执行该命令，并在 `.outputs/releases/` 下保存一份发布记录。

日常交互式查看使用已安装的 `k9s`：打开新 PowerShell 后运行 `k9s -n gv-im-local`，在 Deployment 或 Pod 详情中可查看镜像标签、标签和注解。它连接当前 `kubectl` 上下文，不需要额外部署一套 Kubernetes 管理平台。
