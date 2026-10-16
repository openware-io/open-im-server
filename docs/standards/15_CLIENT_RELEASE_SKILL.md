# 客户端发版 Skill（Android 直装 APK）

> 本文档仅适用于 **Android 直装 APK**。Windows 桌面端 EXE 发版见 [`RELEASE_RUNBOOK.md`](../RELEASE_RUNBOOK.md) 第 5.6 节；Windows 同样必须在后台创建并提交客户端发布记录。
> 对 Android APK 而言，本文档是唯一、可执行的发版操作手册。任何 AI 或人类执行 Android 发版时，先读本文档，再直接运行快捷脚本，不要自行推断打包命令或版本规则。
> 打包命令以 [open-chat-app/BUILD.md](https://github.com/openware-io/open-chat-app/blob/develop/2.2.0-auth/BUILD.md) 为权威来源；本文档负责「一步步怎么做」并引用脚本。
> 治理规则（版本命名、灰度、审计、安全门禁）见 [14_CLIENT_RELEASE_GOVERNANCE.md](14_CLIENT_RELEASE_GOVERNANCE.md)。

## 0. 目标与适用范围

**目标**：给出 Android 直装 APK 的**唯一、可执行**发版路径，使任何执行者（人或 AI）无需推断即可完成一次合规发布，并留下可核验的发布记录。

**适用范围**：仅 Android 直装 APK（官网下载页与 App 内更新共用的正式制品）。

**非目标**：
- 不覆盖 Windows 桌面端 EXE 发版（见 [RELEASE_RUNBOOK](../RELEASE_RUNBOOK.md) 第 5.6 节）；
- 不覆盖 Google Play 上架（AAB）；
- 不替代打包命令的权威来源：命令以 [open-chat-app/BUILD.md](https://github.com/openware-io/open-chat-app/blob/develop/2.2.0-auth/BUILD.md) 为准；
- 不定义治理规则本身（版本命名、灰度、审计、安全门禁见 [14_CLIENT_RELEASE_GOVERNANCE.md](14_CLIENT_RELEASE_GOVERNANCE.md)）。

## 0.1 术语与前置假设

| 术语 | 定义 |
| --- | --- |
| APK / AAB | APK 是直装制品（官网下载页与 App 更新使用）；AAB 仅用于 Google Play 上架，**不可**用于直装发布。 |
| 构建类型 | `prod` 为正式构建（查 `stable` 渠道、开生产推送）；`dev`/`test` 为测试包，正式用户永远不会更新到。 |
| `buildNumber` | `pubspec.yaml` 中 `version: X.Y.Z+N` 的 `N`；必须严格递增，否则客户端不认为有新版本。 |
| 渠道（channel） | 发布记录所属渠道。`prod` 构建与官网下载页均查 `stable`；渠道建错等于更新不可达。 |
| `-DryRun` | 只看不执行，用于核对将要发生的版本变更与制品。 |
| `-SkipPublish` | 只打包并上传制品，不提交上线。 |
| `-SigningPolicy` | 签名策略：默认 `RequireRelease`（必须正式密钥）；无密钥的首次开源部署只能显式 `AllowUnsigned`，产出 `signingMetadata.status=unsigned` 的**不可安装**制品。 |

**前置假设**：版本文件可写；对象存储凭据已配置；正式发布时运营方已持有自己的正式签名密钥（密钥库与 `android/key.properties` 均不入库）。

---

## 0. 一句话执行

```powershell
cd open-chat-app
.\tools\release.ps1 -ReleaseNotes "本次更新说明"
```

脚本自动完成：读版本 → PATCH+1 / buildNumber+1 → 打正式 APK → 上传对象存储 → 创建发布记录 → 提交发布 → 验收。加 `-DryRun` 只看不执行，加 `-SkipPublish` 只打包含不上线。

---

## 1. 原则与禁止项：反复踩坑的五条铁律

1. **正式环境打 APK，不是 AAB。** AAB 仅用于 Google Play 商店上架。直装发布永远是 APK。
   - 正确：`flutter build apk --release`（或 `tools\build.ps1 android prod apk`）
   - 错误：`flutter build appbundle --release`（那是商店包，官网下载页/App 更新用不了）
2. **正式环境用 `prod`，不是测试包。** `prod` 构建才查 `stable` 渠道、才开 JPush 生产推送。测试包（dev/test）永远不会被正式用户更新到。
3. **版本号默认 PATCH+1、buildNumber+1。** 团队历史全是 PATCH 递增（1.0.13→…→1.0.24→1.0.25），禁止为 bug 修复抬 MINOR。buildNumber 必须严格递增。
4. **正式运营必须使用运营方自己的正式签名密钥。** 密钥库与 `android/key.properties` 均不得入库。开源首次部署没有密钥时，只能显式传 `-SigningPolicy AllowUnsigned` 发布带 `signingMetadata.status=unsigned` 的不可安装制品；脚本禁止静默退化为 debug 签名。取得密钥后使用默认 `RequireRelease` 重新发版。
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
| 本地一键部署管理 API | `http://127.0.0.1:30080/api/v1` |
| 发布产物本地路径 | `build\app\outputs\flutter-apk\app-release.apk` |

---

## 3. 完整发版流程（脚本等价步骤）

### 3.1 bump 版本

`pubspec.yaml` 的 `version: X.Y.Z+N` 行，默认 `PATCH+1`、`N+1`（如 `1.0.25+42 → 1.0.26+43`）。脚本自动完成，UTF-8 无 BOM 写回。

### 3.2 打正式 APK

```powershell
cd open-chat-app
.\tools\build.ps1 android prod apk -JPushAppKey "YOUR_JPUSH_APPKEY"
```

产物：`build\app\outputs\flutter-apk\app-release.apk`。

> 国内网络若 Gradle 拉 Maven TLS 失败：`android/build.gradle` 已配置阿里云镜像优先，无需改。
> 打包前若远端分支被推进：先 `git pull --rebase` 再构建，否则 APK 缺别人已合并的代码。

### 3.3 上传制品（对象存储，自动回填三项）

后台「客户端发布」页「选择 APK 并上传」，或直接调管理端接口：

`POST /admin/client-releases/artifacts/upload`（multipart：`file`=APK 文件、`platform`=`android`），返回：

```json
{ "downloadUrl": "https://…/release/android/open-chat-2.2.0.apk", "sha256": "64位小写hex", "sizeBytes": 165597740 }
```

对象键为 `release/<platform>/<品牌-版本>.apk`，内容不可覆盖，每版独立。下载地址是对象存储稳定 URL，不再手工 Copy-Item 到 CMS，也不用手工算摘要。

> 兜底：若上传接口尚未部署，脚本可用 `-UploadMode Cms` 退回「复制到官网 `html/download/open-chat-<版本>.apk` + 本地算 sha256/大小 + 官网 URL」的路径。

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
curl -I "https://example.com/download/open-chat-<版本>.apk"
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
-AdminUser / -AdminPassword      管理端凭据（密码也可用环境变量 OPEN_ADMIN_PASSWORD）
-SkipBuild            跳过构建（复用已打好的 APK）
-SkipPublish          只 bump + 构建 + 上传，不建发布记录
-DryRun               打印将执行的动作，不实际改动
```

---

## 5. 涉及仓库与版本文件

| 仓库 | 路径（相对工作区根） | 版本文件 |
| --- | --- | --- |
| 客户端 Flutter | `open-chat-app/` | `pubspec.yaml` 的 `version: X.Y.Z+N` |
| 后端 Java | `open-im-server/` | 各 `pom.xml`（按服务域独立 bump，见 [RELEASE_RUNBOOK](../RELEASE_RUNBOOK.md)） |
| 管理后台 Vue | `open-chat-admin/` | `package.json` 的 `version` |
| 官网静态站 | `open-website/` | `VERSION`（仅 CMS 内容变更时 +1） |

> 本文档只管客户端发版；后端/管理后台/官网部署仍按 [RELEASE_RUNBOOK](../RELEASE_RUNBOOK.md) 第 4/6/7 节。

## 6. 发版操作模板

**一次正式 Android 直装发布的等价步骤（示例，参数按本次填写）**：

```powershell
# 1. 干跑核对：将发生的版本变更与制品
cd open-chat-app
.\tools\release.ps1 -ReleaseNotes "本次更新说明" -DryRun

# 2. 正式执行：读版本 → PATCH+1 / buildNumber+1 → 打 prod APK → 上传 → 建记录 → 提交发布 → 验收
.\tools\release.ps1 -ReleaseNotes "本次更新说明"

# 3. 只打包不上线（先出制品、后决定发布时）
.\tools\release.ps1 -ReleaseNotes "本次更新说明" -SkipPublish
```

**发布记录的最小字段（示例）**：渠道 `stable`、平台 `android`、版本 `X.Y.Z+N`、制品下载地址、更新说明、是否强制更新。

## 7. 门禁与人工检查项

**可自动化**（发版脚本应断言，失败即中止）：

| 门禁 | 判据 |
| --- | --- |
| 制品类型 | 产出为 APK；出现 AAB 即失败 |
| 构建类型 | 正式发布为 `prod`；`dev`/`test` 不得提交为正式发布 |
| 版本单调性 | `buildNumber` 严格递增；默认 PATCH+1，禁止为修复抬 MINOR |
| 签名策略 | `RequireRelease` 下必须有正式签名；禁止静默退化 debug 签名；`AllowUnsigned` 必须显式且制品标记 `unsigned` |
| 渠道一致性 | 发布记录渠道与客户端查询渠道一致（`prod`/官网均为 `stable`） |
| 密钥不入库 | 密钥库与 `android/key.properties` 均不在版本控制内 |

**人工检查项**（提交发布前确认）：

1. 更新说明是否面向用户、无内部术语与工单号。
2. 制品的下载地址是否可公开访问（官网下载页与 App 更新共用）。
3. 若为强制更新，是否与治理规则（灰度、回滚）一致。
4. `-DryRun` 输出是否与本次预期版本一致。

## 8. 与其他规范的关系

| 关系 | 文档 | 说明 |
| --- | --- | --- |
| 依赖 | [14_CLIENT_RELEASE_GOVERNANCE.md](14_CLIENT_RELEASE_GOVERNANCE.md) | 版本命名、渠道、灰度、审计与安全门禁的规则来源 |
| 引用 | [RELEASE_RUNBOOK](../RELEASE_RUNBOOK.md) | 桌面端发版（第 5.6 节）与后端/后台/官网部署（第 4/6/7 节） |
| 引用 | [open-chat-app/BUILD.md](https://github.com/openware-io/open-chat-app/blob/develop/2.2.0-auth/BUILD.md) | 打包命令的权威来源 |
| 关联 | [20_MAVEN_ENGINEERING_CONVENTIONS.md](20_MAVEN_ENGINEERING_CONVENTIONS.md) | 后端版本治理；客户端与后端版本各自独立 bump |
| 关联 | [43_E2E_TESTING_CONVENTIONS.md](43_E2E_TESTING_CONVENTIONS.md) | 发版前的端到端验收与真实设备要求 |
