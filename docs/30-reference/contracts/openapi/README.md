# OpenAPI 快照（SaaS 服务占位说明）

> E0 基线：本目录存放各服务 Controller 实现后导出的 OpenAPI 快照。**禁止手工伪造快照**（见 `SAAS_PLATFORM_07_EXECUTION.md` §E0）。

## 当前状态

- **最近一次全量重导：2026-09-17（kind 环境，各服务 `/v3/api-docs`，经 `kubectl port-forward` 直连服务端口）**。
  本轮刷新 9 个 SaaS 服务 + 4 个 IM 服务快照；路径、参数、响应信封与 `security` 已按服务实现逐项复核（与各服务 Controller 一致）。
- 快照为服务端 springdoc 的**原始输出**（compact 单行 JSON，OpenAPI 3.1.0），非手工整理；服务或 DTO 变更后重新导出对应快照，并在提交前复核路径、参数、响应信封和安全 scheme。
- **2026-10-29 局部重导 tenant**：权限目录矩阵及四态/并发控制上线后，从运行中的
  `platform-tenant-service/v3/api-docs` 重新导出 `tenant.json`，并覆盖内容一致的 `platform-tenant-service.json`；
  其余服务快照未变。复核：
  - 矩阵：`GET /admin/iam/permissions/matrix`（响应 `ETag = version`，支持 `If-None-Match` 304；`refresh=true` 强制 200）、
    `GET /admin/iam/permissions/matrix/version`；
  - 角色快照/写入：`GET /admin/iam/roles/{roleId}/permissions/snapshot`，`GET /admin/iam/roles/permission-counts`（批量计数），
    `POST /admin/iam/roles/{roleId}/permissions`
    支持 `expectedVersion`，`POST .../permissions/toggle` 同样支持；
  - schema：`PermissionMatrixView` 含 `grantablePermissionCodes`（四态可授予集合）、`version/generatedAt/cached`；
    `RolePermissionSnapshotView` 含 `permissionCodes/version`；`servers[0].url = http://127.0.0.1:4110`。
- 服务端 `security` 快照随服务配置变化；本轮 SaaS 服务快照由 kind 部署（同一份 release manifest）导出，**尚未包含当时未发布的改动**（例如订单取消 `POST /business/orders/{id}/cancel` 在导出的下一批才发布，故本轮快照中不含该路径，属预期）。
- DTO-by-DTO 语义复核仍是 CP6 的独立门禁，不能以 JSON 可解析替代；完成复核后应在本文件记录结果。
- `common-audit-service`（4190）与 `common-sms-service`（4200）**未引入 springdoc**，按「未接 springdoc 的服务」不导出。
- 例外：`hotel-channel-v1.yaml` 是**对外渠道契约**，由渠道协议维护、与 `HotelChannelBookingController`
  手工对齐（不是 springdoc 快照）；二期已实现预订创建/查询/取消，ARI 为 202 no-op 占位。
  order-service 的 springdoc 快照（`order-saas.json`）仍按原规则在 Kind 发布后重导。
  **2026-10-28 已权威重导**：P2-3 清库发布（main `eb663705`）后，经只读 `kubectl port-forward`
  从 `platform-order-service/v3/api-docs` 导出原始 springdoc 输出，覆盖 `order-saas.json` 与
  `platform-order-service.json`（两份内容一致）；已确认不含订单级 `/hold|/unhold`、`HoldRequest`
  与 `OrderPo.holdReason/holdAt`（其中 `/admin/hotel/rooms/{id}/holds` 与 `HoldRequest` 是房态预留，
  属酒店房态功能，保留）。

## 已接 springdoc 的服务（可导出 /v3/api-docs）

| 服务 | 端口 | 导出命令 |
| --- | --- | --- |
| identity | 4100 | `curl http://localhost:4100/v3/api-docs -o docs/contracts/openapi/identity.json` |
| tenant | 4110 | `curl http://localhost:4110/v3/api-docs -o docs/contracts/openapi/tenant.json` |
| resource | 4120 | `curl http://localhost:4120/v3/api-docs -o docs/contracts/openapi/resource.json` |
| order | 4130 | `curl http://localhost:4130/v3/api-docs -o docs/contracts/openapi/order-saas.json` |
| payment | 4140 | `curl http://localhost:4140/v3/api-docs -o docs/contracts/openapi/payment.json` |
| admin | 4150 | `curl http://localhost:4150/v3/api-docs -o docs/contracts/openapi/admin-saas.json` |
| customer | 4160 | `curl http://localhost:4160/v3/api-docs -o docs/contracts/openapi/customer.json` |
| marketing | 4170 | `curl http://localhost:4170/v3/api-docs -o docs/contracts/openapi/marketing.json` |
| payment-channel | 4180 | `curl http://localhost:4180/v3/api-docs -o docs/contracts/openapi/payment-channel.json` |

## 未接 springdoc 的服务

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| audit | 4190 | `common-audit-service` 未引入 springdoc，暂不导出；需先补 springdoc 依赖 |
| sms | 4200 | `common-sms-service` 未引入 springdoc，暂不导出；需先补 springdoc 依赖 |

## 导出注意事项

- 快照文件为 UTF-8 **无 BOM**，末尾保留单个换行。
- 导出前确认服务已启动且 Flyway 迁移完成。
- 服务或 DTO 变更后重新导出对应快照，并在提交前复核路径、参数、响应信封和安全 scheme。

## 参考

- 导出脚本：`scripts/export-openapi-snapshots.ps1`（当前覆盖网关已注册的 IM 服务 user/message/conversation/admin）。
- 契约目录说明：`docs/contracts/README.md`。
