# 05 Bounded Context Map

[中文](05-context-map.md) | [English](05-context-map.en.md)

> Diagram revision: `2026-10-21` · C4 semantics / grouped layout

> View: DDD Context Map  
> Answers: which bounded contexts exist, who depends on whom, and how they collaborate.  
> Does not answer: deployment replicas or table schemas.

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontSize":"14px","primaryColor":"#E8F0FE","primaryTextColor":"#172B4D","primaryBorderColor":"#2563EB","lineColor":"#5E6C84","clusterBkg":"#FAFBFC","clusterBorder":"#C1C7D0","edgeLabelBackground":"#FFFFFF"}}}%%
flowchart LR
  subgraph Edge["Edge and portal"]
    GW["gateway"]
    WS["im-access-ws"]
    PORTAL["unified-portal"]
  end

  subgraph IM["IM domain"]
    USER["User context"]
    CONV["Conversation context"]
    MSG["Message context"]
    IMADM["IM admin read model"]
  end

  subgraph SaaS["SaaS platform"]
    ID["Identity context"]
    TEN["Tenant context"]
    CUS["Customer/member context"]
    ORD["Order/reservation context"]
    RES["Resource/inventory context"]
    MKT["Marketing context"]
    BFF["Platform admin BFF"]
  end

  subgraph Shared["Shared capabilities"]
    MEDIA["Media"]
    AUD["Audit"]
    PAY["Payment"]
    PAYCH["Payment channel"]
    SMS["SMS/mail"]
    IDAAS["Group IAM"]
  end

  GW -->|"REST"| IM
  GW -->|"REST"| SaaS
  WS -->|"commands"| MSG
  PORTAL -->|"OAuth / session"| ID

  CONV -->|"authorization events"| MSG
  USER -->|"friend/profile events"| MSG
  IMADM -.->|"read projection"| MSG
  IMADM -.->|"read projection"| CONV

  BFF -->|"aggregate"| ID
  BFF -->|"aggregate"| TEN
  BFF -->|"aggregate"| ORD
  ORD -->|"room/inventory"| RES
  ORD -->|"customer/member"| CUS
  ORD -->|"collect/refund"| PAY
  ORD -.->|"order events"| AUD
  MKT -->|"coupon/points"| CUS
  MKT -.->|"order events"| ORD
  ID -->|"OIDC / enterprise login"| IDAAS
  PAY -->|"channel config"| PAYCH
```

## Relationships

| Upstream | Downstream | Relationship | Rule |
| --- | --- | --- | --- |
| Gateway / WS | IM, SaaS | sync API / commands | edge handles protocol and auth, not domain rules |
| User, conversation | Message | domain events | friend/conversation changes project into messaging |
| Order/reservation | Resource, customer, payment, audit | sync API / events | order never writes their tables |
| Platform admin BFF | identity, tenant, order | aggregate query | BFF owns no core business data |
| Identity | Group IAM | OIDC/OAuth | enterprise IdP is optional; local accounts fall back |
| IM / SaaS | shared capabilities | API / events | shared capabilities never depend back on domains |

## Boundary rules

1. Contexts collaborate through APIs or events; no shared tables or cross-schema joins.
2. Order orchestrates reservation/session flows but is not the owner of resource, customer or payment data.
3. The IM admin read model is projection-only; it is not a writer of IM business facts.
4. Shared capabilities are downstream; they must not depend on IM/SaaS domains.
5. The edge is an adapter, not a business orchestration layer.

## Related

- [DDD conventions](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.en.md)
- [Data ownership](04-data-ownership.en.md)
- [Service catalog](../../50-services/README.en.md)
