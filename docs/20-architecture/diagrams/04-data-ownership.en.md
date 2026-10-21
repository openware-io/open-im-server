# 04 Data Ownership

[中文](04-data-ownership.md) | [English](04-data-ownership.en.md)

> View: Data Ownership  
> Answers: which service owns which data, what crosses boundaries, and which stores are not authoritative.  
> Does not answer: full ER models; exact columns follow each service's Flyway.

```mermaid
flowchart LR
  subgraph Edge["Edge"]
    WS["im-access-ws"]
  end

  subgraph IM["IM domain"]
    USER["im-user-service"]
    MSG["im-message-service"]
    CONV["im-conversation-service"]
    IMADM["im-admin-service"]
  end

  subgraph SaaS["SaaS platform"]
    ID["platform-identity-service"]
    TEN["platform-tenant-service"]
    CUS["platform-customer-service"]
    ORD["platform-order-service"]
    RES["platform-resource-service"]
    MKT["platform-marketing-service"]
    PADM["platform-admin-service<br/>BFF"]
  end

  subgraph Shared["Shared capabilities"]
    AUD["common-audit-service"]
    MEDIA["common-media-service"]
    PAY["common-payment-service"]
    PAYCH["common-payment-channel-service"]
    SMS["common-sms-service"]
    MAIL["common-mail-service"]
    IDAAS["group-idaas-service"]
  end

  subgraph Stores["Stores"]
    IMDB[("open_im")]
    SADB[("open_saas")]
    GDB[("open_group")]
    ADB[("open_audit")]
    MONGO[("MongoDB<br/>im_chat")]
    REDIS[("Redis")]
    MINIO[("MinIO")]
  end

  USER -->|"owns"| IMDB
  MSG -->|"owns"| IMDB
  CONV -->|"owns"| IMDB
  IMADM -.->|"config / read projection"| IMDB

  ID -->|"owns"| SADB
  TEN -->|"owns"| SADB
  CUS -->|"owns"| SADB
  ORD -->|"owns"| SADB
  RES -->|"owns"| SADB
  MKT -->|"owns"| SADB
  PADM -.->|"read-only aggregation"| SADB

  AUD -->|"owns"| ADB
  IDAAS -->|"owns"| GDB
  PAY -->|"owns"| SADB
  PAYCH -->|"config"| SADB
  SMS -->|"config"| SADB
  MAIL -->|"config"| SADB

  MSG -.->|"hot projection / offline inbox"| MONGO
  USER -.->|"session / presence"| REDIS
  MSG -.->|"cache / routing"| REDIS
  WS -.->|"presence routing"| REDIS
  MEDIA -->|"metadata"| SADB
  MEDIA -->|"objects"| MINIO

  IMDB -.->|"IDs / events only"| SADB
  SADB -.->|"IDs / events only"| IMDB
```

## Ownership table

| Store | Authoritative owner | Core data | Rule |
| --- | --- | --- | --- |
| MySQL `open_im` | `im-user-service`, `im-message-service`, `im-conversation-service` | users/friends/devices, messages/read/recall, conversations/groups | table-prefix ownership inside IM; no cross-domain writes |
| MySQL `open_saas` | `platform-identity/tenant/customer/order/resource/marketing` plus shared payment config | accounts, tenants, customers, orders, resources, marketing, payment config | aggregate ownership inside SaaS; BFF is read-only |
| MySQL `open_audit` | `common-audit-service` | audit events | append-only; never the source of business state |
| MySQL `open_group` | `group-idaas-service` | group IAM | written only by IAM |
| MongoDB `im_chat` | `im-message-service` | message hot projection, offline inbox | **projection, never authority**; rebuildable |
| Redis | each consumer | sessions, presence, cache, cross-instance coordination | **cache, never authority**; rebuildable |
| MinIO | `common-media-service` | image/file objects | metadata in MySQL; bytes in MinIO |

## Reading notes

- One MySQL instance may host multiple schemas, but **one table has exactly one owner**.
- Cross-service communication uses IDs or events; no cross-schema JOIN, foreign key or direct UPDATE.
- `im-admin-service` and `platform-admin-service` are read models/BFFs; they do not own core business tables.
- MySQL is the source of truth for messages; MongoDB and Redis are projections/caches only.
- Media metadata and binary objects are split: MySQL stores references/state, MinIO stores bytes.

## Related

- [Engineering topology](../TOPOLOGY.en.md)
- [DDD conventions](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.en.md)
- [Persistence stack](../../80-development/standards/21_PERSISTENCE_STACK.en.md)
- [MQ registry](../../30-reference/mq/REGISTRY.md)
