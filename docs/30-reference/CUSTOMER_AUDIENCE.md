# Customer service client audience

`platform-customer-service` accepts two tenant client audiences:

- `saas-c` — C-end consumer.
- `saas-b` — B-end merchant/tenant admin.

Internal service calls without a clientApp remain supported. `saas-admin` is not a
valid customer-service audience and is intentionally rejected; the B admin
context token carries `saas-b`.

This is a strict allow-list, not a compatibility alias.
