# 第二阶段 B 端 App 实施方案

> **变更记录（v2）**：首发业态收敛为 KTV——B 端 App 聚焦「包厢看板 / 快速开台 / 计时加项 / 结台 / 收银 / 交班」，房务与酒店/足浴页面降为后续。

## 1. 方案定位

| 项目 | 内容 |
| --- | --- |
| 方案集 | `SAAS_PLATFORM` |
| 顺序号 | `03` |
| 范围 | B 端门店运营 App：店长、收银员、预约、服务员、房务和财务协同 |
| 前置方案 | [SAAS_PLATFORM_01_SERVICE](SAAS_PLATFORM_01_SERVICE.md)、[SAAS_PLATFORM_02_SERVICE](SAAS_PLATFORM_02_SERVICE.md)、[客户端联调基线](CLIENT_INTEGRATION_01_APP.md) |
| 契约与技术依据 | [SAAS_PLATFORM_05_API](SAAS_PLATFORM_05_API.md)、[SAAS_PLATFORM_06_TECHNICAL](SAAS_PLATFORM_06_TECHNICAL.md)、[SAAS_PLATFORM_07_EXECUTION](SAAS_PLATFORM_07_EXECUTION.md) |
| 交互依据 | [SAAS_PLATFORM_08_APP](SAAS_PLATFORM_08_APP.md) |
| 目标工程 | `D:\projects\cnb\gv_chat_app`，新增 B 端独立入口与 product flavor |
| 状态 | 可进入 UI/接口详细实现 |

B 端 App 是一阶段 C 端 App 之外的**独立应用产品**，不与消费者 App 共用安装包、入口、导航、权限模型、推送配置或运行时依赖容器。为降低维护成本，首发可以与 C 端位于同一 Flutter 仓库并复用通用基础包；这不代表它们是同一个 App。B 端必须使用独立的入口、路由、菜单、权限、包名、签名、推送配置和构建标识。

接口字段、错误码、状态机、幂等和租户上下文以 `SAAS_PLATFORM_05_API` 与 `SAAS_PLATFORM_06_TECHNICAL` 为实施依据；页面方案不得自行定义另一套订单或资金规则。

## 2. 产品目标与非目标

### 2.1 首发目标

- 店长查看经营摘要、待办、审批、包厢和班次。
- 收银员完成开班、预订到店、快速开台、开台/计时/加项、收款和交班。
- 服务员完成加项、结台协助和异常提交。
- 前台/预约人员查看/创建/修改/取消包厢预订、登记到店。
- 所有写操作由后端权限、数据范围、状态机和幂等接口最终校验。

### 2.2 首发不做

价格体系、支付密钥、角色配置、复杂报表导出、批量资源配置、跨币种储值兑换、营销自动化旅程和消费者 App 功能不放入 B 端 App。

收银涉及真实资金，首发不承诺离线收款。弱网时允许查看最近缓存和填写待提交草稿，但支付、退款、结台结算和交班必须在线确认。

## 3. 产品与工程形态

### 3.1 产品隔离

推荐在现有 Flutter 工程中建立独立 B 端 product flavor/target，共享无业务语义的 `gv_core`、`gv_ui`、网络基础设施和组件库，但使用：

- 独立包名/应用标识和构建渠道；
- 独立初始路由 `/business/login`；
- 独立菜单注册表和权限过滤；
- 独立埋点、崩溃标识和版本升级策略；
- 独立 API contract 标识 `business-v1`。
- **业态模块化**：一个 B 端 App 承载多业态，登录后按 `store.business_type` 动态加载对应业态的看板/页面/操作模块；首发仅 KTV 模块，未来业态新增模块、不改 App 主体（对齐 D-4）。

如果现有仓库无法安全支持多 target，则建立独立 `gv_business_app` 工程；通过版本化 Dart package 复用 `gv_core`/`gv_ui`，不复制领域 API client、认证和基础组件实现。

### 3.2 分层结构

