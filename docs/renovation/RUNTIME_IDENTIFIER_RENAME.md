# `gv` / `wv` 运行期标识清单（待决定，未改名）

> 背景：工作区旧产品名统一为 `open-*`。**文档与注释（第 1 层）已完成改名**（467 行 / 80 文件）。
> **本清单是第 2 层：运行期标识**——改名会影响**已持久化的用户数据 / 对象存储 / 构建接口**，故单独列出待决定。

## 为什么不能与文档一起改

这些字符串被**写入并读取**（localStorage、IndexedDB、本地文件、对象存储桶、Dart 编译期常量）。改了之后：

- **localStorage / sessionStorage 键**：既有用户的**登录态与偏好丢失**（读到空值）；
- **持久化文件名 / 数据库名**：**旧数据读不到**（需迁移或兼容读）；
- **对象存储桶名 / 前缀**：**既有对象找不到**；
- **Dart `--dart-define` 变量名**：**构建接口变更**，CI 与本地构建脚本需同步。

## 清单（按风险分组）

### A. 客户端持久化键（改名 = 用户数据丢失）

| 仓库 | 位置 | 标识 |
| --- | --- | --- |
| `open-chat-desktop` | `src/renderer/src/stores/auth.ts` | `TOKEN_KEY='gv_chat_access_token'`、`USER_KEY='gv_chat_user'` |
| `open-chat-desktop` | `src/renderer/src/i18n/index.ts` | `LOCALE_STORAGE_KEY='gv_chat_locale'` |
| `open-chat-desktop` | `src/renderer/src/stores/chat.ts` | `gv_chat_drafts`、`gv_chat_clear_cutoffs`、`gv_chat_hidden_messages` 等 6 处 |
| `open-chat-desktop` | `src/main/hybrid.ts` | `gv_chat_access_token`、`persist:gv-hybrid-${policy.appId}` |
| `open-chat-desktop` | `src/main/index.ts` | 本地库文件名 `gv-chat.db` |
| `open-chat-desktop` | `src/main/ipc.ts` | 上传文件名默认值 `wv-chat-media` |
| `open-chat-app` | `lib/models/chat_backup.dart` | 备份格式标识 `wv-chat-backup` |
| `open-chat-app` | `lib/screens/chat_backup_screen.dart` | 备份文件名 `wv-chat-backup-$uid-$stamp.json` |
| `open-chat-app` | `lib/screens/mini_program/...bootstrap.dart` | 小程序 SDK 事件名 `gv-sdk-ready` |

> **建议**：改键名时**同时读旧键**（`newKey ?? oldKey`）并回写，实现无感迁移；否则接受"用户重新登录 / 偏好重置"。

### B. 构建期接口（改名 = 构建脚本与 CI 同步改）

| 仓库 | 变量 |
| --- | --- |
| `open-chat-app` | `GV_API_BASE`、`GV_WS_URI`、`GV_JPUSH_APPKEY`、`GV_JPUSH_PRODUCTION`、`GV_TRUST_SELF_SIGNED`、`GV_ANDROID_JPUSH_ENABLED`、`GV_TEST_USERNAME`、`GV_TEST_PASSWORD`、`GV_MEDIA_BASE`、`GV_PROTOCOL_VERSION`、`GV_CORE` |

> **建议**：**保留 `GV_` 前缀**或统一改为 `OPEN_`；若改，需同步 `BUILD.md`、CI 流水线与本地脚本。**注意**：`--dart-define` 是**编译期**常量，改名不影响已发布包，只影响后续构建。

### C. 后端对象存储（改名 = 既有对象失联）

| 位置 | 标识 | 说明 |
| --- | --- | --- |
| `open-im-server/.env` | `MEDIA_PRIVATE_BUCKET=gv-im-local-private` | 本地私有桶默认名 |
| 后端 media 配置 | `gv-media-public` | 公共桶/前缀（见 `open-saas-admin/src/api/media.test.js`） |

> **建议**：**本地桶**可直接改（重建即清空）；**生产桶**改名需**对象迁移或双写兼容**，建议不改或走迁移方案。

### D. 已在本轮**有意**改掉的运行期标识（1 处）

| 仓库 | 位置 | 改动 | 风险 |
| --- | --- | --- | --- |
| `open-chat-app` | `lib/screens/profile_screen.dart:249` | 二维码保存到相册的**文件名** `wv_chat_qr_*` → `open-chat_qr_*` | **无**（仅后续新文件用新名，不读旧值） |

## 建议处置顺序

1. **D 已完成**（无风险）。
2. **B 构建接口**：统一为 `OPEN_*` 或保留 `GV_*`——**需你定**，改则同步 CI。
3. **C 本地桶**：可随清库重建一并改（**需你确认**是否也改生产桶）。
4. **A 客户端持久化键**：**最后做**，并实现"读旧写新"兼容，避免用户掉登录。
