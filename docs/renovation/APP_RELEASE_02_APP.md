# 客户端版本发布治理：App 实施方案

> 方案集：`APP_RELEASE`；序号：`02`；实施边界：`gv_chat_app` Flutter 客户端；前置：[服务端方案](APP_RELEASE_01_SERVICE.md)；后置：[管理后台方案](APP_RELEASE_03_ADMIN.md)；长期规则：[客户端发布与版本治理规范](../standards/14_CLIENT_RELEASE_GOVERNANCE.md)；实现规范：`gv_chat_app/docs/coding_guidelines.md`、`gv_chat_app/docs/architecture.md`、`gv_chat_app/docs/multi_environment_build_commands.md`；状态：设计待实施。

## 1. 范围和一次性切换原则

后端统一预留并治理 `android`、`ios`、`windows`、`macos`、`linux` 五个平台，但本次 App 交付只启用 Android/iOS 的 release-check 和更新链路。Windows、macOS、Linux 只保留后端枚举、管理端展示和协议模型；各自客户端、安装升级器、制品格式与验收完成后，再由后端按平台独立开启，不得因为 PC 尚未交付而阻断 Android/iOS。

Web 和 HarmonyOS 不属于本次客户端版本治理：App 运行时必须将它们识别为 `ReleaseCheckScope.outOfScope`，不发送 release-check、不创建发布记录，也不返回 `unsupported_client`。所有受管 App 在同一次切换中停止调用旧 `GET /config/app-update`，删除旧 `AppUpdateRepository`、`AppUpdateCheckResult`、`AppUpdateLatest` 与 `hasUpdate/forceUpdate` 解析；不得保留旧接口 fallback。

当前 Flutter 工程已有 Android、iOS、Windows、macOS 工程，且 Windows 使用 Inno Setup `init.iss`；尚不存在 `linux/` 工程。因此 Linux 不是“在版本检查中返回 linux”即可交付，而是必须先创建 Flutter Linux Runner、确定一种包格式和完成安装升级验收后才允许进入任一渠道。

### 1.1 App 工程规范约束

本方案遵循 App 仓库现有规则，不新造平行的 `lib/release/` 业务目录：

- 保持现有按技术类型组织：模型和稳定的非 UI 契约进入 `packages/gv_core`，主工程的 `lib/repositories`、`lib/services`、`lib/providers`、`lib/core`、`lib/screens` 分别承担仓储、服务、界面状态、平台/配置适配和页面职责。
- 数据流固定为 `Widget -> Provider/ViewModel -> Service/Repository -> API/Socket/Storage`。Widget 不得直接访问 `ImApi`、Dio、安装器或安全存储；发布决策不放入 `settings_screen.dart`、Retrofit Client 或 `ApiClient`。
- 新增跨边界依赖只能在 `lib/app/app_injection_module.dart` 注册，采用构造函数注入；不得在 Widget 中直接读取全局 service locator。修改 DI 后执行 `dart run build_runner build`，生成文件不得手工编辑。
- 用户可见文本全部进入 `lib/l10n/app_en.arb`、`lib/l10n/app_zh.arb`，由 `AppLocalizations` 使用；更新页复用 `gv_ui` 的主题、状态组件和 adaptive helper，不自建宽度判断体系。
- 优先复用现有 `ApiClient`、`PackageInfo`、`AppLifecycleObserver`、`url_launcher`、`gv_core` 模型和错误处理；只有系统凭据存储、桌面 updater 等确实不存在的边界才新增依赖或原生适配。
- 质量门禁按 App 工程执行：`dart format`、`flutter analyze`、`flutter test`、生成代码检查和 `tools/run_integration_tests.dart`；不得用服务端 Maven 门禁替代 Flutter 验证。

### 1.2 必须执行 Flutter 代码生成技能

实施本方案中任何 `gv_chat_app` 的 Dart/Flutter 改动前，开发者和自动化代理必须读取并遵循 `gv_chat_app/docs/generate-gv-chat-flutter/SKILL.md` 及其 `references/project-conventions.md`。该技能是本方案的强制工程规范，不是可选参考；它要求先检索并复用既有实现，保持 `Widget -> Provider/ViewModel -> Repository/Service -> API/Socket/Storage` 数据流，并在修改后检查生成差异。