```text
Screen / Widget
    ↓
Feature Controller / State
    ↓
Repository（业务模型与缓存）
    ↓
BusinessApiV1（REST DTO 转换）
    ↓
Gateway /api/v1 + tenant context
```

页面和 Provider 不直接拼接 URL、读取 JSON Map、解析权限或发送 WebSocket 帧。所有 API DTO、枚举、错误、分页、金额和时间转换集中在 data 层。

继续使用当前工程已有的状态管理、路由和网络库，不在本方案中强行引入第二套框架；新增 feature 代码必须遵守现有 lint、目录、测试和依赖规则。

## 4. 登录、租户与门店上下文

### 4.1 登录流程

```text
B 端登录
  → SaaS 账号认证
  → GET /api/v1/auth/contexts
  → 选择租户/组织/门店
  → POST /api/v1/auth/context/select
  → 获取 tenantContextToken 与权限摘要
  → 加载菜单、待办、资源和班次
```

账号 Token 不直接决定当前门店。用户可同时拥有总部运营和某门店店长角色，切换上下文后重新加载权限和数据范围。门店停用、角色变化或授权版本变化时，App 清除上下文并要求重新选择。

### 4.2 本地数据安全

- Access token、Refresh token、租户上下文 Token 仅存系统安全存储，不写普通 SharedPreferences/日志。
- 本地缓存只保存当前租户/门店范围内的非敏感列表和脱敏摘要；切换上下文立即清理旧租户缓存。
- 订单、支付、押金和客户完整证件信息不落本地离线库。
- App 进入后台、切换账号或远程注销时，清理上下文、班次和待提交草稿。

## 5. 首发信息架构与页面

### 5.1 公共页面

| 页面 | 核心内容 | 权限 |
| --- | --- | --- |
| 登录 | 账号、密码/验证码、环境错误提示 | 认证 |
| 上下文选择 | 租户、组织、门店、最近使用门店 | 有效授权 |
| 首页工作台 | 今日营业、待入住/开台/服务/清洁/审批、班次 | 按角色汇总 |
| 消息/待办 | 待处理任务、审批结果、系统通知 | `notification.view` |
| 我的班次 | 开班、当前班次、交班、异常记录 | `shift.*` |
| 我的 | 当前账号、门店、权限摘要、退出 | 账号 |

### 5.2 业务页面

| 模块 | 页面 | 关键操作 |
| --- | --- | --- |
| 资源 | 包厢看板 | 筛选、查看占用、刷新、进入订单 |
| 预订 | 包厢预订列表、详情、到店登记 | 创建/修改/取消/到店；前台和收银使用 |
| 快速开台 | 业态选择、客户、包厢、时段 | KTV 开台（酒店/足浴后续） |
| 订单 | 订单详情、加项、挂单、转单 | 只显示当前范围订单；金额后端返回 |
| KTV | 开台、计时、暂停/恢复、加项、结台 | 包厢时段和计时状态 |
| 收银 | 可用渠道、组合支付、退款申请 | 线上确认，不支持离线收款 |
| 店长 | 退款/作废/反结审批、经营摘要 | 高风险动作二次确认和原因 |

## 6. 角色到页面与动作

| 角色 | 默认页面 | 允许动作 |
| --- | --- | --- |
| 租户老板/总部运营 | 工作台、跨店摘要、会员/营销摘要 | 只读经营与审批；复杂配置回 PC |
| 门店店长 | 全部门店执行页、审批、日结 | 本店资源、订单、退款审批、交班复核 |
| 收银员 | 快速开台、包厢、订单、收银、班次 | 开台、收款、退款申请、交班 |
| 前台/预约 | 包厢预订、客户、到店 | 预订创建/修改/取消、到店登记 |
| 服务员 | 我的待办、订单 | 自己关联订单加项、结台协助 |
| 财务 | 审批、资金、日结摘要 | 退款/反结/线下退款复核、报表摘要 |

