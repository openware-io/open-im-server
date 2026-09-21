# im-admin-service

## 职责

- 管理端后台接口
- 客户端配置下发
- App 版本更新检查
- 举报创建与审核处理
- 小程序服务配置
- 用户状态和消息存储的管理读投影
## 当前接口

- `/admin/**`
- `/config/client/**`
- `POST /client/release-check`
- `/admin/client-releases/**`
- `/miniapp/im-services/**`
- `/reports/**`
- `DELETE /admin/users/{id}`（删除用户：硬删 + 级联清理，消息本体保留；管理员账号 409
  `ADMIN_ACCOUNT_UNDELETABLE`、不能删自己 409 `CANNOT_DELETE_SELF`、用户名不一致 400
  `USERNAME_CONFIRM_MISMATCH`）
- `GET /admin/audit-logs`（审计日志：透传 `common-audit-service`，与 SaaS 后台同一套审计）

## 说明

- 管理域职责已独立，不再回退到聚合 API 服务。
- `/admin/users`、`/admin/messages` 与 `/admin/stats` 读取 `adm_` 投影；`/admin/users/{id}` 保持调用用户域内部详情接口。
- 投影消费 `im_user_event_status_changed_v1` 和 `im_message_event_stored_v1`，以 `adm_projection_event.event_id` 去重并支持重复投递。
- 删除用户与状态变更写 `common-audit-service`（action `im-user.delete` / `im-user.status.update`，
  resourceType `user_account`；detail 只含用户名与级联计数，不落 PII）。
- 删除是 **IM 自己的业务边界**，不引入 SaaS 域门禁：守卫只有「不能删自己」+ 用户服务内的
  「管理员账号（`user.role='admin'`）不可删 / 用户名二次确认 / 账号不存在」。
  统一账号落点（`idt_login_identity` / `idt_oauth_link`，以及成为孤儿的**客户** `idt_account`）的清理
  由 `im-user-service` 在删除事务内调用 `platform-identity-service` 完成；员工 / 平台运营账号本体保留
  （删除后处于「未绑定 IM」，等后台换绑新的 IM 账号）。
