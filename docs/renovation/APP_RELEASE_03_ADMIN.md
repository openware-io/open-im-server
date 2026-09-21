# 客户端版本发布治理：管理后台实施方案

> 方案集：`APP_RELEASE`；序号：`03`；实施边界：`gv_chat_admin` 发布治理界面与 API Client；前置：[服务端方案](APP_RELEASE_01_SERVICE.md)、[App 实施方案](APP_RELEASE_02_APP.md)；长期规则：[客户端发布与版本治理规范](../standards/14_CLIENT_RELEASE_GOVERNANCE.md)；状态：设计待实施。

## 1. 重构范围

当前后台只有单页 `src/views/config/app-release.vue`：支持按平台列出、手填下载/商店地址、布尔 `published` 发布和物理删除。它必须整体替换为“发布治理”工作台，不保留旧请求、字段、表格列或删除操作。

当前 `src/api/appRelease.js` 的 `/admin/app-releases` CRUD 也全部删除，替换为 `src/api/clientRelease.js`。管理路由可从 `config/app-release` 一次性改为 `config/client-releases`；旧前端路由不保留 redirect。后端管理 API 使用 `/admin/client-releases`，以明确其管理的是运行客户端发布，而不是任意 App 配置。

实施提交的最小文件清单为：

```text
删除
  src/api/appRelease.js
  src/views/config/app-release.vue

新增
  src/api/clientRelease.js                    # 强类型请求封装与响应解包
  src/views/config/client-releases/index.vue  # 列表、筛选、状态动作入口
  src/views/config/client-releases/detail.vue # Release、Artifact、审计只读详情
  src/views/config/client-releases/policy.vue # 最低支持构建号工作台
  src/views/config/client-releases/actions.js # 允许动作和确认文案的纯展示映射
  src/utils/idempotentRequest.js              # 重试期间复用同一 UUID 的请求封装

修改
  src/router/index.js                         # 只注册 config/client-releases，无旧 redirect
  src/api/request.js                          # 透传 Idempotency-Key 与 X-Request-Id，统一错误解析
  权限菜单、面包屑、国际化资源和相关单测
```

`actions.js` 只能控制展示，不能成为状态机或权限来源；每次动作完成后必须按服务端返回的 `{data,requestId}` 更新详情。后端拒绝时 UI 以标准错误响应为准。

## 2. API Client 与请求规则

| 能力 | 新调用 |
| --- | --- |
| 列表/详情 | `GET /admin/client-releases`、`GET /admin/client-releases/{id}` |
| CI 创建/编辑草稿 | `POST /admin/client-releases`、`PUT /admin/client-releases/{id}` |
| 提交/定时 | `POST /admin/client-releases/{id}/submit` |
| 扩大灰度 | `POST /admin/client-releases/{id}/rollout` |
| 暂停/恢复/撤回 | `POST /admin/client-releases/{id}/pause`、`resume`、`withdraw` |
| 最低支持版本策略 | `GET /admin/client-release-policies`、`PUT /admin/client-release-policies/{platform}/{channel}` |
| 审计 | `GET /admin/client-releases/{id}/audit-logs` |
| 平台接入状态 | `GET /admin/client-release-platforms` |

列表响应固定为 `{items,page,pageSize,total,updatedAt}`；每一个非列表成功响应均为 `{data,requestId}`，失败响应为 `{code,message,requestId,retryable,fieldErrors}`。每一个写请求由 API Client 生成 UUID `Idempotency-Key`，传递 `X-Request-Id`，并携带 `expectedRowVersion`。网络超时后重试必须复用同一幂等键，不能重新生成后重复发布。冲突响应必须刷新详情并提示“发布记录已被其他管理员修改”，不能自动覆盖。

管理端不上传二进制包：CI 上传制品至受控分发存储，生成 Artifact manifest 后由拥有 `ROLE_ADMIN` 的受控发布机器人调用草稿创建/编辑 API。首发 Admin UI 不提供“新建 Release”或 Artifact 编辑入口，只显示 CI 已创建的草稿并允许填写发布说明、排期和状态动作；UI 不得允许管理员手工填写 SHA-256、证书摘要或任意下载 URL。当前权限模型只有 `ROLE_ADMIN`，这不是服务端区分机器人和人工的安全边界；发布机器人凭据隔离和细粒度发布角色须在 IAM 专项中实现。双人审批同样不属于本次范围，不能以按钮禁用或前端账户字段伪造审批。

页面加载时必须先调用 `GET /admin/client-release-platforms`，展示五个平台及 `enabled`、可用制品类型和接入说明。初始仅 Android/iOS 为 `enabled=true`；Windows/macOS/Linux 显示“PC 预留/未启用”。未启用平台不显示创建、提交、灰度、恢复和最低版本策略入口；若历史上已有该平台 Release，只保留暂停/撤回这类安全处置动作。展示限制只是用户体验保护；服务端仍必须对所有写操作和公开 release-check 做最终拒绝/决策，不能依赖前端开关。

