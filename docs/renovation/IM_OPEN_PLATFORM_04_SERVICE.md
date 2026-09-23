# IM 开放平台 · 服务板块接入规范（小程序服务项）

> **变更记录（v1）**：首发创建。补充「服务板块 → 小程序服务项 → 外部服务（打车等）」这一**轻量接入层**，与 [IM_OPEN_PLATFORM_01_SERVICE](IM_OPEN_PLATFORM_01_SERVICE.md) 的 OAuth 开放平台接入（重量接入层）并列；本规范是 C 端服务板块接入的唯一实施依据，字段/接口以 im-admin-service 现行实现为准。

## 0. 方案集声明

| 项目 | 内容 |
| --- | --- |
| 方案集 | `IM_OPEN_PLATFORM` |
| 顺序号 | `04` |
| 实施边界 | `SERVICE`（服务板块：服务类型 / 服务项 / 启用停用 / C 端公开读 + 后台管理写） |
| 前置方案 | [IM_OPEN_PLATFORM_01_SERVICE](IM_OPEN_PLATFORM_01_SERVICE.md)（开放平台 OAuth 接入，可选叠加）、[CLIENT_INTEGRATION_01_APP](CLIENT_INTEGRATION_01_APP.md) §5.4 N-01（C 端契约）、[CLIENT_INTEGRATION_02_ADMIN](CLIENT_INTEGRATION_02_ADMIN.md) §6 AD-18/AD-19（后台契约） |
| 目标工程 | `im-services/admin/im-admin-service`（`MiniappServiceItem` / `MiniappServiceType`、`AdminManagementController`、`V1__adm_management_domain_baseline.sql`、`V7__seed_miniapp_services.sql`）、`gateway`（`/api/v1/miniapp/**`、`/api/v1/admin/**` 路由） |

## 1. 定位与目标

IM 对第三方外部服务提供**两层接入**，外部服务（打车 / 外卖 / KTV / 电商 / 本地生活等）按需选择：

| 层级 | 名称 | 解决的问题 | 接入成本 | 是否必经 |
| --- | --- | --- | --- | --- |
| **A（本规范）** | 服务板块接入（小程序服务项） | C 端入口 + 跳转 + 展示：让用户在「服务板块」看到并点击进入外部服务 | 低（后台登记一个服务项即可） | **是，默认路径** |
| **B** | 开放平台 OAuth 接入 | 账号互通 + 授权 + 用户信息同步 + 消息触达 | 高（应用注册 / OAuth / 事件订阅） | 否，仅当外部服务需要 IM 账号体系时叠加 |

- 「打车」等外部服务**默认先走层级 A**：在服务板块注册为一个「服务项（小程序）」，C 端点击后跳转到打车小程序 / H5 / 自定义 scheme，业务闭环由打车方自持。
- 若打车方还需要 IM 的登录态 / 手机号 / 用户触达，再叠加层级 B（见 [IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE](IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md)）。两层可独立启用、独立停用。

## 2. C 端入口链路

``text
App 头部 A380 板块 ──点击──▶ 服务页（服务板块）
                                │
                                ├─ 服务类型分组（便民服务 / 休闲娱乐 / …）
                                │     └─ 服务项（小程序）：外卖 / 打车 / KTV 预订 / …
                                │            └─ 点击跳转 link（小程序路径 / H5 / 自定义 scheme）
``

- 入口位于 **App 头部 A380 板块**，进入「服务页」即服务板块。
- 服务页数据来自 C 端公开读接口 `GET /api/v1/miniapp/services`（见 §5），**只返回已发布（`status = true`）的服务项**，按「服务类型」分组展示，置顶项优先。
- 服务板块只负责**发现与跳转**；跳转后的下单 / 支付 / 履约由外部服务方在其小程序或 H5 内闭环完成。

## 3. 数据模型（与实现一致）

### 3.1 服务类型 `MiniappServiceType`（表 `adm_miniapp_service_type`）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | int | 主键，自增 |
| `name` | varchar(128) | 服务类型名称，如「便民服务」「休闲娱乐」 |
| `sortOrder` | int | 排序值，升序展示（同值按 `id` 升序） |
| `createdAt` / `updatedAt` | datetime(3) | 创建 / 更新时间 |

> 说明：当前实现中服务类型**没有** `icon` 与 `enabled` 字段；图标与启用开关落在「服务项」上。新增类型时不要按已废弃的后台契约写入 `iconObjectId` / `enabled`。

