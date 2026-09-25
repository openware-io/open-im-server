# 版本管理与发布规范（Runbook）

> 本文档是「怎么发版、怎么管版本」的唯一操作手册，供人类与后续 AI 代理共同遵守。
> **Android APK 发版先看 [`standards/15_CLIENT_RELEASE_SKILL.md`](standards/15_CLIENT_RELEASE_SKILL.md) 并直接运行 `open-chat-app/tools/release.ps1`；Windows 桌面端 EXE 发版按本文档第 5.6 节执行。** 后端/管理后台/官网部署仍按本文档执行。
> 治理规则（版本命名、灰度、审计、安全门禁等）见 [`standards/14_CLIENT_RELEASE_GOVERNANCE.md`](standards/14_CLIENT_RELEASE_GOVERNANCE.md)；
> ACK 集群拓扑与域名见 [`deployment/ACK.md`](deployment/ACK.md)。本文档与这两篇互补，本文档负责「一步步怎么做」。

---

## 0. 仓库与分支速览

| 仓库 | 路径 | 分支 | 版本文件 |
| --- | --- | --- | --- |
| 后端（Java 微服务） | `D:\projects\cnb-oss\open-im-server` | `develop/develop-bz-20260812` | 所有 `pom.xml` 的 `<version>` |
| 客户端（Flutter） | `D:\projects\cnb-oss\open-chat-app` | `javachat` | `pubspec.yaml` 的 `version: X.Y.Z+buildNumber` |
| 管理后台（Vue） | `D:\projects\cnb-oss\open-chat-admin` | `develop/api-20260731-th` | `package.json` 的 `version` |
| 官网（静态站） | `D:\projects\cnb\open-website` | `main` | `VERSION` |
| SaaS 移动 H5（C端 A380 更多服务 + B端 商户端） | `D:\projects\cnb-oss\open-saas-mobile` | `main` | `VERSION` |

> 分支名可能随时间演进；以 `git remote -v` + `git branch` 实测为准。官网 CMS 的版本号独立维护。

---

## 1. 版本号规范（必须遵守）

版本语义统一为 `MAJOR.MINOR.PATCH`（主.次.补丁）：`MAJOR`=不兼容变更，`MINOR`=兼容功能发布，`PATCH`=兼容修复。

> **默认 `PATCH`（最后一位）+1**：修复、优化、小改动一律 `PATCH`；只有真正的「兼容功能发布」才 `MINOR` +1；只有「不兼容变更」才 `MAJOR` +1。**禁止为 bug 修复抬 MINOR**。

### 1.1 后端（open-im-server）：服务域独立版本模型

> 与 [`standards/20_MAVEN_ENGINEERING_CONVENTIONS.md`](standards/20_MAVEN_ENGINEERING_CONVENTIONS.md)「版本治理」一致。**禁止全仓 pom lockstep 统一 bump。**

| 版本单元 | 版本声明位置 | 何时 bump |
| --- | --- | --- |
| 根聚合 `im-server` | 根 `pom.xml` 的 `<version>` | 仅 `MAJOR`（平台级不兼容 / 结构大改） |
| 结构聚合父 POM（`im-services`、`im-gateways`） | 各自 `<version>` | 随根基线，仅 `MAJOR` |
| 平台库 `sdk` | `sdk/pom.xml` 的 `<version>` | 平台库（`common`/`infrastructure`/`protocol-ws`/`protocol-mq`）变更时 |
| 服务域 `<domain>`（同域 `*-api` 与 `*-service` 同版本） | `im-services/<domain>/pom.xml` 的 `<version>` | 该服务域迭代时（子模块继承，不逐个改） |
| 可部署叶子服务（产生部署 JAR） | 叶子服务 `pom.xml` 的 `<version>`；开发为 `<基础版本>-SNAPSHOT`，正式为纯 SemVer | 该服务实现迭代时；基础版本必须与领域 POM一致 |
| 网关 `gateway` / `im-access-ws` | 各自 POM 的 `<version>` | 各自迭代时 |

要点：

