# 跨服务只读审计报告：异常与错误处理规范性 + 国际化/文案一致性

- **审计对象**：`gv_im_server`（分支 `develop/2.0.0-saas-20260826`，HEAD `9cbec066`）、`gv_saas_admin`、`gv_saas_mobile`
- **性质**：只读。未修改/创建/删除任何业务代码，未提交，未改版本号，未触碰 `k8s/`，未跑发版脚本。
- **实测环境**：本地 kind 集群 `kind-gv-im-local`（网关 `192.168.31.91:30002`，后台 `:30081`，B 端 `:30082`；`kubectl` 直连 18 个服务 Pod + `saas-admin` Pod 内 `curl`）
- **审计时间**：2026-09-17
- **说明**：本机为 PowerShell 5.1（pwsh），无 heredoc；curl 探针走 `$env:TEMP\cbn_audit\` 临时目录。

---

## ⚠️ 0. 并发变更与勘误（务必先读）

审计期间 `gv_saas_admin` 与 `gv_im_server/platform-services/order` **正被其它 agent 实时改写**，部分行号与结论在审计过程中已经发生变化。以下为**核验后的当前状态**，本报告正文中受影响处已就地标注。

### 0.1 已被并发修复（不要按旧结论行动）

| 曾被观察到的问题 | 位置 | 当前状态（已核验） |
|---|---|---|
| `orders.vue` 重复声明 `formatTime` → ESM `SyntaxError`，`npm run build` 失败 | `gv_saas_admin/src/views/tenant/orders.vue` | ✅ **已修复**。当前无任何重复顶层函数声明（全 views 扫描 0 命中）；`formatTime` 现从 `@/utils/format` 导入（`:452`）并被包装函数调用（`:720`） |
| `members.vue` 金额用 `' 元'` 后缀 | `views/tenant/members.vue:150` | ✅ **已修复**，全仓已无 `+ ' 元'` |
| `order` 服务缺少必填请求头处理（本报告 H-6 涉及 order 的部分） | `platform-services/order/.../handler/GlobalExceptionHandler.java` | ✅ **已新增** `MissingRequestHeaderException`（`:70-77` → 400 `REQUEST_HEADER_MISSING`）与 `ServletRequestBindingException`（`:80-87` → 400 `REQUEST_BINDING_INVALID`） |

### 0.2 仍然存在（已按当前文件状态重新核验）

| 问题 | 位置（当前行号） | 证据 |
|---|---|---|
| **双货币符号 `¥ ¥ 123.45`** | `gv_saas_admin/src/views/tenant/orders.vue:410` | `<span class="collect-amount">¥ {{ fmtCents(payableMinor) }}</span>`，而 `:818` `const fmtCents = formatYuan`，`utils/format.js:21` 返回 `'¥ ' + ...`。**全仓唯一一处**（`:380` 与 `products.vue:38` 是内联 `/100`，不带前缀，正常）。快照 md5 `9B2614AAA014EBDB5F14AE8BD9AFC373` |
| order 兜底 500 仍无 `code` 且英文 | `.../order/.../GlobalExceptionHandler.java:44` | `Map.of("message", "Internal error")`，**未修** |
| order 的 `Exception.class` 仍吞掉 `NoResourceFoundException` / `HttpRequestMethodNotSupportedException` | `.../order/.../GlobalExceptionHandler.java:41` | 当前文件仍只有 `HttpMessageNotReadableException` / `MissingServletRequestParameterException` / `MissingRequestHeaderException` / `ServletRequestBindingException` 四个显式分支，**未覆盖 404/405**；实测 500 依然存在 |

### 0.3 行号可信度

| 范围 | 可信度 |
|---|---|
| `gv_im_server`（除 `platform-services/order`） | 高。`git status` 仅 `k8s/*` 与 `platform-services/order/**` 有改动 |
| `gv_im_server/platform-services/order` | **中**。15+ 个文件未提交改动中，含 `GlobalExceptionHandler.java`；引用前请重新 `grep` |
| `gv_saas_admin` | **低**。16 个文件未提交改动中（含 4 个新增工具文件），行号在审计期间持续漂移（同一文件 10 分钟内从 1661 行 → 1701 → 1728 → 1725 行）；**引用前必须重新 `grep`** |
| `gv_saas_mobile` | **高**。`git status` 为空，HEAD `737928e`，审计期间无变化 |

```powershell
# 引用任何前端行号前先跑这个
git -C D:\projects\cnb\gv_saas_admin status --porcelain
git -C D:\projects\cnb\gv_im_server status --porcelain
```

> **审计自身的只读保证**：本审计未写入上述任何仓库。上表所列改动全部由并发 agent 产生（`git status` 的 ` M` / `??` 标记），本报告对它们的描述仅用于区分「已修 / 未修」。

---

## 1. 结论摘要

### 后端（5 条最重要）

1. **「统一异常出口」并未全量落地，漏网服务比预期多。** 20 个可部署服务中，只有 15 个有 `@RestControllerAdvice`；`common-audit` / `common-mail` / `common-sms` **完全没有任何 advice**（各自都有 REST controller），`group-idaas` 有 advice 却**连 404 都返回 Tomcat 原生 HTML 错误页**（`Content-Type: text/html;charset=utf-8`）。另有 6 个服务（`platform-admin/customer/identity/marketing/payment/payment-channel`）只映射 `ApiException` 而**没有 `Exception` 兜底**，任何非业务异常直接落到 Spring 默认错误体 `{"timestamp","status","error","path"}`。**没有任何 CI 门禁覆盖这一点**（`scripts/validate/` 16 个脚本均不检查）。

2. **最严重的退化不是「403→500」，而是「客户端错误→500」+「内部细节外泄」。** 8 个服务在 advice 里写了 `@ExceptionHandler(Exception.class)`，它把 Spring MVC 自己用于表达 4xx 的异常（`NoResourceFoundException`、`HttpRequestMethodNotSupportedException`、`HttpMediaTypeNotSupportedException`、`MissingServletRequestHeaderException`）**全部吞成 500**。实测：`PATCH /`、`GET /zzz-nonexistent`、错误 Content-Type 在 `platform-order` / `platform-resource` / `platform-tenant` **一律返回 500**；`platform-identity` 的**登录凭据错误返回 500**（`IllegalStateException` 未映射）。
   其中 4 个 IM 服务（`im-admin` / `im-conversation` / `im-message` / `im-user`）的兜底分支把 `ex.getMessage()` **原样写进 500 响应体**，实测泄露 Jackson 解析器内部信息、Java 方法签名与内部资源路径：
   `{"message":"Required request parameter 'platform' for method parameter type String is not present"}`。

3. **静默 500：用户拿到 500，运维拿不到任何日志。** `common-media` 与 4 个 IM 服务的兜底分支**没有任何日志语句**。实测人为触发的 5 个 500，`kubectl logs --since=6m` **一条记录都没有**（而同一 Pod 的其它 INFO/WARN 正常输出，证明日志管道健康）。`platform-admin/customer/identity/marketing/payment/payment-channel` 连兜底分支都没有，同样无业务上下文日志。

4. **必填请求头缺失绕过了已经写好的统一错误码（可精确证明）。** `customer` / `marketing` / `payment` 的应用层**已经**有 `IDEMPOTENCY_KEY_REQUIRED`（400 + 中文），但 Controller 上写的是 `@RequestHeader("Idempotency-Key")`（`required` 默认 true），**头缺失时根本走不到应用层**：
   - 头**缺失** → `400 {"timestamp":...,"status":400,"error":"Bad Request","path":...}`（**非统一体**）
   - 头**存在但为空** → `400 {"code":"IDEMPOTENCY_KEY_REQUIRED","message":"缺少 Idempotency-Key"}`（**统一体**）

5. **5xx 兜底消息本身也不统一。** `platform-order` 与 `common-media` 的兜底返回英文 `{"message":"Internal error"}` 且**没有 `code` 字段**；只有 `platform-resource` 与 `platform-tenant` 是正确形态（`{"code":"INTERNAL_ERROR","message":"服务内部错误，请稍后重试"}` + 记堆栈）。`group-idaas` / `common-audit` / `common-mail` / `common-sms` 的错误体是 Spring 默认体，`gateway` 是 WebFlux 默认体，`im-access-ws` 无 REST 面。**7 种不同的错误体形态并存。**

### 前端（4 条最重要）

1. **两个前端都没有 i18n 基础设施**。`gv_saas_admin` 的 `package.json` 依赖只有 `element-plus / axios / pinia / vue / vue-router`，无 `vue-i18n`；两个仓库均无 `locales/`、`zh-CN.json`、`en.json` 之类文件。**任务要求的「i18n key 覆盖核对」在当前代码下不适用（N/A）**：没有 key，也就没有缺 key/多余 key，只有全量硬编码中文。

2. **后台前端的错误提示工具已经「知道」后端在返回非统一体，并因此主动丢弃后端的英文消息** —— 这既是证据也是影响。`gv_saas_admin/src/utils/adminErrorMessage.js:7-11` 的注释直接写明：*「线上存在两类不带 `code/message` 的响应 … 如缺少 Idempotency-Key / 请求头缺失时 `{timestamp,status,error,path}`」*；第 64-65 行还有 `CJK_PATTERN` 白名单，**只展示含中日韩字符的服务端 message**。
   后果实测可得：`ADMIN_LOGIN_INVALID` 的消息是英文 `invalid username or password`（`AdminLoginApplicationService.java:37`），且未列入 `ERROR_CODE_MESSAGES` → 后台**把「用户名或密码错误」显示成「登录状态已失效，请重新登录。」**（状态码 401 兜底）。全量比对：后端 141 个业务码中 **120 个** 无前端映射，其中 **18 个的消息是英文**，会全部退化成状态码泛化文案。

3. **储值品牌名有 4 种写法，且违反了代码里自己写下的「不得硬编码」规则。** `gv_saas_admin/src/constants/payment-methods.js:14-15` 明确注释 *「储值是品牌展示名，取租户配置 `tnt_tenant_config.wallet_brand_name`（默认 A380币），**不得硬编码**」*，但 `views/tenant/wallet.vue:114,123,140` 三处硬编码 `'A380币'`；`payments.vue:108` 与 `orders.vue:535` 又把默认值写成 `'储值'`；`payment-methods.js:4,11,22` 是 `'储值币(A380币)'` / `'储值币'` / `'储值'`。同一概念 **储值 / 储值币 / 储值币(A380币) / A380币** 四名并存。

4. **术语与金额/时间格式都在「各写各的」。** 菜单标题直接写成 `'包厢/资源'`（`gv_saas_admin/src/router/index.js:71`）—— 术语尚未定稿；「应收」25 次 vs「待收」1 次（`gv_saas_mobile/src/b-end/views/Orders.vue:58`）指同一金额；`¥` 前缀与 `元` 后缀并存（`members.vue:150` 是 `... + ' 元'`），同一仓库至少 **7 处各自实现**了元/分转换（`views/tenant/orders.vue:818,824`、`payments.vue:138`、`shift.vue:174`、`reservations.vue:75`、`members.vue:150`、`ktv-config.vue:270`），而规范实现 `src/utils/format.js:16-21` 反而没人用；「分」同时表示**货币最小单位**和**分钟**（`ktv-config.vue:65,223` 的 `递增粒度(分)` 是分钟，同文件 `:270 fenToYuan` 是分币）。日期则绝大多数**直接渲染原始值**（`platform/tenants.vue:19`、`shift.vue:64`、`ktv-config.vue:164`）。

5. **除风格问题外，另有 6 个确定性缺陷（可直接修，非风格偏好）。** ① `gv_saas_admin` 收银台渲染 **"¥ ¥ 123.45"**（`views/tenant/orders.vue:410` 字面 `¥` + `fmtCents` 已含 `'¥ '` 前缀）；② C 端同一 DTO 同一字段用两种金额尺度，`c-end/app.js:853` 用不除 100 的 `money()`，**显示为实际值的 100 倍**；③ C 端**钱包余额未除 100**，显示的是「分」的数值（`c-end/app.js:200,204,222,227,248,756,760`，而 `c-end/saas.js:107` 注释与 `fenToYuan` 明确契约是「分→元」；mock 数据 `data.js:16 balance: 8880` 恰好是元形状，**掩盖了这个 bug**）；④ C 端 `dateOffset` 用 `toISOString().slice(0,10)`（**UTC**），UTC+8 下本地 00:00–07:59 的「今天+N」**早一天**（`c-end/app.js:33,655`）；⑤ `c-end/app.js:314-316` 本地解析、UTC 序列化，提交的预约时间**早 8 小时**（需后端契约确认）；⑥ `formatTime` 在 admin 有 9 处本地重复实现，而 `utils/format.js` 的 `formatYuanValue` / `resourceStateText` **0 调用点**。
   > 注：审计过程中曾观察到 `orders.vue` **重复声明 `formatTime` 导致构建失败**、以及 `members.vue` 的 `' 元'` 后缀 —— 这两项已被并发改动的另一个 agent 修复，见 §0.1。

---

## 2. 异常处理问题清单（服务 → 文件:行 → 现象 → 影响 → 建议 → 严重度）

### 2.1 高（High）

#### H-1 路由 / 方法 / 媒体类型不匹配一律退化成 500 —— 8 个服务

| 服务 | 统一出口位置 |
|---|---|
| platform-order | `platform-services/order/platform-order-service/src/main/java/com/gvchat/platform/order/handler/GlobalExceptionHandler.java:41-45` |
| platform-resource | `platform-services/resource/platform-resource-service/src/main/java/com/gvchat/platform/resource/handler/GlobalExceptionHandler.java:84-91` |
| platform-tenant | `platform-services/tenant/platform-tenant-service/src/main/java/com/gvchat/platform/tenant/handler/GlobalExceptionHandler.java:85-92` |
| common-media | `common-services/media/common-media-service/src/main/java/com/gvchat/common/media/handler/GlobalExceptionHandler.java:38-43` |
| im-admin | `im-services/admin/im-admin-service/src/main/java/com/gvchat/im/admin/handler/GlobalExceptionHandler.java:62-67` |
| im-conversation | `im-services/conversation/im-conversation-service/src/main/java/com/gvchat/im/conversation/handler/GlobalExceptionHandler.java:53-58` |
| im-message | `im-services/message/im-message-service/src/main/java/com/gvchat/im/message/handler/GlobalExceptionHandler.java:52-57` |
| im-user | `im-services/user/im-user-service/src/main/java/com/gvchat/im/user/handler/GlobalExceptionHandler.java:69-74` |

- **现象**：`@ExceptionHandler(Exception.class)` 的匹配优先级高于 `DefaultHandlerExceptionResolver`，把 Spring 用于表达 4xx 的框架异常全部截获。Pod 日志原文（`platform-resource-service`）：
  `org.springframework.web.HttpRequestMethodNotSupportedException: Request method 'GET' is not supported`
  `org.springframework.web.servlet.resource.NoResourceFoundException: No static resource admin/zzz-nonexistent for request '/admin/zzz-nonexistent'.`
- **影响**：404/405/415 全部变成 500。前端把它归成「后端服务异常」并可能触发重试；监控上 4xx 与 5xx 完全混淆，500 告警失去意义（本次审计中 5 次「500」全是客户端错误）。
- **建议**：兜底改为 `@ExceptionHandler(Throwable.class)` 之前先显式声明框架异常，或最简做法 —— 让 advice 继承/委托 `ResponseEntityExceptionHandler`，仅覆盖 `Exception.class` 之外的未知异常；对已声明的框架异常统一翻译成 `{code,message}` 并保留正确状态码（404 `ROUTE_NOT_FOUND`、405 `METHOD_NOT_ALLOWED`、415 `MEDIA_TYPE_UNSUPPORTED`）。
- **严重度**：**高**

**实测响应原文**

```
### platform-order-service (经网关 :30002，已登录+已选上下文)
GET  /api/v1/business/orders/999999999   -> HTTP 500  {"message":"Internal error"}
GET  /api/v1/business/orders/abc         -> HTTP 500  {"message":"Internal error"}
PATCH http://platform-order-service:4130/ -> HTTP 500 {"message":"Internal error"}
GET  http://platform-order-service:4130/zzz-nonexistent -> HTTP 500 {"message":"Internal error"}

### platform-resource-service（直连 :4120）
PATCH http://platform-resource-service:4120/ -> HTTP 500 {"code":"INTERNAL_ERROR","message":"服务内部错误，请稍后重试"}
GET   http://platform-resource-service:4120/admin/resources/999999999 -> HTTP 500 {"code":"INTERNAL_ERROR","message":"服务内部错误，请稍后重试"}

### platform-tenant-service（直连 :4110）
GET  http://platform-tenant-service:4110/admin/tenant/contexts -> HTTP 500 {"code":"INTERNAL_ERROR","message":"服务内部错误，请稍后重试"}
POST http://platform-tenant-service:4110/admin/tenant/stores (Content-Type: application/xml) -> HTTP 500 {"code":"INTERNAL_ERROR","message":"服务内部错误，请稍后重试"}
```

> **对照证据（说明统一出口本身是好的，只是没接住框架异常）**：
> `PUT /api/v1/admin/resources/999999999` → `404 {"code":"RESOURCE_NOT_FOUND","message":"资源不存在或已被删除"}`
> `POST /api/v1/business/orders/999999999/confirm` → `404 {"code":"ORDER_NOT_FOUND","message":"订单不存在"}`

#### H-2 500 泄露内部细节（Jackson 内部信息 / Java 方法签名 / 内部路径）—— 4 个 IM 服务

- **位置**：`im-admin/.../GlobalExceptionHandler.java:62-67`、`im-conversation/.../GlobalExceptionHandler.java:53-58`、`im-message/.../GlobalExceptionHandler.java:52-57`、`im-user/.../GlobalExceptionHandler.java:69-74`
- **现象**：`body.put("message", ex.getMessage() != null ? ex.getMessage() : "Internal error")`
- **影响**：向未认证/已认证调用方泄露依赖版本特征与内部结构，可用于指纹识别与探测；同时消息是英文，直接违反对外中文约定。
- **建议**：兜底返回固定文案 + `code`（`INTERNAL_ERROR` / `服务内部错误，请稍后重试`），原始消息只进日志；把上述框架异常单独映射为 400/404/405。
- **严重度**：**高**

**实测响应原文（均为未认证即可触达的公开路由）**

```
POST http://im-admin-service:3400/client/release-check  -d '{bad json'
  -> HTTP 500 {"message":"JSON parse error: Unexpected character ('b' (code 98)): was expecting double-quote to start property name"}

GET  http://im-admin-service:3400/client/releases/latest          （缺 platform 参数）
  -> HTTP 500 {"message":"Required request parameter 'platform' for method parameter type String is not present"}

GET  http://im-admin-service:3400/config/client/zzz
  -> HTTP 500 {"message":"No static resource config/client/zzz for request '/config/client/zzz'."}

GET  http://im-user-service:3100/oauth/authorize                  （缺 response_type）
  -> HTTP 500 {"message":"Required request parameter 'response_type' for method parameter type String is not present"}

POST http://im-user-service:3100/oauth/token  -d '{bad'
  -> HTTP 500 {"message":"JSON parse error: Unexpected character ('b' (code 98)): was expecting double-quote to start property name"}

GET  http://im-user-service:3100/open/zzz
  -> HTTP 500 {"message":"No static resource open/zzz for request '/open/zzz'."}

GET  http://im-user-service:3100/oauth/zzz
  -> HTTP 500 {"message":"No static resource oauth/zzz for request '/oauth/zzz'."}
```

> `/oauth/zzz` 这条尤其值得注意：**OAuth 端点返回 500 + 非 OAuth 错误体**，而不是 `{"error":"invalid_request"}`，会让标准 OAuth 客户端拿到无法解释的响应。

#### H-3 静默 500：有响应、无日志 —— `common-media` + 4 个 IM 服务

- **位置**：同 H-2 四处 + `common-services/media/common-media-service/src/main/java/com/gvchat/common/media/handler/GlobalExceptionHandler.java:38-43`
- **现象**：兜底方法体内没有任何 `log.*` 调用。
- **影响**：线上 500 无法定位；排障只能靠复现。本项与 H-1 叠加后危险度更高（大量「500」其实是客户端错误，却连日志都没有，只能靠猜）。
- **建议**：兜底必须 `log.error("<service> unhandled exception, uri={}", request.getRequestURI(), ex)`，并把 advise 加上 `@Slf4j`。
- **严重度**：**高**

**实测证据（日志管道健康，唯独没有这些 500 的记录）**

```
$ kubectl -n gv-im-local logs im-admin-service-77596c844b-2b942 --since=6m
（空）
$ kubectl -n gv-im-local logs im-user-service-6f4cf5b588-b4ppk --since=6m
（空）
$ kubectl -n gv-im-local logs common-media-service-67854d859c-p2jrn --tail=12
2026-09-17T01:44:18.000Z  INFO ... c.g.c.m.media.MediaImageUploadService : Public image stored: ...
2026-09-17T02:35:15.856Z  WARN ... .i.s.InternalServiceAuthenticationFilter : Rejected internal service request: ...
（同 Pod 其它 INFO/WARN 正常，证明日志可用；人为触发 500 的时间点 02:34-02:36 无任何错误日志）
```

#### H-4 登录失败返回 500（应 401）—— platform-identity-service

- **位置**：`platform-services/identity/platform-identity-service/src/main/java/com/gvchat/platform/identity/application/AccountApplicationService.java:153-155`
  ```java
  if (li == null || !passwordEncoder.matches(credential, li.getCredential())) {
      throw new IllegalStateException("AUTH_INVALID_CREDENTIAL");
  }
  ```
  与 `platform-services/identity/.../handler/GlobalExceptionHandler.java:17-33`（只映射 `ApiException` 与 `IllegalArgumentException`，**没有 `IllegalStateException`，也没有 `Exception` 兜底**）
- **现象**：`IllegalStateException` 落到 Spring 默认错误处理。
- **影响**：登录失败前端的「账号密码错误」分支永远拿不到 401；错误统计里登录失败全部计入 5xx，掩盖真实故障率；用给定凭据 `admin/e8280ac0d25d4bc0a1e1` 直接调用该接口也是 500。
- **建议**：改抛 `ApiException(401, "AUTH_INVALID_CREDENTIAL", "用户名或密码错误")`；同时给该服务补 `Exception` + `HttpMessageNotReadableException` 兜底。
- **严重度**：**高**

**实测响应原文**

```
POST http://192.168.31.91:30081/api/v1/identity/login  {"loginType":"username","loginIdentifier":"admin","credential":"e8280ac0d25d4bc0a1e1"}
  -> HTTP 500 {"timestamp":"2026-09-17T02:33:27.125Z","status":500,"error":"Internal Server Error","path":"/identity/login"}
POST 同上（不存在的用户 + 错误密码）
  -> HTTP 500 {"timestamp":"2026-09-17T02:33:33.500Z","status":500,"error":"Internal Server Error","path":"/identity/login"}
POST 同上（空体 {}）
  -> HTTP 500 {"timestamp":"2026-09-17T02:33:33.599Z","status":500,"error":"Internal Server Error","path":"/identity/login"}
POST 同上（畸形 JSON '{not json'）
  -> HTTP 400 {"timestamp":"2026-09-17T02:33:33.563Z","status":400,"error":"Bad Request","path":"/identity/login"}
```

#### H-5 6 个服务没有 500 兜底，4 个服务完全没有统一出口

| 服务 | 统一出口 | 缺失项 |
|---|---|---|
| platform-admin | `platform-services/admin/platform-admin-service/src/main/java/com/gvchat/platform/admin/handler/GlobalExceptionHandler.java:15-23` | 只有 `ApiException`；无 `Exception` 兜底、无 `@Slf4j` |
| platform-customer | `.../customer/.../handler/GlobalExceptionHandler.java:17-25` | 同上 |
| platform-identity | `.../identity/.../handler/GlobalExceptionHandler.java:17-33` | 无 `Exception` 兜底；`handleIllegalArgument:27-33` 直接回显 `ex.getMessage()` |
| platform-marketing | `.../marketing/.../handler/GlobalExceptionHandler.java:17-25` | 只有 `ApiException` |
| common-payment | `common-services/payment/.../handler/GlobalExceptionHandler.java:18-26` | 只有 `ApiException` |
| common-payment-channel | `common-services/payment-channel/.../handler/GlobalExceptionHandler.java:15-23` | 只有 `ApiException` |
| **common-audit** | **无** | 有 `AuditController`（`common-services/audit/.../api/controller/AuditController.java`），无 advice |
| **common-mail** | **无** | 有 `MailController`，无 advice |
| **common-sms** | **无** | 有 `SmsController`，无 advice |
| group-idaas | `group-services/idaas/.../handler/GlobalExceptionHandler.java:17-34` | 只有 `ApiException` + 方法参数校验；**404 返回 Tomcat HTML** |

- **影响**：非业务异常一律 Spring 默认错误体，前端只能按状态码兜底（见摘要前端第 2 条）；audit/mail/sms 的 `ClassCastException`（例如 `AuditLogApplicationService.java:20-21` 对 `tenantId` 做 `(Number)` 强转）会直接变成 500 默认页。
- **实测：group-idaas 的 404 是 HTML，不是 JSON**
  ```
  GET http://group-idaas-service:4220/zzz
  HTTP/1.1 404
  Content-Type: text/html;charset=utf-8
  Content-Language: en
  Content-Length: 431
  <!doctype html><html lang="en"><head><title>HTTP Status 404 – Not Found</title>...
  ```
  非 JSON + `lang="en"`，任何 JSON 客户端、以及后台的错误解析工具都会直接失败。
- **建议**：给 audit/mail/sms 从零补 advice（可复制 `platform-resource` 版本，含 `Exception` 兜底 + `@Slf4j`）；给 6 个「只有 ApiException」的服务补 `Exception` 兜底；排查 group-idaas 为何未注册 Boot `BasicErrorController`（`application.yml` 的 `server:` 段或 war 打包方式）。
- **严重度**：**高**

#### H-6 必填 `@RequestHeader("Idempotency-Key")` 缺失绕过统一错误码

- **位置（Controller 声明，`required` 默认为 true）**：
  - `platform-services/customer/.../api/controller/WalletAdminController.java:20,27`
  - `platform-services/marketing/.../api/controller/CouponController.java:44`
  - `common-services/payment/.../api/controller/CollectController.java:27`
  - `common-services/media/.../controller/MediaUploadSessionController.java:26,34`
  - `im-services/admin/.../api/controller/ClientReleaseController.java:27-36`（且类型是 `UUID`）
- **已经写好却打不到的统一错误码**：`platform-services/customer/.../application/WalletApplicationService.java:110,196`、`platform-services/marketing/.../application/CouponApplicationService.java:79`、`common-services/payment/.../application/CollectApplicationService.java:94`（均为 `400 IDEMPOTENCY_KEY_REQUIRED / 缺少 Idempotency-Key`）
- **现象**：头缺失时 Spring 抛 `MissingRequestHeaderException`，在 advice 未声明该类型的前提下由 `DefaultHandlerExceptionResolver` 处理 → 400 + 默认体；在声明了 `Exception` 兜底的服务里（media / im-admin 等）反而变成 500。
- **影响**：同一业务错误因「头缺失 vs 头为空」返回两种完全不同的响应体；客户端与客服脚本无法按 `code` 分支。
- **建议**（二选一，推荐前者）：① Controller 改 `@RequestHeader(value="Idempotency-Key", required=false)`，让应用层统一抛码；② 在 advice 里补 `@ExceptionHandler(MissingRequestHeaderException.class)` → `400 REQUEST_HEADER_MISSING`（中文）。**注意 media 已实现该分支，但文案是英文**（见 M-2）。
- **严重度**：**高**

**实测响应原文（同一接口，仅请求头有无之差）**

```
POST /api/v1/admin/wallets/recharge   （不带 Idempotency-Key）
  -> HTTP 400 {"timestamp":"2026-09-17T02:36:30.207Z","status":400,"error":"Bad Request","path":"/admin/wallets/recharge"}
POST /api/v1/admin/wallets/recharge   （带空值 Idempotency-Key）
  -> HTTP 400 {"code":"IDEMPOTENCY_KEY_REQUIRED","message":"缺少 Idempotency-Key"}
POST /api/v1/business/coupons/999999999/issue  （不带 Idempotency-Key）
  -> HTTP 400 {"timestamp":"2026-09-17T02:35:59.545Z","status":400,"error":"Bad Request","path":"/business/coupons/999999999/issue"}
```

---

### 2.2 中（Medium）

#### M-1 兜底 500 缺 `code` 字段且为英文 —— platform-order / common-media

- `platform-services/order/.../handler/GlobalExceptionHandler.java:44`：`return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(Map.of("message", "Internal error"));`
- `common-services/media/.../handler/GlobalExceptionHandler.java:40-42`：同形态
- **影响**：与 `{code,message}` 契约不符；前端只能走状态码兜底；违反对外中文约定。实测 `{"message":"Internal error"}`。
- **建议**：统一为 `{"code":"INTERNAL_ERROR","message":"服务内部错误，请稍后重试"}`。
- **严重度**：中

#### M-2 media 的部分错误体缺 `code` 且文案是英文

- `common-services/media/.../handler/GlobalExceptionHandler.java:30`：`"Validation failed"`（且无 `code`）
- `:35`：`"Required request header is missing: " + exception.getHeaderName()`
- `:46`：`badRequest(message)` → `Map.of("message", message)` — **只有 message，没有 code**
- **影响**：media 的 400 全部没有机器可判别的 `code`。
- **建议**：补 `INVALID_ARGUMENT` / `REQUEST_HEADER_MISSING`，文案改中文。
- **严重度**：中

#### M-3 Spring 默认错误体大面积存在（可实测枚举）

直连每服务的「不支持方法 / 未知路径 / 错误 Content-Type」四连探针结果（`ci` = 在 `saas-admin` Pod 内 `curl`）：

| 服务 | PATCH `/` | GET `/zzz-nonexistent` | 判定 |
|---|---|---|---|
| platform-admin | 404 默认体 | 404 默认体 | 非统一体 |
| platform-customer | 404 默认体 | 404 默认体 | 非统一体 |
| platform-identity | 404 默认体 | 404 默认体 | 非统一体 |
| platform-marketing | 404 默认体 | 404 默认体 | 非统一体 |
| common-audit | 404 默认体 | 404 默认体 | 非统一体 |
| common-mail | 404 默认体 | 404 默认体 | 非统一体 |
| common-payment | 404 默认体 | 404 默认体 | 非统一体 |
| common-payment-channel | 404 默认体 | 404 默认体 | 非统一体 |
| common-sms | 404 默认体 | 404 默认体 | 非统一体 |
| group-idaas | 404 **HTML** | 404 **HTML** | 非 JSON |
| gateway | 404 WebFlux 默认体 | 404 WebFlux 默认体 | 非统一体 |
| platform-order | **500** | **500** | 见 H-1 |
| platform-resource | **500** | **500** | 见 H-1 |
| platform-tenant | **500** | **500** | 见 H-1 |
| common-media / im-admin / im-conversation / im-message / im-user | 401（Spring Security 先拦） | 401 | 未认证不可达，但已认证请求命中同一兜底 |

- **严重度**：中

#### M-4 网关自身的错误体不统一

- `gateways/gateway/src/main/java/com/gvchat/gateway/ratelimit/RateLimitFilter.java:32-33`：`{"code":429,"message":"请求过于频繁，请稍后再试"}` —— **`code` 是数字 429**，而全仓库其它 141 个码都是 `UPPER_SNAKE` 字符串；429 也不在 `sdk/common/.../HttpStatusCodes.java` 常量表里（该表没有 429）。
- `gateways/gateway/.../security/SaasSessionAuthenticationFilter.java:200-207`：401 体 `{"code":"SAAS_SESSION_REQUIRED","message":"SaaS session is required"}` —— 英文；且**会话存在但租户上下文为空时报的码是 `SAAS_CONTEXT_REQUIRED`**（`:87`），与下游的 `TENANT_CONTEXT_REQUIRED` / `TENANT_CONTEXT_MISSING` 三足鼎立（见 4.1）。
- 网关未匹配路由：WebFlux 默认体 `{"timestamp":"...","path":"...","status":404,"error":"Not Found","requestId":"..."}`（实测）。
- **严重度**：中

#### M-5 出错时返回 HTTP 200 + 错误载荷 —— platform-order 报表

- `platform-services/order/platform-order-service/src/main/java/com/gvchat/platform/order/api/controller/ReportController.java:42`、`:68`、`:90`
  ```java
  if (tenantId == null) { return Map.of("error", "TENANT_CONTEXT_MISSING"); }
  ```
- **影响**：与同仓库其它报表（`platform-admin/ReportController.java:171` 抛 `ApiException(401, "TENANT_CONTEXT_MISSING", ...)`）行为相反；调用方按 HTTP 状态判断成功会误判为空报表。
- **建议**：改为抛 `ApiException(401, "TENANT_CONTEXT_MISSING", "缺少租户上下文")`。
- **严重度**：中

#### M-6 im-admin 把下游响应体原样透传

- `im-services/admin/.../handler/GlobalExceptionHandler.java:50-55`
  ```java
  @ExceptionHandler(RestClientResponseException.class)
  ... .body(exception.getResponseBodyAsString());
  ```
- **影响**：下游服务的内部错误体（含 H-2 那类泄露文案）会经 im-admin 二次转发给客户端，放大 H-2 的影响面；且内容类型/形状不受本服务控制。
- **建议**：只透传 `code`/`message` 白名单字段，其余落日志。
- **严重度**：中

#### M-7 media 的 `IllegalArgumentException` 变 500

- `common-services/media/.../controller/MediaUploadSessionController.java:50-56`：`requireIdempotencyKey` 抛英文 `IllegalArgumentException("Idempotency-Key must be a UUID")`；media 的 advice 未声明 `IllegalArgumentException` → 命中 `Exception` 兜底 → `500 {"message":"Internal error"}`
- **影响**：客户端传了非 UUID 的幂等键，得到 500 而非 400，且原因丢失。
- **建议**：改抛 `ApiException(400, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 必须是 UUID")`。
- **严重度**：中

#### M-8 IM 服务的参数校验错误缺 `code`

- `im-admin/.../GlobalExceptionHandler.java:42-48`、`im-conversation/.../:40-46`、`im-message/.../:40-46`、`im-user/.../:56-62`：`handleValidation` 只写 `message`，不写 `code`
- **实测**：`POST http://im-admin-service:3400/client/release-check -d '{}'` → `400 {"message":"must not be blank"}` —— **既无 `code`，文案还是英文 Bean Validation 默认值**。
- **建议**：补 `INVALID_ARGUMENT`，并让 DTO 上的 `message` 用中文。
- **严重度**：中

---

### 2.3 低（Low）

| # | 服务 | 文件:行 | 现象 | 建议 | 严重度 |
|---|---|---|---|---|---|
| L-1 | platform-identity | `.../identity/.../handler/GlobalExceptionHandler.java:27-33` | `handleIllegalArgument` 把 `ex.getMessage()` 原样回显（可能是内部解析信息） | 固定文案 + 记日志 | 低 |
| L-2 | platform-order | `.../order/.../handler/GlobalExceptionHandler.java:66-80` | `httpStatusFor` 用 `switch` 硬编码码表，与其它服务的「按 ApiException.status」两套机制并存；新增码若忘记登记会静默变 400 | 将码→状态收敛到共享枚举/`HttpStatusCodes` | 低 |
| L-3 | group-idaas | `.../idaas/.../handler/GlobalExceptionHandler.java:31-32` | 校验消息拼接 `e.getField() + ": " + e.getDefaultMessage()`，字段名（英文技术名）暴露给最终用户 | 只用 `getDefaultMessage()` | 低 |
| L-4 | gateway | `RateLimitFilter.java:32` | 429 的 `code` 是数字 | 统一为 `RATE_LIMITED` 字符串码 | 低 |
| L-5 | 多服务 | `.../handler/GlobalExceptionHandler.java`（resource/tenant/order） | 500 日志无 `uri`/`traceId`/请求 id，多实例下无法定位 | 日志加 `request.getRequestURI()` | 低 |
| L-6 | gateway | `SaasSessionAuthenticationFilter.java:179-187` | `contextToken` 的 `catch (Exception ignored) { return Optional.empty(); }` 把「JSON 解析失败」与「无上下文」合并，最终对外报 `SAAS_CONTEXT_REQUIRED`，真实原因（会话数据损坏）无日志 | 加 `log.warn` | 低 |

---

## 3. 已规范的证据清单（服务 → 统一出口位置 + 覆盖情况）

> 覆盖情况按「是否有出口 / 是否兜底 `Exception` / 兜底是否记堆栈 / 兜底是否含 `code` / 是否 `@Slf4j`」五维标注。

### 3.1 完全达标（可作为模板，建议其余服务照抄）

| 服务 | 统一出口 | 覆盖 |
|---|---|---|
| **platform-tenant** | `platform-services/tenant/platform-tenant-service/src/main/java/com/gvchat/platform/tenant/handler/GlobalExceptionHandler.java`（`:23` `@RestControllerAdvice`，`:24` `@Slf4j`） | `BusinessException`(:27) → 400；`ApiException`(:35) → 按 status；`DataAccessException`(:50) → 租户上下文缺失转 **401**、否则 500+`DATABASE_ERROR`；`HttpMessageNotReadableException`(:66) → 400 `REQUEST_BODY_INVALID`；`MissingServletRequestParameterException`(:75) → 400 `REQUEST_PARAM_MISSING`；`Exception`(:85) → **500 `INTERNAL_ERROR` + `log.error(..., ex)`**。✅ 全维度达标 |
| **platform-resource** | `platform-services/resource/platform-resource-service/src/main/java/com/gvchat/platform/resource/handler/GlobalExceptionHandler.java:22-24,26,34,49,65,74,84` | 与 tenant 同构。✅ 全维度达标 |

- **实测印证**：`GET http://platform-resource-service:4120/admin/resources`（无租户上下文）→ `401 {"code":"TENANT_CONTEXT_REQUIRED","message":"缺少有效的租户/门店上下文"}`；`POST` 同路径 → `403 {"code":"PERMISSION_DENIED","message":"缺少权限: resource.manage"}`。**这正是历史上「403 退化成 500」的修复证据，该问题在这两个服务已闭环。**

### 3.2 有出口但缺口明确

| 服务 | 统一出口 | 有出口 | 兜底 `Exception` | 兜底记堆栈 | 兜底含 `code` | `@Slf4j` |
|---|---|---|---|---|---|---|
| platform-order | `.../order/.../handler/GlobalExceptionHandler.java:17-19` | ✅ | ✅ `:41` | ✅ `:43` | ❌ `:44` 只有 message | ✅ `:18` |
| common-media | `.../media/.../handler/GlobalExceptionHandler.java:14-15` | ✅ | ✅ `:38` | ❌ | ❌ | ❌ |
| im-admin | `.../im/admin/.../handler/GlobalExceptionHandler.java:18-19` | ✅ | ✅ `:62` | ❌ | ❌ | ❌ |
| im-conversation | `.../im/conversation/.../handler/GlobalExceptionHandler.java:16-17` | ✅ | ✅ `:53` | ❌ | ❌ | ❌ |
| im-message | `.../im/message/.../handler/GlobalExceptionHandler.java:16-17` | ✅ | ✅ `:52` | ❌ | ❌ | ❌ |
| im-user | `.../im/user/.../handler/GlobalExceptionHandler.java:17-18` | ✅ | ✅ `:69` | ❌ | ❌ | ❌ |
| platform-admin | `.../admin/.../handler/GlobalExceptionHandler.java:12-13` | ✅ | ❌ | — | — | ❌ |
| platform-customer | `.../customer/.../handler/GlobalExceptionHandler.java:14-15` | ✅ | ❌ | — | — | ❌ |
| platform-identity | `.../identity/.../handler/GlobalExceptionHandler.java:14-15` | ✅ | ❌ | — | — | ❌ |
| platform-marketing | `.../marketing/.../handler/GlobalExceptionHandler.java:14-15` | ✅ | ❌ | — | — | ❌ |
| common-payment | `.../payment/.../handler/GlobalExceptionHandler.java:15-16` | ✅ | ❌ | — | — | ❌ |
| common-payment-channel | `.../payment-channel/.../handler/GlobalExceptionHandler.java:12-13` | ✅ | ❌ | — | — | ❌ |
| group-idaas | `.../idaas/.../handler/GlobalExceptionHandler.java:14-15` | ✅ | ❌ | — | — | ❌ |

### 3.3 无统一出口

| 服务 | 有 REST Controller | 统一出口 |
|---|---|---|
| common-audit | ✅ `common-services/audit/.../api/controller/AuditController.java` | ❌ |
| common-mail | ✅ `common-services/mail/.../api/controller/MailController.java` | ❌ |
| common-sms | ✅ `common-services/sms/.../api/controller/SmsController.java` | ❌ |
| gateways/gateway | ❌（0 个 `@RestController`，WebFlux `GlobalFilter`） | N/A，但过滤器自造错误体（见 M-4） |
| gateways/im-access-ws | ❌（0 个 `@RestController`，纯 WebSocket） | N/A |

### 3.4 跨切面已规范的部分（值得肯定）

| 项 | 位置 | 结论 |
|---|---|---|
| Spring Security 认证失败统一 JSON | `sdk/infrastructure/.../security/RestAuthenticationEntryPoint.java:22-37` | ✅ 401 + `{code,message}`；❌ message 固定英文 `"Unauthorized"`；⚠️ 未注册 `accessDeniedHandler`，403 仍走容器错误页 |
| 内部服务认证失败 | `sdk/infrastructure/.../security/InternalServiceAuthenticationFilter.java:61` | ✅ 只记布尔，不泄露签名/密钥 |
| 日志实现统一 | 全仓库 `src/main/java` | ✅ `LoggerFactory.getLogger` / `import org.slf4j.Logger;` / `Logger x =` **各 0 命中**；106 个类带 `@Slf4j`，104 个有日志调用的类**全部**已 `@Slf4j`。`validate-java-logging-conventions.ps1` **当前通过** |
| `printStackTrace` | 全仓库 | ✅ 0 命中，`validate-java-exception-logging.ps1` 通过 |
| 错误码风格 | SaaS 侧 141 个码 | ✅ **100% 符合 `^[A-Z][A-Z0-9_]*$`**，无大小写混杂 |
| 网关限流失败开放 | `RateLimitFilter.java:93-101` | ✅ Redis 不可用 fail-open 且 `log.warn` 带 path/key/异常 |

---

## 4. i18n / 文案不一致清单（位置 → 现状 → 建议）

### 4.1 后端：同一语义「上下文缺失」用了 5 个码 + 2 种状态码

| 位置 | 现状（code / message / status） | 建议统一值 |
|---|---|---|
| `gateways/gateway/.../security/SaasSessionAuthenticationFilter.java:87` | `SAAS_CONTEXT_REQUIRED` / `SaaS session is required` / 401 | `TENANT_CONTEXT_REQUIRED` / `缺少运营上下文` / 401 |
| `platform-services/admin/.../api/controller/StaffController.java:107` | `SAAS_CONTEXT_REQUIRED` / `请先选择运营上下文` / 401 | 同上 |
| `platform-services/admin/.../api/controller/MediaImageController.java:65` | `SAAS_CONTEXT_REQUIRED` / `请先选择运营上下文` / 401 | 同上 |
| `platform-services/admin/.../api/controller/StaffController.java:113` | `SAAS_CONTEXT_INVALID` / `运营上下文已过期，请刷新页面` / 401 | `TENANT_CONTEXT_EXPIRED` / 401 |
| `platform-services/admin/.../infra/CustomerServiceClient.java:100` | `TENANT_CONTEXT_REQUIRED` / `请先选择经营上下文` / **409** | `TENANT_CONTEXT_REQUIRED` / **401** |
| `platform-services/admin/.../infra/RestKtvConfigDomainClient.java:286` | `TENANT_CONTEXT_REQUIRED` / 同上 / **409** | 同上 |
| `platform-services/admin/.../infra/ReservationDomainClient.java:103` | `TENANT_CONTEXT_REQUIRED` / 同上 / **409** | 同上 |
| `platform-services/order/.../api/controller/OrderController.java:253` | `TENANT_CONTEXT_REQUIRED` / `缺少有效的租户/组织/门店上下文` / 401 | `TENANT_CONTEXT_REQUIRED` / 401 |
| `platform-services/order/.../api/controller/ReservationController.java:108`、`ReservationCompatController.java:35`、`KtvPricingController.java:32`、`CatalogController.java:61,172`、`OrderItemController.java:78` | `TENANT_CONTEXT_MISSING` / `缺少租户上下文` / 401 | → `TENANT_CONTEXT_REQUIRED` |
| `platform-services/order/.../application/ReservationApplicationService.java:291` | `TENANT_CONTEXT_MISSING` / 401 | 同上 |
| `platform-services/order/.../application/ProductApplicationService.java:158`、`InventoryApplicationService.java:182`、`ProductCategoryApplicationService.java:178` | `TENANT_CONTEXT_REQUIRED` / `缺少有效门店上下文` / 400（`BusinessException` 恒 400，**与同为 401 的其它实现不一致**） | `TENANT_CONTEXT_REQUIRED` / 401 |
| `platform-services/tenant/.../api/controller/TenantConfigController.java:68` | `TENANT_CONTEXT_MISSING` / 401 | → `TENANT_CONTEXT_REQUIRED` |
| `platform-services/admin/.../api/controller/ReportController.java:171` | `TENANT_CONTEXT_MISSING` / 401 | → `TENANT_CONTEXT_REQUIRED` |
| `common-services/payment/.../application/PaymentMethodApplicationService.java:142`、`api/controller/InternalPaymentQueryController.java:45` | `TENANT_CONTEXT_MISSING` / 401 | → `TENANT_CONTEXT_REQUIRED` |
| `common-services/payment/.../api/controller/CollectController.java:31` | **`AUTH_CONTEXT_EXPIRED` / `缺少租户上下文` / 401** —— **码与语义不符**（码说「认证过期」，消息说「缺上下文」） | `TENANT_CONTEXT_REQUIRED` |
| `platform-services/tenant/.../handler/GlobalExceptionHandler.java:54`、`platform-services/resource/.../handler/GlobalExceptionHandler.java:53` | `TENANT_CONTEXT_REQUIRED` / `缺少有效的租户/门店上下文` / 401 | ✅ 保持 |

**结论**：同一语义 → **5 个码**（`SAAS_CONTEXT_REQUIRED` / `SAAS_CONTEXT_INVALID` / `TENANT_CONTEXT_REQUIRED` / `TENANT_CONTEXT_MISSING` / `AUTH_CONTEXT_EXPIRED`）+ **3 种状态**（401 / 409 / 400）+ **6 种 message 措辞**。前端 `adminErrorMessage.js:33,36,37` 因此要同时映射三个码，`c-end/auth-errors.js:10` 又要映射第四个 —— 映射表必然漂移。

### 4.2 后端：英文裸文案（对外 message 必须是简体中文）

**总量：全仓库 292 条**（无 CJK 字符且非纯码字面量的 `ApiException` / `BusinessException` message）。按服务：

| 服务 | 条数 |
|---|---|
| im-user | 112 |
| im-message | 55 |
| im-conversation | 47 |
| common-media | 30 |
| im-admin | 24 |
| platform-admin | 24（含 im-admin 归属，按目录计 24） |
| platform-identity | 14 |
| group-idaas | 5 |
| platform-order | 3 |
| common-payment-channel | 1 |
| platform-customer | 1 |

**SaaS 面（platform-services + common-services + group-services）逐条 —— 这 54 条是优先级最高的：**

| 位置 | 现状 | 建议 |
|---|---|---|
| `platform-services/identity/.../api/controller/AuthContextController.java:46`、`:77` | `"missing or invalid CSRF token"`（403 `CSRF_TOKEN_INVALID`）| `CSRF 令牌缺失或已过期` |
| `platform-services/identity/.../api/controller/AuthContextController.java:85` | `"SaaS session is required"`（401 `SAAS_SESSION_REQUIRED`）| `登录状态已失效，请重新登录` |
| `platform-services/identity/.../infra/security/AccountTokenResolver.java:25,29,36` | `"Bearer token is required"` / `"Bearer token is invalid or expired"` | `缺少访问令牌` / `访问令牌无效或已过期` |
| `platform-services/identity/.../application/OAuthImBindApplicationService.java:88,100` | `"IM userinfo missing open_id"` / `"IM bind conflict"` | `IM 用户信息缺少 open_id` / `IM 账号已被绑定` |
| `platform-services/admin/.../application/AdminLoginApplicationService.java:37` | `"invalid username or password"`（401 `ADMIN_LOGIN_INVALID`）**实测返回** | `用户名或密码错误` |
| `platform-services/admin/.../application/AdminPasswordApplicationService.java:23,26` | `"missing SaaS admin session"` / `"invalid or expired SaaS admin session"` | `缺少平台账号会话` / `平台账号会话已失效` |
| `platform-services/admin/.../application/SsoAuthApplicationService.java:37,47`、`infra/IdaasSsoClient.java:43` | `"invalid or expired SSO ticket"` / `"group session expired"` | `SSO 票据无效或已过期` / `集团会话已过期` |
| `platform-services/customer/.../api/controller/MyAssetsController.java:57`、`platform-services/order/.../api/controller/MyOrderController.java:47`、`platform-services/order/.../api/controller/ReservationController.java:116` | `"missing authenticated account"`（401 `ACCOUNT_REQUIRED`） | `缺少已认证账号` |
| `platform-services/order/.../api/controller/MyOrderController.java:51` | `"customer profile is not initialized"` | `会员档案尚未初始化` |
| `common-services/payment-channel/.../application/ChannelProviderApplicationService.java:57,64,68` | `"payment channel disabled: " + ...` / `"missing payment channel provider"` / `"unknown payment channel provider: " + provider` | 中文（且第 3 条是拼接消息，见 4.3） |
| `common-services/media/.../media/*.java`（30 条，`MediaObjectService:35,45,51,70,73`、`MediaUploadSessionService:46,79,83,105,141,165,170,173`、`MediaMultipartUploadService:31,36,76,99,117,119,132`、`MediaReferenceService:36,80,82`、`MinioMediaStorage:106,120,151`、`OssMediaStorage:102`、`ReleaseArtifactService:31,43,51`） | 全部英文（`"Media object not found"` / `"Idempotency key does not match the upload request"` 等） | 批量中文 |
| `group-services/idaas/.../application/AuthApplicationService.java:47,78,80`、`infra/security/GroupJwtSigner.java:64`、`infra/security/IdaasSessionStore.java:61` | `"invalid username or password"` / `"invalid or expired SSO ticket"` / `"group session expired"` / `"invalid or expired sso token"` / `"invalid or expired session"` | 中文 |

**网关过滤器内置英文**（grep 不到 `ApiException`，但确实对外）：

| 位置 | 现状 | 建议 |
|---|---|---|
| `gateways/gateway/.../security/SaasSessionAuthenticationFilter.java:204` | `{"code":"SAAS_SESSION_REQUIRED","message":"SaaS session is required"}`（**实测返回**） | 中文 |
| `sdk/infrastructure/.../security/RestAuthenticationEntryPoint.java:29,32` | `{"code":"UNAUTHORIZED","message":"Unauthorized"}`（**实测返回**） | 中文 `未认证或登录状态已失效` |

### 4.3 后端：拼接 / 回显型消息

| 位置 | 现状 | 问题 | 建议 |
|---|---|---|---|
| `platform-services/admin/.../api/controller/ContextController.java:69` | `"contextId 格式非法: " + request.contextId()` | 回显用户输入（含中英混排） | `contextId 格式非法，应为 tenant:org:store` |
| `sdk/infrastructure/.../tenant/PermissionGuard.java:13` | `"缺少权限: " + permissionCode` | 技术码直出给运营人员 | 映射为权限中文名，或保留码但统一格式 |
| `common-services/payment/.../application/CollectApplicationService.java:202` | `new ApiException(422, "PAYMENT_CHANNEL_DISABLED", "未知支付方式: " + p.method())` | **码说「渠道未启用」，消息说「未知支付方式」——码与消息矛盾** | 二者对齐（`PAYMENT_METHOD_UNKNOWN` 或改消息） |
| `common-services/payment/.../application/CollectApplicationService.java:119,357`、`PaymentMethodApplicationService.java:136` | `"支付方式未授权: " + p.method()` / `"使用 " + method + " 抵扣必须指定会员(customerId)"` / `"未知支付方式: " + method` | 中英括号混排 `(customerId)` | 统一中文括注「会员」 |
| `common-services/payment-channel/.../ChannelProviderApplicationService.java:57,68` | 英文 + 拼接 provider | 见 4.2 | 中文 |
| `platform-services/tenant/.../handler/GlobalExceptionHandler.java:80`、`platform-services/resource/.../handler/GlobalExceptionHandler.java:79` | `"缺少请求参数: " + ex.getParameterName()` | 参数名为英文技术名 | `缺少请求参数`（技术名只进日志） |
| `common-services/media/.../handler/GlobalExceptionHandler.java:35` | `"Required request header is missing: " + exception.getHeaderName()` | 英文 + 拼接 | 中文 |
| `common-services/media/.../media/MediaUploadSessionService.java:161` | `"文件大小超出限制，" + mediaKindLabel(...)` | 中文但拼接动态片段，长度不可控 | 可接受，建议模板化 |

### 4.4 后端 code ↔ 前端映射漂移（跨服务）

| 项 | 现状 | 建议 |
|---|---|---|
| `gv_saas_admin/src/utils/adminErrorMessage.js:15-40` | 映射 21 个码，**0 个是幽灵码**（后端都真实存在）✅；但后端 141 个码中 **120 个无映射** | 由后端导出码表（OpenAPI/常量）驱动前端映射 |
| 其中 **18 个**「无映射 **且** 英文 message」——后台会退化成状态码泛化文案 | `ACCOUNT_REQUIRED`、`ADMIN_LOGIN_INVALID`、`ADMIN_SESSION_INVALID`、`ADMIN_SESSION_MISSING`、`AUTH_INVALID_CREDENTIAL`、`AUTH_INVALID_SESSION`、`AUTH_INVALID_TOKEN`、`AUTH_TOKEN_INVALID`、`AUTH_TOKEN_REQUIRED`、`CLIENT_RELEASE_PLATFORM_NOT_ENABLED`、`IM_BIND_CONFLICT`、`IM_OPEN_ID_MISSING`、`MEMBER_NOT_FOUND`、`PAYMENT_CHANNEL_DISABLED`、`PAYMENT_CHANNEL_UNKNOWN`、`SAAS_SESSION_REQUIRED`、`SSO_TICKET_EXPIRED`、`SSO_TICKET_INVALID` | 英文改中文 + 补映射 |
| **实测** | `POST /api/v1/admin/auth/login`（错密码）→ `401 {"message":"invalid username or password","code":"ADMIN_LOGIN_INVALID"}` → 后台 `CJK_PATTERN` 不通过 → 走 401 兜底 → 运营看到**「登录状态已失效，请重新登录。」** | — |
| `gv_saas_mobile/c-end/auth-errors.js:5-39` | 映射 7 个码，其中 **5 个后端从未产生**：`NO_CONSUMER_ACCESS`、`CONTEXT_SELECTION_REQUIRED`、`IM_SESSION_EXPIRED`、`IM_BRIDGE_AUTH_FAILED`、`PKCE_UNAVAILABLE`（全仓库 grep 0 命中；只有 `IAM_CONTEXT_UNAVAILABLE` 与 `SAAS_SESSION_REQUIRED` 真实存在） | 删除幽灵码或补齐后端产出 |

### 4.5 前端：i18n 基础设施（结论：不存在）

| 仓库 | i18n 依赖 | locale 文件 | 结论 |
|---|---|---|---|
| `gv_saas_admin` | ❌ 无（依赖仅 `element-plus` / `axios` / `pinia` / `vue` / `vue-router`） | ❌ 无 | 全量硬编码中文；**无 key 覆盖问题可言** |
| `gv_saas_mobile`（`src/b-end`） | ❌ 无 | ❌ 无 | 同上 |
| `gv_saas_mobile`（`c-end`，纯 JS） | ❌ 无 | ❌ 无 | 同上；唯一「locale」痕迹是 `app.js:39,200,204,222,227,242,248,756,760` 的 `toLocaleString('zh-CN')` |

**⚠️ 产品信号**：`gv_saas_admin/src/views/platform/tenants.vue:30-31` 已有 **「默认语言」「默认时区」** 两个租户配置项（`form.defaultLocale` / `form.defaultTimezone`），说明多语言是已规划的产品能力；但前端没有任何承载它的机制。**这是本次审计最需要产品决策的一项**：先定「多语言是否 2.0 范围」，再决定是引入 `vue-i18n` 抽取，还是明确只支持简体中文并把这些配置项标注为「预留」。

### 4.6 前端：术语不一致

出现次数（不含 `node_modules`/`dist`）：

| 术语 | gv_saas_admin/src | mobile/src(b-end) | mobile/c-end |
|---|---|---|---|
| 包厢 | 56 | 21 | 31 |
| 资源 | 19 | 3 | 0 |
| 点单目录 | 11 | 2 | 2 |
| 商品 | 35 | 5 | 11 |
| 应收 | 25 | 10 | 0 |
| 已收 | 7 | 5 | 0 |
| **待收** | 0 | **1** | 0 |
| 储值 | 23 | 8 | 4 |
| 充值 | 14 | 2 | 2 |
| 余额 | 10 | 2 | 2 |
| 门店 | 80 | 10 | 10 |
| **商户** | 2（仅注释） | **3（用户可见）** | 0 |
| **商家** | 0 | 0 | **1（用户可见）** |
| 租户 | 67 | 8 | 8 |

| 位置 | 现状 | 建议 |
|---|---|---|
| `gv_saas_admin/src/router/index.js:71` | `meta: { title: '包厢/资源' }` —— **菜单标题用斜杠并列两个词**，说明术语未定稿 | 产品确认后择一（建议用户面「包厢」、代码面 `resource`） |
| `gv_saas_mobile/src/b-end/views/Orders.vue:58` | `· 待收 ¥{{ fenToYuan(orderBill(order).payableAmount) }}`，同文件 `:111,:137,:226` 用「应收」 | 统一「应收」 |
| `gv_saas_mobile/src/b-end/App.vue:5,49,64` | 用户可见 **「A380 商户工作台」/「A380 商户端」**，而 `orders.vue:116,425` 用「租户」、admin 用「租户后台」 | 统一（建议 B 端称「商户端」或「运营端」，并在 admin 保持一致） |
| `gv_saas_mobile/c-end/app.js:625` | `'请联系商家开通支付方式'`（商家），同文件 `:74,:307,:453` 用「门店」 | 统一「门店」 |
| `gv_saas_admin/src/constants/payment-methods.js:4,11,22` | `'储值币(A380币)'` / `'储值币'` / `'储值'` | 见 4.7 |

### 4.7 前端：储值品牌名（明确违反代码内自述规则）

| 位置 | 现状 |
|---|---|
| `gv_saas_admin/src/constants/payment-methods.js:14-15` | **规则原文**：*「储值是品牌展示名，取租户配置 `tnt_tenant_config.wallet_brand_name`（默认 A380币），不得硬编码」* |
| `gv_saas_admin/src/views/tenant/wallet.vue:114,123,140` | 硬编码 `'A380币'`（含 `const tokenName = ref('A380币')`、`configForm = { brandName: 'A380币', ... }`） |
| `gv_saas_admin/src/views/tenant/wallet.vue:82` | placeholder `如 A380币` |
| `gv_saas_admin/src/views/tenant/payments.vue:108` | `const walletBrand = ref('储值')` ← 默认值与规则不符 |
| `gv_saas_admin/src/views/tenant/orders.vue:535` | `const walletBrand = ref('储值')` ← 同上（`:1182` 才被配置覆盖） |
| `gv_saas_admin/src/views/tenant/ktv-config.vue:5,88,121,493`、`api/payment.js:70`、`api/ktv.js:38` | `A380币` 硬编码 |
| `gv_saas_admin/src/router/index.js:137` | 菜单名「储值管理」 |

**建议**：全部改为读取同一 store 中的 `walletBrand`（初值用配置或空字符串，避免首帧闪 `'储值'`）；`payment-methods.js:4` 的长名给设置页用，`methodLabel` 的默认值改为必传。

### 4.8 前端：金额单位与格式

| 位置 | 现状 | 问题 |
|---|---|---|
| `gv_saas_admin/src/utils/format.js:16-21` | `/** 最小货币单位（分）→ \`¥ 12.00\`。 */ return '¥ ' + (number / 100).toFixed(2)` | **规范实现存在** |
| `views/tenant/orders.vue:818` | `'¥ ' + (n / 100).toFixed(2)` | 重复实现（有空格） |
| `views/tenant/orders.vue:824` | `'¥ ' + (n / 100).toLocaleString('zh-CN', {...})` | 重复实现（带千分位）→ **同一页面两种金额格式** |
| `views/tenant/orders.vue:376` | 模板内联 `¥{{ (Number(row.unitPrice) / 100).toFixed(2) }}` | 无空格 |
| `views/tenant/products.vue:38` | 模板内联 `¥{{ (Number(row.salePrice) / 100).toFixed(2) }}` | 无空格 |
| `views/tenant/payments.vue:138` | `'¥ ' + (Number(v) / 100).toFixed(2)` | 重复实现 |
| `views/tenant/shift.vue:174` | `'¥ ' + (n / 100).toFixed(2)` | 重复实现 |
| `views/tenant/reservations.vue:75` | `'¥' + (Number(pricing.roomUnitPrice || 0) / 100).toFixed(2)` | 无空格 |
| `views/tenant/members.vue:150` | `(Number(v) / 100).toFixed(2) + ' 元'` | **后缀「元」，与其它全部「¥ 前缀」相反** |
| `views/tenant/ktv-config.vue:270-272` | 本地 `fenToYuan(v)` | 又一份重复实现 |
| `views/tenant/ktv-config.vue:65,223` | `递增粒度(分)` | **「分」在此表示分钟**，同一文件 `:270` 的「分」表示分币 → 术语冲突 |
| `views/tenant/reports.vue:126` | `h + ' 小时 ' + m + ' 分'` | 「分」= 分钟 |
| `views/tenant/orders.vue:1068,1111,1175`、`payments.vue:136`、`shift.vue:217,239`、`ktv-config.vue:342,343,418` | `Math.round(x * 100)` 各自实现「元→分」 | 至少 8 处重复；建议收敛到 `yuanToMinor` |

**建议**：① 全部改调 `src/utils/format.js` 的 `formatMoney`，并统一为 `¥ 12.00`（前缀 + 空格 + 两位小数 + 千分位）；② 新增 `yuanToMinor` / `minorToYuan` 到 `format.js`，删除 8 处内联；③ 「分」表示分钟的地方改写为「分钟」（`递增粒度(分钟)`）。

### 4.9 前端：时间格式

| 位置 | 现状 |
|---|---|
| `views/tenant/orders.vue:615` | 唯一一处格式化：`new Date(x).toLocaleTimeString('zh-CN', { hour12: false })` |
| `views/platform/tenants.vue:19` | `prop="createdAt" label="创建时间"` —— **直接渲染后端原始值** |
| `views/tenant/shift.vue:64` | `prop="businessDate" label="营业日期"` —— 原始值 |
| `views/tenant/ktv-config.vue:164` | `prop="createdAt" label="时间"` —— 原始值 |
| `gv_saas_mobile/src/b-end` | 全目录 **0 处** 日期格式化调用 |
| `gv_saas_mobile/c-end/app.js` | 9 处 `toLocaleString('zh-CN')`（仅金额/数值，无显式日期格式） |

**建议**：新增 `formatDateTime` / `formatDate`（`YYYY-MM-DD HH:mm` / `YYYY-MM-DD`）到共享 utils，替换所有原始值渲染；避免裸 `toLocaleString()`（无 locale 参数时依赖运行环境）。

---

## 5. 建议的修复批次与可执行验证命令

### 批次 1 — 纯机械、可一次改完、零产品依赖（建议本周）

| # | 内容 | 涉及文件数 | 风险 |
|---|---|---|---|
| 1.1 | 给 `common-audit` / `common-mail` / `common-sms` 增加 `GlobalExceptionHandler`（照抄 `platform-resource` 版本，含 `Exception` 兜底 + `@Slf4j`） | 3 新文件 | 极低 |
| 1.2 | 4 个 IM 服务的兜底去掉 `ex.getMessage()`，改为 `{"code":"INTERNAL_ERROR","message":"服务内部错误，请稍后重试"}` + `log.error(..., ex)` + 补 `@Slf4j` | 4 | 低 |
| 1.3 | `platform-order` / `common-media` 兜底补 `code: INTERNAL_ERROR` 并改中文；media 的 `badRequest` 补 `code` | 2 | 低 |
| 1.4 | 8 个服务的 advice 增补框架异常的显式映射（`NoResourceFoundException`→404、`HttpRequestMethodNotSupportedException`→405、`HttpMediaTypeNotSupportedException`→415、`MissingRequestHeaderException`→400），避免被 `Exception` 兜底截获 | 8 | 低（纯新增分支） |
| 1.5 | 6 个「只有 ApiException」的服务补 `Exception` 兜底（标 1.2 形态） | 6 | 低 |
| 1.6 | 必填 `Idempotency-Key` 改为 `required=false`（5 个 Controller），让已有的 `IDEMPOTENCY_KEY_REQUIRED` 生效 | 5 | 低 |
| 1.7 | `AccountApplicationService.java:154` 改抛 `ApiException(401,"AUTH_INVALID_CREDENTIAL", ...)` | 1 | 低 |
| 1.8 | `order/ReportController.java:42,68,90` 改抛 `ApiException(401,"TENANT_CONTEXT_MISSING", ...)` | 1 | 低 |
| 1.9 | 网关 429 的 `code` 改字符串；`HttpStatusCodes` 补 `TOO_MANY_REQUESTS = 429`、`UNPROCESSABLE_CONTENT = 422` | 2 | 低 |
| 1.10 | 前端：全部金额走 `utils/format.js`；新增 `yuanToMinor`；改掉 `members.vue:150` 的「元」后缀；`「递增粒度(分)」→「递增粒度(分钟)」` | ~10 | 低（有测试兜底：`npm test`） |
| 1.11 | 前端：储值品牌名去硬编码（`wallet.vue:114,123,140,82`；`payments.vue:108`、`orders.vue:535` 默认值） | 3 | 低 |
| 1.12 | 前端：日期统一走 `formatDateTime`；`c-end/auth-errors.js` 删除 5 个幽灵码 | ~6 | 低 |
| 1.13 | **`gv_saas_admin/src/views/tenant/orders.vue:410` 删除字面 `¥ `**（`fmtCents` 已带符号）→ 消除 "¥ ¥ 123.45" | 1 | 极低（唯一确定性的 UI 文案缺陷） |
| 1.14 | **C 端金额尺度**：`c-end/app.js:853` 改用 `minorMoney(...)`；`:200,204,222,227,248,756,760` 补 `/100`（或统一走一个 `walletBalance()`）；删除死代码 `:240-242 fenToYuanStr` | 1 | 中（涉及金额，改后须人工核对 mock 与真机各一次） |
| 1.15 | **C 端 UTC 日期**：`c-end/app.js:33`、`:655` 弃用 `toISOString().slice(0,10)`，改本地拼装（抄 `admin/views/tenant/shift.vue:161-163`）；`:314-316` 的 `scheduleAt` 需与后端确认时区语义后再改 | 1 | 中（`:314-316` 待契约确认） |
| 1.16 | 前端文案去重：5 份 `'服务端未返回 CSRF token'` → 1 常量；`b-end/App.vue:82` 不再透传 `e.message`，改走 code→中文映射 | ~6 | 低 |
| 1.17 | `roles.vue:41,42` 下拉改中文（复用 `staff.vue:161`）；`ktv-config.vue:219,220,232,233,234` 裸枚举移到 `value` | 2 | 低 |
| 1.18 | 后端敏感日志脱敏：`SmtpPasswordResetMailSender.java:37`、`SmsPasswordResetSender.java:37`、`TencentSmsProvider.java:64,101,104`、`AliyunSmsProvider.java:54,87,90`（手机号走已有 `PhonePrivacy.mask`） | 4 | 低（纯日志） |
| 1.19 | 后端静默 500 补日志：`GlobalExceptionHandler` 兜底统一加 `log.error(..., ex)`（4 个 IM 服务 + media）；`AuditClient.java:75`、`MessageProjectionService.java:43,105,121` 由 `getMessage()`/`toString()` 改为传 `ex` 本体 | ~8 | 低（纯日志） |
| 1.20 | 后端空 catch 至少补注释+日志：`StaffAccountApplicationService.java:154,165`、`CollectApplicationService.java:349`、`PermissionSnapshotCache.java:38,55,61,69,77`、`ResourceStateClient.java:130,166,186` | ~6 | 低（行为不变，仅加可观测性） |

> **批次 1 的注意事项**
> 1. 1.4 需在 **8 个服务**同时落地；`platform-order` 已在并发改动中新增了 `MissingRequestHeaderException` 与 `ServletRequestBindingException` 两个分支，**该服务只差 404/405/415 三个**（见 §0.2）。
> 2. 1.10 / 1.13 / 1.14 会改动金额展示，改后必须跑 `npm test`（现有 `format.test.js` 是文案锁，会挡住不小心的格式漂移）。
> 3. 所有前端行号在并发改动结束前都不可信，**动手前必须重新 `grep`**（§0.3）。

### 批次 1.5 — 行为变更类（需产品/后端确认后再动，不要混进批次 1）

| # | 内容 | 为什么不能自动改 |
|---|---|---|
| 1.5.1 | `common-payment-channel` 的 4 处支付回调验签静默失败（`WechatChannel.java:166,305`、`AlipayChannel.java:123`、`StripeChannel.java:115`）补日志 + 告警 | 补日志本身安全，但「验签失败是否应告警/限流/落库审计」是业务决策；且该服务**当前零 logger**，加日志需先确认日志脱敏口径 |
| 1.5.2 | `CollectApplicationService.java:349` 退款补偿失败、`ResourceStateClient.java:130` 开台占用降级 | 二者当前是**有意的降级放行**（注释自述「骨架忽略」「服务不可达则降级放行」）；要改成失败关闭还是保留降级放行 + 告警，属产品/资金口径决策 |
| 1.5.3 | `SmsPasswordResetSender.java:56` + `PasswordRecoveryApplicationService.java:81` 的「发送失败仍 200」 | 改成 5xx/明确失败会改变前端体验，需与产品确认（也可能产品就是要「不暴露账号是否存在」——那应改成静默成功但**必须告警**） |
| 1.5.4 | `c-end/app.js:314-316` 的 `scheduleAt` 时区修正 | 需先确认后端按 UTC 还是本地解释；改错会让所有预约偏 8 小时 |
| 1.5.5 | `sdk/common/.../AesGcmCipher.java:32`、`order/.../ResourceStateClient.java:38` 的硬编码密钥默认值 | 属配置/密钥治理，且 `validate-secret-exposure.ps1` 当前不扫 `.java`（见 6.5 末尾），建议另立专项 |

### 批次 2 — 需要产品/术语确认（建议与产品过一轮术语表后统一改）

| # | 需确认项 | 候选 |
|---|---|---|
| 2.1 | 「包厢 / 资源」对外用哪个（`router/index.js:71` 当前写 `包厢/资源`） | 用户面「包厢」，代码/JSDoc 保留 resource |
| 2.2 | B 端称呼：「商户端」还是「运营端」（`b-end/App.vue:5` 现为「A380 商户工作台」） | 与 admin 的「租户」关系需一并定义 |
| 2.3 | 同一金额：「应收」还是「待收」（`b-end/Orders.vue:58`） | 建议「应收」 |
| 2.4 | 「商家 / 商户 / 门店 / 租户」四选几、分别对应哪一层 | 建议：租户（平台）→ 门店（经营主体）→ 商户端（B 端 App）；删除「商家」 |
| 2.5 | 储值品牌名最终形态（是否就叫 A380币，且是否允许租户改名） | 若允许 → 前端不得有默认值 |
| 2.6 | **多语言是否在 2.0 范围**（`platform/tenants.vue:30-31` 已有「默认语言/默认时区」配置项） | 是 → 引入 `vue-i18n` + 抽 key 是独立大工程；否 → 明确标注为预留并统一中文规范 |
| 2.7 | 错误码统一：上下文类 5 码收敛为 `TENANT_CONTEXT_REQUIRED` + 401 是否可接受 | 影响前端映射与文档 |
| 2.8 | **「实收」是否等同「已收」** —— `admin/views/tenant/reports.vue:63 '实收(元)'` 对应字段 `paidAmount`，而 `orders.vue` 同字段叫「已收」 | 若口径不同（现金实点 vs 含储值/积分），必须分开命名且报表要对得上 |
| 2.9 | **「房态看板」vs「包厢状态」** —— admin 用「房态实时看板」，`c-end/app.js:261` 用「包厢状态」 | 同一块 UI 两套称呼 |
| 2.10 | **死词汇是否清理**：`goods` / `sku` / `商品目录` / `房台` 全仓 0 命中（但「房态看板」在用「房」字根） | 确认后加入禁用词表 |
| 2.11 | **C 端/B 端是否允许出现任何英文** —— `b-end/App.vue:82` 透传 `e.message`，其生产者是英文（`OAuth state mismatch` / `missing pkce verifier` / `bind failed` / `session not established`） | 决定是「全部映射成中文」还是「保留技术英文」 |
| 2.12 | **`orders.vue` 报表/接口时间是否保留秒** —— `reports.vue:140 '数据时点：…slice(0,19)'`、`tenants.vue:53` 保留秒，其余 10 处不保留 | 对账场景可能确实需要秒 → 应确认「哪些字段保留秒」而非一刀切 |

### 批次 3 — 后端中文文案与码表治理（可在批次 1 后并行）

| # | 内容 |
|---|---|
| 3.1 | SaaS 面 54 条英文 message 批量改中文（4.2 表；im-* 112+55+47 条可另立 IM 专项，若 IM 客户端确为纯技术面则改用 `code` 为主、message 可为英文但需产品确认） |
| 3.2 | 上下文类 5 码收敛（4.1 表） |
| 3.3 | 修正 `CollectApplicationService.java:202` 的码/消息矛盾 |
| 3.4 | 从后端导出错误码清单（OpenAPI/常量类）驱动 `gv_saas_admin` 的 `ERROR_CODE_MESSAGES`，消除 120 个未映射码 |
| 3.5 | 新增门禁 `scripts/validate/validate-exception-handling.ps1`：校验每个可部署服务存在 `GlobalExceptionHandler` 且包含 `Exception` 兜底 + `log.error(..., ex)` + `code` 字段；校验对外 message 不含 ASCII-only 文案（白名单机制）；登记到 `invoke-engineering-validation.ps1` 的 `$validators`（当前 16 个脚本**没有一个**覆盖异常出口） |

### 可执行验证命令

**A. 仓库既有门禁（只跑与异常/日志相关的 3 个子脚本，秒级）**

```powershell
cd D:\projects\cnb\gv_im_server
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\validate-java-logging-conventions.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\validate-java-exception-logging.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\validate-secret-exposure.ps1
```
> 当前预期：前两个 **pass**（本次审计确认 0 命中）；`secret-exposure` 与本次异常/文案问题无关，可跳过。
> 全量门禁（`-Scope Full` 会执行 `mvnw clean verify`，**耗时很长**，不建议在审计阶段跑）：
> ```powershell
> powershell -File .\scripts\validate\invoke-engineering-validation.ps1 -Scope Full
> ```

**B. 一键回归「统一错误体」探针（在集群内执行，避免依赖 NodePort）**

```powershell
# 把 18 个服务一次性打完：期望 404/405/415 均为 {code,message}，且不再出现 500
$script = @'
for hp in platform-admin-service:4150 platform-customer-service:4160 platform-identity-service:4100 \
          platform-marketing-service:4170 platform-order-service:4130 platform-resource-service:4120 \
          platform-tenant-service:4110 common-audit-service:4190 common-mail-service:4210 \
          common-media-service:3600 common-payment-service:4140 common-payment-channel-service:4180 \
          common-sms-service:4200 im-admin-service:3400 im-conversation-service:3300 \
          im-message-service:3200 im-user-service:3100 group-idaas-service:4220
