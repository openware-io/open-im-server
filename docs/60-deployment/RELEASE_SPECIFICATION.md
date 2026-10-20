# 发布规范（强制）

本规范适用于所有 Kind、ACK 及本地/测试环境的业务 Deployment、发布清单和镜像。

## 适用范围

- **自研业务服务**（IM、SaaS、管理后台、门户及本组织维护的业务镜像）必须严格遵守本文的版本、manifest、镜像引用、来源提交和发布校验规范。
- **第三方组件**（MySQL、Redis、MongoDB、MinIO、RocketMQ、Ingress、`minio/mc` 等）不套用自研服务的版本标签规则；其镜像版本、升级方式和校验方式遵循该组件官方发布规范及本项目基础设施审批要求。
- 第三方组件不得写入自研业务 manifest，也不得用第三方组件版本替代自研服务版本；其持久化、凭据、网络暴露和升级策略仍须符合本项目基础设施与安全要求。

## 镜像引用

- **禁止**在 Kubernetes `Deployment.spec.template.spec.containers[].image`、发布脚本下发参数或发布记录的运行时 `image` 字段中使用 `image@sha256:<64位摘要>` 形式。
- 运行时镜像只能使用带仓库和唯一版本标签的形式：`registry/repository:tag`。
- `digest` 可以作为清单中的校验和审计字段保存，也可以用于拉取后校验；它不得拼接到运行时镜像引用后面。
- 禁止 `latest`、`dev`、`dirty`、手工临时标签、可重复覆盖的开发标签以及未解析的占位符。
- 开发标签使用对应模块版本的 `-SNAPSHOT` 形式，例如 `2.0.7-SNAPSHOT`，允许开发环境覆盖；正式标签使用纯 SemVer（如 `2.0.7`），正式标签一经存在不得覆盖。

## 发布清单与来源

- 每个服务的 `tag`、`image`、`moduleVersion` 和 `sourceRevision` 必须相互一致；多仓库前端使用各自仓库的提交号，不能复用 `open-im-server` 提交号。
- Manifest、Deployment 模板元数据和运行中 Pod 必须指向同一服务版本标签；发布后必须逐 Deployment、逐 Pod 校验标签及就绪状态。
- 禁止直接 `kubectl set image`、手工改 tag 或绕过发布脚本部署。所有发布必须由经过校验的 schema v2 manifest 驱动。
- 任何 `local-*dirty`、旧 digest 引用、来源提交不匹配或未完成校验的发布均视为不合规，必须清理并按本规范重新发布。

## 发布门禁

1. 发布仓库工作区必须干净，构建和镜像标签必须记录服务自身 Git 提交号。
2. 先完成构建、推送和 manifest 校验，再部署 Kind；Kind 回归通过后才允许晋级 ACK。
3. 发布完成后检查所有业务 Deployment：镜像不含 `@sha256:`，标签与 manifest 一致，Pod 全部 Ready，且不存在 dirty release id。
4. 旧 ReplicaSet、旧 dirty 发布记录和不再被任何 Deployment 使用的本地应用镜像必须在确认范围后清理；不得删除基础设施镜像或其他业务使用中的镜像。