### 3.2 服务项 `MiniappServiceItem`（表 `adm_miniapp_service_item`）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | int | 主键，自增 |
| `typeId` | int | 所属服务类型 `id`（外键指向 `adm_miniapp_service_type.id`） |
| `name` | varchar(128) | 服务项名称，如「打车」 |
| `link` | varchar(2048) | 跳转地址：小程序路径 / H5 URL / 自定义 scheme（如 `gvchat://`） |
| `introduction` | text | 服务介绍（选填，可为空字符串） |
| `icon` | varchar(2048) | 服务图标（媒体 `objectId`，选填）；后台写入字段名为 `icon` |
| `status` | tinyint(1) | 发布状态：`1/true`=已发布（C 端可见），`0/false`=停用/下架（C 端不可见） |
| `isTop` | tinyint(1) | 是否置顶：`1/true`=置顶（同组内优先展示） |
| `audience` | varchar(32) | 面向对象：`consumer`=消费者服务（固定展示 + 可搜索），`operator`=运营后台（仅搜索展示，不进固定展示）；默认 `consumer` |
| `sortOrder` | int | 排序值，同组内升序展示（置顶优先，同值按 `id` 升序） |
| `createdAt` / `updatedAt` | datetime(3) | 创建 / 更新时间 |

**字段命名要点（与旧契约的差异，务必按此为准）：**

| 旧契约用词（已废弃） | 现行实现字段 | 差异 |
| --- | --- | --- |
| `entryUrl` | `link` | 跳转地址统一叫 `link` |
| `iconObjectId`（后台写） | `icon` | 后台读写用 `icon`（C 端读接口才返回 `iconObjectId`+`iconUrl`，见 §5） |
| `sort` | `sortOrder` | 排序值统一叫 `sortOrder` |
| `enabled` | `status` | 启停统一用布尔 `status` |
| （缺失） | `introduction`、`isTop` | 旧契约未列出，现行实现已具备 |

## 4. 启用 / 停用（发布 / 下架）语义

- **启用（发布）**：将服务项 `status` 置为 `true`。该服务项立即出现在 C 端 `GET /api/v1/miniapp/services` 返回中，服务页可见。
- **停用（下架）**：将服务项 `status` 置为 `false`。该服务项从 C 端公开读中剔除（`findPublishedItems` 只查 `status = true`），但**记录保留**，可随时重新启用，无需重建。
- 启用 / 停用通过后台「更新服务项」接口完成（`PUT /admin/miniapp/services/{id}`，只改 `status`），**不删除记录、不改 `link`**。
- 删除服务项（`DELETE /admin/miniapp/services/{id}`）为物理删除并解绑图标媒体；若只是暂时下线，优先停用而非删除。

## 5. C 端公开读接口（服务板块数据源）

`GET /api/v1/miniapp/services`（Gateway 路由 `Path=/api/v1/miniapp/**` → im-admin-service，`StripPrefix=2`；公开接口，无需认证）

- **无 `keyword`（固定展示）**：仅返回**已发布且面向消费者**（`status = true` 且 `audience = consumer`）的服务项，按服务类型分组；没有任何已发布消费者项的类型不返回。运营后台（`audience = operator`）**不进固定展示**。
- **带 `keyword`（搜索）**：按 `name` / `introduction` 模糊匹配（`%keyword%`）搜索已发布（`status = true`）服务项，返回**平铺的**服务项列表（含消费者与运营后台），用于 C 端小程序搜索。
- 分组顺序：类型按 `sortOrder` 升序（同值按 `id` 升序）；组内服务项按 `isTop` 降序、`sortOrder` 升序、`id` 升序。
- 响应 JSON 样例（与 `AdminManagementController#published()` 一致）：

``json
[
  {
    "typeName": "便民服务",
    "items": [
      { "id": 2, "typeId": 1, "name": "打车", "link": "https://www.example.com/taxi",
        "introduction": "示例：打车服务入口", "iconObjectId": "", "iconUrl": "",
        "isTop": false, "audience": "consumer", "sortOrder": 1 }
    ]
  }
]
``

- C 端服务项字段：`id`、`typeId`、`name`、`link`、`introduction`、`iconObjectId`、`iconUrl`、`isTop`、`audience`、`sortOrder`。
- **命名不对称（已知差异，如实标注）**：后台读写字段为 `icon`（媒体 objectId），C 端公开读字段为 `iconObjectId` + `iconUrl`（前者是 objectId，后者是解析后的访问 URL）。两端字段命名暂不一致，属历史实现差异，本文档仅如实记录，未改后端代码。

## 6. 后台管理接口（管理员写）

均在 `ROLE_ADMIN` 下，Gateway 前缀 `/api/v1/admin/**` → im-admin-service（`StripPrefix=2`），即路径等价于控制器的 `/admin/...`。

### 6.1 服务类型