菜单权限只决定页面展示；按钮动作还必须检查服务端返回的 `allowedActions` 和 API 结果。未知权限、未知状态和后端拒绝必须显示安全的通用提示并刷新订单。

## 7. 统一 UI 状态与交互规则

所有列表必须有加载、刷新、空态、错误、重试和分页/加载更多状态。写操作必须有提交中状态，按钮在请求完成前不可重复点击；页面恢复时重新查询权威状态。

金额显示：服务端返回字符串或最小单位与币种；App 使用货币格式化组件，不自行计算税费、折扣、积分抵扣或退款金额。跨币种只展示服务端结果和汇率快照，不在客户端兑换。

资源状态至少显示：可用、已预留、使用中、待清洁/待确认、冲突/不可用。订单状态显示公共商业状态和业态专属状态两个字段，禁止用一个状态文案表达两套含义。

高风险操作弹窗必须显示：订单号、门店、金额、影响范围、原因输入、当前操作者和需要的审批角色；提交后显示“申请已提交”而不是伪装为已完成。

## 8. B 端 REST v1 客户端契约

所有请求到 Gateway，统一 `Authorization`、`X-Client-Contract: business-v1`、`X-Tenant-Context`、客户端版本和 `requestId`。写请求携带 `Idempotency-Key`。错误统一解析为 `{code,message,requestId,retryable,fieldErrors}`。

| 编号 | 方法与路径 | 页面/用途 |
| --- | --- | --- |
| APP-01 | `GET /api/v1/auth/contexts` | 获取可用租户/组织/门店 |
| APP-02 | `POST /api/v1/auth/context/select` | 选择当前经营上下文 |
| APP-03 | `GET /api/v1/business/menu` | 获取权限过滤后的菜单和动作 |
| APP-04 | `GET /api/v1/business/workbench` | 首页摘要与待办数量 |
| APP-05 | `GET /api/v1/business/resources` | 房态/包厢/足浴房/技师状态 |
| APP-06 | `GET/POST/PUT /api/v1/business/reservations` | 预约列表、创建、修改、到店 |
| APP-07 | `POST /api/v1/business/orders` | 快速开单 |
| APP-08 | `GET /api/v1/business/orders/{id}` | 订单详情与允许动作 |
| APP-09 | `POST /api/v1/business/orders/{id}/items` | 加项/加钟 |
| APP-10 | `POST /api/v1/business/hotel/stays/{id}/check-in` | 入住（后续） |
| APP-11 | `POST /api/v1/business/hotel/stays/{id}/change-room` | 换房（后续） |
| APP-12 | `POST /api/v1/business/hotel/stays/{id}/check-out` | 退房结算（后续） |
| APP-13 | `POST /api/v1/business/ktv/sessions/{id}/open`、`pause`、`resume`、`close` | 开台/计时/结台（首发） |
| APP-14 | `POST /api/v1/business/spa/sessions/{id}/assign`、`start`、`complete` | 派技师/开始/完成（后续） |
| APP-15 | `GET /api/v1/business/payments/available-methods` | 获取后端计算的支付方式 |
| APP-16 | `POST /api/v1/business/payments` | 发起支付/拆分支付 |
| APP-17 | `POST /api/v1/business/deposits`、`/refund-requests` | 收押金、申请退押金 |
| APP-18 | `POST /api/v1/business/refund-requests` | 退款/作废申请 |
| APP-19 | `POST /api/v1/business/shifts/open`、`close` | 开班/交班 |
| APP-20 | `GET /api/v1/business/approvals`、`POST /{id}/approve` | 店长/财务审批 |
| APP-21 | `GET /api/v1/business/housekeeping/tasks`、`POST /{id}/complete` | 房务清洁 |
| APP-22 | `GET /api/v1/business/reports/summary` | 店长摘要，不提供复杂导出 |

以上接口只定义 App 调用边界；服务端详细请求/响应字段、错误码和状态机以 [SAAS_PLATFORM_02_SERVICE](SAAS_PLATFORM_02_SERVICE.md) 及其 OpenAPI 为准。App 不调用 `/internal/**`、服务端口或数据库，不依赖 Admin API。