1. **只 bump 实际改动的版本单元**：改 `im-message-service` 只调整 `im-services/message` 服务域基础版本及对应叶子服务版本（开发追加 `-SNAPSHOT`），不动根聚合、其他域、其他网关。
2. **父 POM 跟 MAJOR**：根聚合与结构聚合父 POM 版本只在 `MAJOR` 变化，服务日常迭代不改父版本。
3. **跨域依赖显式锁版本**：某服务依赖另一服务域的 `*-api` 时，用属性（如 `im.conversation-api.version`）显式锁定，被依赖域发版后消费方同步更新该属性。
4. **开发分支默认使用开发版本**：可部署叶子服务的 Maven 版本和镜像标签使用 `<主版本>-SNAPSHOT`（如 `1.2.0-SNAPSHOT`）；根聚合、结构聚合和领域父 POM 保持纯 `MAJOR.MINOR.PATCH`，不追加后缀。
5. **正式版本必须显式指定**：只有用户明确要求打正式版本包时，才使用不带 `-SNAPSHOT` 的纯 SemVer。正式版本标签不可覆盖；开发 `-SNAPSHOT` 标签允许在 ACR 覆盖，以满足开发环境持续验证。
6. **镜像标签 = 对应服务 Maven 版本**，各服务标签不再强制一致，禁止 `latest`、`dev`、时间戳和其他临时后缀。

### 1.2 客户端 / 官网

- 客户端 `pubspec.yaml` 的 `version: X.Y.Z+buildNumber`；`buildNumber` **严格递增**，一次发版 +1，服务端只比较 buildNumber。
- Windows 桌面端 `open-chat-desktop/package.json` 的 `version` 独立维护；Windows `buildNumber` 在 `windows/stable` 渠道内严格递增，不能与 Android 共用。
- 官网 CMS `VERSION` 独立维护，每次 CMS 内容变更 +1。
- SaaS 移动 H5（`open-saas-mobile`）`VERSION` 独立维护，每次 H5 页面/接口契约变更 +1。

> 现状：当前部分工程仍为历史版本模型。迁移期间不得为了生成开发镜像修改根聚合、结构聚合或领域父 POM 的稳定版本；应在对应可部署叶子服务完成版本迁移后再使用 `-SNAPSHOT` 构建。

> 五个独立概念，禁止互相替代：服务 JAR/Maven 版本、镜像标签、客户端展示版本、客户端 buildNumber、REST/WS 协议版本。

---

## 2. 发布渠道（channel）约定

| 渠道 | 用途 | 客户端构建环境 |
| --- | --- | --- |
| `internal` | 研发/自动化验证 | dev/test 构建 |
| `beta` | 受控测试 | — |
| `stable` | 正式用户 | prod 构建 |

客户端通过 `lib/core/gv_app_update_platform.dart` 的 `gvClientReleaseChannelKey()` 决定：`prod` 构建查 `stable`，否则查 `internal`。官网下载页固定查 `stable`。**发布记录必须建在客户端实际查询的渠道上**，否则 App 永远查不到更新。

---

## 3. 发布总流程（一次完整发版）

按顺序执行，每步都要提交并（建议）推送：

```
改代码 → bump 改动的版本单元（后端按服务域 / 各客户端 / 管理后台 / 官网） → 提交 → 构建/推送后端镜像 → 部署后端
      → 构建客户端 APK 或 Windows EXE → 上传制品 → 建对应平台「客户端发布」记录（草稿→提交发布）
      → 部署管理后台 → 部署官网（纯静态，APK 走对象存储）
      → 验证下载页与 App 更新
```

### 3.1 增量发版（只发改动服务，日常迭代默认走这条）

一次改动只涉及少数服务时，不要重建/推送/滚动全部工作负载：

