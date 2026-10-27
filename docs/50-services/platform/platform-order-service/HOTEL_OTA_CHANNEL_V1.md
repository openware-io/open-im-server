# 酒店 OTA channel v1 适配器

> 状态：二期已实现（最小但真实的 OTA 预订适配器）
> 入口：`/api/v1/open/hotel/v1/**`（网关不剥前缀）
> 契约：[hotel-channel-v1.yaml](../../../30-reference/contracts/openapi/hotel-channel-v1.yaml)
> 表：`htl_channel_booking`（Flyway `V30__htl_channel_booking.sql`）

## 1. 范围

| 接口 | 状态 | 说明 |
| --- | --- | --- |
| `POST /v1/bookings` | 已实现 | 创建 OTA 预订，`externalRef` 幂等；新建 201、同内容回放 200 |
| `GET /v1/bookings/{externalRef}` | 已实现 | 查询，返回 `htl_booking.status` 实时状态；不存在 404 |
| `POST /v1/bookings/{externalRef}/cancel` | 已实现 | 取消幂等；复取消/前台已取消返回 200，状态机不允许时 409 |
| `POST /v1/ari` | 契约占位 | 固定 `202 Accepted`，逐项 `accepted=false` + `ARI_NOT_IMPLEMENTED`，**不写数据** |
| `POST /v1/bookings/{externalRef}/modify` | 不在 v1 | 未实现，契约中已删除 |

创建/查询/取消全部复用既有 `HotelBookingApplicationService`：报价、房量原子占用/确认/释放、
`ord_order` 应收、取消政策都由酒店域执行；适配器**不直接写 `htl_booking`**。

## 2. 鉴权（channel HMAC-SHA256）

请求头：

| Header | 必填 | 说明 |
| --- | --- | --- |
| `X-Channel-Code` | 是 | 渠道编码；未配置的 channel 直接 401 |
| `X-Channel-Timestamp` | 是 | epoch 毫秒；允许 ±5 分钟（`hotel.channel.max-clock-skew-ms`） |
| `X-Channel-Signature` | 是 | hex(HMAC-SHA256(secret, canonical))，小写 64 位 |

canonical request（顺序/分隔符不可变）：

```text
channelCode \n
HTTP-METHOD \n
requestPath \n      # request.getRequestURI()，如 /api/v1/open/hotel/v1/bookings
queryString \n      # 无 query 为空串
timestamp \n        # 十进制毫秒
sha256Hex(body)     # 空体为 sha256("")
```

- 失败一律 `401 {"code":"CHANNEL_UNAUTHORIZED","message":"渠道签名校验失败"}`，由
  `HotelChannelAuthenticationFilter` 在进入业务前返回；没有「签名失败仍放行」的开关。
- 网关对本入口**不 StripPrefix**，所以下游 `requestURI` 与外部签名路径完全一致。
- 写接口天然幂等（创建按 externalRef 回放、取消按最终状态回放），v1 只做时间窗防重放，不做 nonce 表。
- 与 `InternalServiceSignature` 共用 SHA-256 内容哈希实现，canonical 因渠道语义独立。

## 3. 配置与本地 Kind 注入

`platform-order-service/src/main/resources/application.yml`：

```yaml
hotel:
  channel:
    max-clock-skew-ms: ${HOTEL_CHANNEL_MAX_CLOCK_SKEW_MS:300000}
    channels:
      sandbox:
        tenant-id: ${HOTEL_CHANNEL_SANDBOX_TENANT_ID:0}
        secret: ${HOTEL_CHANNEL_SANDBOX_SECRET:}
```

| 配置 | env | 说明 |
| --- | --- | --- |
| `hotel.channel.max-clock-skew-ms` | `HOTEL_CHANNEL_MAX_CLOCK_SKEW_MS` | 时钟窗口，默认 300000 |
| `...channels.sandbox.tenant-id` | `HOTEL_CHANNEL_SANDBOX_TENANT_ID` | 渠道绑定的租户 |
| `...channels.sandbox.secret` | `HOTEL_CHANNEL_SANDBOX_SECRET` | HMAC secret，**≥32 字符** |

本地 Kind 在 `k8s/local/saas.yaml` 的 `platform-order-service` Deployment 注入：

```yaml
- { name: HOTEL_CHANNEL_SANDBOX_TENANT_ID, value: '100' }
- { name: HOTEL_CHANNEL_SANDBOX_SECRET, value: local-kind-ota-channel-secret-0123456789abcdef }
```

- 仅本地 Kind / E2E 使用，生产不得复用该值；生产从 Secret/环境变量注入。
- secret 缺失或不足 32 字符 → 该渠道视为未配置 → 401（fail-closed）。
- v1 一个渠道凭证绑定一个租户；没有「请求里带 tenantId」的旁路。

## 4. 门店权威解析（外部请求不带 org/business）