本功能的生成源与命令固定如下：

| 变更源 | 必须执行的生成命令 | 禁止事项 |
| --- | --- | --- |
| Retrofit 声明、Freezed/JSON 模型、injectable 注解或注入模块 | `dart run build_runner build` | 手工编辑 `generated_im_api_client.g.dart`、`*.freezed.dart`、`*.g.dart`、`app_service_locator.config.dart` |
| `app_en.arb`、`app_zh.arb` | `flutter gen-l10n` | 手工编辑本地化生成 Dart 文件 |
| Drift 表/DAO（本方案当前不涉及） | `dart run build_runner build` | 以手改数据库生成物规避生成失败 |

生成只在源定义已经修改后执行，并将仓库已跟踪的生成物与源文件一起提交；检查到异常的大面积生成改动时，必须先核对 Flutter SDK、依赖锁定版本和生成器版本，不能直接提交。实施完成的最低验证顺序为：对变更 Dart 文件执行 `dart format`，执行 `dart analyze lib test`、`flutter test`、受影响的生成器和目标 Provider/Repository/Widget 测试，最后执行 `git diff --check`；涉及 ARB 时必须包含 `flutter gen-l10n`。

## 2. 当前调用点与删除清单

| 现有位置 | 当前行为 | 必须替换 |
| --- | --- | --- |
| `lib/im-services/generated_im_api_client.dart` | Retrofit `@GET('/config/app-update')` | `@POST('/client/release-check')` 与新请求/响应 DTO；执行 `build_runner` 重生成 `.g.dart` |
| `lib/im-services/im_api.dart` | `checkAppUpdate(platform, versionCode)` | 强类型 `checkClientRelease(ReleaseCheckRequest)` |
| `lib/repositories/app_update_repository.dart` | 旧仓储接口 | 删除，替换 `ClientReleaseRepository` |
| `lib/repositories/im_app_update_repository.dart` | 旧仓储实现 | 删除，替换新实现并更新 GetIt/injectable 注册 |
| `packages/gv_core/lib/src/models/app_update_models.dart` | `hasUpdate/mandatory/forceRecommend` | 删除，替换 `client_release_models.dart`；仅以源文件生成 Freezed/JSON 文件 |
| `lib/core/gv_app_update_platform.dart` | Web/Fuchsia 映射与旧请求平台 | 替换为受管平台、渠道、构建号、架构和 installationId 提供器 |
| `lib/screens/settings_screen.dart` | 设置页手动检查和 URL 外跳 | 迁移到统一更新协调器；设置页仅触发同一流程 |
| `pubspec.yaml` | `version: 1.0.5+6` | 保留 Flutter 版本来源，但 CI 必须为每个平台渠道注入一致 version/buildNumber/channel/architecture |

`packages/gv_core/lib/gv_core.dart` 的导出、`lib/models/app_update_models.dart` 的转导出、依赖注入生成文件、相关单测、国际化文案和设置页 Widget 测试必须同步更新。禁止手工编辑 `*.g.dart`、`*.freezed.dart`、`app_service_locator.config.dart`。

### 2.1 新增文件与实施工作包

在删除上述旧文件的同一 PR 中按 App 现有技术类型目录新增以下实现；类名可遵循现有命名空间微调，但不能把发布决策散回设置页或 API Client：