do
  host=${hp%%:*}; port=${hp##*:}
  for probe in "-X PATCH|/" "-X POST -H Content-Type:text/plain -d x|/" "|/zzz-nonexistent"
  do
    opts=${probe%%|*}; path=${probe##*|}
    code=$(curl -s -o /tmp/b -w "%{http_code}" --max-time 8 $opts "http://$host:$port$path")
    echo "$host $path -> $code $(head -c 120 /tmp/b | tr -d '\n')"
  done
done
'@
$script | kubectl -n gv-im-local exec -i saas-admin-84d6db87df-w6rgp -- sh -s
```
> **当前基线（修复前）**：`platform-order` / `platform-resource` / `platform-tenant` 三行全是 `500`；9 个服务是 Spring 默认体；`group-idaas` 是 HTML。

**C. 必填请求头是否走统一体**

```powershell
$d = "$env:TEMP\cbn_audit"; New-Item -ItemType Directory -Force $d | Out-Null
# 登录拿会话
'{"username":"admin","password":"e8280ac0d25d4bc0a1e1","ttlHours":1}' |
  Set-Content "$d\login.json" -Encoding ASCII -NoNewline
curl.exe -s -c "$d\ck.txt" -X POST http://192.168.31.91:30081/api/v1/admin/auth/login `
  -H "Content-Type: application/json" -d "@$d\login.json"
# 取 csrf + 选上下文
$csrf = (curl.exe -s -b "$d\ck.txt" http://192.168.31.91:30081/api/v1/admin/auth/csrf | ConvertFrom-Json).csrfToken
'{"contextId":"100:100:100"}' | Set-Content "$d\ctx.json" -Encoding ASCII -NoNewline
curl.exe -s -b "$d\ck.txt" -X POST http://192.168.31.91:30081/api/v1/admin/context/select `
  -H "Content-Type: application/json" -H "X-CSRF-Token: $csrf" -d "@$d\ctx.json"
# 对照探针：头缺失 vs 头为空
'{"customerId":1,"amount":100,"currency":"CNY","paymentMethod":"CASH"}' |
  Set-Content "$d\w.json" -Encoding ASCII -NoNewline
curl.exe -s -i -b "$d\ck.txt" -X POST http://192.168.31.91:30002/api/v1/admin/wallets/recharge -H "Content-Type: application/json" -d "@$d\w.json"
curl.exe -s -i -b "$d\ck.txt" -X POST http://192.168.31.91:30002/api/v1/admin/wallets/recharge -H "Content-Type: application/json" -H "Idempotency-Key;" -d "@$d\w.json"
```
> **期望修复后**：两条都返回 `{"code":"IDEMPOTENCY_KEY_REQUIRED","message":"缺少 Idempotency-Key"}`。
> **当前基线**：第 1 条是 `{"timestamp",...}` 默认体，第 2 条是统一体。

**D. 登录失败状态码**

```powershell
'{"loginType":"username","loginIdentifier":"admin","credential":"e8280ac0d25d4bc0a1e1"}' |
  Set-Content "$env:TEMP\cbn_audit\idlogin.json" -Encoding ASCII -NoNewline
curl.exe -s -i -X POST http://192.168.31.91:30081/api/v1/identity/login `
  -H "Content-Type: application/json" -d "@$env:TEMP\cbn_audit\idlogin.json"
```
> **期望修复后**：`401 {"code":"AUTH_INVALID_CREDENTIAL","message":"用户名或密码错误"}`（错误凭据）；当前基线是 **500 默认体**。

**E. 「500 是否记日志」回归**

```powershell
# 触发一次公开路由 500，然后看日志（修复后应能看到 ERROR 行）
curl.exe -s -o NUL -X POST http://192.168.31.91:30002/client/release-check `
  -H "Content-Type: application/json" -d "{bad"
kubectl -n gv-im-local logs im-admin-service-77596c844b-2b942 --since=2m | Select-String "ERROR"
```
> **当前基线**：无任何输出。

**F. 前端检查**

```powershell
cd D:\projects\cnb\gv_saas_admin; npm test; npm run build
cd D:\projects\cnb\gv_saas_mobile; npm test          # 仓库根即有 test 脚本
# 金额/时间格式回归（应只剩 utils/format.js 一个实现）
Select-String -Path 'src\**\*.vue','src\**\*.ts' -Pattern "/ 100|\* 100"
# 硬编码品牌名回归（应为 0）
Select-String -Path 'src\**\*.vue','src\**\*.ts' -Pattern "A380币|储值币"
```

**F2. 本次新发现的前端确定性缺陷（修复前后都能跑，用来确认）**

```powershell
$admin = 'D:\projects\cnb\gv_saas_admin'
$mobile = 'D:\projects\cnb\gv_saas_mobile'

# F2.1 双货币符号：期望修复后 0 命中
Select-String -Path "$admin\src\views\**\*.vue" -Pattern '¥\s*\{\{\s*(fmtCents|formatYuan|formatYuanValue|fmtCentsCompact)\('
# 当前命中：orders.vue:410

# F2.2 本地重复声明的 formatTime（期望 0 命中；仅 utils/format.js 应有实现）
Select-String -Path "$admin\src" -Include *.vue,*.js -Pattern '^(export )?function formatTime' -Recurse |
  ForEach-Object { "{0}:{1}" -f $_.Path,$_.LineNumber }

# F2.3 C 端金额尺度（人工核对：853 应改为 minorMoney）
Select-String -Path "$mobile\c-end\app.js" -Pattern 'money\(it\.totalAmount|function money|function minorMoney|fenToYuanStr'
# 期望修复后：853 行不再出现 money(it.totalAmount；fenToYuanStr 已删除

# F2.4 C 端余额是否除 100（期望修复后这些行都出现 /100 或改用统一函数）
Select-String -Path "$mobile\c-end\app.js" -Pattern 'availableAmount \|\| 0\)\.toLocaleString'

# F2.5 C 端 UTC 日期（期望修复后 0 命中）
Select-String -Path "$mobile\c-end\app.js" -Pattern 'toISOString\(\)\.slice\(0,\s*10\)'

# F2.6 裸枚举 / 英文下拉（期望修复后 0 命中）
Select-String -Path "$admin\src\views\platform\iam\roles.vue" -Pattern 'label="(PLATFORM|TENANT)"'
Select-String -Path "$admin\src\views\tenant\ktv-config.vue" -Pattern 'HOUR|HALF_HOUR|FLOOR_BLOCK|ROUND_UP|CONSUMER_FAVOR'
Select-String -Path "$mobile\c-end\oauth.js" -Pattern 'IM login session has expired|IM bridge authorization failed'

# F2.7 重复文案常量（期望修复后各只 1 处）
Select-String -Path "$admin\src","$mobile\src","$mobile\c-end" -Include *.vue,*.js -Recurse -Pattern '服务端未返回 CSRF token' |
  ForEach-Object { "{0}:{1}" -f $_.Path,$_.LineNumber }
```

**G. 后端敏感日志 / 静默吞异常回归（静态，秒级）**

```powershell
cd D:\projects\cnb\gv_im_server
# G1 明文验证码 / 重置链接 / 手机号（期望修复后 0 命中）
git grep -n -E 'would send reset link to|code=\{\}", mask\(phone\)|to=\{\}.*", phone|tenant.*to=\{\}' -- '*.java'
# 更精确：
Select-String -Path 'im-services\user\im-user-service\src\main\java\com\gvchat\im\user\infra\mail\SmtpPasswordResetMailSender.java' -Pattern 'resetLink|email'
Select-String -Path 'common-services\sms\common-sms-service\src\main\java\com\gvchat\common\sms\infra\provider\*.java' -Pattern 'to=\{\}'
# G2 完全空的 catch（期望修复后加注释+日志）
git grep -n -E 'catch\s*\(\s*\w+\s+ignored\s*\)\s*\{\s*\}' -- '*.java'
# G3 丢弃 exception 形参的辅助方法
Select-String -Path 'common-services\media\common-media-service\src\main\java\com\gvchat\common\media\media\*.java' -Pattern 'unavailable\(String'
# G4 兜底分支是否记堆栈（期望 8 个服务都有 log.error 且传 ex）
Get-ChildItem -Recurse -Filter GlobalExceptionHandler.java | Where-Object { $_.FullName -notmatch '\\target\\' } |
  ForEach-Object { $t=Get-Content $_.FullName -Raw; "{0,-22} generic={1,-4} logs={2}" -f `
    ($_.FullName -split '\\java\\')[0].Split('\')[-1], `
    ($t -match '@ExceptionHandler\(Exception\.class\)'), ($t -match 'log\.error') }
```


---

## 6. 后端日志规范与「吞异常」（对应任务 1.4，静态全量扫描）

### 6.1 门禁实际规则（先看门禁，再看现实）

| 脚本 | 规则 | 范围 | 当前结果 |
|---|---|---|---|
| `scripts/validate/validate-java-logging-conventions.ps1`（31 行，**只有 1 条规则**） | 第18行 `-match 'LoggerFactory\.getLogger\|import\s+org\.slf4j\.Logger\s*;\|\bLogger\s+\w+\s*='` | `git ls-files '*src/main/java/*.java'` | **pass** |
| `scripts/validate/validate-java-exception-logging.ps1`（16 行，**只有 1 条规则**） | 第6行 `git grep -n -e 'printStackTrace(' -- '*.java'` | 全部已跟踪 `*.java`（含 test） | **pass** |

> **门禁全绿，但本报告 H-1~H-6、M-1~M-8 与下面 6.2/6.3 的所有问题都不被任何门禁覆盖。** `invoke-engineering-validation.ps1` 的 16 个脚本里没有一个检查「统一异常出口是否存在」「500 是否记堆栈」「message 是否为中文」。这是本次审计最值得上报的**流程性结论**。

### 6.2 日志实现本身：已统一（结论：合规）

| 检查项 | main | test | 门禁覆盖 |
|---|---|---|---|
| `LoggerFactory.getLogger` | **0** | **0** | ✅ 规则1 |
| `import org.slf4j.Logger;` | **0** | **0** | ✅ 规则2 |
| `Logger xxx =` | **0** | **0** | ✅ 规则3 |
| `LogManager.getLogger` / `java.util.logging` | **0** | **0** | ❌ 盲区 |
| `System.out.*` / `System.err.*` | **0** | **0** | ❌ 门禁不检查 |
| `printStackTrace(` | **0** | **0** | ✅ 另一脚本 |
| `@Log4j2` / `@Log` / `@CommonsLog` | **0** | **0** | ❌ 盲区 |

- **106 个类**带 `@Slf4j`；**365 处** `log.*` 调用分布在 **104 个类**，**全部**已 `@Slf4j`（违规 0）。2 个类注解了但无日志调用（冗余，低）。
- **结论**：参考 6.1，两个 java 日志门禁当前都是绿的。

### 6.3 敏感信息进入日志（按严重度）

| # | 服务 | 文件:行 | 现状 | 影响 | 严重度 |
|---|---|---|---|---|---|
| 1 | im-user | `im-services/user/im-user-service/src/main/java/com/gvchat/im/user/infra/mail/SmtpPasswordResetMailSender.java:37` | `log.info("[password-reset] mail disabled, would send reset link to {}: {}", email, resetLink)` | **密码重置链接（含一次性 token）+ 邮箱明文**；日志可读即可接管账号。`im.mail.enabled` 默认 false → **这是默认分支** | 高 |
| 2 | im-user | `.../infra/sms/SmsPasswordResetSender.java:37` | `... code={}", mask(phone), code)` | **短信验证码明文**（手机号已脱敏、验证码未脱敏）。`im.sms.enabled` 默认 false → 默认分支 | 高 |
| 3 | common-sms | `common-services/sms/.../infra/provider/TencentSmsProvider.java:64,101,104` | `... to={} ...", phone` | **完整手机号（PII）**；同模块 `SmsApplicationService.java:34-35` 已在用 `PhonePrivacy.digest/mask`，provider 层未脱敏 | 高 |
| 4 | common-sms | `.../infra/provider/AliyunSmsProvider.java:54,87,90` | 同上 | 同上 | 高 |
| 5 | common-mail | `common-services/mail/.../infra/provider/SmtpMailProvider.java:46,56,58` | `... to={} subject={}", to` | 收件人邮箱 PII | 中 |
| 6 | im-user | `.../infra/mail/SmtpPasswordResetMailSender.java:47,49` | `... reset email to {}", email` | 邮箱 PII | 中 |
| 7 | payment-channel | `common-services/payment-channel/.../infra/provider/WechatChannel.java:142,259`；`StripeChannel.java:83` | `... String.valueOf(e.getMessage())` 作为**返回值字段** | 底层异常 message 进入响应体，可能泄露网关/证书配置线索；无日志无堆栈 | 中 |
| 8 | idaas | `group-services/idaas/.../application/AuthApplicationService.java:46,53` | `... username={}", request.username()` | 用户名 PII，可用于账号枚举 | 低 |

**明确核对为安全（未发现泄露）**：`JwtAuthenticationFilter.java:100,104`（只记 token expired/invalid + method/uri，不打印 token 本体）、`InternalServiceAuthenticationFilter.java:61`（只记布尔）、`OpenPlatformApplicationService.java:336,382`（OAuth token 轮换只记 id）、`RedisOauthTokenStore.java:224`（只记 type）。

### 6.4 异常日志丢失堆栈 / 缺定位上下文

| 服务 | 文件:行 | 现状 | 严重度 |
|---|---|---|---|
| sdk | `sdk/infrastructure/.../audit/AuditClient.java:75-76` | `log.warn("异步写审计失败, action={}... cause={}", ..., e.getMessage())` → **无堆栈**且 `return null` **吞掉**审计写失败 | 高 |
| im-message | `.../infra/projection/MessageProjectionService.java:43`、`:105`、`:121` | 均为 `ex.getMessage()` / `ex.toString()`，**无堆栈** | 中 |
| im-message | `.../infra/integration/admin/AdminFeatureToggleAdapter.java:55` | `log.warn("Failed to fetch feature flags, keep last cache")` —— **既无异常也无 id**，开关不生效无线索 | 中 |
| im-message | `.../infra/integration/admin/AdminModerationWordSource.java:52` | 同上（只记缓存条数） | 中 |
| im-message | `.../scheduler/SecretMessageDestroyScheduler.java:23`、`SecretGroupMessageDestroyScheduler.java:23` | `log.warn("... scan failed", e)` —— 批处理失败**无批次/时间窗/条数** | 中 |
| im-user | `.../infra/scheduler/SelfDestructSweepJob.java:27`、`AccountCancellationSweepJob.java:35` | 同上 | 中 |
| im-message / im-conversation | `.../infra/integration/*Adapter.java`（`UserServiceProfileAdapter.java:35`、`UserServiceDeviceKeyAdapter.java:41`） | 无 groupId/userId | 中 |
| platform-* | `tenant/.../handler/GlobalExceptionHandler.java:58,87`；`resource/.../:57,86`；`order/.../:43` | `log.error("Unhandled xxx-service exception", ex)` —— **无 uri/请求 id/traceId**（H-3 之外的次要项；这 3 个服务至少有堆栈） | 中 |

> 另有 **21 组 / 42 处** `Failed to start/close X consumer/listener` 模板日志（各 `*EventConsumer` / `*EventListener`），均无 id/code 但都带堆栈 —— 属噪音+弱定位，可接受，建议补 topic/group。

### 6.5 吞异常 / 出错后返回成功（Top 10，与本次 H-1~H-6 互补）

| # | 服务 | 文件:行 | 现状 | 影响 | 严重度 |
|---|---|---|---|---|---|
| 1 | payment-channel | `common-services/payment-channel/.../infra/provider/WechatChannel.java:166-168`、`:305-307`；`AlipayChannel.java:123-125`；`StripeChannel.java:115-117` | 回调验签 `catch (Exception e) { return fail(request); }` —— **该服务 24 处宽泛 catch 全部无日志，整个 `common-payment-channel` 没有 logger** | 支付回调验签异常被静默转成「签名失败」，无任何痕迹 | 高 |
| 2 | payment | `common-services/payment/.../application/CollectApplicationService.java:349-351` | `catch (RuntimeException ignored) {}` + 注释`// 补偿失败：真实实现写 Outbox/告警重试，骨架忽略` | **积分/储值退款补偿失败被吞**，资金侧静默丢失 | 高 |
| 3 | order | `platform-services/order/.../infra/client/ResourceStateClient.java:130-132` + `.../application/KtvSessionApplicationService.java:110-113` | `occupy` 失败静默 `return Optional.empty()`，调用方 `.ifPresent(...)` 跳过占用后继续 `setStatus("OPEN")` | 资源服务故障期间**开台成功但无占用记录**，可重复开台/重复计费 | 高 |
| 4 | admin | `platform-services/admin/.../application/StaffAccountApplicationService.java:154-155`、`:165-166` | `catch (RuntimeException ignored) {}` —— **完全空体** | 角色绑定回滚补偿失败无痕 | 高 |
| 5 | media | `common-services/media/.../media/MinioMediaStorage.java:267-269`、`OssMediaStorage.java:164` | 辅助方法 `unavailable(String, Exception)` **丢弃 exception 形参** → 该两文件 **26 处** catch 全部丢失根因；`MinioMediaStorage.java:106,120` 还把基础设施故障报成 400 | 存储故障不可诊断 | 高 |
| 6 | im-user | `.../infra/sms/SmsPasswordResetSender.java:56-58` + `.../application/account/PasswordRecoveryApplicationService.java:80-81` | sender 吞掉发送失败；调用方随后 `log.info("[password-reset] issued reset sms code ...")` 并 **200 返回** | 短信网关故障时接口「成功」，用户永远收不到码 | 高 |
| 7 | common-sms | `common-services/sms/.../application/service/SmsApplicationService.java:50-59` | `catch (RuntimeException ex) { log.setStatus("FAILED"); }` —— **无任何日志** | 短信不发无人可查 | 中 |
| 8 | common-mail | `common-services/mail/.../application/service/MailApplicationService.java:44-49` | 同上 | 邮件不发无人可查 | 中 |
| 9 | tenant | `platform-services/tenant/.../infra/authorization/PermissionSnapshotCache.java:38,55,61,69,77` | 5 处空 catch（`/* redis 不可用时依赖本地缓存 */` 等） | Redis 故障时权限快照静默降级 | 中 |
| 10 | gateway | `gateways/gateway/.../security/SaasSessionAuthenticationFilter.java:184-186` | `catch (Exception ignored) { return Optional.empty(); }` → 上层 `:87` 报 `SAAS_CONTEXT_REQUIRED` | 会话数据损坏被误报成「缺上下文」，真实原因无日志 | 中 |

**宽泛 catch 基数**：`catch (Exception|Throwable|RuntimeException)` 全仓 **287 处**（`RuntimeException` 60，`Throwable` 0）。按服务：im-message 52、im-access-ws 51、platform-admin 34、common-media 30、common-payment-channel 24、im-user 18、common-payment 15、platform-order 14、platform-identity 9、sdk 8、im-admin 7、platform-tenant 6、common-sms 4、platform-marketing 4、im-conversation 4、platform-resource 3、common-mail 2、gateway 1、group-idaas 1、**platform-customer 0、common-audit 0**（这两个服务既无 catch 也无任何日志）。

**相邻发现（超出异常范围，仅标注）**：`validate-secret-exposure.ps1:13` 的 `$candidateExtensions` 不含 `.java`，因此 **Java 内硬编码密钥默认值逃过该门禁**，例如 `sdk/common/.../crypto/AesGcmCipher.java:32`（`@Value("${app.crypto.aes-secret:gv-aes-dev-secret-change-me}")`）、`platform-services/order/.../infra/client/ResourceStateClient.java:38`（`...:gv-im-internal-dev-secret`）。建议另派一个配置面审计。

---

## 7. 前端补充发现（逐文件核对，含确定性缺陷）

### 7.1 确定性缺陷（可直接修，非风格问题）

| # | 位置（行号随并发改动漂移，请重新 grep） | 现状 | 影响 |
|---|---|---|---|
| 1 | `gv_saas_admin/src/views/tenant/orders.vue:410` | `¥ {{ fmtCents(payableMinor) }}` + `:818 const fmtCents = formatYuan`（`formatYuan` 返回 `'¥ ' + ...`） | 渲染 **"¥ ¥ 123.45"**。**已按当前文件核验仍存在**（md5 `9B2614AAA014EBDB5F14AE8BD9AFC373`） |
| 2 | `gv_saas_mobile/c-end/app.js:853` vs `:809` | `'金额：' + money(it.totalAmount)`（`money` **不除 100**）vs `minorMoney(o.totalAmount, ...)`（除 100） | 同一 DTO 同一字段两种尺度，L853 **显示为实际值的 100 倍** |
| 3 | `gv_saas_mobile/c-end/app.js:200,204,222,227,756,760`（`:248` 账本同理） | `el.textContent = Number(w.availableAmount \|\| 0).toLocaleString('zh-CN')` —— **未除 100** | 钱包余额显示为**分的数值**（如余额 88.80 元显示为 `8880`）。而 `c-end/saas.js:107` 注释与 `:108-111 fenToYuan` 明确契约是「分 → 元」；mock 数据 `data.js:16 balance: 8880` 是**元形状**，恰好掩盖了该 bug |
| 4 | `gv_saas_mobile/c-end/app.js:33`（`dateOffset`）与 `:655` | `date.toISOString().slice(0,10)` | `toISOString()` 是 **UTC**；UTC+8 下本地 00:00–07:59 时「今天+N」标签**早一天** |
| 5 | `gv_saas_mobile/c-end/app.js:314-316` | `new Date(\`${dateStr}T${timeStr}:00\`).toISOString()` | 按**本地**解析、按 **UTC** 序列化 → 提交的时间比预期早 8 小时（预约时间是**功能性**缺陷，需后端契约确认） |
| 6 | `gv_saas_mobile/c-end/app.js:240-242` | `fenToYuanStr(v)` 除 100 —— **0 调用点**（死代码） | C 端因此有 **3 个并存**的金额渲染器（`money` 不除、`minorMoney` 除+Intl、裸 `toLocaleString`）+ 1 个死函数 |

### 7.2 硬编码英文 / 裸枚举进入用户界面

| 位置 | 现状 | 建议 |
|---|---|---|
| `gv_saas_admin/src/views/platform/iam/roles.vue:41,42` | `<el-option label="PLATFORM" .../>` / `label="TENANT"` —— 下拉直接显示英文枚举，而**同一枚举在别处已翻译**（`views/tenant/staff.vue:161` → `{ TENANT: '租户级', STORE: '门店级', ORGANIZATION: '组织级', PLATFORM: '平台级', SELF: '本人' }`） | 复用 `staff.vue` 的映射 |
| `gv_saas_admin/src/views/tenant/ktv-config.vue:219,220,232,233,234` | `label="小时 HOUR"` / `"半小时 HALF_HOUR"` / `"让利消费者 CONSUMER_FAVOR"` / `"向上取整 ROUND_UP"` / `"封顶 FLOOR_BLOCK"` —— **5 处裸枚举拼在中文后** | 中文放 `label`，枚举放 `value` |
| `gv_saas_mobile/c-end/oauth.js:253,255` | `oauthError('IM_SESSION_EXPIRED', 'IM login session has expired')` / `'IM bridge authorization failed'` —— 英文；但 `c-end/auth-errors.js:26,31` 已有对应中文 | 改中文 |
| `gv_saas_mobile/src/b-end/App.vue:82` | `errorDescription.value = e?.code ? (e.message \|\| '请稍后重试…') : '…'` —— **任何带 code 的错误都直接渲染原始 `e.message`**，而这些生产者是英文：`b-end/oauth.js:148 'OAuth state mismatch'`、`:170 'missing pkce verifier'`、`:178/:236 'bind failed'`、`c-end/oauth.js:302/:317 'session not established'` | 改走 code→中文映射（对齐 `c-end/auth-errors.js`） |
| `gv_saas_admin/src/views/platform/tenants.vue:30,31` | `placeholder="zh-CN"` / `"Asia/Shanghai"` | 输入示例，可接受 |

**负向结论（重要）**：两个仓库**没有任何** `ElMessage.*('English…')` / `alert('English…')` / `showToast('English…')` / 英文表头 / 英文按钮。所有 sink 的事实用语都是简体中文 —— **问题在「全量硬编码」与「少量裸枚举/透传 message」，不在 sink 层的中英混排**。

### 7.3 中英混排 / 间距不一致

- **`A380` / `KTV` / `IM` 后是否加空格不统一**：`'A380租户'`（`b-end/App.vue:6`、`c-end/context.test.js:30`）vs `'A380 租户'`（`b-end/preview/oauth.js:6`）；`'KTV 业务'`（`c-end/data.js:4`、`c-end/app.js:256`）vs `'KTV四小时欢唱券'`（`c-end/data.js:110`）；`'门店ID'`/`'员工ID'`（`admin/reports.vue:78,80`）vs `'客户 ID'`/`'流水 ID'`（`admin/ktv-config.vue:124,151,163`）。
- **同一字符串 5 份拷贝**：`'服务端未返回 CSRF token'` 出现在 `admin/api/request.js:34`、`mobile/src/shared/api/request.js:22`、`mobile/src/b-end/oauth.js:72`、`c-end/saas.js:46`、`c-end/oauth.js:79`。同理 `'当前页面不支持安全的 PKCE 授权'`（2 处）、`'GVBridge 未注入'`（2 处）。
- **全角/半角括号混用**：`'储值币(A380币)'`（半角，`constants/payment-methods.js:4`）vs `'2 小时（推荐）'`（全角，`login/index.vue:21`）vs `'应收(元)'`（半角）vs `'包厢单价（元/计费单位）'`（全角，`ktv-config.vue:23`）。

### 7.4 时间格式共 11 种变体（管理端单独 5 种）

| 格式 | 处数 | 示例位置 |
|---|---|---|
| `YYYY-MM-DD HH:mm`（`replace('T',' ').slice(0,16)`） | **10** | `admin/utils/format.js:13`（**「统一」实现**）+ 9 处页面内联/本地重定义 |
| `YYYY-MM-DD HH:mm:ss`（`.slice(0,19)`） | 2 | `admin/views/platform/tenants.vue:53`、`admin/views/tenant/reports.vue:140` |
| Element-Plus `value-format="YYYY-MM-DD"` | 2 | `reports.vue:16`、`shift.vue:119` |
| 手工 `getFullYear/getMonth/getDate` + `padStart` | 1 | `admin/views/tenant/shift.vue:161-163` |
| `toLocaleTimeString('zh-CN',{hour12:false})` → `HH:mm:ss` | 1 | `admin/views/tenant/orders.vue:568` |
| `toLocaleTimeString(...,{hour:'2-digit',minute:'2-digit'})` → `HH:mm` | 1 | `admin/views/tenant/orders.vue:651` |
| `M月D日` | 3 | `c-end/app.js:37,322`、`b-end/views/Reservations.vue:106` |
| `M月D日 HH:mm` | 1 | `c-end/app.js:709-714` |
| `toISOString().slice(0,10)`（UTC） | 2 | `c-end/app.js:33,655` |
| 裸 `toISOString()` | 1 | `c-end/app.js:316` |

**负向结论（好）**：`toLocaleDateString` 全仓 **0**；**12 处** `toLocaleString/toLocaleTimeString` **全部**显式传了 `'zh-CN'`，不存在「未传 locale 的 locale 依赖 API」风险；`dayjs` / `moment` 全仓 **0**（无需引入新依赖）。唯一残余 ICU 依赖：`c-end/app.js:899` 的 `Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'})`（可能渲染为 `¥` 或 `CN¥`）。

**`utils/format.js` 采纳率**：`formatTime` / `formatYuan` / `currencyText` / `promotionTypeText` 已被 `orders.vue` + `api/request.js` 使用；`formatYuanValue`、`resourceStateText` **0 调用点**；`admin` 仍有 **9 处**本地重复定义 `function formatTime`。

### 7.5 前端无任何质量门禁

`gv_saas_admin` / `gv_saas_mobile` **都不存在** `.github/`、`scripts/`、`tools/`、`.husky/`、`eslint.config.*`、`.eslintrc*`、`.prettierrc*`、`.editorconfig`、`.stylelintrc*`，也无 `lint` / `lint:i18n` 脚本。唯一的自动门禁是：

```
gv_saas_admin:  check = "npm test && npm run build && npm audit --omit=dev --audit-level=high"
gv_saas_mobile: check = "npm test && npm run build && npm audit --omit=dev --audit-level=high"
```

**但现有测试事实上是「文案锁」** —— 任何改词都会让它们失败，必须一并更新：
`gv_saas_admin/src/utils/format.test.js:19,20,31,32,42,43`（`'¥ 123.45'`、`'¥ 0.00'`、`'人民币（元）'`、`'整单折扣'`、`'优惠券'`）、`src/utils/ktv-config.test.js:10`（`expect(source).not.toContain('BFF 骨架回显')`）、`gv_saas_mobile/c-end/auth-errors.test.js:13,14,15,26,36,47,48,60`、`c-end/context.test.js:16,29,30`。

---

## 附录 A：可部署服务 × 统一出口 一览表

| 服务 | 端口 | `@RestController` 数 | 统一出口 | `Exception` 兜底 | 兜底记堆栈 | 兜底含 code | `@Slf4j` |
|---|---|---|---|---|---|---|---|
| platform-admin | 4150 | 12 | ✅ | ❌ | — | — | ❌ |
| platform-customer | 4160 | 5 | ✅ | ❌ | — | — | ❌ |
| platform-identity | 4100 | 6 | ✅ | ❌ | — | — | ❌ |
| platform-marketing | 4170 | 4 | ✅ | ❌ | — | — | ❌ |
| platform-order | 4130 | 15 | ✅ | ✅ | ✅ | ❌ | ✅ |
| platform-resource | 4120 | 5 | ✅ | ✅ | ✅ | ✅ | ✅ |
| platform-tenant | 4110 | 9 | ✅ | ✅ | ✅ | ✅ | ✅ |
| common-audit | 4190 | 1 | **❌** | — | — | — | ❌ |
| common-mail | 4210 | 1 | **❌** | — | — | — | ❌ |
| common-media | 3600 | 7 | ✅ | ✅ | ❌ | ❌ | ❌ |
| common-payment | 4140 | 8 | ✅ | ❌ | — | — | ❌ |
| common-payment-channel | 4180 | 2 | ✅ | ❌ | — | — | ❌ |
| common-sms | 4200 | 1 | **❌** | — | — | — | ❌ |
| im-admin | 3400 | 16 | ✅ | ✅（泄露 message） | ❌ | ❌ | ❌ |
| im-conversation | 3300 | 15 | ✅ | ✅（泄露 message） | ❌ | ❌ | ❌ |
| im-message | 3200 | 6 | ✅ | ✅（泄露 message） | ❌ | ❌ | ❌ |
| im-user | 3100 | 18 | ✅ | ✅（泄露 message） | ❌ | ❌ | ❌ |
| group-idaas | 4220 | 3 | ✅ | ❌ | — | — | ❌ |
| gateways/gateway | 3002 | 0（WebFlux） | N/A | — | — | — | ✅（Filter） |
| gateways/im-access-ws | 3001 | 0（WebSocket） | N/A | — | — | — | — |

## 附录 B：审计方法与可复现性

- **静态**：`read` / `grep`（ripgrep）/ `glob` 工具，排除 `target/`；`src/test` 仅用于交叉验证（本报告主结论均取自 `src/main`）。
- **动态**：`kubectl -n gv-im-local exec saas-admin-... -- sh -s` 管道内联脚本，一次性扫全 18 个服务（避免大量 port-forward）；`gateway :30002` 与 `saas-admin :30081` 走 NodePort；管理员会话由 `POST /api/v1/admin/auth/login`（`admin` / `e8280ac0d25d4bc0a1e1`，来自 `platform-admin-service/src/main/resources/db/migration/V2__saa_admin_account_seed.sql`）取得，随后 `GET /api/v1/admin/auth/csrf` + `POST /api/v1/admin/context/select {"contextId":"100:100:100"}`。
- **只读确认**：除登录/选择上下文（会话态，非业务数据变更）与无效请求探针外，未创建/修改/删除任何业务数据；未执行任何写操作型业务接口；未触碰 `k8s/`（该目录在审计开始时已存在他人未提交改动：`k8s/kind/local-cluster.yaml`、`k8s/local/gateway-admin.yaml`、`k8s/local/infrastructure.yaml`、`k8s/local/ingress.yaml` —— 均为其它 agent 的工作，本审计未参与）。