```powershell
cd D:\projects\cnb-oss\open-im-server
# 后端服务改了实现：只构建该模块（不带 -am 之外的全量构建），产物落到对应 target/
.\mvnw.cmd -B -ntp -pl platform-services/tenant/platform-tenant-service -am package
# 只构建/推送 -Targets 列出的服务，发布清单里也只登记这些服务
.\scripts\deploy\build-saas-release.ps1 -SkipPackage -Targets platform-tenant-service,saas-admin -ReleaseManifestPath .\.outputs\releases\kind-saas-dev-<ts>.json
# Kind 增量部署：只滚动清单内的服务，并逐 Pod 校验 ACR digest
.\scripts\deploy\k8s-scoped.ps1 -SaasReleaseManifestPath .\.outputs\releases\kind-saas-dev-<ts>.json
```

- `-Targets` 是**开发（`-SNAPSHOT`）发版**的目标过滤开关，与 `-FormalRelease` 互斥；正式发版继续用 `-FormalTargets`，语义不变。
- `-SkipPackage` 表示复用 `target/` 下已有 JAR；不带它时脚本会执行全量 `mvn clean package`（慢，仅改动单个模块时不需要）。
- 增量部署要求目标 Deployment 已存在（环境已由 `k8s.ps1` 全量部署建好），否则脚本直接报错并提示先做全量部署。
- 首次建环境、基础设施/环境变量变更、需要套用 `k8s/*.yaml` 清单、或需要重建全部工作负载时，仍走 `k8s.ps1` 全量部署。
- 两条路径消费同一份 schema v2 清单、同一套「标签回查 ACR digest + 逐 Pod digest 校验」，禁止手工 `kubectl set image` 或直接传 tag。
- ACK 侧按 §4.4：`build-saas-release.ps1 -FormalRelease -FormalTargets <本次晋级的服务>` → `ack.ps1`。

---

## 4. 后端发布（open-im-server）

### 4.1 bump 版本（只 bump 改动的版本单元）

按「服务域独立版本模型」，**只改实际改动模块的版本单元，不做全仓 lockstep 替换**。

单服务域发版（例：`im-message` 域 `1.0.21 → 1.0.22`）：

```powershell
cd D:\projects\cnb-oss\open-im-server
# 只改该服务域父 POM 版本；同域 *-api 与 *-service 继承，无需逐个改
.\mvnw.ps1 -f im-services/message/pom.xml versions:set -DnewVersion=1.0.22
# 若其他域依赖本域 *-api，同步它们的版本属性（如 im-message-api.version）
```

平台库发版：改 `sdk/pom.xml` 版本；根 `dependencyManagement` 若以 `sdk.version` 属性引用平台库，需同步该属性。

> 根聚合 `im-server` 与 `im-services`/`im-gateways` 等结构聚合父 POM 只在 `MAJOR` 变化，日常发版不动。全量替换旧做法（`-replace '旧','新'`）仅用于 `MAJOR` 级根基线升级，且仍需逐文件核对。

### 4.2 构建

```powershell
cd D:\projects\cnb-oss\open-im-server
mvn -B clean package -DskipTests        # 全量构建，产物在各模块 target/
```

> 全量跑测试偶发 Mockito 在 JDK 25 的 `NoClassDefFoundError`（环境抖动），重跑即可；单服务快速验证用 `-DskipTests`。

### 4.3 构建并推送镜像（8 个后端服务）

镜像前缀：`ghcr.io/openware-io`（集群用 `-vpc.` 前缀拉取，同一仓库）。

```powershell
$tag = '1.0.14'; $rev = (git rev-parse --short HEAD)
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$prefix = 'ghcr.io/openware-io'
$services = [ordered]@{
  'im-user-service'        = 'im-services/user/im-user-service/target/im-user-service-1.0.14.jar'
  'im-message-service'     = 'im-services/message/im-message-service/target/im-message-service-1.0.14.jar'
  'im-conversation-service'= 'im-services/conversation/im-conversation-service/target/im-conversation-service-1.0.14.jar'
  'common-media-service'     = 'common-services/media/common-media-service/target/common-media-service-1.0.14.jar'
  'im-order-service'       = 'im-services/order/im-order-service/target/im-order-service-1.0.14.jar'
  'im-admin-service'       = 'im-services/admin/im-admin-service/target/im-admin-service-1.0.14.jar'
  'im-access-ws'           = 'gateways/im-access-ws/target/im-access-ws-1.0.14.jar'
  'gateway'                = 'gateways/gateway/target/gateway-1.0.14.jar'
}
foreach ($e in $services.GetEnumerator()) {
  $img = "$prefix/$($e.Key):$tag"
  docker build --pull=false --build-arg "JAR_PATH=$($e.Value)" --build-arg "IMAGE_VERSION=$tag" --build-arg "IMAGE_REVISION=$rev" --build-arg "IMAGE_CREATED=$stamp" --build-arg "IMAGE_SOURCE=https://github.com/openware-io/open-im-server" -t $img $root
  docker push $img
}
```

