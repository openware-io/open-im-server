# Hotel Prototype Review Guide

[中文](HOTEL_PROTOTYPE_GUIDE.md) | [English](HOTEL_PROTOTYPE_GUIDE.en.md)

> Status: `draft` (`develop/2.4.0-hotel`).  
> Purpose: review the C-end, B-end web and mobile front-desk prototypes without a backend.

## 1. Entries

| Client | Repository | Entry |
| --- | --- | --- |
| C-end | `open-chat-app` | Service Center → Hotel (`/services/demo/hotel`) |
| B-end web | `open-saas-admin` | `/admin/tenant/hotel`; dev sidebar "Hotel management" |
| B-end mobile | `open-chat-app` | `/business/hotel/dashboard`; B-end login "Hotel front desk prototype" |

All data is local mock; no hotel backend calls.

## 2. Run

### B-end web

```powershell
cd open-saas-admin
npm install
npm run dev
```

Open the Vite URL, login/select tenant, then:

```text
/admin/tenant/hotel
```

The dev sidebar adds "Hotel management"; production waits for backend menu delivery.

### C-end / mobile front desk

```powershell
cd open-chat-app
flutter pub get
flutter run
```

```text
B-end login → Hotel front desk prototype
or /business/hotel/dashboard
```

## 3. Review focus

- access mode (group/single store) and sales entry;
- prepay/pay-at-hotel/deposit, stay deposit, points redemption, USD/CNY;
- 7-day inventory, stop-sell, room status;
- arrivals/in-house/due-out, check-in with deposit, checkout with deduction/refund;
- order list/status.

## 4. Known limitations

- mock only, no backend writes;
- no real inventory concurrency, payment callback or refund;
- no invoice/guest registration;
- mobile front desk not wired to BFF;
- C-end cross-group order center is designed in ADR-0009, not implemented.

## 5. Related

- [Implementation plan](HOTEL_IMPLEMENTATION_PLAN.en.md)
- [Decisions](HOTEL_DECISIONS.en.md)
- [Design review](HOTEL_DESIGN_REVIEW.en.md)
- [Billing domain](../billing/README.en.md)
