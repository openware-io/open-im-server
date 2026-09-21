# A380 Consumer Context and Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Let ordinary IM consumers enter A380 through a scoped consumer context and recover cleanly from stale or unavailable authorization state.

**Architecture:** Add an additive consumer-application registry in tenant IAM. Persist OAuth `appId` only in the server-side SaaS session, resolve a namespaced consumer context through tenant-service, and keep operator IAM contexts unchanged. Make the H5 context fetch bounded-retry and render typed recovery states.

**Tech Stack:** Java 21/Spring Boot/MyBatis/Flyway, vanilla A380 H5 JavaScript, Node test runner/Vite build.

**Spec:** `docs/superpowers/specs/2026-09-04-a380-consumer-context-recovery-design.md`

## Global Constraints

- Do not trust tenant, store, member, token, or permission values from the browser.
- Do not grant consumer accounts operator roles.
- Preserve legacy operator sessions without an app ID.
- Retry authorization at most once and never loop redirects.
- Sync colleague changes before release, then retest and publish the merged result.

### Task 1: Consumer registry and internal IAM contract

**Files:** tenant migration, mapper/row/provider/controller, identity client.

- Add `iam_consumer_application` with A380 mapping to tenant `100`, organization `100`, store `100`, version `1`, and permissions `reservation.view`/`reservation.create`.
- Add internal tenant endpoints for resolving the namespaced context and its permission snapshot.
- Add identity client methods and tests for the new contract.

### Task 2: Server session app binding and context selection

**Files:** identity session store, OAuth callback controller, context service/controller/tests.

- Persist callback `app_id` in the HttpOnly server session while keeping old session JSON readable.
- Return the consumer context when the session app is registered and select it through the registry, never through arbitrary request scope data.
- Preserve operator selection and reject malformed/unauthorized consumer IDs.

### Task 3: H5 bounded recovery and error states

**Files:** `gv_saas_mobile/c-end/oauth.js`, `app.js`, tests.

- Revalidate context reads, retry one time, and distinguish context failure from OAuth fallback.
- Add typed errors and recovery copy/actions for missing access, expired session, service outage, and multiple contexts.
- Add tests proving no redirect loop and friendly messages.

### Task 4: Verification and release gate

- Run focused Java and Node tests, then the full backend test/build and A380 production build.
- Sync remote colleague changes, resolve conflicts, rerun all verification, commit, push, build immutable images, and verify ACK rollout.
