# A380 Consumer Context and Recovery Design

## Problem

An IM OAuth login creates a normal consumer account without an IAM operator role. The A380 C end nevertheless requires exactly one operator context, so ordinary users receive a generic authorization failure even though OAuth succeeded. Session/context refresh failures are also currently rendered as an unfriendly generic error.

## Design

The tenant service owns an explicit `iam_consumer_application` registry. Each approved consumer application maps to an active tenant/organization/store scope and a minimal permission set. Identity stores the OAuth `appId` in the server-side session, exposes the operator contexts plus the mapped consumer context, and signs the consumer context only after resolving the application registry. Operator roles and consumer permissions remain separate.

The A380 client sends no tenant or permission data. It requests contexts with cache revalidation headers and retries one authoritative context read when the list is empty or a context token is rejected. After the bounded retry, the client renders a typed recovery page for expired login, unavailable authorization service, missing consumer access, or ambiguous operator contexts. It never loops through OAuth redirects or displays a generic “no permission” message.

## Compatibility and security

- Existing operator context IDs and selection behavior remain unchanged.
- Legacy sessions without `appId` continue to work for operator contexts.
- Consumer context IDs are namespaced with the registered `appId`; arbitrary tenant scopes cannot be selected.
- Consumer permissions are limited to the application registry and are not added to operator roles.
- Database migrations are additive and idempotent.

## Verification

Unit tests cover consumer context lookup/selection, legacy sessions, empty-context recovery, and typed error rendering. The affected Maven modules and the A380 frontend tests/build must pass before release.
