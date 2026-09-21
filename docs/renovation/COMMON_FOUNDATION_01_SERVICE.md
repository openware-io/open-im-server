# 通用基础能力：发号器 + 字段加密（简易实现）

> **变更记录（v1）**：首发创建。为「分布式会员号」「姓名/手机密文落库」提供 common 服务的简易实现，满足规范内最小要求，后续在其基础上迭代升级。

## 0. 方案集声明

| 项目 | 内容 |
| --- | --- |
| 方案集 | `COMMON_FOUNDATION` |
| 顺序号 | `01` |
| 实施边界 | `SERVICE`（common 通用能力 + 接入点） |
| 目标工程 | `sdk/common`（`SnowflakeIdGenerator`、`AesGcmCipher`）、`platform-customer-service`（会员号/密文接入） |

## 1. 背景与问题

| # | 问题 | 现状（改造前） | 风险 |
| --- | --- | --- | --- |
| P1 | 会员号不唯一 | `"M" + System.currentTimeMillis()` | 同毫秒撞号；多实例部署不保证全局唯一，唯一键 `uk_cst_member_tenant_no` 会冲突 |
| P2 | 姓名/手机明文落库 | `name_cipher`/`phone_cipher` 存明文 | 个人隐私（PII）不合规，泄露即暴露真实手机号/姓名 |
| P3 | 手机号检索需不可逆 | 原 `phone_digest` 存明文 | 明文可被直接反查，应只存不可逆摘要用于检索/去重 |

## 2. 方案

### 2.1 发号器（分布式会员号）

- 复用 `com.gvchat.common.util.SnowflakeIdGenerator`（已存在于 common，Twitter Snowflake 算法，输出无符号十进制字符串）。
- 会员号 = `"M" + snowflakeIdGenerator.nextId()`，全局唯一、单调递增、含时间与工作节点信息。
- 接入：`MemberApplicationService` 注入 `SnowflakeIdGenerator`；启动类 `@Import(SnowflakeIdGenerator.class)`。

### 2.2 字段加密（姓名/手机密文）

- 新增 `com.gvchat.common.crypto.AesGcmCipher`：AES-256-GCM 加解密。
- 密钥：由配置 `app.crypto.aes-secret` 经 SHA-256 派生 32 字节 AES-256 密钥（对配置长度不敏感，避免 16/24/32 字节限制）。
- 密文格式：`v1:base64(iv(12B) || ciphertext+tag)`，带版本前缀，支持将来轮换/换算法。
- 接入：`MemberApplicationService` 注入 `AesGcmCipher`，`name`→`name_cipher`、`phone`→`phone_cipher`；启动类 `@Import(AesGcmCipher.class)`。

### 2.3 手机号摘要（检索）

- `phone_digest` = SHA-256(phone)（十六进制），仅用于同租户内按手机号检索/去重，不可逆。
- 已在 `MemberApplicationService#sha256Hex` 实现。

## 3. 简化点与局限（务必知悉）

| 项 | 简易实现 | 局限 | 升级方向 |
| --- | --- | --- | --- |
| 发号器工作节点 | 构造时 `Math.random()` 随机取 10 位 workerId | 多实例可能撞 workerId（概率低但存在）；无中心化分配 | 工作节点 ID 由配置/Redis/注册中心分配 |
| 发号器时钟回拨 | 未处理 | 系统时钟回拨会重复发号 | 检测时钟回拨并自旋/报错（或换 Leaf/号段发号） |
| 加密密钥 | 配置 `app.crypto.aes-secret` 经 SHA-256 派生 | 密钥为静态配置、无托管/轮换、单密钥全字段 | 接入 KMS/密钥服务，按字段分级密钥 + 轮换（密文版本前缀已预留） |
| 加密算法 | AES-256-GCM，随机 IV | 无 AAD（附加认证数据）绑定字段/租户 | 加密时绑定 `tenant_id`/字段名作为 AAD 防串改 |
| 密钥默认值 | `gv-aes-dev-secret-change-me` | 生产必须覆盖，否则等于未加密 | 生产强制注入、缺失即启动失败 |

## 4. 升级路径（在简易实现上迭代）

1. 发号器：workerId 由配置注入 → 时钟回拨保护 → 可选替换为号段/Leaf 方案（对上层透明）。
2. 加密：`AesGcmCipher` 保持 `encrypt/decrypt` 签名不变，内部切换为 KMS 密钥 + 轮换；按字段/租户绑定 AAD。
3. 密文版本前缀 `v1:` 已预留，将来换密钥/算法时按版本解旧、写新。

## 5. 变更清单

- [x] `sdk/common` 新增 `AesGcmCipher`。
- [x] `platform-customer-service` `MemberApplicationService` 接入雪花发号器 + AES-GCM 加密。
- [x] `PlatformCustomerApplication` `@Import` 发号器/加密组件。
- [ ] 生产环境注入 `app.crypto.aes-secret`（缺失即失败，替换 dev 默认值）。