```text
packages/gv_core/lib/src/models/
  client_release_models.dart                 # Freezed/JSON API 契约与 decision 值
  client_release_models.freezed.dart         # 生成文件
  client_release_models.g.dart               # 生成文件

lib/models/
  client_release_models.dart                 # 与 gv_core 既有模型导出方式保持一致
lib/repositories/
  client_release_repository.dart             # 主工程仓储抽象（若不下沉 gv_core）
  im_client_release_repository.dart          # 仅调用 ImApi
lib/im-services/
  client_release_coordinator.dart            # 启动/恢复/重试/节流/并发合并
  client_release_updater.dart                 # Android/iOS 首发更新器边界，不承载 UI 状态
lib/providers/
  client_release_provider.dart               # ChangeNotifier，状态和用户意图
lib/core/
  client_release_scope.dart                   # managed/deferredDesktop/outOfScope 范围判定
  client_release_context.dart                # sdk/channel/build/protocol 提供器
  installation_id_store.dart                 # 安全存储抽象/适配器
  config.dart                                # 统一暴露 release define，不混入 EnvConfig
lib/screens/
  client_release_update_screen.dart          # 强更/不支持/可选更新页面
lib/app/
  app_injection_module.dart                  # 新增依赖注册
  app_dependencies.dart                      # 启动顺序与 Provider 列表
  app_routes.dart                            # 更新页路由常量
lib/
  app_router.dart                            # 将 ClientReleaseProvider 纳入路由刷新与守卫
lib/l10n/
  app_en.arb、app_zh.arb                    # 更新页面文案
```

模型归属遵循现有 `gv_core` 的生成 API 模型边界，`lib/models` 只做转导出；不得同时在两个目录定义实现。仓储抽象保持当前工程的 `lib/repositories` 组织方式，不为本功能单独迁移既有 Repository。`ImApi` 与 `generated_im_api_client.dart` 只传输 DTO，不能承担灰度、强更、安装或节流判断。

在 `app_injection_module.dart` 注册仓储、context、installation store、updater、coordinator 和 Provider 所需依赖；`AppDependencies` 将 `ClientReleaseProvider` 放入现有 Provider 列表，并在 `initialize()` 的登录恢复/生命周期注册/Socket 建连之前完成启动检查。`app_router.dart` 的 redirect 接收 Auth 与 Release Provider，强更/不支持时只允许更新页、重试和退出路径。通过 `dart run build_runner build` 重建 `app_service_locator.config.dart`、Retrofit 和 Freezed/JSON 文件。生成前后的 diff 必须只包含由源模型和 DI 源码派生的内容。

## 3. 新客户端模型与调用时机

### 3.1 请求上下文

```dart
class ReleaseCheckRequest {
  final ReleasePlatform platform;       // 仅 managed 范围内的 android/ios
  final ReleaseChannel channel;         // internal/beta/stable
  final String version;                 // 例如 1.4.0
  final int buildNumber;                // > 0
  final TargetArchitecture architecture; // universal/x64/arm64
  final String protocolVersion;         // v1
  final String installationId;          // 随机 UUID，不含用户身份
}
```

- `ClientReleaseScope` 必须先于 `ClientReleaseContext` 判定范围：Android/iOS 且 CI release define 完整时为 `managed`，构造完整请求并执行 POST；Windows/macOS/Linux 为 `deferredDesktop`，本轮不生成 installationId、不发请求、不阻断业务；Web、HarmonyOS、Fuchsia 和未知平台为 `outOfScope`，同样不发请求、不阻断业务。任何跳过都不得伪装成 Android/iOS，也不得把网络失败混同为跳过。
- `platform` 仅在 `managed` 后由 Flutter `defaultTargetPlatform` 映射；未知映射属于本地构建/实现错误，绝不向服务端上报为 Android。
- `version/buildNumber` 从 `PackageInfo.fromPlatform()` 获取，并在空值、非数字或 `buildNumber <= 0` 时视为构建错误，不发出伪造 `0`。
- `channel`、`architecture` 和 `protocolVersion` 由 CI 以 `GV_RELEASE_CHANNEL`、`GV_RELEASE_ARCHITECTURE`、`GV_PROTOCOL_VERSION` 注入；仅 `managed` 的 release 构建缺少任一值时，`ClientReleaseContext` 必须本地报构建配置错误。`deferredDesktop/outOfScope` 不读取这些值。不得用运行时 CPU 推断架构，避免 macOS Rosetta 等兼容层误判。
- `installationId` 仅在 `managed` 范围内首次启动时由 `InstallationIdStore` 生成 UUID。现有 `LocalStorage`/`SharedPreferences` 只用于普通偏好，严禁保存该值；本轮先完成 Android KeyStore、iOS Keychain 的安全存储适配验证。仓库没有现成实现时，先进行 `flutter_secure_storage`（最终锁定版本以实施 PR 为准）及原生能力 PoC，再将经过审查的依赖和各平台配置同 PR 提交，不能为赶工降级为账号 ID、MAC、机器序列号或其它硬件标识。Windows Credential Manager、macOS Keychain、Linux Secret Service 随各 PC 平台实施方案和 updater 一并启用。