1. 鉴权通过后，过滤器只写入 `tenantId`（来自渠道凭证）的临时上下文；
2. `hotelRef` = `htl_hotel.id`，按租户过滤读取酒店，要求存在、`ACTIVE`、有 `store_id`；
3. 用 `htl_hotel.store_id` 调 `HotelStoreScopeResolver.requireHotelScope`，从 `tnt_store` 取权威
   `organization_id` / `business_type`，要求门店启用且业态为 `HOTEL`；
4. 收敛上下文后调 `HotelBookingApplicationService`。请求体里的 `tenantId/organizationId/storeId/
   businessType` 字段被忽略（集成测试显式用敌对字段验证）。

适配器在服务端持有的权限仅为 `hotel.booking.view` + `hotel.booking.create`（
`HotelChannelContexts.ADAPTER_PERMISSIONS`），仍受 `PermissionGuard` 约束。

## 5. 幂等与状态语义

- `externalRef` 是幂等键，唯一约束 `(channel_code, external_ref)`；
- 创建事务内先插映射占位（`PENDING`）再创建预订，任何一步失败整体回滚：
  不会出现「渠道单占位但未占房」或「已占房但无渠道映射」；
- 规范化指纹 `request_hash` 覆盖：channelCode、externalRef、hotelRef、roomTypeRef、ratePlanRef、
  checkIn/checkOut、rooms、guests、住客姓名/手机号、amountMinor、currency、paymentResponsibility；
- 同 externalRef 同内容 → 200 回放；不同内容 → 409 `CHANNEL_BOOKING_REQUEST_MISMATCH`；
- 并发唯一键冲突：失败事务整体回滚，控制器按 externalRef 回放 200；
- `htl_channel_booking.status` 是 `htl_booking.status` 投影；响应状态读实时值；
- 状态沿用 `htl_booking` 词表：`PENDING_CONFIRM / CONFIRMED / CHECKED_IN / CHECKED_OUT / CANCELLED / NO_SHOW`
  （默认 PREPAY 策略创建后 `PENDING_CONFIRM`；`PAY_AT_HOTEL` 策略创建即 `CONFIRMED`）；
- 金额/币种必须等于服务端报价，否则 422：`CHANNEL_AMOUNT_MISMATCH` / `CHANNEL_CURRENCY_MISMATCH`；
- 房量不足 409 `ROOM_AVAILABILITY_UNAVAILABLE`；
- 取消释放 `HELD`/`SOLD` 房量并回写取消政策金额（`cancel_fee_minor` / `refund_minor`）。

## 6. 数据库与 E2E 断言 SQL

`htl_channel_booking` 关键列：`tenant_id, store_id, channel_code, external_ref, booking_id, booking_no,
request_hash, status, last_action, cancel_reason, audit_json, created_at, updated_at`。

E2E（task-9）建议断言：

```sql
-- 1) 内部预订由酒店域创建（源标记 OTA_<CHANNEL>，组织/业态来自门店权威数据）
SELECT id, tenant_id, store_id, organization_id, business_type, booking_no, status,
       rooms, amount_minor, currency, source, order_id
FROM htl_booking
WHERE tenant_id = 100 AND booking_no = '<bookingNo>';

-- 2) 渠道映射存在且唯一（重复 externalRef / 回放后仍为 1 行）
SELECT channel_code, external_ref, booking_id, booking_no, status, last_action, request_hash
FROM htl_channel_booking
WHERE channel_code = 'sandbox' AND external_ref = '<externalRef>';

SELECT COUNT(*) FROM htl_channel_booking
WHERE channel_code = 'sandbox' AND external_ref = '<externalRef>';

-- 3) 创建后房量占用 / 取消后释放（示例日期需在 V26 种子窗口内）
SELECT room_type_id, stay_date, total, sold, held
FROM htl_room_availability
WHERE tenant_id = 100 AND store_id = 102 AND room_type_id = 1
  AND stay_date IN ('2026-10-29', '2026-10-30');

-- 4) 应收订单（创建预订时必须生成；trade_status=DRAFT，重复回放不新增）
SELECT o.id, o.order_no, o.trade_status, o.total_amount, o.idempotency_key
FROM ord_order o
JOIN htl_booking b ON b.tenant_id = o.tenant_id AND b.order_id = o.id
WHERE b.booking_no = '<bookingNo>';

-- 5) 取消幂等的审计轨迹
SELECT status, last_action, cancel_reason, audit_json
FROM htl_channel_booking
WHERE channel_code = 'sandbox' AND external_ref = '<externalRef>';
```

## 7. 真实请求示例（本地 Kind）

Demo 种子（V26）：tenant 100 / store 102 / hotel 1 / roomType 1(DLX 89900 CNY/night) / ratePlan 1；
房量种子窗口 2026-10-27 ~ 2026-11-26（8 间/天）。E2E 日期请落在该窗口内，否则先补房量种子。