## 9. 弱网、刷新与幂等

- GET 列表可展示当前上下文的短期缓存，并标注刷新时间；超过缓存有效期或切换门店必须重新请求。
- 开单、入住、开台、派技师、支付、退款、押金和交班不允许离线提交。
- 网络超时后不得自动重放支付、退款和押金请求；使用同一幂等键查询原请求结果。
- 资源冲突、订单版本冲突和支付处理中状态由后端返回；App 刷新详情后展示最终状态。
- 页面退出不取消正在进行的资金请求；应用重启后根据幂等键或订单查询恢复结果。
- 请求重试仅限 GET 和明确 `retryable=true` 的非资金写操作。

## 10. 实时与通知

首发不把经营资源事件混入现有 IM WebSocket。资源和待办使用页面进入时拉取、下拉刷新、前台定时刷新（默认 15-30 秒）和系统推送提醒；资金状态以订单查询为准。

未来需要实时经营事件时，新增版本化业务事件通道，事件必须带租户、门店、资源/订单 ID 和版本号；不能复用 `chat:receive` 或客户端自行推断房态。

## 11. 实现任务批次

### APP-WP-01 工程与认证

- [ ] 盘点现有 Flutter 工程，建立 B 端 product flavor/target、包名、图标、环境参数和 `business-v1` contract。
- [ ] 实现安全 Token 存储、上下文选择、权限菜单和登出清理。
- [ ] 建立 BusinessApiV1、错误转换、日志脱敏和基础 Widget 状态模板。

### APP-WP-02 工作台与资源

- [ ] 完成首页、待办、资源看板、预约列表和门店切换。
- [ ] 完成刷新、缓存、空态、权限过滤和资源状态文案。

### APP-WP-03 订单与 KTV 执行

- [ ] 完成快速开单、订单详情、加项/加钟和订单版本刷新。
- [ ] 完成酒店入住/换房/退房、KTV 开台/计时/结台、足浴派技师/服务/结单。
- [ ] 完成房务清洁和房态确认。

### APP-WP-04 收银与审批

- [ ] 完成开班、可用支付方式、组合支付、押金、退款申请和交班。
- [ ] 完成店长/财务审批、原因确认和操作结果页面。
- [ ] 禁止离线资金操作；完成重复点击、超时恢复和支付处理中测试。

### APP-WP-05 验收与发布

- [ ] 每个 APP 编号具备成功、401、403、空态、网络错误、重复提交和状态冲突测试。
- [ ] 真机验证扫码/相机（如启用）、打印（如启用）、推送、后台恢复和弱网。
- [ ] 通过 KTV 端到端闭环和旧 C 端回归，不改变 C 端构建目标。

## 12. 端到端验收用例

| 编号 | 场景 | 验收结果 |
| --- | --- | --- |
| BAPP-E2E-01 | 登录并选择租户/门店 | 只能看到授权上下文，切换后缓存清理 |
| BAPP-E2E-02 | 收银员开班、KTV 开台、现金收款 | 订单/履约/收款状态一致，重复点击只产生一笔 |
| BAPP-E2E-03 | KTV 计时、暂停/恢复、加项、结台 | 服务端计算金额，包厢释放，退款走审批 |
| BAPP-E2E-04 | 酒店续住、换房、退房、押金抵扣（后续） | 房态进入清洁，退款走审批/原渠道 |
| BAPP-E2E-05 | 足浴排班、派技师、服务、加钟、结单（后续） | 技师和房间状态正确，无提成字段或计算 |
| BAPP-E2E-06 | 服务员仅操作自己关联订单 | 越权 API 返回 403，页面不泄露其他订单 |
| BAPP-E2E-07 | 店长审批退款/作废，财务复核反结 | 审批链、原因和审计完整，申请人不能自批 |
| BAPP-E2E-08 | 弱网与应用重启恢复支付处理中 | 不重复扣款，查询得到最终支付状态 |
| BAPP-E2E-09 | 租户切换后查询订单和会员 | 不显示上一租户数据，所有请求带新上下文 |
| BAPP-E2E-10 | 旧 C 端登录、预约和消息回归 | 现有 C 端行为不受 B 端发布影响 |

