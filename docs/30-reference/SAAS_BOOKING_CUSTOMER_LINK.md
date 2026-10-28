# SaaS hotel booking customer link

The B-end hotel booking flow accepts an optional `bookedByAccountId`. When the
operator leaves it empty, `platform-order-service` resolves the guest phone via
identity-service:

```text
GET /internal/identity/accounts/lookup?loginType=PHONE&loginIdentifier=<phone>
```

A found account is written to `htl_booking.booked_by_account_id`, so the C-end
customer sees the booking in “我的预约”. A missing account leaves the booking
unlinked; it is not a client-side convenience fallback.

`IDENTITY_SERVICE_BASE_URL` is configured for platform-order-service in the
local manifest.
