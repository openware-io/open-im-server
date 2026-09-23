# 客户端发版 Skill（Android 直装 APK）

> 本文档仅适用于 **Android 直装 APK**。Windows 桌面端 EXE 发版见 [`RELEASE_RUNBOOK.md`](../RELEASE_RUNBOOK.md) 第 5.6 节；Windows 同样必须在后台创建并提交客户端发布记录。
> 对 Android APK 而言，本文档是唯一、可执行的发版操作手册。任何 AI 或人类执行 Android 发版时，先读本文档，再直接运行快捷脚本，不要自行推断打包命令或版本规则。
> 打包命令以 [gv_chat_app/BUILD.md](../../../gv_chat_app/BUILD.md) 为权威来源；本文档负责「一步步怎么做」并引用脚本。
> 治理规则（版本命名、灰度、审计、安全门禁）见 [14_CLIENT_RELEASE_GOVERNANCE.md](14_CLIENT_RELEASE_GOVERNANCE.md)。

---

## 0. 一句话执行

```powershell
cd D:\projects\cnb\gv_chat_app
.\tools\release.ps1 -ReleaseNotes "本次更新说明"
```

脚本自动完成：读版本 → PATCH+1 / buildNumber+1 → 打正式 APK → 上传对象存储 → 创建发布记录 → 提交发布 → 验收。加 `-DryRun` 只看不执行，加 `-SkipPublish` 只打包含不上线。

---

## 1. 必读：反复踩坑的五条铁律

1. **正式环境打 APK，不是 AAB。** AAB 仅用于 Google Play 商店上架。直装发布永远是 APK。
   - 正确：`flutter build apk --release`（或 `tools\build.ps1 android prod apk`）
   - 错误：`flutter build appbundle --release`（那是商店包，官网下载页/App 更新用不了）
2. **正式环境用 `prod`，不是测试包。** `prod` 构建才查 `stable` 渠道、才开 JPush 生产推送。测试包（dev/test）永远不会被正式用户更新到。
3. **版本号默认 PATCH+1、buildNumber+1。** 团队历史全是 PATCH 递增（1.0.13→…→1.0.24→1.0.25），禁止为 bug 修复抬 MINOR。buildNumber 必须严格递增。
4. **正式环境 APK 必须使用正式签名密钥签名**（原短期例外已于 2026-09-10 取消）。密钥库 `xchpro.jks` + `android/key.properties`（`storePassword`/`keyPassword`/`keyAlias`/`storeFile`，均已被 `.gitignore` 排除，不得入库）；缺 `key.properties` 时 `flutter build apk --release` 会退化为 debug 签名，**禁止发布该退化产物**。打包后必须验签：`apksigner verify --print-certs build\app\outputs\flutter-apk\app-release.apk`，签名证书 SHA-256 应为 `27cdc427d6152d0db97b78819d1ac5ba732300e5cc48bdb8820a9ce909fee10d`（别名 `xch`）。
5. **发布记录必须建在客户端实际查询的渠道上。** `prod` 构建查 `stable`；官网下载页固定查 `stable`。渠道建错，App 永远查不到更新。

---

## 2. 权威常量（直接抄，不要改）

| 项 | 值 |
| --- | --- |
| Android 极光 AppKey | `YOUR_JPUSH_APPKEY` |
| 发布渠道 channel | `stable` |
| 平台 platform | `android` |
| 制品 packageType | `apk`（architecture=`universal`） |
| 协议快照 | `protocolVersion=v1`, `minimumServerCapabilityVersion=1` |
| 管理端 API 基址 | `https://api.dev.example.com/api/v1` |
| 官网下载域名 | `https://example.com` |
| 发布产物本地路径 | `build\app\outputs\flutter-apk\app-release.apk` |

---

## 3. 完整发版流程（脚本等价步骤）

### 3.1 bump 版本

`pubspec.yaml` 的 `version: X.Y.Z+N` 行，默认 `PATCH+1`、`N+1`（如 `1.0.25+42 → 1.0.26+43`）。脚本自动完成，UTF-8 无 BOM 写回。

### 3.2 打正式 APK

```powershell
cd D:\projects\cnb\gv_chat_app
.\tools\build.ps1 android prod apk -JPushAppKey "YOUR_JPUSH_APPKEY"
```

产物：`build\app\outputs\flutter-apk\app-release.apk`。