## 13. 发布门槛

B 端 App 首发必须同时满足：后端 OpenAPI 与错误码已冻结；权限/租户上下文通过服务端测试；KTV 一条 E2E 通过（酒店、足浴后续）；支付/退款无重复资金结果；弱网不伪造成功；切换租户清理缓存；生产构建无调试地址、密钥或服务内网地址；崩溃和关键业务埋点包含版本、租户匿名标识、门店匿名标识和请求关联 ID，但不包含手机号、证件、Token、完整支付信息或订单敏感正文。

## 14. 当前 Flutter 工程的落地映射

### 14.1 已核实的当前基线

当前 `gv_chat_app` 使用 Flutter、`Provider`、`GetIt`/`injectable`、`GoRouter`、Dio、Drift 和 `gv_core`/`gv_ui` 本地包。C 端入口为 `lib/main.dart`，依赖装配为 `lib/app/app_dependencies.dart`，路由为 `lib/app_router.dart`，HTTP 客户端为 `lib/im-services/api_client.dart`，登录态为 `lib/providers/auth_provider.dart`。

`AppDependencies` 会初始化 IM Socket、聊天、通话、好友、群组和 C 端预约 Provider，因此 B 端不得复用它，也不得在 B 端入口初始化 `SocketService`、`ChatProvider`、`CallProvider` 或 C 端聊天路由。

### 14.2 必建文件与目录

```text
lib/
  main.dart                         C 端入口：不修改行为
  main_business.dart                B 端独立入口
  business/
    business_app.dart               B 端 MaterialApp、主题、全局错误/锁屏
    business_dependencies.dart      B 端 GetIt 装配，不初始化 IM/RTC
    business_router.dart            B 端 GoRouter 和登录/上下文守卫
    core/
      business_config.dart          B 端环境与 API 配置
      business_session_storage.dart 安全 Token、上下文与缓存索引
      business_failure.dart         API 错误转换
    data/
      business_api_client.dart      Dio：business-v1、上下文、稳定幂等键
      dto/                          REST DTO，仅传输用途
      business_cache_database.dart  Drift 数据库，仅短期只读缓存
    repositories/                   Context、Workbench、Resource、Order、Payment 等
    providers/                      BusinessAuth、Context、Workbench、Resource、Order、Shift、Approval
    screens/                        login、context、workbench、resource（包厢看板）、order、ktv、cashier、approvals
    widgets/                        B 端共享表单、金额、状态、权限和错误组件
```

`packages/gv_core` 只复用通用 ID、错误、JSON、媒体和安全存储抽象；`packages/gv_ui` 只复用主题、导航和交互组件。业务 DTO、订单状态、支付、会员和门店逻辑全部留在 `lib/business`，避免反向污染 C 端聊天模型。

### 14.3 入口、构建与应用标识

新增 Android flavor：`consumer` 与 `business`。`consumer` 保持现有 `com.gv.chat.gv_chat_app`；`business` 使用独立 `applicationIdSuffix ".business"`、独立图标、极光 AppKey/渠道配置和版本策略。B 端启动命令：

```text
flutter run --flavor business -t lib/main_business.dart --dart-define=APP_ENV=dev
flutter build appbundle --flavor business -t lib/main_business.dart --dart-define=APP_ENV=prod
```

iOS 建立 `Business` scheme、独立 Bundle ID、AppIcon、推送配置和签名；Windows、macOS、Web 首发不提供 B 端构建物，除非后续专项明确收银外设、浏览器权限和桌面窗口验收。该限制不影响 C 端现有多端构建。

### 14.4 会话与本地存储改造

