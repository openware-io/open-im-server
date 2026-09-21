# 部署文档索引

本目录集中维护本仓库的部署、运行、验证和版本追溯说明。业务架构、接口契约和服务职责仍分别维护在 `docs/`、`docs/im-services/` 与 `k8s/`。

| 文档 | 适用范围 | 内容 |
| --- | --- | --- |
| [本地运行说明](./LOCAL.md) | Windows 本地开发与联调 | 直接运行本地进程、Docker Compose、独立 Kind 集群的依赖条件、启动顺序、端口、停止范围和验证方式。 |
| [Docker Compose 部署](./DOCKER.md) | Docker Compose | 首次部署、日常更新、停止、数据重置和容器化基础设施说明。 |
| [Kubernetes 清单说明](../../k8s/README.md) | Kind Kubernetes | 本地清单、镜像导入、Secret 生成、节点拓扑与静态校验。 |
| [脚本目录说明](../../scripts/README.md) | 部署与验证脚本 | 各脚本的职责、稳定入口、运行期验证和发布版本追溯命令。 |

部署脚本的稳定入口始终位于仓库根目录：`deploy-local.ps1`、`deploy-docker.ps1`、`deploy-k8s.ps1`。不得直接修改生成的 Kubernetes 运行资源或绕过部署脚本手工替换镜像，否则版本、Git 修订和发布记录会失去一致性。
## ACK 部署

生产 ACK 部署的隔离边界、域名证书准备项和执行顺序见 [ACK 部署方案](./ACK.md)。当前已部署至 `im-business`；后续发布只接受由 `build-saas-release.ps1` 生成的发布清单，并通过 `scripts/deploy/ack.ps1 -ReleaseManifestPath <清单>` 执行。开发清单使用 `-SNAPSHOT`，正式清单需显式传入 `-FormalRelease -FormalTargets ...`。