```powershell
$Gateway = 'http://127.0.0.1:30080'
$Channel = 'sandbox'
$Secret  = 'local-kind-ota-channel-secret-0123456789abcdef'

function New-OtaChannelHeaders([string]$Method, [string]$Path, [byte[]]$Body) {
  $ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  $sha = [System.Security.Cryptography.SHA256]::Create()
  $bodyHash = -join ($sha.ComputeHash($Body) | ForEach-Object { $_.ToString('x2') })
  $canonical = "$Channel`n$Method`n$Path`n`n$ts`n$bodyHash"
  $hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
  $sig = -join ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)) | ForEach-Object { $_.ToString('x2') })
  return @{ 'X-Channel-Code' = $Channel; 'X-Channel-Timestamp' = "$ts"; 'X-Channel-Signature' = $sig }
}

# 1) 创建（201；重复同内容 200 回放）
$bodyObj = @{
  externalRef = 'OTA-E2E-20261028-0001'
  hotelRef = '1'; roomTypeRef = '1'; ratePlanRef = '1'
  checkIn = '2026-10-29'; checkOut = '2026-10-31'
  rooms = 1; guests = 2
  guest = @{ name = 'OTA Guest'; phone = '13800000000' }
  amountMinor = 179800; currency = 'CNY'; paymentResponsibility = 'OTA_COLLECT'
}
$body = ($bodyObj | ConvertTo-Json -Depth 5 -Compress)
$bytes = [Text.Encoding]::UTF8.GetBytes($body)
$createPath = '/api/v1/open/hotel/v1/bookings'
$resp = Invoke-WebRequest -Uri "$Gateway$createPath" -Method Post `
  -Headers (New-OtaChannelHeaders 'POST' $createPath $bytes) `
  -ContentType 'application/json' -Body $bytes
$booking = $resp.Content | ConvertFrom-Json

# 2) 查询
$getPath = "/api/v1/open/hotel/v1/bookings/$($bodyObj.externalRef)"
Invoke-WebRequest -Uri "$Gateway$getPath" -Method Get `
  -Headers (New-OtaChannelHeaders 'GET' $getPath ([byte[]]@()))

# 3) 取消（200；重复取消 200）
$cancelPath = "$getPath/cancel"
$cancelBody = '{"reason":"E2E cancel"}'
$cancelBytes = [Text.Encoding]::UTF8.GetBytes($cancelBody)
Invoke-WebRequest -Uri "$Gateway$cancelPath" -Method Post `
  -Headers (New-OtaChannelHeaders 'POST' $cancelPath $cancelBytes) `
  -ContentType 'application/json' -Body $cancelBytes

# 4) 401 反例：错误签名
Invoke-WebRequest -Uri "$Gateway$createPath" -Method Post `
  -Headers @{ 'X-Channel-Code'=$Channel; 'X-Channel-Timestamp'=[string][DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();
              'X-Channel-Signature'=('0' * 64) } `
  -ContentType 'application/json' -Body $bytes -SkipHttpErrorCheck
```

> 注意：签名必须对**实际发送的字节**计算。PowerShell 里先 `[Text.Encoding]::UTF8.GetBytes($body)`
> 再同时用于签名与 `-Body`，避免字符串编码差异导致 401。

## 8. 测试

```powershell
cd open-im-server
mvn -pl platform-services/order/platform-order-service "-Dtest=HotelChannel*Test" test
```

当前 15 个测试全绿：

- `HotelChannelBookingIntegrationTest`（8，H2+Flyway+MyBatis+真实验签过滤器+真实酒店域）：
  401 不落库、201 创建并双表断言、externalRef 回放 200 不重复占房、同单号不同内容 409、
  库存不足 409 且无渠道残留、查询 200/404、取消幂等并释放房量、ARI 202 no-op；
- `HotelChannelAuthenticationFilterTest`（4）：有效签名、错签名/错 body、未知渠道、过期时间戳、非渠道路径放行；
- `HotelChannelSignatureTest`（3）：canonical 字段顺序、签名稳定且对 body/path/channel 敏感、格式校验。

## 9. E2E 待跑项（task-9）

本任务**未部署 Kind**，真实 E2E 等二期最终 Kind 发布后执行：

- [ ] task-9 在 `scripts/verify/e2e-c-end-ota-channel.ps1` 中按第 7 节签名调用创建/查询/取消；
- [ ] 断言 201/200/200、坏签名 401、重复 externalRef 仍只有 1 行映射、库存不足 409；
- [ ] 按第 6 节 SQL 查 `htl_booking` / `htl_channel_booking` / `htl_room_availability` / `ord_order`；
- [ ] 取消后房量 `held/sold` 归零、`htl_booking.status=CANCELLED`、re-cancel 200；
- [ ] 记录实际 Kind 版本、镜像 tag 与执行时间到发布记录。

## 10. 已知限制

- ARI（房量/价格/限制推送）v1 未实现，仅 202 no-op；渠道当前只能按已发布房价创建预订。
- 不支持 modify；改期请取消后重建。
- 一个渠道凭证绑定一个租户；多租户渠道映射留待后续版本。
- `amountMinor` 必须等于服务端报价（严格校验，防渠道改价）；渠道需先获得房价。
- `paymentResponsibility` 仅信息字段，实际支付/押金模式以 `htl_policy.payment_mode` 为准。