## 3. 页面与交互设计

### 3.1 发布列表

页面顶部按“移动端 / PC 客户端”分组，筛选项为平台、渠道、状态、版本/构建号和发布时间。平台选项仅 Android、iOS、Windows、macOS、Linux；不得展示 Web。列表必须在平台列显示 `enabled/预留` 标识，最少展示：

- 平台、渠道、版本、buildNumber、状态、灰度百分比、最低支持构建号；
- Artifact 数、架构/包类型、制品校验状态；Android 允许显示 `google-play`（商店跳转）或 `apk`（直装），禁止把 `aab` 当作客户端可下载制品；
- 排期/首次发布时间、操作人、最后更新时间；
- 允许操作（查看、编辑草稿、提交、上调灰度、暂停、恢复、撤回），由服务端返回状态决定。

删除按钮和“立即发布”开关完全移除。`released`/`rolling_out` 不允许在表格中行内编辑；所有危险动作打开原因必填的确认弹窗，并显示影响的平台、渠道、目标构建号和灰度比例。

### 3.2 CI 草稿编辑器

草稿表单分为以下不可混淆的区域：

1. **发布身份**：平台、渠道、SemVer、buildNumber、协议兼容基线。由 CI 写入且全程只读；未启用平台的 manifest 由服务端拒绝，不能通过 UI 强行提交。
2. **制品清单**：由 CI manifest 导入；显示架构、包类型、下载域名、大小、SHA-256、签名/公证摘要。全程只读，服务端做权威校验。
3. **发布内容**：发布说明、商店链接、排期时间。
4. **发布策略**：初始灰度、是否命中后强更、该 Release 的最低支持构建号。最低版本变更必须在独立策略页面进行，不能随着编辑草稿暗中上调。
5. **兼容性**：协议版本与服务端能力基线；动态 Feature Flag 不出现在此处。

提交前 UI 只校验可编辑字段：发布说明非空、灰度范围、排期晚于当前时间和变更原因非空。制品、SemVer、构建号、HTTPS 域名和架构匹配由 CI 与服务端验证；UI 展示通过不代表可发布。

### 3.3 Release 详情与审计

详情页展示不可变制品、灰度命中规则摘要、版本策略、所有状态变更及操作者/原因/请求 ID。撤回原因必须完整保留。后台不得提供修改、删除审计记录的 UI 或 API。

## 4. 与服务端和 App 的协同顺序

1. **准备发布**：先将新 Admin 静态站与新 App 包提交/分发到可控渠道。新 App 的 release-check 在后端尚未切换时仅按“检查不可达且本地未被阻断”处理，绝不调用旧 GET 作为 fallback；新 Admin 此时不执行发布写操作。
2. **服务端切换**：同一发布窗口部署包含新代码和 V2 的后端、Gateway 与安全配置，立即删除旧 Gateway 路由、旧 Controller、旧 DTO、旧 Admin API/页面和旧 App 请求。部署后请求旧路径必须得到 `404`，不能返回旧数据。
3. **可用性验证**：刷新新 Admin，使用新 App internal 包验证 POST 检查、草稿详情、状态动作与审计；任一步失败，回滚应用二进制/Gateway 并按数据方案处理，不能临时恢复旧 Controller。
4. **首次发布**：CI 创建 Android/iOS internal 草稿；管理员只通过新工作台提交、灰度、暂停和撤回。Windows/macOS/Linux 在各自平台交付验收前不得创建或提交 Release；未通过 internal 完整演练，不开放 beta/stable 操作权限。

管理后台不是发布事实的权威来源；权威状态在 `im-admin-service` 的事务和审计中。前端不能因按钮禁用、缓存列表或本地状态而假设发布成功，所有操作后必须读取服务端详情。

## 5. 测试与验收

- API Client：所有新路径、Idempotency-Key 重放、乐观锁冲突、401/403、网络超时重试。
- 页面：平台过滤没有 Web；五平台 `enabled/预留` 展示；未启用平台不出现可执行动作；CI 草稿展示；Artifact manifest 只读；Android `aab` 不可作为客户端 Artifact；状态机允许/禁止动作；灰度只能提高；撤回必须填写原因；无删除入口。
- 联调：草稿→排期/灰度→暂停→恢复→全量→撤回；并行管理员编辑；CI manifest 不匹配；最低版本策略改变后的 App 决策。
- 发布后：旧 `appRelease.js`、`app-release.vue`、旧路由和旧 `/admin/app-releases` 请求均不存在，OpenAPI 与后台实际响应一致。
