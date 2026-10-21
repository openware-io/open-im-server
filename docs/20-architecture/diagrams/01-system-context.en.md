# 01 System Context

[中文](01-system-context.md) | [English](01-system-context.en.md)

> Diagram revision: `2026-10-21` · C4 semantics / grouped layout

> View: C4 Level 1 — System Context  
> Answers: who uses OpenChat, and which external systems sit outside its boundary?  
> Does not answer: internal services, databases or class structure.

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontSize":"14px","primaryColor":"#E8F0FE","primaryTextColor":"#172B4D","primaryBorderColor":"#2563EB","lineColor":"#5E6C84","clusterBkg":"#FAFBFC","clusterBorder":"#C1C7D0","edgeLabelBackground":"#FFFFFF"}}}%%
flowchart LR
  subgraph Actors["Actors"]
    C["C-end users<br/>App / Desktop / H5"]
    O["Operations and store staff<br/>IM Admin / SaaS Admin"]
    A["Platform admins<br/>tenant / IAM / audit"]
    D["Open-platform developers<br/>third-party apps"]
  end

  subgraph OpenChat["OpenChat system boundary"]
    PLATFORM["OpenChat<br/>IM + multi-tenant SaaS<br/>REST / WebSocket"]
  end

  subgraph External["External systems"]
    PUSH["Push<br/>APNs / FCM / JPush"]
    SMS["SMS"]
    PAY["Payment channels<br/>WeChat / Alipay / others"]
    IDP["Enterprise IdP / OAuth"]
    OBS["Monitoring and alerting"]
  end

  C -->|"HTTPS / WSS"| PLATFORM
  O -->|"HTTPS"| PLATFORM
  A -->|"HTTPS"| PLATFORM
  D -->|"OAuth2 / OpenAPI"| PLATFORM

  PLATFORM -->|"push"| PUSH
  PLATFORM -->|"verification / notification"| SMS
  PLATFORM -->|"payment / refund"| PAY
  PLATFORM -->|"OAuth / OIDC"| IDP
  PLATFORM -->|"metrics / logs / alerts"| OBS
  classDef actor fill:#F4F5F7,stroke:#8993A4,color:#172B4D
  classDef system fill:#0052CC,stroke:#0747A6,color:#FFFFFF,stroke-width:2px
  classDef external fill:#FFFAE6,stroke:#FF991F,color:#172B4D
  class C,O,A,D actor
  class PLATFORM system
  class PUSH,SMS,PAY,IDP,OBS external
```

## Reading notes

- OpenChat is one system boundary; its internal IM, SaaS and shared services are in the [container diagram](02-container.en.md).
- C-end clients use HTTPS for REST and WSS for the IM long connection.
- Admin consoles and open-platform apps enter through the public edge; they do not connect to internal services directly.
- Object storage is an internal capability (MinIO plus `common-media-service`), not an external system.
- External systems are only cross-organisation dependencies; internal infrastructure such as Redis/MySQL stays out of this view.

## Boundary rules

1. External systems integrate over explicit protocols; no shared database.
2. Third-party apps use OpenAPI/OAuth, never direct internal-service access.
3. Push, SMS and payment failures need retry or degradation; they must not block the core business transaction.
4. Enterprise IdP is optional; the local account model must still run independently.

## Implementation

- Edge: [ingress.yaml](../../../k8s/local/ingress.yaml)
- Push: [PUSH_SETUP.md](../../60-deployment/PUSH_SETUP.md)
- Open-platform contracts: [contracts](../../30-reference/contracts/README.md)