### 3.2 响应模型与界面状态

服务端线上的成功体是 `{data,requestId}`，但现有 `ApiClient` 响应拦截器会统一解包 `data`；因此 `ImApi` 和 `ClientReleaseRepository` 只解析强类型 `ReleaseCheckResult`，不得二次读取 `data`。若后端缺少 `data`、DTO 解析失败或 `decision` 未知，按 `unsupported_client` 处理并上报脱敏诊断，不能默认放行。需要关联服务端请求时，应扩展 `ApiClient` 保留 `requestId` 到受控响应元数据，不能让每个 Repository 绕过统一拦截器。

| 决策 | 启动行为 | 设置页行为 |
| --- | --- | --- |
| `up_to_date` | 继续启动 | 显示已是最新 |
| `optional_update` | 进入业务，轻量提示并本地节流 | 显示说明和“立即更新” |
| `mandatory_update` | 只进入不可跳过的更新页 | 不允许关闭更新页进入业务 |
| `unsupported_client` | 读取固定 `blockReason`；有 `target` 时显示升级/退出/重试，无 `target` 时只显示联系支持、重试和退出 | 与强更相同，不能猜造下载 URL |
| `no_release` | 仅 `internal/beta` 显示诊断并继续；`stable` 视为协议错误，进入阻断错误页并上报 | stable 不得误判为最新 |

启动检查必须在依赖注入和网络层初始化完成后、`AppDependencies.initialize()` 中的 `auth.hydrateFromDisk()`、`AppLifecycleObserver.register()` 与 `auth.initSocketIfNeeded()` 之前执行；不能在应用启动时用阻塞弹窗覆盖根 Navigator。`ClientReleaseCoordinator` 负责启动、前后台恢复、网络恢复、手动检查、提示节流和并发合并；`ClientReleaseProvider` 仅将其结果转为 `ChangeNotifier` UI 状态和用户意图。设置页、路由守卫和更新页都只消费该 Provider，不能各自重新判断版本。

`AppLifecycleObserver` 通过构造函数接收 coordinator 的恢复回调：恢复前台时先异步完成版本检查，只有结果允许访问业务时才执行现有 WebSocket 重连。当前仓库没有统一网络恢复服务，实施前先检查已有能力；确需新增时定义 `ConnectivityService` 边界、在 `AppInjectionModule` 注册并由 coordinator 订阅，不能在 Widget 中直接引入网络监听插件。不得在 Widget 中监听生命周期或从 service locator 反查 Provider。

检查超时、DNS、5xx 和无网络时：如果本地未处于 `mandatory_update/unsupported_client`，继续离线可用流程并记录可重试状态；不能因为检查服务暂时不可达而把全部用户锁在启动页。若上次已得到强更/不支持决策，则仅允许重试、查看下载页和退出。

## 4. 各平台制品与更新实现

| 平台 | 当前基础 | 首发更新实现 | 进入 stable 的额外前置 |
| --- | --- | --- | --- |
| Android | 已有 Gradle 与 Flutter build number | 商店跳转或经签名 APK 安装；不可假装后台静默安装 | APK 签名、包名、buildNumber 与摘要校验 |
| iOS | 已有 iOS Runner | 只跳转 App Store/TestFlight 允许的更新路径 | `CFBundleVersion`、Bundle ID、商店审核和更新链接正确 |
| Windows | 已有 Runner 与 Inno Setup 脚本 | 后续独立方案：首发沿用 `exe`，由受控 updater/helper 下载、SHA-256 与 Authenticode 校验后启动安装器 | x64 包、证书、安装/覆盖安装/失败回退；完成前不启用平台 |
| macOS | 已有 Runner | 后续独立方案：DMG/PKG + updater/helper，校验摘要、Team ID 与公证 | Intel/Apple Silicon 策略明确、签名、公证、隔离属性场景；完成前不启用平台 |
| Linux | 尚无 Runner | 后续独立方案：先创建 Runner，首发只选 Flatpak 或 AppImage 之一 | CI 构建、目标发行版、安装升级与卸载验收；完成前不启用平台 |

