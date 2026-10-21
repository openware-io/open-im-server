# Engineering Repositories and Branch Matrix

[中文](ENGINEERING_REPOSITORIES.md) | [English](ENGINEERING_REPOSITORIES.en.md)

> Status: `current` (snapshot 2026-10-21).  
> Purpose: define product repositories, version-branch naming and cross-repository consistency checks.  
> Principle: **one version theme uses the same branch name in every product repository**; never switch only the backend.

## 1. Product repositories

| Repository | Role | Current version branch |
| --- | --- | --- |
| `open-im-server` | backend, gateway, platform services, engineering docs | `develop/2.4.0-hotel` |
| `open-chat-app` | C-end app / H5 | `develop/2.4.0-hotel` |
| `open-chat-desktop` | desktop client | `develop/2.4.0-hotel` |
| `open-chat-admin` | IM admin console | `develop/2.4.0-hotel` |
| `open-saas-admin` | SaaS operations console | `develop/2.4.0-hotel` |
| `open-saas-mobile` | SaaS C-end mobile | `develop/2.4.0-hotel` |
| `open-chat-turn` | TURN / WebRTC access | `develop/2.4.0-hotel` |
| `open-website` | website / operations site | `develop/2.4.0-hotel` |

## 2. Excluded repositories

| Repository | Reason |
| --- | --- |
| `.github` | organization community-health files, not versioned with the product |
| `open-green-pass` | standalone software-engineering test governance platform, not part of the im-saas delivery chain |

If GreenPass joins the release train later, register it here first.

## 3. Branch rules

```text
main                         release/sync only; no direct development
develop/<version>-<theme>    version development; same name in every repository
feature/<topic>              optional; cut from the version branch
```

1. Create the same `develop/<version>-<theme>` in **all** product repositories.
2. Base each branch on a pushed previous-version branch already synced with `main`.
3. A missing repository means the version is not ready.
4. Do not keep `open-im-server` on `2.4.0-hotel` while clients remain on `2.3.0`.
5. `main` carries releases, not half-finished hotel work.
6. Confirm merge or explicit abandonment before deleting a branch.

## 4. 2.4.0-hotel baseline

| Repository | `develop/2.3.0` base | `develop/2.4.0-hotel` remote |
| --- | --- | --- |
| `open-im-server` | `86d7bd9` | pushed |
| `open-chat-app` | `1397dd2` | pushed |
| `open-chat-desktop` | `7786ff8` | pushed |
| `open-chat-admin` | `7eee9ec` | pushed |
| `open-saas-admin` | `4af7263` | pushed |
| `open-saas-mobile` | `10bc2b8` | pushed |
| `open-chat-turn` | `100293d` | pushed |
| `open-website` | `c30dcab` | pushed |

## 5. Consistency check

```powershell
pwsh -File open-im-server/scripts/check-engineering-branches.ps1
```

Checks:

- target branch exists in every product repository;
- current branch matches;
- working tree is clean;
- remote branch exists;
- local HEAD matches the remote.

## 6. Release checklist

- all product repositories on the same version branch;
- clean working trees;
- version branches pushed;
- gates, builds and client packaging pass;
- release manifest records every repository commit;
- merge to `main` only in the release window.

## 7. Related

- [Engineering rules](ENGINEERING_RULES.md)
- [Gate governance](../standards/04_GATE_GOVERNANCE.en.md)
- [Hotel business](../40-domain/hotel/README.en.md)
