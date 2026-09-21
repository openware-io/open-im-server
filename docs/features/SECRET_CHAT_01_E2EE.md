# 私密聊天与 E2EE 服务端设计（SECRET_CHAT_01_E2EE）

> 文档定位：私密聊天（Secret Chat）+ E2EE 能力在服务端的工程沉淀文档。核心原则：**传输加密 ≠ E2EE；删除/撤回 = 同步删除动作**（产品语义见 [会话空间与隐私边界](../business/CONVERSATION_TYPES_AND_PRIVACY.md)）。实现遵守 [10 DDD 服务工程规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) 与 [ENGINEERING_RULES](../ENGINEERING_RULES.md)。

## 1. 能力总览

| 能力 | 承载服务 | 状态 |
|------|---------|------|
| 私密会话（握手状态机/安全码/销毁策略） | im-conversation-service | ✅ 已部署 |
| 设备身份（DeviceKey 注册/续期/恢复） | im-user-service | ✅ 已部署 |
| 密文存储（SecretMessage，服务端不解密） | im-message-service | ✅ 已部署 |
| 定时销毁引擎（已读后计时 + 周期扫描） | im-message-service | ✅ 已部署 |
| 客户端 E2EE 加解密（X25519 密钥协商 + AES-GCM） | gv_chat_app（前端） | ✅ 已实现（生产双设备链路验证通过） |

## 2. 私密会话域（im-conversation-service）

### 领域模型（`domain.secretchat`）

- `SecretChat`：一对一 E2EE 会话聚合根。`userA/userB` 规范化（小号在前）并唯一约束，同一对用户只能有一个会话。
- 握手状态机：`handshake/pending -> ready`；双方通过 `submitHandshake` 提交公钥，齐备后进入 `ready` 并计算安全码指纹。
- 安全码：`computeSafeCode` 对 `(publicKeyA + ":" + publicKeyB)` 做 SHA-256，取前 8 字节十六进制（展示用指纹；真加密后应由客户端本地计算并与服务端比对）。
- 销毁策略：`off/30s/5m/1h/1d`，白名单校验，参与者可设置。

### 接口

- 客户端：`POST /secret-chats`、`GET /secret-chats/mine`、`GET /secret-chats/{id}`、`POST /secret-chats/{id}/handshake`、`POST /secret-chats/{id}/destroy-policy`（网关 `/api/v1/secret-chats/**`）。
- 内部：`GET /internal/secret-chats/{id}/destroy-policy` → `{destroyPolicy}`，供消息服务定时销毁引擎读取；会话不存在或调用失败按 `off` 兜底（宁可保留不误删）。

### 数据模型（Flyway）

- `V4__init_secret_chat_schema.sql`：`secret_chat` 表（user_a/user_b + 唯一约束 + 状态 + 安全码 + 销毁策略）。
- `V5__upgrade_secret_chat_for_e2ee.sql`：补齐 E2EE 握手字段（公钥 + handshakeState）。
- `V6__fix_channel_secret_chat_schema_conventions.sql`：表名规范化 + 审计字段 + unsigned 主键。

## 3. 设备身份域（im-user-service）

- `DeviceKey`：客户端生成密钥对并登记公钥与设备指纹；`register` 建立、`renew` 轮换、`restore` 供新设备恢复。
- 接口：`POST /device-keys`、`GET /device-keys`（网关 `/api/v1/device-keys/**`）。
- 迁移：`V3__init_user_device_key_schema.sql`。

## 4. 密文存储域（im-message-service）

- `SecretMessage`：仅存储密文与元数据（`secretChatId/msgId/fromUserId/ciphertext/seq/status/destroyAt`），服务端不解密。
- 接口：`POST /secret-messages`、`GET /secret-messages?secretChatId=&afterSeq=&limit=`（网关 `/api/v1/secret-messages/**`）。
- 迁移：`V3__init_msg_secret_message_schema.sql`（`msg_secret_message`，含 `idx_msg_secret_message_destroy_at` 销毁扫描索引）。
- 分层：`domain.secretmessage`（模型/仓储/端口）+ `infra.persistence.secretmessage`（PO/Mapper/Adapter），`SecretMessageApplicationService` 编排。

## 5. 定时销毁引擎

### 已读后计时

- `SecretMessageApplicationService.list(viewerId, secretChatId, afterSeq, limit)`（接收方已读拉取）：
  - 通过 `SecretChatPolicyPort` 读取会话销毁策略（内部接口，失败兜底 `off`）。
  - 对 `active` 且 `destroyAt == null` 且 `fromUserId != viewerId`（接收方已读）的消息，设置 `destroyAt = now + 策略时长`。
  - 发送方拉取自己的消息不触发计时；`scheduleDestroy` 保持最早截止（重复已读不可推迟）。

### 周期扫描销毁

- `SecretMessageDestroyScheduler`：`@Scheduled(fixedDelayString = "${im.message.secret.destroy-scan-delay-ms:5000}")`，每 5 秒调用 `destroyExpired(now, 200)`。
- `SecretMessageRepository.findExpired(now, limit)`：扫描 `status='active' AND destroy_at <= now`（按销毁时间升序，每批最多 200 条）。
- 命中消息标记 `status='destroyed'`；双端拉取均可见该状态（同步删除动作），不再参与会话同步。

### 跨服务安全

- 内部接口走既有 HMAC 签名鉴权；`/internal/secret-chats/` 已登记至 `InternalServiceAuthenticationInterceptor`、`InternalServiceAuthenticationFilter`、`SecurityConfig` 三处白名单。
- 策略读取失败按 `off` 兜底，调度器异常仅 warn 不影响主流程。

## 6. 测试

- `SecretChatApplicationServiceTest`：创建/握手/获取/列表/销毁策略 5 用例。
- `SecretMessageApplicationServiceTest`：发布/接收方计时/发送方不计时/off 不销毁/到期销毁/最早截止保持 6 用例。
- 生产回归：注册双用户 → 创建私密会话 → 设 30s 策略 → 发密文 → 接收方拉取（destroyAt=now+30s）→ 36s 后拉取 `status=destroyed`；off 策略消息保持 active（详见团队交付报告）。
- 客户端：`e2ee_crypto_test` 5 用例（ECDH 双方一致/AES-GCM 往返/错钥拒绝/安全码格式/密钥对）；`test/integration/e2ee_e2e_probe_test.dart`（tag=integration）生产双设备全链路：共享密钥一致、本地安全码与服务端核验一致、加密发送→解密明文逐字一致。

## 7. 遗留与后续

- destroyed 消息保留密文行仅标记状态；如需彻底清除密文可增加每日清理任务。
- 安全码：客户端已按服务端算法本地计算公钥指纹并与服务端核验一致；后续可考虑移除服务端安全码字段，完全以客户端指纹为权威。
