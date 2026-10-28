# OpenChat 2.4.1 / 本地 Kind 现状交接（2026-10-28）

> 用途：新会话/新窗口接手时先读本文件，避免重复排查。
> 工作目录：`D:\projects\cnb-oss`；后端仓库：`open-im-server`。

## 1. 代码基线

| 仓库 | 分支/HEAD | 状态 |
| --- | --- | --- |
| open-im-server | `main` / `15dfac0a` | 已 push origin/main；工作区除一个未跟踪 ADR 外干净 |
| open-saas-admin | `main` / `2c68281` | 已 push，0 dirty |
| open-saas-mobile | `main` / `695a424` | 已 push，0 dirty |
| open-chat-app | `main` / `b5b7cd6` | 已 push，0 dirty |

- 发布 tag：`v2.4.1` → `83b69eff`（release merge commit）
- 注意：`83b69eff` 之后还有本地热修，当前 main 比 tag 多：
  - `a59e7e43` 恢复 `Admin@123` 与 E2E 凭证默认
  - `c5fea953` 菜单按业务上下文剪枝
  - `15dfac0a` 文档说明
- 若要把 tag 与最终 main 对齐，建议下一轮按 PATCH 规则升 **2.4.2** 后重打 tag，不要直接移动已推送的 `v2.4.1`。

## 2. 本地访问入口

- SaaS 管理后台：<http://127.0.0.1:30080/saas/#/login>
- IM 管理后台：<http://127.0.0.1:30080/im/>（同一套 admin 账号，SSO 进入）
- A380/B 端：<http://127.0.0.1:30080/b/>
- C 端 H5：<http://127.0.0.1:30080/c/>
- MinIO API：<http://127.0.0.1:30900/>
- MinIO Console：<http://127.0.0.1:30901/>
- Kind API 的 7562 是内部管理端口，不是业务入口。

### 账号

| 用途 | 用户名 | 密码 |
| --- | --- | --- |
| SaaS 超级管理员 | `admin` | `Admin@123` |
| 租户管理员 | `tenant-admin` | `Admin@123` |
| MinIO | `local-minio-access-key` | `ZnjWP0RZKFnsaMJ_Ju-ZyuVfTTqbtlia4FGhK6vQqAo` |

- 当前清库库 `cons_admin_account` 只有以上两个 SaaS 账号；`platform-operator` / `store-manager` / `ktv-manager` **未 seed**，不是密码问题。
- `Admin@123` 已由 admin `V7__reset_demo_admin_password.sql` 固定；E2E 准备脚本也改为同一密码，避免再次被改乱。
- 实测：admin/tenant-admin 登录 200；MinIO 登录 204。

## 3. 本轮修复的关键问题

### 3.1 菜单显示权限（本轮重点）

- 现象：`tenant-admin` 在租户总部上下文（`100:100:0`，`businessType=null`）仍看到「KTV 配置」，打开后提示“当前门店不支持该业务操作”。
- 根因：`AdminMenuApplicationService.matchesBusinessType()` 在上下文 `businessType` 为空时直接 `return true`，导致业务专属菜单（`business_type=KTV` 或 `domain_code=ktv`）在没有业务上下文的 HQ 也可见。
- 修复：业务专属菜单必须有匹配的 `businessType`；HQ/空业态上下文返回 false。core 菜单不受影响。
- 代码：`platform-services/admin/platform-admin-service/.../AdminMenuApplicationService.java`
- 回归测试：`AdminMenuApplicationServiceTest#tenantHeadquartersHidesBusinessSpecificMenusWithoutBusinessContext` 与 `businessSpecificMenuIsVisibleInMatchingBusinessContext`
- 实机验证：
  - HQ `100:100:0`：`/admin/ktv/config` 数量 = **0**
  - KTV 门店 `100:100:100`：`/admin/ktv/config` 数量 = **1**
- 当前 live admin 镜像已用 `c5fea953` 的 scoped 部署（`admin-menu-fix.json`），其他服务仍是 2.4.1 发布镜像。

### 3.2 账号被 E2E 改密