现有 C 端 `LocalStorage` 使用 `SharedPreferences` 保存 C 端会话。B 端新增 `BusinessSessionStorage`：Token、刷新令牌和租户上下文 Token 使用 `flutter_secure_storage`；Drift/SharedPreferences 只保存非敏感的缓存索引、界面偏好和最后门店标识。

缓存主键固定为 `accountId:tenantId:organizationId:storeId:resource`。上下文切换、登出、授权版本变化或 401 时删除旧上下文的 Drift 行和内存 Provider；不删除 C 端聊天缓存，也不读取 C 端 Token key。

### 14.5 网络层与幂等

不得修改 C 端 `ApiClient` 的 `im-v1` Header、Socket 行为或错误处理。新增 `BusinessApiClient`，复用其 Dio 脱敏日志与 `ApiFailure` 解析规则，但独立配置：

- 固定发送 `X-Client-Contract: business-v1`；
- 在选择上下文后发送 `X-Tenant-Context`；
- 每个业务命令在 Repository 创建一次 UUID 并在超时查询期间复用，禁止 Dio 拦截器每次重试生成新键；
- 401 清理 B 端会话，`AUTH_CONTEXT_EXPIRED` 仅回到上下文选择页，403 不清全局登录；
- 禁止自动重试支付、退款、押金、入住、开台、派技师和交班请求；
- API base 继续使用 `AppConfig`/`EnvConfig` 的受管 `--dart-define`，不写死任何 IP 或服务端口。

### 14.6 B 端依赖与路由守卫

`BusinessDependencies` 的初始化顺序固定为：安全存储 → BusinessApiClient → BusinessAuthProvider → ContextProvider → BusinessRouter → Workbench/Resource Provider。认证成功后先加载上下文；未选择上下文时只允许 `/business/context-select`；已选择上下文但菜单无对应权限时返回工作台并显示无权限。

路由命名固定：

```text
/business/login
/business/context-select
/business/workbench
/business/resources
/business/reservations
/business/orders/new
/business/orders/:id
/business/hotel/stays/:id
/business/ktv/sessions/:id
/business/spa/sessions/:id
/business/cashier
/business/shifts
/business/approvals
/business/housekeeping
/business/profile
```

所有带 ID 的路由进入时必须重新请求或校验当前上下文，不能依赖上个门店页面传来的内存对象。

### 14.7 具体 Provider 与 Repository 职责

| 类 | 职责 | 禁止事项 |
| --- | --- | --- |
| `BusinessAuthProvider` | B 端登录、登出、会话失效 | 不连接 IM Socket |
| `BusinessContextProvider` | 上下文列表、选择、缓存清理、授权版本 | 不从请求体伪造范围 |
| `WorkbenchProvider` | 待办、经营摘要、定时刷新 | 不直接聚合多个领域写操作 |
| `ResourceProvider` | 资源看板、刷新、状态版本 | 不在本地改变资源最终状态 |
| `OrderProvider` | 开单、详情、加项、版本冲突刷新 | 不计算最终金额 |
| `PaymentProvider` | 可用渠道、支付意图查询、处理中恢复 | 不自动重放资金请求 |
| `ShiftProvider` | 开班、交班、现金差异 | 不支持离线交班 |
| `ApprovalProvider` | 退款/作废/反结审批 | 不允许申请人自批 |

### 14.8 App 级测试与发布检查

新增单元测试：BusinessApiClient Header/幂等键、ContextProvider 缓存清理、路由守卫、金额格式化、未知状态、支付超时恢复。新增 Widget 测试：角色菜单、资源看板、订单详情、审批二次确认、离线资金按钮禁用。新增真机集成测试：`BAPP-E2E-01` 至 `10`。

CI 必须分别运行 C 端 `flutter test`、B 端 `flutter test`、`flutter analyze`、Android consumer/business Debug 构建；B 端失败不得修改或跳过 C 端现有测试。发布前验证 business 包不能覆盖 consumer 安装、两个推送 AppKey/Bundle ID 不混用、日志中无租户 Token/手机号/支付信息。
