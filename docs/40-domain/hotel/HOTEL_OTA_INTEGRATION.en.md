# Hotel OTA / Channel Integration Contract

[中文](HOTEL_OTA_INTEGRATION.md) | [English](HOTEL_OTA_INTEGRATION.en.md)

> Status: `draft` (`develop/2.4.0-hotel`).  
> Scope: an external integration contract, not a single OTA's private API.  
> Principle: contract first, adapter second; an OTA is a sales channel, not a second hotel domain.

## Scope

- Hotel, room type, rate plan, availability/rates/restrictions (ARI) mapping;
- OTA booking intake, confirmation, cancellation, modification and refund status;
- Channel auth, idempotency, retries, errors, versioning and sandbox;
- Channel-manager boundary.

## Roles

```text
OTA / Channel Manager  <-->  OpenChat Hotel Channel API  <-->  Hotel Domain
```

OTAs and channel managers never write `res_inventory_calendar` or `ord_hotel_booking` directly. The channel contract is the only entry.

## Channel model

`Channel`, `ChannelCredential`, `ChannelMapping`, `ExternalBookingRef`, `ChannelSyncLog`. Credentials are secret references, never plaintext.

## External API v1

```text
POST /api/v1/open/hotel/v1/ari
POST /api/v1/open/hotel/v1/bookings
POST /api/v1/open/hotel/v1/bookings/{externalRef}/cancel
POST /api/v1/open/hotel/v1/bookings/{externalRef}/modify
GET  /api/v1/open/hotel/v1/bookings/{externalRef}
```

### ARI push

A push carries `channelCode`, `hotelRef`, `roomTypeRef`, `ratePlanRef`, `stayDate`, `total`, `sold`, `stopSell`, `priceMinor`, `currency`, `minStay`, `version`.

- `(channelCode, hotelRef, roomTypeRef, stayDate)` is unique;
- `version` increases monotonically; older versions must not overwrite newer ones;
- batch results are per item.

### Booking intake

An OTA booking carries `externalRef`, hotel/room/rate refs, dates, rooms, guests, guest contact, amount, currency and `paymentResponsibility` (`OTA_COLLECT` / `HOTEL_COLLECT` / `PREPAID`).

- `(channelCode, externalRef)` is idempotent; duplicate submission returns the same booking;
- insufficient inventory returns `409 INVENTORY_UNAVAILABLE`, never partial confirmation;
- response includes `bookingNo`, `status`, `confirmedAt`.

### Cancel / modify / query

Cancel and modify enforce the rate-plan policy and re-check inventory. All operations are idempotent. Query returns status and payment/refund state without unnecessary PII.

## Security

- mTLS preferred; HMAC-SHA256 minimum with timestamp, nonce and body hash;
- five-minute window, nonce replay protection;
- per-channel credentials and secret references;
- no plaintext guest ID in channel payloads or logs;
- every request traceable by `channelCode + externalRef + requestId`.

## Idempotency and errors

- Writes require `Idempotency-Key`;
- retries only for `429` and `5xx`, with exponential backoff;
- business `4xx` is not retried automatically;
- error codes: `INVALID_REQUEST`, `UNAUTHORIZED`, `CHANNEL_FORBIDDEN`, `MAPPING_NOT_FOUND`, `INVENTORY_UNAVAILABLE`, `BOOKING_STATE_CONFLICT`, `POLICY_REJECTED`, `RATE_LIMITED`, `INTERNAL_ERROR`.

## Versioning and sandbox

- URL major version `/v1/`; additive changes are compatible, breaking changes need a major version;
- `channelCode=sandbox` with fixed data and cases: normal booking, duplicate, sold out, cancel, duplicate cancel, bad signature, out-of-order ARI;
- monitor push success, booking latency, inventory conflicts and reconciliation variance.

## Open decisions

1. Real OTA at launch or sandbox + direct first?
2. Ctrip, Meituan, Fliggy or a channel manager first?
3. OTA collect, hotel collect or prepaid?
4. Virtual card / credit-card guarantee?
5. Channel manager build or buy?
6. Reconciliation frequency and SLA?

## Related

- [Implementation plan](HOTEL_IMPLEMENTATION_PLAN.en.md)
- [Hotel decisions](HOTEL_DECISIONS.en.md)
- [API contracts](../../30-reference/contracts/README.md)
- [Database migration standard](../../80-development/standards/23_DATABASE_MIGRATION_STANDARD.md)
