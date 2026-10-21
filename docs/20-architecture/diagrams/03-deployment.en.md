# 03 Deployment Topology

[中文](03-deployment.md) | [English](03-deployment.en.md)

> View: Deployment  
> Answers: where containers run, how the edge, namespace, ports, storage and dependencies connect.  
> Default: Kind local environment `open-im-local`; ACK changes edge/infrastructure implementations, not logical service boundaries.

```mermaid
flowchart TB
  subgraph Clients["Clients and developer machines"]
    APP["Mobile / desktop / web"]
    ADMIN["Admin consoles"]
    DEV["Local dev / curl"]
  end

  subgraph Kind["Kind cluster · namespace open-im-local"]
    ING["ingress-nginx<br/>NodePort 30080 / 30443"]

    subgraph Edge["Edge pods"]
      GW["gateway<br/>:3002"]
      WS["im-access-ws<br/>:3001"]
    end

    subgraph Apps["Application pods"]
      IM["im-*<br/>:3100 / :3200<br/>:3300 / :3400"]
      PLAT["platform-*<br/>:4100–:4170"]
      COMMON["common-*<br/>:3600 / :4140–:4220"]
      WEB["saas-admin / pc-admin<br/>saas-mobile / open-website<br/>:80"]
    end

    subgraph Infra["Infrastructure"]
      MYSQL["MySQL<br/>:3306"]
      REDIS["Redis<br/>:6379"]
      MONGO["MongoDB<br/>:27017"]
      MQ["RocketMQ<br/>namesrv :9876"]
      MINIO["MinIO<br/>:9000 / :9001"]
    end

    PVC[("local-path PVC")]
  end

  APP -->|"HTTPS :30080"| ING
  ADMIN -->|"HTTPS :30080"| ING
  DEV -->|"kubectl port-forward"| GW

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
  MINIO --> PVC
  MYSQL --> PVC
  REDIS --> PVC
  MONGO --> PVC
```

## Ports and namespace

| Object | Port / address | Source |
| --- | --- | --- |
| `ingress-nginx` | `30080` HTTP / `30443` HTTPS (Kind NodePort) | `k8s/local/ingress.yaml` |
| `gateway` | `3002` | `k8s/local/applications.yaml` |
| `im-access-ws` | `3001` | same |
| `im-user-service` | `3100` | same |
| `im-message-service` | `3200` | same |
| `im-conversation-service` | `3300` | same |
| `im-admin-service` | `3400` | same |
| `platform-identity-service` | `4100` | same |
| `platform-tenant-service` | `4110` | same |
| `platform-resource-service` | `4120` | same |
| `platform-order-service` | `4130` | same |
| `platform-admin-service` | `4150` | same |
| `platform-customer-service` | `4160` | same |
| `platform-marketing-service` | `4170` | same |
| `common-media-service` | `3600` | same |
| `common-payment-service` | `4140` | same |
| `common-payment-channel-service` | `4180` | same |
| `common-audit-service` | `4190` | same |
| `common-sms-service` | `4200` | same |
| `common-mail-service` | `4210` | same |
| `group-idaas-service` | `4220` | same |
| MySQL / Redis / MongoDB | `3306` / `6379` / `27017` | `k8s/local/infrastructure.yaml` |
| RocketMQ | namesrv `9876` | same |
| MinIO | API `9000` / console `9001` | same |

## Reading notes

- All application pods run in `open-im-local`; services communicate through Kubernetes Service DNS.
- Only Ingress is exposed to the host; internal Services stay `ClusterIP`.
- `minio` uses `NodePort` locally; production should use an internal service plus object-storage gateway.
- `local-path` PVC is local-only; production needs a replicated, backed-up StorageClass.
- Pods of the same class are grouped here; the K8s files are authoritative.

## Related

- [Local deployment](../../60-deployment/LOCAL.md)
- [Docker Compose](../../60-deployment/DOCKER.md)
- [ACK deployment](../../60-deployment/ACK.md)
- [Container diagram](02-container.en.md)
