# im-user-service

## 职责

- 注册登录
- 用户资料
- 设备令牌
- 设备密钥（E2EE：注册/续期/恢复，`/device-keys/**`）
- 好友关系链
- 用户积分
- 用户贴纸

## 当前接口

- 网关公开入口使用 `/api` 前缀，服务直连使用以下无前缀路径。
- `/auth/**`
- `/users/**`
- `/device-tokens/**`
- `/device-keys/**`
- `/friends/**`
- `/user-stickers/**`

## 说明

- 好友关系链属于用户社交关系，统一收敛在该服务
- 积分能力已迁至 SaaS（`platform-customer-service`，后台页面 `gv_saas_admin` 的 `views/tenant/points.vue`）；本服务原有的 `/points/**` 与 `/internal/admin/points/accounts/**` 已于 2026-09-10 移除，IM 管理后台的「积分管理 / 代币管理」入口一并下线
- 设备密钥承载 E2EE 身份：`register` 建立设备密钥、`renew` 轮换、`restore` 供新设备恢复，密钥本身由客户端生成并托管，服务端仅登记公钥与设备指纹
- 当前数据库初始化迁移由该服务持有
- Service 内部按 `api -> application -> domain <- infra` 分层；MySQL 是权威数据源，好友通知通过 Outbox 异步投递。