### 4.4 部署到 ACK

kubeconfig 路径从 VS Code 配置读取：`Code\User\settings.json` 的 `vs-kubernetes.kubeconfig`。目标上下文必须是 `209277982874572694-c8629a647dce749afbec6d16972bc6ec7`（`ack.ps1` 会校验）。ACK 可接收已通过 Kind 回归的开发或正式发布清单；脚本会校验清单类型、对应的 tag 规范与 ACR digest。

```powershell
# 先在干净工作树生成正式清单；FormalTargets 必须只列出本次晋级的服务/前端。
.\scripts\deploy\build-saas-release.ps1 -FormalRelease -FormalTargets im-user-service -ReleaseManifestPath .\.outputs\releases\formal.json

# 使用同一份清单部署 ACK；禁止 kubectl set image 和直接传 tag。
.\scripts\deploy\ack.ps1 -ReleaseManifestPath .\.outputs\releases\formal.json
```

> 命名空间 `im-business`。中间件（mysql/redis/mongodb/minio/rocketmq）不动。ACK 工作负载引用清单中已验证的 digest，发布记录保留 tag、digest、Git 修订和时间；开发 `-SNAPSHOT` 清单允许部署，`latest`、非规范 tag 和手工 `kubectl set image` 一律拒绝。

### 4.5 后端服务与端口

| 服务 | 端口 | | 服务 | 端口 |
| --- | --- | --- | --- | --- |
| gateway | 3002 | | im-user-service | 3100 |
| im-access-ws | 3001 | | im-message-service | 3200 |
| im-conversation-service | 3300 | | im-admin-service | 3400 |
| im-order-service | 3500 | | common-media-service | 3600 |

---

## 5. 客户端发布（open-chat-app）

### 5.1 bump 版本

`pubspec.yaml` 的 `version:` 行：`1.0.14+30 → 1.0.15+31`（版本号与 buildNumber 各 +1）。

### 5.2 构建 APK

```powershell
cd D:\projects\cnb-oss\open-chat-app
.\tools\build.ps1 android prod apk -JPushAppKey "YOUR_JPUSH_APPKEY"
# 产物：build\app\outputs\flutter-apk\app-release.apk
```

> 国内网络下若 Gradle 拉取 `repo.maven.apache.org` TLS 失败，`android/build.gradle` 已配置阿里云 Maven 镜像优先，无需改。

### 5.3 上传 APK（后台点选文件，自动得下载 URL / SHA-256 / 大小）

发布制品不再手工 `Copy-Item` 到 CMS，也不再手工计算摘要：后台「客户端发布」页在「APK（直装）」形态下提供「选择 APK 并上传」，由管理服务转发到支撑服务对象存储（MinIO/OSS），自动回填下载地址、SHA-256、文件大小三项。

```powershell
# 只需本地构建 APK（正式环境直装包）
cd D:\projects\cnb-oss\open-chat-app
.\tools\build.ps1 android prod apk -JPushAppKey "YOUR_JPUSH_APPKEY"
# 产物：build\app\outputs\flutter-apk\app-release.apk
```

> 下载地址由对象存储的稳定 URL 生成；对象键为 `release/<platform>/<品牌-版本>.apk`，内容不可覆盖，每版独立。

### 5.4 创建「客户端发布」记录

