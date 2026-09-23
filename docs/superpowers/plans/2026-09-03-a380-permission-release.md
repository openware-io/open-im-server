# A380 Permission Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Restore A380 operator access and prevent stale authentication images from reaching ACK.

**Architecture:** Cookie-backed SaaS sessions remain the browser contract. Identity maps every IM/IDaaS identity to the canonical SaaS account before IAM lookup; deployment smoke checks verify that the running image exposes that contract.

**Tech Stack:** Spring Boot 4, JUnit 5, PowerShell, Docker, Kubernetes ACK, Vue/Vite.

**Spec:** `docs/superpowers/specs/2026-09-03-a380-permission-design.md`

## Global Constraints

- Preserve `/api/v1/auth/session`, `/api/v1/auth/csrf`, `/api/v1/auth/contexts`, and `/api/v1/auth/context/select` contracts.
- Never treat an internal service failure as an empty permission set.
- Do not claim release completion without fresh tests and live rollout evidence.

---

### Task 1: Lock the authentication contract with tests

**Files:**
- Modify: `platform-services/identity/platform-identity-service/src/test/java/io/openware/platform/identity/application/AuthContextApplicationServiceTest.java`
- Create: `scripts/verify/ack-auth-contract.ps1`

- [ ] Add tests proving tenant failures raise a typed error and valid account contexts remain available.
- [ ] Run the focused identity test and observe the failure before implementation.
- [ ] Add the ACK HTTP contract script to reject 404 on session/CSRF endpoints.

### Task 2: Stop permission failures being masked

**Files:**
- Modify: `platform-services/identity/platform-identity-service/src/main/java/io/openware/platform/identity/application/AuthContextApplicationService.java`
- Modify: `platform-services/identity/platform-identity-service/src/main/java/io/openware/platform/identity/api/controller/AuthContextController.java`

- [ ] Return stable `IAM_CONTEXT_UNAVAILABLE` errors for tenant-service failures.
- [ ] Reject malformed or unauthorized context selection instead of returning opaque placeholder tokens.
- [ ] Keep normal qian001 role/context behavior unchanged.

### Task 3: Add release consistency gates

**Files:**
- Modify: `scripts/deploy/ack.ps1`
- Modify: `k8s/ack/platform-identity-service.yaml`

- [ ] Assert each desired image tag after `set image` and rollout.
- [ ] Run `ack-auth-contract.ps1` against the gateway/identity route after rollout.
- [ ] Keep Redis host/password sourced from cluster service and Secret.

### Task 4: Build, deploy, and verify

**Files:**
- Modify: generated ACK image references as part of release.

- [ ] Run Maven full tests and frontend checks.
- [ ] Build/push identity, gateway, admin, tenant, and mobile artifacts as required.
- [ ] Roll out ACK and verify qian001 mapping, auth endpoints, contexts, and selected store context.
- [ ] Commit and push all repository changes only after green verification.
