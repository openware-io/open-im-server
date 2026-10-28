# common-payment-service

[中文](README.md) | [English](README.en.md)

> English summary; the Chinese document is normative.

## Responsibility

- Shared payment orchestration: payment intents, state transitions and reconciliation.
- Idempotent channel-callback settlement (`POST /internal/payment/channel-settlement`, internal HMAC v2):
  persists `pay_intent`/`pay_transaction` and posts the payment/refund to `ord_order` in one local
  transaction. Replays are detected by `pay_channel_settlement (provider, transaction_id, kind)`;
  failed/closed callbacks never touch order payment; refund callbacks roll back paid/refundable amount.
  Cross-tenant/store/currency and amount mismatches are rejected with 422.
- Refund idempotency: `POST /business/refund-requests` accepts `Idempotency-Key` (same key replays the
  existing row; reused key with a different payload → 409), and a duplicate same-order/same-amount
  application while one is still PENDING/APPROVED returns 409 `REFUND_DUPLICATE` without inserting a row.
  Repeating `POST /admin/refund-requests/{id}/refund` returns 409 `REFUND_STATE_INVALID` and never
  rolls back order amounts twice.

## Boundary

- Channel-specific configuration is owned by the payment-channel service.

## Related

- [Service directory](../../README.en.md)
- [Engineering topology](../../../20-architecture/TOPOLOGY.en.md)
- [Architecture overview](../../../20-architecture/OVERVIEW.en.md)
