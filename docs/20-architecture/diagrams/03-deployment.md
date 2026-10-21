# 03 部署拓扑

[中文](03-deployment.md) | [English](03-deployment.en.md)

> 图版本：`2026-10-21` · C4 语义 / 分组布局

> 视角：Deployment  
> 回答：容器实际跑在哪里？入口、命名空间、端口、存储和依赖怎么连？  
> 默认示例：Kind 本地环境 `open-im-local`；ACK 只替换入口和基础设施实现，不改变逻辑服务边界。

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontSize":"14px","primaryColor":"#E8F0FE","primaryTextColor":"#172B4D","primaryBorderColor":"#2563EB","lineColor":"#5E6C84","clusterBkg":"#FAFBFC","clusterBorder":"#C1C7D0"}}}%%
flowchart TB
  subgraph CLIENT["客户端"]
    direction TB
    APP["Mobile / desktop / web"]
    ADMIN["Admin consoles"]
  end
  subgraph K8S["Kind · open-im-local"]
    direction TB
    ING["ingress-nginx<br/>:30080 / :30443"]
    subgraph EDGE["Edge"]
      direction TB
      GW["gateway :3002"]
      WS["im-access-ws :3001"]
    end
    subgraph APPS["Application pods"]
      direction TB
      IM["IM services<br/>:3100–:3400"]
      PLAT["Platform services<br/>:4100–:4170"]
      COMMON["Common services<br/>:3600–:4220"]
      WEB["Admin / web :80"]
    end
    subgraph INFRA["Infrastructure"]
      direction TB
      MYSQL[("MySQL :3306")]
      REDIS[("Redis :6379")]
      MONGO[("MongoDB :27017")]
      MQ[("RocketMQ :9876")]
      MINIO[("MinIO :9000")]
      PVC[("local-path PVC")]
    end
  end
  APP --> ING
  ADMIN --> ING
  ING --> GW
  ING --> WS
  ING --> WEB
  GW --> IM
  GW --> PLAT
  GW --> COMMON
  WS --> MQ
  IM --> MYSQL
  IM --> REDIS
  IM --> MONGO
  IM --> MQ
  PLAT --> MYSQL
  PLAT --> MQ
  COMMON --> MYSQL
  COMMON --> MINIO
  MYSQL --> PVC
  REDIS --> PVC
  MONGO --> PVC
  MINIO --> PVC
  classDef client fill:#F4F5F7,stroke:#8993A4,color:#172B4D,stroke-width:1px
  classDef edge fill:#DEEBFF,stroke:#0052CC,color:#172B4D,stroke-width:1.5px
  classDef apps fill:#E3FCEF,stroke:#00875A,color:#172B4D,stroke-width:1.5px
  classDef infra fill:#EAE6FF,stroke:#5243AA,color:#172B4D,stroke-width:1.5px
  class APP,ADMIN client
  class GW,WS,ING edge
  class IM,PLAT,COMMON,WEB apps
  class MYSQL,REDIS,MONGO,MQ,MINIO,PVC infra
```

## 端口与命名空间

| 对象 | 端口 / 地址 | 来源 |
| --- | --- | --- |
| `ingress-nginx` | `30080` HTTP / `30443` HTTPS（Kind NodePort） | `k8s/local/ingress.yaml` |
| `gateway` | `3002` | `k8s/local/applications.yaml` |
| `im-access-ws` | `3001` | 同上 |
| `im-user-service` | `3100` | 同上 |
| `im-message-service` | `3200` | 同上 |
| `im-conversation-service` | `3300` | 同上 |
| `im-admin-service` | `3400` | 同上 |
| `platform-identity-service` | `4100` | 同上 |
| `platform-tenant-service` | `4110` | 同上 |
| `platform-resource-service` | `4120` | 同上 |
| `platform-order-service` | `4130` | 同上 |
| `platform-admin-service` | `4150` | 同上 |
| `platform-customer-service` | `4160` | 同上 |
| `platform-marketing-service` | `4170` | 同上 |
| `common-media-service` | `3600` | 同上 |
| `common-payment-service` | `4140` | 同上 |
| `common-payment-channel-service` | `4180` | 同上 |
| `common-audit-service` | `4190` | 同上 |
| `common-sms-service` | `4200` | 同上 |
| `common-mail-service` | `4210` | 同上 |
| `group-idaas-service` | `4220` | 同上 |
| MySQL / Redis / MongoDB | `3306` / `6379` / `27017` | `k8s/local/infrastructure.yaml` |
| RocketMQ | namesrv `9876` | 同上 |
| MinIO | API `9000` / console `9001` | 同上 |

## 读图要点

- 所有应用 Pod 在 `open-im-local` 命名空间；服务之间用 Kubernetes Service DNS 通信。
- 只有 Ingress 暴露到宿主机；内部 Service 保持 `ClusterIP`。
- `minio` 在本地 Kind 使用 `NodePort`；生产应改为内部 Service + 对象存储网关。
- `local-path` PVC 只适合本地；生产必须换成有副本和备份的 StorageClass。
- 上图把同类 Pod 合并展示，精确清单以 K8s 文件为准。

## 相关文档

- [本地部署](../../60-deployment/LOCAL.md)
- [Docker Compose](../../60-deployment/DOCKER.md)
- [ACK 部署](../../60-deployment/ACK.md)
- [容器图](02-container.md)