- 根因：`prepare-e2e-credentials.ps1` 曾把 `admin`/`tenant-admin` 重置为 `E2ePassw0rd!2026`，且初始种子 hash 并非文档 `Admin@123`。
- 修复：admin `V7` 迁移重置为 `Admin@123`；E2E 准备/管理端/C 端脚本默认密码统一为 `Admin@123`。
- 已验证 V7 在 live Flyway 为 applied（version 7，success=1）。

### 3.3 2.4.1 版本与 C 端 KTV

- 版本已从 2.4.0 升到 **2.4.1**；POM/前端/Flutter 版本与镜像 tag 已统一。
- C 端 KTV 预约已补：
  - `GET /api/v1/me/reservations`
  - `POST /api/v1/me/reservations`
  - 租户/门店/业态从签名上下文派生，会员从当前账号派生；E2E 已 required PASS（不再有 KTV C 403 optional gap）。
- 2.4.1 全量 E2E 结果（菜单修复之前）：
  - admin **99/99 PASS**
  - C/OTA/渠道 **142 PASS / 0 FAIL / 0 GAP**

### 3.4 部署脚本 env 被覆盖

- `k8s.ps1` 的镜像 patch 曾把容器 `env` 覆盖为 5 个 build-info 变量，导致 `SAAS_COOKIE_PLAIN`、`SAAS_DEV_SESSION_ENABLED`、`HOTEL_CHANNEL_SANDBOX_TENANT_ID`、`SANDBOX_PAY_ENABLED` 丢失。
- 已修为：patch 不带 `env`，用 `kubectl set env` 追加 build-info；本地关键 cookie/dev-session/OTA/SANDBOX 变量在部署后显式设置。

## 4. 当前集群状态

- Kind：`open-im-local`
- Pods：**32 Running + 3 Completed**，0 异常
- 订单服务：`ghcr.io/openware-io/platform-order-service:2.4.1-SNAPSHOT`
- admin：scoped 镜像 `c5fea953`（菜单修复）
- Flyway admin：V7 applied；旧列/旧表/旧权限收口已由 V27/V28/V29/V35 生效
- `/saas/` 返回 200

## 5. 校验命令

```powershell
# 全量门禁（当前 main 最后一次：15dfac0a 通过）
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\invoke-engineering-validation.ps1 -Scope Full

# E2E（需要单实例；会准备 Admin@123 凭证）
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify\run-e2e-domain.ps1

# 菜单 API 手测
# 1) 登录 tenant-admin/Admin@123
# 2) 选择 100:100:0 后 GET /api/v1/admin/menus?scope=TENANT -> 不含 /admin/ktv/config
# 3) 选择 100:100:100 后同一接口 -> 含 /admin/ktv/config
```

## 6. 本地镜像/缓存清理记录

- 已删除 37 个 `2.2.0-SNAPSHOT` / `2.4.0-SNAPSHOT` 旧 tag。
- 已删除 Kind 内 522 个 `import-2026-*` 旧导入引用。
- Docker Build Cache 已清 0；`docker system df` 镜像约 8 GB。
- 当前保留的是运行所需 2.4.1 / 基础设施镜像；数据卷未动。
- `.outputs` 约 1.37 GB，`.dsh` 约 0.34 GB；如新窗口继续排查，优先保留 `.outputs/releases` 证据，清理旧日志需先确认。

## 7. 遗留/下一轮建议

1. **tag 与 main 对齐**：升 2.4.2 后重打 tag；不要把已推送的 `v2.4.1` 强移。
2. **clean Kind 重发**：当前 admin 是 scoped 热更，其他服务是 b9d 发布镜像；若要一次性发布 2.4.2，需重新 build full manifest + 清库部署。
3. **未跟踪 ADR**：`docs/20-architecture/adr/0016-organization-management-boundary.md` 仍是 untracked，接手时决定是否合入。
4. **额外账号**：`platform-operator` / `store-manager` / `ktv-manager` 未 seed；需要时再建，并绑定角色/门店上下文。
5. 菜单修复后只跑了目标测试 + 全量门禁，未重跑整套 E2E；下轮 clean deploy 后建议跑一次 `run-e2e-domain.ps1` 确认。
