# 本地 Kubernetes 清单

`local/` 包含本地 Kind 部署使用的声明式 Kubernetes 资源。清单有意省略 `metadata.namespace`，由 `scripts/deploy/k8s.ps1` 按 `-Namespace` 参数应用到目标命名空间。

部署脚本从未提交的仓库 `.env` 文件创建 `open-im-env`，并从发布清单按 GHCR `image@sha256:digest` 拉取 SaaS 候选镜像后导入专属 Kind 节点；Kind 不重新构建这些候选镜像。应用 Service 使用 `ClusterIP`，统一由固定版本的 ingress-nginx 暴露。控制器 HTTP 使用 Kind NodePort `30080`，默认集群名为 `open-im-local`，上下文为 `kind-open-im-local`。

本地统一入口为 `http://<宿主机局域网IP>:30080`：根路径是统一门户，`/im/` 是 IM 管理端，`/saas/` 是 SaaS 管理端，`/a380/` 与 `/b/` 是 SaaS 移动端，`/api/` 和 `/ws/` 分别进入网关和 IM WebSocket。Ingress-nginx 安装清单来自官方 Kind provider `controller-v1.12.1`，脚本会校验固定 SHA-256 后再应用。

候选制品遵循单次构建模型：`build-saas-release.ps1` 默认生成带 `-SNAPSHOT` 的开发 tag 并推送到 ACR，开发 tag 允许覆盖；传入 `-FormalRelease` 才生成不带后缀的正式 tag，正式 tag 不可覆盖。Kind 与 ACK 都可使用开发或正式清单；同一发布类型的 Kind 验证和 ACK 发布必须消费同一份 schema v2 发布清单。禁止 `latest`、`dev`、时间戳、提交号及其他临时后缀，也禁止用本地 `RepoDigests` 代替 ACR 远端 manifest 校验。

本地 Docker 业务镜像按服务仓库最多保留最近两个版本。清理只针对本地镜像标签，必须先排除容器引用中的镜像；不删除 GHCR 远程制品。`open-im/rocketmq:5.3.1-local` 等规范指定的基础设施镜像按运行依赖保留。

`kind/local-cluster.yaml` 定义本地双节点拓扑：控制平面带 `NoSchedule` 污点，业务与基础设施 Pod 只能调度到 worker。该拓扑用于本地验证控制平面与业务工作负载隔离；生产环境应使用多控制平面和独立、可扩缩的工作节点池。

本地 MinIO 使用 `emptyDir`，因此必须在各 Deployment 滚动完成后再应用 MinIO 初始化任务。仅当媒体访问密钥与 MinIO 根用户一致时，才应用 `media-root-credentials-patch.yaml`。

网关的公共媒体路由 `/api/v1/media-public/**` 直连对象存储，因此网关容器必须持有 `MEDIA_INTERNAL_ENDPOINT`：Kind 与 ACK 都填 `http://minio:9000`（见 `local/gateway-admin.yaml` 与 `ack/edge.yaml`）。缺少该变量时路由会退化成 `http://localhost:9000`，图片全部 502。

如需在不修改集群的前提下校验静态语法和 API（媒体凭据文件是策略性补丁，应与主应用清单一起校验），执行：

```powershell
Get-ChildItem .\k8s\local\*.yaml | Where-Object Name -ne 'media-root-credentials-patch.yaml' | ForEach-Object {
  kubectl apply --dry-run=client -f $_.FullName
}
```
