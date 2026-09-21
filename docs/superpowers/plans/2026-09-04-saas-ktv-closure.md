# SaaS/KTV Transaction Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the SaaS/KTV identity, resource, billing, payment, and frontend contract gaps identified in the 2026-09-04 review.

**Architecture:** Keep the gateway/session model introduced today, make IAM authorization the only source of tenant context, and connect order aggregates to resource and payment facts through idempotent commands/events. C/B/Admin clients consume server-owned context and bill snapshots.

**Tech Stack:** Spring Boot 4, Java 25, MyBatis-Plus, Redis, MySQL/Flyway, RocketMQ, Vue 3, Vite, Vitest.

**Spec:** `docs/superpowers/specs/2026-09-04-saas-ktv-closure-design.md`

## Global Constraints

- Never trust client `tenantId`, `organizationId`, `storeId`, `accountId`, `permissions`, or `payable`.
- Every state-changing command requires CSRF, `Idempotency-Key`, and optimistic `expectedVersion` where the aggregate has a version.
- Preserve existing local deployment and ACK configuration changes.
- Do not change IM messaging or friend features in this work.

---

### Task 1: Lock down context authorization

**Files:** identity context service/controller/tests; shared tenant context tests.

- [ ] Add failing tests for unauthorized context selection and empty permission snapshots.
- [ ] Implement exact IAM scope verification and explicit 403/503 errors.
- [ ] Ensure business services reject missing context and body scope overrides.
- [ ] Run identity and infrastructure tests.

### Task 2: Split consumer and operator authorization

**Files:** order reservation/order controllers/services; customer controllers; controller tests.

- [ ] Add failing tests for `/me` ownership, business permissions, and cross-tenant access.
- [ ] Implement ownership filters and permission guards for every KTV write endpoint.
- [ ] Remove tenant/store fallbacks from request bodies and default IDs.
- [ ] Run order/customer module tests.

### Task 3: Connect KTV resources and lifecycle

**Files:** order KTV application services/controllers, resource client/commands, migrations/POs, tests.

- [ ] Add failing tests for concurrent occupation, release, transfer, reservation conversion, and pause accumulation.
- [ ] Add occupation IDs and idempotent resource orchestration.
- [ ] Persist pause boundaries and use immutable pricing snapshots.
- [ ] Emit and consume lifecycle events idempotently.
- [ ] Run order/resource integration tests.

### Task 4: Make bill and payment authoritative

**Files:** settlement/bill/payment services/controllers, order payment consumer, migrations, tests.

- [ ] Add failing tests for tampered payable, partial collection, duplicate collection, and completion.
- [ ] Validate against server bill snapshots and write payment/order facts atomically or via idempotent outbox.
- [ ] Materialize service-person items and update `paid_amount`, status, and completion event.
- [ ] Run payment/order integration tests.

### Task 5: Align frontend interaction contracts

**Files:** `gv_saas_mobile` C/B clients/views and `gv_saas_admin` APIs/views/stores/tests.

- [ ] Add failing Vitest tests for fixed idempotency keys, CSRF refresh, context selection, and no mock/default writes.
- [ ] Make multi-store selection explicit and remove `?? 100`, deleted `tenantContext()`, and editable payable.
- [ ] Use wallet brand configuration and formal wallet endpoints.
- [ ] Make all KTV pages consume server bill/config and gate actions by `allowedActions`.
- [ ] Run both frontend suites and production builds.

### Task 6: Full verification and release

- [ ] Run all module tests, frontend tests, checkstyle, and Maven reactor builds.
- [ ] Run KTV E2E-01~14 plus cross-tenant, concurrency, duplicate, and failure-compensation cases.
- [ ] Review diff for deployment/ACK files and unrelated changes.
- [ ] Commit only after every required check passes, then follow the documented release workflow and add desktop release record.