| 方法与路径 | 请求 / 响应要点 |
| --- | --- |
| `GET /admin/miniapp/service-types` | -> `TypeResponse{id,name,sortOrder,createdAt,updatedAt}` |
| `POST /admin/miniapp/service-types` | `{name(必填), sortOrder?}` -> `TypeResponse` |
| `PUT /admin/miniapp/service-types/{id}` | `{name?, sortOrder?}` -> `TypeResponse` |
| `DELETE /admin/miniapp/service-types/{id}` | 仍有服务项时返回 409（`Type still has services`） |
| `POST /admin/miniapp/service-types/sort` | `{items:[{id, sortOrder}]}` -> `{ok:true}` |

### 6.2 服务项

| 方法与路径 | 请求 / 响应要点 |
| --- | --- |
| `GET /admin/miniapp/services?typeId=&page=&pageSize=` | 列表 `PageResult<ItemResponse{id,typeId,name,link,introduction,icon,status,isTop,audience,sortOrder,createdAt,updatedAt}>` |
| `POST /admin/miniapp/services` | `{typeId(必填), name(必填), link(必填), introduction?, icon?, status?, isTop?, audience?, sortOrder?}` -> `ItemResponse` |
| `PUT /admin/miniapp/services/{id}` | 同上（全可选，按需覆盖） -> `ItemResponse` |
| `DELETE /admin/miniapp/services/{id}` | 删除并解绑图标媒体 |

> 注意：服务项单条更新/删除路径统一为 `/admin/miniapp/services/{id}`（旧契约里出现的 `/admin/miniapp/im-services/{id}` 是**不一致写法，已废弃**）。`status` / `isTop` 写入时传整数 `0/1`，读取时返回布尔。

## 7. 外部服务（打车）接入步骤

以「打车」为例，外部服务方 + IM 运营/管理员协同完成：

``text
1 建类型：管理员在后台建/复用「便民服务」等 MiniappServiceType（若无）
2 建服务项：POST /admin/miniapp/services 登记 { typeId, name:"打车", link:"<打车小程序/H5/自定义scheme>",
             introduction:"打车服务入口", icon:"<媒体objectId>", status:1, isTop:0, sortOrder:<排序> }
3 启用：确保 status=1（发布）；如需临时下线，PUT status=0（停用）
4 C 端验证：App 头部 A380 板块 → 服务页，确认「打车」服务项出现、图标/介绍正确
5 跳转验证：点击服务项，正确跳转打车小程序/H5/scheme；业务闭环由打车方自持
6 （可选）叠加 OAuth：若需 IM 账号互通/用户同步，再按 IM_OPEN_PLATFORM_02 注册应用 + 授权
``

## 8. 与开放平台 OAuth 的边界

| 维度 | 服务板块接入（A，本规范） | 开放平台 OAuth（B） |
| --- | --- | --- |
| 目标 | C 端入口 + 跳转 + 展示 | 账号互通 + 授权 + 用户同步 + 触达 |
| 数据 | 服务项元数据（名称/链接/图标/排序/启停） | 应用 + scope + 用户授权 + 事件 |
| 是否必须 | 外部服务在服务板块上线**必须** | 仅需要 IM 身份时**可选叠加** |
| 停用影响 | C 端不再显示入口，外部业务不受影响 | 撤销后 token/userinfo/事件失效，外部业务数据保留 |
| 归属 | im-admin-service（`adm_miniapp_service_item`） | im-user-service 开放平台子域（`open_application` 等） |

## 9. 验收清单

- [ ] 一个外部服务（打车）按 §7 完成服务项注册并 `status=1` 发布，C 端服务页正确展示。
- [ ] 服务项 `status=0` 停用后从 C 端公开读中消失，记录保留，重新启用后恢复。
- [ ] `isTop` / `sortOrder` 排序、服务类型分组与置顶优先在 C 端正确。
- [ ] 后台服务类型删除受「仍含服务项」保护（409）；服务项删除后图标媒体解绑。
- [ ] `link` 支持小程序路径 / H5 / 自定义 scheme，C 端点击正确跳转。
- [ ] 字段契约以 §3 / §5 / §6 为准（`link` / `icon` / `status` / `isTop` / `sortOrder`），不与旧 `entryUrl` / `enabled` / `sort` / `iconObjectId` 混用。

## 10. 交付物与官网引导

- **技术文档**：本规范（仓库内，面向内部 / 评审）+ [IM_OPEN_PLATFORM_01](IM_OPEN_PLATFORM_01_SERVICE.md)/[02](IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md)（OAuth 层）。
- **官网引导页**：`developer.html` 已新增「服务板块接入引导」章节，与本规范字段 / 步骤一致（见 open-website 官网静态页更新）。
