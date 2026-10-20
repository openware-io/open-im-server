> Status: historical
> Archived: 2026-10-19
> Superseded-by:
> Original-location: docs/renovation/RUNTIME_IDENTIFIER_RENAME.md

# `gv` / `wv` 运行期标识改名（**已全部完成**）

> 背景：工作区旧产品名统一为 `open-*`。**两层均已完成**：
> - **第 1 层 文档与注释**：467 行 / 80 文件；
> - **第 2 层 运行期标识**：138 处 / 32 文件（构建接口、对象存储、客户端持久化键）。
>
> 用户裁决：**构建接口统一改 `OPEN_*`**；**均为开发环境、无生产数据，可直接改**；**客户端持久化键一次性改，无历史数据包袱**；要求**改后系统业务正常运行**。

## 为什么运行期标识要单独一层

这些字符串被**写入并读取**（localStorage、IndexedDB、本地文件、对象存储桶、Dart 编译期常量），与纯文档不同：

- **localStorage / sessionStorage 键**：改名会让**既有用户读到空值**（掉登录、偏好重置）；
- **持久化文件名 / 数据库名**：**旧数据读不到**；
- **对象存储桶名 / 前缀**：**既有对象找不到**；
- **Dart `--dart-define` 变量名**：**构建接口变更**，CI 与本地构建脚本需同步改。

> ⚠️ 本次能**一次性直接改**，唯一前提是用户确认了**无历史数据**。若将来有生产数据，必须改为"**读旧键、写新键**"的兼容迁移。

## 改名映射（已完成）

### A. 构建接口（`GV_` 前缀 → `OPEN_` 前缀）

`OPEN_API_BASE`、`OPEN_WS_URI`、`OPEN_JPUSH_APPKEY`、`OPEN_JPUSH_PRODUCTION`、`OPEN_TRUST_SELF_SIGNED`、`OPEN_ANDROID_JPUSH_ENABLED`、`OPEN_TEST_USERNAME`、`OPEN_TEST_PASSWORD`、`OPEN_TEST_PEER_ID`、`OPEN_TEST_CHAT_TYPE`、`OPEN_TEST_CONVERSATION_NAME`、`OPEN_TEST_ALLOW_SEND`、`OPEN_MEDIA_BASE`、`OPEN_PROTOCOL_VERSION`、`OPEN_RELEASE_CHANNEL`、`OPEN_RELEASE_ARCHITECTURE`、`OPEN_RELEASE_ADMIN_TOKEN`、`OPEN_API_CONTRACT`、`OPEN_ADMIN_PASSWORD`、`OPEN_IM_OAUTH_APP_SECRET`，以及二维码 scheme `OPEN_LOGIN:`、`OPEN_UID:`。

> **自洽性**：这些名字的"声明"（`BUILD.md`/`README.md` 的 `--dart-define=`）与"读取"（Dart 代码）**在同一仓库内**，故同仓同步改即自洽。

### B. 对象存储

`open-media-public`、`open-im-local-private`、`open-im-local-public`。

### C. 客户端持久化键与本地文件

`open-chat_access_token`、`open-chat_user`、`open-chat_locale`、`open-chat_drafts`、`open-chat_clear_cutoffs`、`open-chat_hidden_messages`；本地库 `open-chat.db`；Electron 会话分区 `persist:open-hybrid-*`；上传/备份/图标文件名 `open-chat-media`、`open-chat-backup`、`open-chat-icon`；小程序 SDK 事件 `open-sdk-ready`。

### D. 二维码分享文件名（`open-chat_qr_` 前缀）

仅影响**后续新保存**的文件名，不读旧值，无风险。

## 有意保留的旧名（不是遗漏）

以下**属于历史事实或外部标识**，不应改名，扫描时应视为预期：

| 位置 | 内容 | 原因 |
| --- | --- | --- |
| `docs/audit-2026-09-17-exceptions-and-i18n.md` | 当时实测的循环名称、集群名 | **历史审计快照**，改了等于篡改证据 |
| `docs/currency-scope-inventory-2026-09-18.md` | 当时的前端包名 | 同上（日期化盘点） |
| `docs/renovation/*` 中引用的旧名 | 如 `gv_core`、`gv_ui` | 描述历史方案；**已实现的方案会迁入实现/规范文档** |
| 密钥默认值示例（如 AES dev secret） | 旧字符串字面量 | **安全审计对象**，改名会掩盖证据 |

## 改后校验（本次实际执行）

| 检查 | 结果 |
| --- | --- |
| 旧 token 全工作区残留（排除产物目录与上述有意保留项） | **0** |
| 改名两端一致性（如 QR scheme 发送/解析） | 一致（对端在 App/Web，不在此仓） |
| 二进制产物误报 | 已识别为 APK 内容，**未改动** |
| 生成/缓存目录 | **未触碰**（含回滚过的 Flutter iOS 生成文件） |

> 前端四仓（`open-chat-desktop`/`open-chat-admin`/`open-saas-admin`/`open-saas-mobile`）**本地无 `node_modules`**，无法离线跑 `npm test`/`npm run build`。故本次以"**两端一致性 + 旧 token 清零 + 生成目录未触碰**"作为验证手段；**发布前请在具备依赖的环境重跑各仓 `npm test` 与 `npm run build`**。