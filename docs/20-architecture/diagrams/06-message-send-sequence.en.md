# 06 Message Send Sequence

[中文](06-message-send-sequence.md) | [English](06-message-send-sequence.en.md)

> View: Sequence  
> Answers: what happens between a client sending an IM message and the recipient receiving it.  
> Focus: idempotency, transactions, Outbox, retries and offline compensation.

```mermaid
sequenceDiagram
  autonumber
  participant C as Sender
  participant G as gateway
  participant U as im-user-service
  participant W as im-access-ws
  participant MQ as RocketMQ
  participant M as im-message-service
  participant DB as MySQL
  participant MG as MongoDB
  participant R as Redis
  participant RCV as Recipient
  participant PUSH as Push

  C->>G: POST /api/v1/auth/ws-ticket
  G->>U: validate account and issue one-time ticket
  U-->>G: ticket
  G-->>C: ticket

  C->>W: WSS /ws/im/v1?ticket=...
  W->>U: validate ticket / auth version
  U-->>W: ok
  W-->>C: connected

  C->>W: SEND(commandId, conversationId, payload)
  W->>MQ: publish im_message_command_send_v1<br/>key=conversationId

  MQ->>M: consume command
  alt commandId already processed
    DB-->>M: existing message (idempotent hit)
  else new command
    M->>DB: one transaction: message + Outbox
    DB-->>M: commit
  end

  M->>MG: upsert message projection
  M->>R: update cache / presence routing
  M->>MQ: Outbox publishes im_message_event_stored_v1<br/>key=conversationId

  MQ->>W: event stored
  W->>RCV: WSS downlink
  W->>PUSH: offline push
  W-->>C: ACK / send result

  Note over MQ,M: RocketMQ retries failures; threshold moves to DLQ<br/>eventId is idempotent; commandId prevents duplicate submit
```

## Reading notes

| Boundary | Rule |
| --- | --- |
| Client → edge | establish WS with a one-time ticket; do not keep a long-lived access token in the URL |
| Edge → MQ | produce commands only; never write the message table |
| MQ → message service | `conversationId` is the ordering key, `commandId` the idempotency key |
| Message service → MySQL | message fact and Outbox commit in one transaction |
| Message service → MongoDB/Redis | projection/cache; retryable, not authoritative |
| MQ → edge | `eventId` idempotent; online downlink plus offline push |
| Failure path | retry → DLQ; alert; never silently drop |

## Invariants

1. MySQL is the only authoritative message store; MongoDB is not the source of truth.
2. Duplicate `commandId` must not create a second message.
3. Duplicate `eventId` must not repeat downlink or push.
4. Do not publish an MQ event before the Outbox transaction commits.
5. A client ACK means the edge accepted it, not that every recipient has read it.

## Related

- [MQ registry](../../30-reference/mq/REGISTRY.md)
- [MQ topology](07-mq-topology.en.md)
- [Message service](../../50-services/im/im-message-service/README.en.md)
- [RocketMQ conventions](../../80-development/standards/12_ROCKETMQ_CONVENTIONS.en.md)