数据表 `adm_client_release` + `adm_client_release_artifact`，管理后台在「客户端发布」页操作（或直接调内部 API）：

**人工发布（后台页面操作，两种模式）**：

1. 打开后台「客户端发布」页，右上「创建发布」。
2. 「发布方式」二选一：
   - **直接发布**：创建后立即对全部用户上线（`released`）。
   - **预发布**：展开预发布表单 —— 填「定时上线时间」（必填，到点自动发布）+「灰度比例」（默认 100；<100 时到点先进入灰度 `rolling_out`，再手动扩大）。
3. 平台选 `Android`、渠道选 `Stable`（与官网/App 一致），填 `版本`（如 `1.0.17`）与 `构建号`（如 `33`，需大于当前已发布的最大 buildNumber），写更新说明。
4. `制品类型` 选 **APK（直装）**，点「选择 APK 并上传」选本地 APK 文件，上传成功后自动填充：
   - 下载地址（HTTPS）：对象存储稳定 URL
   - SHA-256：服务端计算的 64 位小写十六进制
   - 文件大小（字节）：APK 的字节数
   （也可手工填写这三项，但推荐用上传按钮自动回填）
5. 点「直接发布」或「创建预发布」完成（内部自动先建草稿再提交）。

> 商店类型（Google Play / App Store）则只填「商店地址」，无需下载地址/SHA/大小；`iOS` 只支持 `app-store`。下载域名必须在后台 `CLIENT_RELEASE_ALLOWED_DOWNLOAD_HOSTS` 白名单内（已含 `example.com`）。

**内部 API 等价操作**：

1. **创建草稿**：`POST /admin/client-releases`，字段含 `platform=android`、`channel=stable`、`version`、`buildNumber`、`releaseNotes`、`compatibility`、`artifacts`（直装 APK 需 `downloadUrl` + `sha256` + `sizeBytes`）。
2. **提交发布**：`POST /admin/client-releases/{id}/submit`：
   - `initialRolloutPercent=100` 且不填 `scheduledAt` → **立即发布**（状态 `released`，记录 `publishedAt`）。
   - 填 `scheduledAt`（未来时间）→ **定时发布**（状态 `scheduled`），到点由调度任务自动转为 `released`。

> 后台「客户端发布」列表默认渠道已设为 `stable`，与官网/App 一致。

### 5.5 官网下载页如何取数

`https://example.com/download.html` 调用 `GET /api/v1/client/releases/latest?platform=android&channel=stable`，返回 `stable` 渠道**最新一条 `released`** 记录（含版本、下载 URL、SHA-256、更新说明）。所以「客户端发布」里最新一条已发布记录 = 官网下载页展示的数据。

### 5.6 Windows 桌面端发布（open-chat-desktop）

> **构建 EXE 不等于发版完成。每次 Windows 桌面端发版都必须在管理后台「客户端发布」创建并提交发布记录；未创建后台记录，即使 EXE 已构建或已上传也不算完成发版。**

1. 在 `open-chat-desktop/package.json` 将 `version` 默认 PATCH +1。Windows `buildNumber` 必须取后台 `windows/stable` 已发布记录的最大值严格 +1，**不得借用 Android 或其他平台的 buildNumber**。
2. 构建安装包：

   ```powershell
   cd D:\projects\cnb-oss\open-chat-desktop
   npm run build:win
   # 产物：dist\WV Chat Setup <version>.exe
   ```

3. 打开管理后台「客户端发布」创建发布：平台选 `Windows`，渠道选 `Stable`，填入版本、Windows buildNumber 和更新说明；制品类型选 `EXE`，架构选 `x64`，上传该 EXE。上传完成后由对象存储自动回填下载 URL、SHA-256 和文件大小。
4. 选择「直接发布」使状态成为 `released`；定时或灰度发布按审批流程提交，不可停留在草稿。
5. 验收：

   ```powershell
   curl "https://api.dev.example.com/api/v1/client/releases/latest?platform=windows&channel=stable"
   ```

   接口必须返回本次 `version`、`buildNumber` 和 `status=released`；对返回的下载 URL 执行 `curl -I`，必须得到 HTTP `200`。

