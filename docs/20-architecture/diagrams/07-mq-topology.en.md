# 07 MQ / Event Topology

[中文](07-mq-topology.md) | [English](07-mq-topology.en.md)

> Diagram revision: `2026-10-21` · C4 semantics / grouped layout

> View: Event Flow  
> Answers: which event families exist, who produces/consumes them, and what are the ordering and idempotency keys.  
> Exact topics/consumer groups live in the [MQ registry](../../30-reference/mq/REGISTRY.md); this diagram groups by family.

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontSize":"14px","primaryColor":"#E8F0FE","primaryTextColor":"#172B4D","primaryBorderColor":"#2563EB","lineColor":"#5E6C84","clusterBkg":"#FAFBFC","clusterBorder":"#C1C7D0","edgeLabelBackground":"#FFFFFF"}}}%%
flowchart LR
  subgraph Producers["Producers"]
    WS["im-access-ws"]
    MSG["im-message-service"]
    USER["im-user-service"]
    CONV["im-conversation-service"]
    ORD["platform-order-service"]
    PAY["common-payment-service"]
    MKT["platform-marketing-service"]
    CUS["platform-customer-service"]
    RES["platform-resource-service"]
  end

  subgraph Topics["Topic families"]
    CMD["im_message_command_*<br/>send / read / recall<br/>secret_destroy"]
    MEVT["im_message_event_*<br/>stored / edited / recalled<br/>chat_cleared / secret_*"]
    UEVT["im_user_event_*<br/>friend / status / auth<br/>profile / purge / wipe"]
    CEVT["im_conversation_event_*<br/>authorization / secret_chat"]
    SEVT["SaaS event topics<br/>ord_* / pay_* / mkt_*<br/>cst_* / res_*"]
  end

  subgraph Consumers["Consumers"]
    MSGC["im-message-service<br/>write / read / recall / secret"]
    WSC["im-access-ws<br/>delivery / notification"]
    ADMC["im-admin-service<br/>projection"]
    IDC["platform-identity-service<br/>profile"]
    ORDC["platform-order-service"]
    PAYC["common-payment-service"]
    CUSC["platform-customer-service<br/>points"]
    RESC["platform-resource-service<br/>occupation"]
  end

  WS -->|"commandId"| CMD
  CMD -->|"conversationId"| MSGC
  MSG -->|"Outbox · eventId"| MEVT
  MEVT -->|"conversationId"| WSC
  MEVT --> ADMC

  USER -->|"Outbox · eventId"| UEVT
  UEVT -->|"userId"| WSC
  UEVT -->|"friend accepted"| MSGC
  UEVT -->|"userId"| IDC

  CONV -->|"Outbox · eventId"| CEVT
  CEVT -->|"conversationId"| WSC
  CEVT --> MSGC

  ORD -->|"Outbox · tenantId"| SEVT
  PAY -->|"Outbox · tenantId"| SEVT
  MKT -->|"Outbox · tenantId"| SEVT
  CUS -->|"Outbox · tenantId"| SEVT
  RES -->|"Outbox · tenantId"| SEVT
  SEVT --> ORDC
  SEVT --> PAYC
  SEVT --> CUSC
  SEVT --> RESC
```

## Ordering and idempotency

| Family | Ordering key | Idempotency key | Note |
| --- | --- | --- | --- |
| `im_message_command_*` | `conversationId` | `commandId` | command; duplicate submit must not create a second message |
| `im_message_event_*` | `conversationId` | `eventId` | fact event; downlink/projection must be idempotent |
| `im_user_event_*` | `userId` (friend accepted: requester/acceptor) | `eventId` (friend accepted also `requestId`) | status, friends, profile, purge, wipe |
| `im_conversation_event_*` | `conversationId` | `eventId` | authorization and secret-chat lifecycle |
| `ord_*` / `pay_*` / `mkt_*` / `cst_*` / `res_*` | `tenantId` | `eventId` | SaaS events isolated by tenant |

## Rules

1. **Outbox first**: business write and event record commit together; a relay publishes; no dual write.
2. **Consumer idempotency**: deduplicate by the registered key; a redelivery has one side effect.
3. **Ordering**: preserve order per `conversationId`/`tenantId`; no global ordering across keys.
4. **Failure**: retry then DLQ; alert and support manual replay.
5. **Payload**: IDs, versions and necessary metadata only; no sensitive plaintext in MQ.
6. **No cross-service DB reads**: consumers use APIs or their own projections.

## Related

- [MQ registry](../../30-reference/mq/REGISTRY.md)
- [RocketMQ conventions](../../80-development/standards/12_ROCKETMQ_CONVENTIONS.en.md)
- [Message send sequence](06-message-send-sequence.en.md)
