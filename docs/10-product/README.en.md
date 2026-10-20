# OpenChat Product Introduction

[中文](README.md) | [English](README.en.md)

> Status: `current` (`develop/2.3.0`)  
> One line: **OpenChat is an open-source instant-messaging + multi-tenant SaaS platform, covering mobile, desktop, operations consoles and one-click local deployment.**

![OpenChat website](../assets/screenshots/website.jpg)

## 1. What it is

OpenChat combines two capabilities that are usually built separately:

- **IM open platform**: private/group chats, channels, secret chats, WebSocket access, reliable messaging and multi-device sync;
- **Multi-tenant SaaS platform**: tenants/stores, accounts and permissions, members/points/wallet, KTV reservations and orders, payments and audit;
- **Unified experience**: OpenChat App, desktop client, SaaS C-end H5, B-end merchant workspace, IM admin, SaaS operations console and website.

It is not a chat-SDK demo. It is a product-grade foundation that runs locally end to end, is split by domain, and can keep evolving.

## 2. What it solves

| Common problem | OpenChat approach |
| --- | --- |
| IM and business systems are isolated | IM is an open platform; SaaS connects through OAuth/tenant context with unified accounts and permissions |
| Multi-tenant, store and role models are hard | Platform accounts, IM accounts, tenant IAM and store scope are modeled in layers |
| Local environments are hard to reproduce | Windows + Docker Desktop + Kind one-click build, deploy and verification |
| Long connections vs reliable messaging | `im-access-ws` for access, RocketMQ for buffering/ordering, MySQL as authority, MongoDB as hot projection |
| Service boundaries erode | `api → application → domain ← infra` layering; the gateway stays cross-cutting only |

## 3. Product surfaces

### 3.1 Website and brand entry

![OpenChat website](../assets/screenshots/website.jpg)

### 3.2 SaaS C-end service board

The in-app service board and H5 home cover hotels, KTV, bars, billiards, food, delivery and travel.

Local demo data includes the `OpenChat 生活服务` tenant, `OpenChat KTV 旗舰店`, six KTV rooms, the service catalog, paid/completed orders and reservations.

![OpenChat C-end service board](../assets/screenshots/c-end.jpg)

### 3.3 IM admin console

The console manages users, conversations, groups, client releases, open-platform apps and audit logs.

![OpenIM admin login](../assets/screenshots/im-admin.jpg)

### 3.4 OpenChat App (Xiaomi 9 device)

The conversation list, group chat and service board come from the running Kind environment; demo accounts and messages are local seed data.

<table>
  <tr>
    <td align="center"><img src="../assets/screenshots/phone-im-home.jpg" width="260" alt="OpenChat conversations"><br><sub>Conversations</sub></td>
    <td align="center"><img src="../assets/screenshots/phone-im-group.jpg" width="260" alt="OpenChat group chat"><br><sub>Group chat</sub></td>
    <td align="center"><img src="../assets/screenshots/phone-im-service.jpg" width="260" alt="OpenChat service board"><br><sub>Service board</sub></td>
  </tr>
</table>

## 4. Capability matrix

| Layer | Capabilities |
| --- | --- |
| Instant messaging | Private/group chats, channels, secret chats, read receipts, recall, multi-device sync, WebSocket access |
| Accounts and permissions | SaaS/IM accounts, tenant/store/role, OAuth/OIDC, unified sign-in |
| Business platform | Tenants, customers, members, points, wallet, KTV reservations, orders, resources, payments, marketing |
| Administration | IM admin, SaaS operations console, merchant workspace, client releases and service-board configuration |
| Infrastructure | MySQL, MongoDB, Redis, RocketMQ, MinIO, Kind/Docker Compose |
| Engineering governance | DDD layering, documentation gates, version matrix, E2E and release standards |

## 5. Architecture at a glance

```text
Client / H5 / admin
  → Ingress
  → gateway (REST) / im-access-ws (WebSocket)
  → platform-* / im-* / common-*
  → MySQL / MongoDB / Redis / RocketMQ / MinIO
```

See the [engineering topology](../20-architecture/TOPOLOGY.en.md) for layers, repository/deployment mapping and data ownership; see the [architecture overview](../20-architecture/OVERVIEW.en.md) for runtime boundaries.

## 6. Who it is for

- Startups that need an IM + business SaaS foundation quickly;
- Industry applications requiring multi-tenancy, stores, permissions and business orchestration;
- Teams studying microservice boundaries, reliable long connections and one-click local Kubernetes;
- Anyone who needs a hackable open-source foundation rather than a demo.

## 7. Quick start

Requirements, Kind one-click deployment, entry points and accounts are in the [root README](../../README.en.md#one-click-deployment-recommended).

Recommended first-run order:

```text
One-click deploy → website/C-end → SaaS admin → engineering topology → service docs
```

## 8. Project status

- Development branch: `develop/2.3.0`
- Latest stage tag: `2.2.1`
- License: Apache License 2.0
- Documentation: [Documentation center](../README.en.md)