> 国内网络若 Gradle 拉 Maven TLS 失败：`android/build.gradle` 已配置阿里云镜像优先，无需改。
> 打包前若远端分支被推进：先 `git pull --rebase` 再构建，否则 APK 缺别人已合并的代码。

### 3.3 上传制品（对象存储，自动回填三项）

后台「客户端发布」页「选择 APK 并上传」，或直接调管理端接口：

`POST /admin/client-releases/artifacts/upload`（multipart：`file`=APK 文件、`platform`=`android`），返回：

```json
{ "downloadUrl": "https://…/release/android/wv-chat-1.0.26.apk", "sha256": "64位小写hex", "sizeBytes": 165597740 }
```

对象键为 `release/<platform>/<品牌-版本>.apk`，内容不可覆盖，每版独立。下载地址是对象存储稳定 URL，不再手工 Copy-Item 到 CMS，也不用手工算摘要。

> 兜底：若上传接口尚未部署，脚本可用 `-UploadMode Cms` 退回「复制到官网 `html/download/wv-chat-<版本>.apk` + 本地算 sha256/大小 + 官网 URL」的旧路径。

### 3.4 创建发布记录（草稿 → 提交发布）

`POST /admin/client-releases`（草稿），字段：

```json
{
  "platform": "android",
  "channel": "stable",
  "version": "1.0.26",
  "buildNumber": 43,
  "mandatory": false,
  "releaseNotes": "更新说明",
  "storeUrl": null,
  "reason": "直接发布",
  "compatibility": { "protocolVersion": "v1", "minimumServerCapabilityVersion": 1 },
  "artifacts": [ { "architecture": "universal", "packageType": "apk", "downloadUrl": "…", "sha256": "…", "sizeBytes": 165597740 } ]
}
```

再 `POST /admin/client-releases/{id}/submit`：

```json
{ "expectedRowVersion": 0, "reason": "直接发布", "initialRolloutPercent": 100, "scheduledAt": null }
```

`initialRolloutPercent=100` 且 `scheduledAt=null` → 立即发布（状态 `released`）。要定时/灰度则填 `scheduledAt`、`initialRolloutPercent<100`。

> 管理端写接口都要求请求头 `Idempotency-Key`（UUID）；登录用 `POST /auth/login`，之后带 `Authorization: Bearer <token>` 与 `X-Client-Contract: im-v1` / `X-Client-Version` / `X-Client-Platform: pc-admin`。

### 3.5 验收

```powershell
curl "https://api.dev.example.com/api/v1/client/releases/latest?platform=android&channel=stable"
curl -I "https://example.com/download/wv-chat-<版本>.apk"
```

两条都 200；第一条返回 `version`=`1.0.26`、`buildNumber`=`43`、`status`=`released`；下载文件大小与 sha256 与发布记录一致。

---

## 4. 脚本参数速查（`tools/release.ps1`）

```text
-Version "1.0.26"     指定版本（默认 PATCH+1）
-BuildNumber 43       指定构建号（默认 +1，必须 > 当前）
-ReleaseNotes "…"     更新说明（发布时必填）
-Channel stable       渠道（默认 stable，一般别改）
-UploadMode ObjectStorage|Cms   上传方式（默认对象存储；接口未部署时用 Cms）
-AdminBaseUrl …       管理端 API 基址（默认生产）
-AdminUser / -AdminPassword      管理端凭据（密码也可用环境变量 GV_ADMIN_PASSWORD）
-SkipBuild            跳过构建（复用已打好的 APK）
-SkipPublish          只 bump + 构建 + 上传，不建发布记录
-DryRun               打印将执行的动作，不实际改动
```

---

## 5. 涉及仓库与版本文件

| 仓库 | 路径 | 版本文件 |
| --- | --- | --- |
| 客户端 Flutter | `D:\projects\cnb\gv_chat_app` | `pubspec.yaml` 的 `version: X.Y.Z+N` |
| 后端 Java | `D:\projects\cnb\gv_im_server` | 各 `pom.xml`（按服务域独立 bump，见 [RELEASE_RUNBOOK](../../RELEASE_RUNBOOK.md)） |
| 管理后台 Vue | `D:\projects\cnb\gv_chat_admin` | `package.json` 的 `version` |
| 官网静态站 | `D:\projects\cnb\open-website` | `VERSION`（仅 CMS 内容变更时 +1） |

> 本文档只管客户端发版；后端/管理后台/官网部署仍按 [RELEASE_RUNBOOK](../../RELEASE_RUNBOOK.md) 第 4/6/7 节。
