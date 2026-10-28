# Billing Domain

[中文](README.md) | [English](README.en.md)

> Status: `draft` (`develop/2.4.0-hotel`).  
> Position: supporting domain; turns business charge items into a bill/amount due. It does not own rates, KTV pricing or menu prices.  
> Key: `Billing` is not `Checkout`; `Bill` is not `Invoice`.

## 1. Terms

| Term | Meaning | Owner |
| --- | --- | --- |
| `Pricing` | set base price/rate/package price | business core |
| `Rating / Charging` | calculate charges from usage/time/quantity/price | Billing support |
| `Bill` | line items, discounts, tax, deposit offset, amount due | Billing support |
| `Amount Due` | amount receivable after offsets/payments | Billing support |
| `Checkout` | front-desk settlement process calling Billing/Payment | edge/application |
| `Payment` | funds movement/refund | Payment support |
| `Settlement` | merchant/OTA/channel split and reconciliation | Settlement support |
| `Invoice` | tax document | finance/tax, not in MVP |

Do not model `Checkout` as a domain service, and do not call a `Bill` an `Invoice`.

## 2. Why it can be shared

Every business eventually computes:

```text
line charges
discounts
tax/service charge
deposit offset
paid amount
amount due
change/refund
```

These are calculation/accounting invariants and can be shared.

But "what is a charge" is business-specific:

```text
hotel: room nights, rate plan, minibar, damage, deposit
KTV: room fee, service fee, goods, overtime, package
restaurant: dishes, service charge, table fee, discount
gym: class, personal training, goods
```

Business cores produce charge items; Billing calculates; it does not own business pricing rules.

## 3. Model

```text
Bill
  billNo / tenantId / storeId / sourceType / sourceId
  currency / status / version
  subtotalMinor / discountMinor / taxMinor / serviceChargeMinor
  depositAppliedMinor / paidMinor / amountDueMinor

BillLine
  billId / lineType / title / quantity / unitPriceMinor
  amountMinor / taxMinor / sourceRef

DiscountLine
  billId / promotionId / type / amountMinor / allocation

PaymentRef
  billId / paymentId / amountMinor / status
```

```text
amountDue =
  sum(line.amount)
  - discount
  + tax
  + serviceCharge
  - depositApplied
  - paid
```

All money uses minor units plus currency; never floating point.

## 4. Flow

```text
Business core
  -> ChargeRequest / BillLine (business items, price snapshot)
Billing
  -> subtotal, discount, tax, service charge, deposit offset
  -> Bill / AmountDue
Payment
  -> collect/refund
Billing
  -> write back paid / amountDue
Checkout (BFF)
  -> orchestrates Billing + Payment + deposit
Settlement
  -> merchant/channel split and reconciliation
```

Rules:

- Billing does not read hotel availability, KTV sessions or restaurant menus; it receives line items.
- Business cores send price snapshots/source IDs; Billing never queries business DBs.
- One source document + idempotency key produces one bill.
- A bill can be recalculated before settlement; a settled bill is immutable and requires adjustment/reversal.
- Every calculation is traceable: lines, discount, tax, rounding, operator.

## 5. Boundary

| Business | Business core owns | Billing owns |
| --- | --- | --- |
| Hotel | room nights, rate plan, cancellation, deposit policy | bill aggregation, tax, discount, deposit offset, amount due |
| KTV | room/service/package/overtime rules | line aggregation, discount, amount due, payment write-back |
| Restaurant | dishes, service charge, discount | aggregation, tax, amount due |
| Gym | class/PT/goods | aggregation, discount, amount due |

## 6. Professional naming

Recommended:

```text
Billing / Charging Domain
Rating Engine
Bill
Bill Line
Amount Due
Checkout
Settlement
Invoice
```

Avoid:

```text
Checkout Service      process as a domain
Payable Service      too narrow, confuses accounts payable
Billing Center       empty middle-platform term
Invoice Service      invoice is not a bill
```

## 7. Evolution

### H1: module

Billing is a calculation module inside order/hotel, not a new service. Serve hotel first; reuse Payment; freeze bill/line/idempotency/rounding.

### H2: multi-business

Trigger: KTV/restaurant/gym depend on the same calculation.

Extract `platform-billing-service`, separate tables/contracts; business cores submit a `ChargeRequest` through an ACL.

### H3: settlement/finance

Settlement/reconciliation, invoice/tax, multi-currency/multi-tax.

## 8. Isolation and permissions

- bills carry `tenant_id + store_id`;
- a business can only read its own store bills;
- backend resolves context from the session;
- cross-store/tenant returns 403/404;
- adjustments/reversals require permission and audit.

## 9. Anti-patterns

- hotel rate rules inside Billing;
- one amount-calculation implementation per business;
- using `double` for money;
- mutating a settled bill;
- treating Checkout as a domain service;
- calling a Bill an Invoice.

## 10. Related

- [Domain map](../DOMAIN_MAP.en.md)
- [Public support blueprint](../PLATFORM_DOMAINS.en.md)
- [Order platform](../order/README.en.md)
- [Hotel implementation plan](../hotel/HOTEL_IMPLEMENTATION_PLAN.en.md)
- [Currency standard](../../80-development/standards/24_CURRENCY_CONVENTIONS.en.md)