内部 API 等价流程：先上传制品（`POST /admin/client-releases/artifacts/upload-sessions`，再 complete），再创建草稿（`POST /admin/client-releases`），最后提交（`POST /admin/client-releases/{id}/submit`）。

---

## 6. 管理后台发布（open-chat-admin）

1. bump `package.json` 的 `version`，并在干净工作树确认本次只有 `pc-admin` 被提升为正式版本。
2. 使用统一发布构建生成正式清单；不得直接 `docker push` 或更新工作负载镜像：

```powershell
cd D:\projects\cnb-oss\open-im-server
.\scripts\deploy\build-saas-release.ps1 -FormalRelease -FormalTargets pc-admin -ReleaseManifestPath .\.outputs\releases\pc-admin-formal.json
# Kind 回归通过后，使用同一清单发布 ACK。
.\scripts\deploy\ack.ps1 -ReleaseManifestPath .\.outputs\releases\pc-admin-formal.json
```

---

## 7. 官网发布（open-website）

官网是纯静态站点，**镜像只放静态资源（HTML/CSS/JS），不打包 APK**。APK 统一走第 5 节对象存储（MinIO/OSS），由管理端「客户端发布」页上传并回填下载地址；下载页通过 `GET /api/v1/client/releases/latest` 取对象存储 URL，不从官网镜像读 APK。

1. bump `VERSION`（每次内容/镜像变更 +1）。
2. 确认 `.dockerignore` 已排除 `html/download/`（历史遗留的本地 APK 兜底目录，禁止进镜像）。
3. 构建 + 推送 + 部署（镜像标签 = `VERSION`，不用 `latest`；集群用 `-vpc.` 前缀拉取）：

```powershell
cd D:\projects\cnb\open-website
docker build --platform linux/amd64 -t ghcr.io/openware-io/open-website:1.0.37 -f Dockerfile .
docker push ghcr.io/openware-io/open-website:1.0.37
kubectl -n meta-cogni set image deployment/xch-cms xch-cms=ghcr.io/openware-io/open-website:1.0.37
kubectl -n meta-cogni rollout status deployment/xch-cms --timeout=300s
```

> APK 不进官网镜像；`.dockerignore` 必须含 `html/download/`。

---

## 7.5 SaaS 移动 H5 发布（open-saas-mobile，Vue 双端 C端 + B端）

版本由仓库根 `VERSION` 文件维护（语义化 `MAJOR.MINOR.PATCH`，默认 PATCH +1）。开发分支镜像 tag 使用 `<version>-SNAPSHOT`，允许在 ACR 覆盖；只有用户明确要求正式包时才使用不带后缀的 `<version>`，且正式 tag 不可覆盖。禁止 `latest`、时间戳、提交号及其他临时后缀；UTC 时间戳与 Git 短提交只写入镜像 label 和发布清单（`buildIdentity`/`sourceRevision`）。

1. bump `VERSION`，并在干净工作树确认本次只有 `saas-mobile` 被提升为正式版本。
2. 使用统一发布构建生成正式清单；不得直接构建、推送或替换 ACK 工作负载：

```powershell
cd D:\projects\cnb-oss\open-im-server
.\scripts\deploy\build-saas-release.ps1 -FormalRelease -FormalTargets saas-mobile -ReleaseManifestPath .\.outputs\releases\saas-mobile-formal.json
# Kind 回归通过后，使用同一清单发布 ACK。
.\scripts\deploy\ack.ps1 -ReleaseManifestPath .\.outputs\releases\saas-mobile-formal.json
```

3. 验收：`https://www.miniservice.dev.example.com/a380/` 返回 200、静态资源可加载，且清单中的 tag、digest 和 Git 修订与 `VERSION` 一致；正式发布时不得使用 `-SNAPSHOT`。

---

## 8. 客户端「检查更新」行为（App 侧）

