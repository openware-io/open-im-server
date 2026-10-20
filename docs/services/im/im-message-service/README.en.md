# im-message-service

[中文](README.md) | [English](README.en.md)

> English summary; the Chinese document is normative.

## Responsibility

- Message domain: history, offline messages, read receipts, recall/clear, channel cursors and secret-message ciphertext.

## Boundary

- MySQL is authoritative; RocketMQ carries commands/events; MongoDB stores hot projections and the offline inbox.

## Related

- [Service directory](../../README.en.md)
- [Engineering topology](../../../architecture/TOPOLOGY.en.md)
- [Architecture overview](../../../architecture/OVERVIEW.en.md)