当前设置页只是用 `url_launcher` 打开下载地址，无法下载后校验 SHA-256 或安装器签名。新版本不能把该行为描述为“安全自动更新”：桌面端必须新增受控 updater/helper，或在 updater 未落地前只允许商店/人工下载页并不得把直装 Artifact 发布到 stable。移动端与桌面端不得共用“下载 URL 后外跳”的实现。updater 的原生工程、签名证书配置和构建脚本必须与对应 Runner 同仓提交，不能由 Dart 层模拟校验结果。

客户端不得在运行中覆盖自身二进制。Updater 必须在主进程退出后执行，失败时保留旧安装；App 只接收 updater 的结果码。Linux 的包管理器/Flatpak/AppImage 更新差异必须由 Linux 适配层处理，不能写入共享 Dart 业务层。

## 5. 版本、渠道和构建产物约束

1. 每一次 CI 构建输入一个明确的 `version`、`buildNumber`、`channel`、`architecture`，并将这四项写入包元数据和 Release Artifact manifest。`APP_ENV=dev|test|prod` 只选择 API/推送等运行环境，不能代替 `internal|beta|stable` 发布渠道；两组值必须分别注入和审计。
2. `version` 与 `buildNumber` 必须同后端草稿完全一致；CI 创建草稿时服务端校验，不一致则失败。
3. `internal`、`beta`、`stable` 使用不同的构建配置和安装标识策略，避免测试版覆盖 stable。当前 `tools/build.ps1` 只接受 `android/ios/web/windows/hap` 和 `dev/test/prod`，本轮先为 Android/iOS 扩展 `-ReleaseChannel`、`-ReleaseArchitecture` 参数，并保留 `-ApiBase/-WsUri/-MediaBase/-JPushAppKey` 现有参数；不得让构建者手写散落的长命令。Windows/macOS/Linux 的受管构建目标在各自平台启用方案中增加。至少 Android applicationId suffix、iOS bundle identifier/签名和 Windows 安装目录/升级 GUID 必须专项确认。
4. `protocolVersion` 与 App build 独立。只改 UI、安装器或本地修复时不提升协议版本；破坏 REST/WS 协议时先完成协议版本化，再发布新客户端。
5. 包制品、SHA-256、签名元数据必须由 CI 计算并上传到分发存储后生成 Artifact manifest；客户端和人工均不得手填摘要。

构建入口的验收命令按 App 工程现有 `tools/build.ps1` 和 `docs/multi_environment_build_commands.md` 执行；发布前必须保存命令行中的 `APP_ENV`、Release channel、architecture、version 和 buildNumber，确保它们与后端草稿一致。Linux 需要先补齐 `linux/` Runner 和构建入口，不能仅添加一个 `linux` 字符串；在后端未启用平台前，PC 的构建产物只可用于本地/隔离环境验收，不得写入生产 Release API。

## 6. 与后端、Gateway 和动态配置的协作

- 唯一更新检查接口是 `POST /api/v1/client/release-check`；客户端从 `GV_API_BASE` 调用它，不直接访问 `im-admin-service`。
- 在 `ApiClient` 的统一请求拦截器中补齐 `X-Client-Platform`、`X-Client-Channel`、`X-Client-Build-Number`、`X-Client-Protocol-Version`；保留现有 `X-Client-Version` 的兼容观测含义，不能让各 Repository 分别写 Header。Header 缺失或伪造不能成为权限来源。
- `managed` 范围内 WebSocket 在版本检查通过后才申请 ticket 并建立连接；`deferredDesktop/outOfScope` 保持既有业务启动顺序。首发阶段无需改变现有 ticket 协议。未来服务端版本硬拦截时，必须同步扩展 ticket 绑定与接入层校验，不能只拦 REST。
- `GET /config/client` 继续负责动态 Feature Flag。Release check 只给兼容基线，不能缓存或替代动态业务开关。

