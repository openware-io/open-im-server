# ADR 0013：角色工作台作为平台能力

## Status / 状态

**proposed（提案中，2026-10-22）**。待原型评审后接受。

## Context / 背景

平台已有角色与权限数据：

```text
iam_role
iam_permission
iam_user_role
iam_menu.required_permission
```

但角色只影响“能否访问”，没有影响“登录后进入哪个工作台、看到哪套导航、默认什么数据范围”。

当前问题：

- 平台超管、租户管理员、门店店长共用一套侧栏；
- 没有门店角色时，门店菜单直接消失；
- 所有角色都从第一个菜单叶子进入；
- 酒店配置与门店运营混在一个页面；
- 新增业态只能继续加 Tab；
- 仍存在“菜单可见、操作 403”的体验。

## Decision / 决策

1. **角色工作台是平台能力**，由 `platform-admin-service` 提供角色档案和登录落点，前端统一渲染工作区。
2. 引入 `iam_role_profile`：

   ```text
   role_id
   workspace_code     PLATFORM / HEADQUARTERS / STORE / IM
   landing_route
   default_scope
   default_business_type
   nav_profile
   data_scope
   status
   ```

3. 登录/会话响应返回：

   ```text
   roles
   workspaces
   defaultWorkspace
   landing
   ```

4. 菜单按 `workspace + context + businessType` 返回；前端不再维护全量菜单或手工注入业务菜单。
5. 四个工作区：

   ```text
   Platform        平台运营
   Headquarters    集团/租户总部
   Store           门店经营
   IM              IM 管理
   ```

6. 统一两套角色口径：

   - `cons_admin_account.role` 只保留为引导/迁移期账号角色；
   - `iam_user_role` 是授权与工作台的唯一运行口径；
   - 最终由 IAM 角色映射工作区。

7. 门店按业态注册工作台模块：

   ```text
   businessModule
     code
     workspace
     routeBase
     menuProvider
     dashboardProvider
     capabilities
   ```

8. 权限三要素取交集：

   ```text
   capability（业务开通）
   permission（角色权限）
   context（租户/门店/业态）
   ```

   不满足时菜单不返回、按钮不渲染、深链回角色首页并说明原因。

## Consequences / 后果

正面：

- 每个账号只看到自己的 workbench；
- KTV/酒店/未来业态共用一套壳；
- 角色管理真正影响体验，而不只是 403；
- 新业态接入不修改平台壳；
- 权限与菜单一致，减少“看得见点不了”。

代价与风险：

- 需要补角色档案、菜单契约和工作区壳；
- 需要清理两套角色口径；
- 需要给门店角色做数据迁移/初始化；
- 需要角色 × 工作区 × 菜单的快照测试。

## Alternatives / 备选方案

- **继续用一套侧栏 + 权限隐藏**：拒绝。角色体验仍然缺失。
- **每个后台独立前端**：拒绝。重复壳层和权限逻辑。
- **只在酒店页面做角色判断**：拒绝。平台级问题不能业务定制。
- **继续用第一个菜单叶子作为首页**：拒绝。角色落点不可控。

## References / 参考

- [后台角色工作台与信息架构](../../40-domain/ADMIN_WORKBENCH.md)
- [业务域分类规范](../../80-development/standards/05_DOMAIN_CLASSIFICATION.md)
- [订单平台域](../../40-domain/order/README.md)
- [酒店实现方案](../../40-domain/hotel/HOTEL_IMPLEMENTATION_PLAN.md)