- 设置页「检查更新 / 当前版本」入口（手动检查）+ 启动时 `ClientReleaseCoordinator.checkOnLaunch()` 自动检查。
- 调用 `POST /client/release-check`，服务端返回五类决策：`up_to_date` / `optional_update` / `mandatory_update` / `unsupported_client` / `no_release`。
- `mandatory_update` 与 `unsupported_client` 阻断进入业务界面；`optional_update` 可延后。下载走服务端返回的 Artifact URL，客户端不自行拼 URL。

---

## 9. 关键脚本与文件索引

| 脚本/文件 | 用途 |
| --- | --- |
| `scripts/deploy/ack.ps1` | 校验并部署已生成的正式发布清单；不构建、不推送、不接受直接 tag |
| `open-chat-app/tools/build.ps1` | 客户端构建（`android prod apk`） |
| `open-chat-app/tools/release.ps1` | 客户端一键发版（bump → 构建 → 上传 → 建记录 → 提交 → 验收），见 15_CLIENT_RELEASE_SKILL.md |
| `open-website/scripts/build-and-push.sh` | 官网镜像构建推送 |
| `open-website/scripts/deploy.sh` | 官网镜像部署 |
| `open-saas-mobile/Dockerfile` | SaaS 移动 H5（Vue 双端）镜像构建（版本读 `VERSION`） |
| `open-website/html/download.html` | 官网下载页（读 latest release） |
| `im-services/admin/.../ClientReleaseController.java` | 客户端发布管理 API（草稿/提交/灰度/审计） |
| `im-services/admin/.../ClientReleaseCheckApplicationService.java` | release-check 决策 |

---

## 10. 常见陷阱（务必注意）

1. **`imagePullPolicy: IfNotPresent`（后端）**：同名标签不会重新拉取。**改了代码必须 bump 版本号**，否则集群仍跑旧镜像。
2. **版本 bump 用 UTF-8 无 BOM** 写文件，避免 PowerShell 默认编码破坏 pom/pubspec。
3. **`gv-im-secret` 与 `gv-im-config` 都定义了 `IM_GATEWAY_ALLOWED_ORIGINS`**，Secret 在后、优先生效；改 CORS 两处都要改，改完 `kubectl rollout restart deployment/gateway`。
4. **改 Secret 用 `kubectl apply` 且只带一个 key 会覆盖其余 key**：必须回填完整 `data`（或 `stringData`），否则服务启动即崩。
5. **中文写入 DB 会因 PowerShell → kubectl exec stdin 编码乱码**：用 `CONVERT(0x<utf8hex> USING utf8mb4)` 写中文字段。
6. **提交信息用中文**，遵循仓库既有风格。
7. **客户端 rebase 后再打包**：若远端分支被别人推进，先 `git pull --rebase` 再构建，否则 APK 缺别人已合并的代码。
8. **发布记录渠道必须与客户端查询渠道一致**（prod 查 `stable`），否则 App 永远无更新提示。
9. **测试设备**：vivo `10AF9Y31YG002M3`、小米 `4d4fc229`，adb 在 `C:\Users\liuxi\Android\Sdk\platform-tools\adb.exe`；设备未连接则无法真机验收。

---

## 11. 一次发版后的验收清单

- [ ] 后端各服务 `kubectl get pods -n im-business` 全部 `1/1 Running`，无重启。
- [ ] `curl https://api.dev.example.com/api/v1/client/releases/latest?platform=android&channel=stable` 返回最新版本（含 downloadUrl/sha256）。
- [ ] `curl -I https://example.com/download/wv-chat-<版本>.apk` 返回 200，且 SHA-256 与发布记录一致。
- [ ] 官网下载页显示新版本号。
- [ ] App 设置页「当前版本」为新版本；用旧 buildNumber 的包触发 release-check 能返回 `optional_update`/`mandatory_update`。
- [ ] 如发布 Windows 桌面端，`platform=windows&channel=stable` 的 latest 接口返回本次 `released` 记录，EXE 下载 URL 返回 200。
- [ ] 各仓库提交已推送；各端版本与 buildNumber 按各自平台规则独立递增。