## 7. App 测试与发布验收

### 7.0 实施顺序与切换闸门

1. **模型与传输**：先定义 release-check DTO、`ImApi` 方法和 Repository 测试，遵循 `ApiClient` 已有的 `{data}` 解包行为，删除旧 GET 端点生成代码；此阶段不接入启动流程。
2. **运行时编排**：在现有 `lib/services`、`lib/providers`、`lib/core` 和 `lib/app` 目录实现 coordinator、Provider、安全 installationId、启动/恢复/网络恢复挂点和设置页复用入口；确保登录恢复与 WebSocket 初始化受 coordinator 的阻断状态控制。
3. **平台更新器**：本轮只完成 Android/iOS：Android `google-play` 仅跳转 `storeUrl`，直装 `apk` 才执行摘要校验；iOS `app-store` 仅跳转。Windows/macOS/Linux 的 updater、安全凭据存储和 E2E 留待各自实施方案完成后再单独开启。
4. **联调切换**：仅在 Gateway 新 POST 和后端五类 decision 已就绪后，合入 Android/iOS 的 App 删除清单；同一发布窗口内请求旧 GET 必须不存在。若新接口尚未可用，停止发布而不是增加 fallback。
5. **渠道演练**：internal 依次验证可选更新、强更、最低版本、暂停、撤回和断网；通过后才允许 beta，stable 仍受各平台安装升级验收约束。

### 7.1 自动化

- DTO/Repository：Android/iOS、三渠道、所有 decision、未知 decision、异常响应、buildNumber 非法、installationId 初始化；桌面 deferred 与 Web/HarmonyOS outOfScope 均不发请求。
- Coordinator：并发检查合并、启动/恢复/网络恢复、可选更新节流、强更持久化、检查失败不误阻断。
- Widget：更新页不可返回、下载/商店按钮、无发布与错误状态、本地化文案。
- Platform adapter：Android/iOS 只走受允许的商店/安装路径；桌面 updater 测试随未来各自平台方案交付。

### 7.2 真机/桌面矩阵

本轮 Android/iOS 至少验证：首次安装、同渠道覆盖升级、升级失败保留旧版、摘要或签名不符、网络中断、暂停/撤回后重新检查、强更、最低版本拦截、设置页手动检查、升级后登录/消息同步/媒体/RTC 不回归。Windows/macOS/Linux 必须在自己的客户端、Runner（Linux）、包格式、updater 和 CI 全部完成后，重复这套矩阵才可启用；在此之前不得创建该平台任一 Release。

### 7.3 App 工程门禁命令

在 `gv_chat_app` 根目录执行，命令顺序遵循现有工程规范：

```powershell
dart run build_runner build
dart format --set-exit-if-changed lib packages/gv_core/lib packages/gv_ui/lib test integration_test
flutter analyze
flutter test
dart run tools/run_integration_tests.dart --target=integration_test/app_startup_test.dart
```

涉及 `tools/build.ps1`、原生 Runner、Channel/Architecture 注入或 `pubspec.yaml` 时，再按 `docs/multi_environment_build_commands.md` 对应平台执行构建；生成文件只由代码生成命令产生，禁止手工改写。

## 8. 完成定义

本轮完成必须同时满足：旧更新代码及其生成物已删除；Android/iOS App 只调用新契约并通过统一 `ApiClient` 解包；启动与设置页共用唯一 coordinator/Provider 链路；受管平台的渠道/构建号/架构来自 CI，且与 `APP_ENV` 分离；Web/HarmonyOS 不发版本检查且不被阻断；动态功能配置仍独立工作；`dart format`、`flutter analyze`、`flutter test`、代码生成检查和 Android/iOS 安装升级演练通过。Windows/macOS/Linux 的直装包、updater、凭据存储和安装升级演练属于后续平台启用门禁，未完成前不得打开后端开关。
