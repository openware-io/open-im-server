# 文本编码与批量改写规范

[中文](30_TEXT_ENCODING_AND_BATCH_EDITING.md) | [English](30_TEXT_ENCODING_AND_BATCH_EDITING.en.md)

## 目标与适用范围

**目标**：让仓库内的文本**始终是 UTF-8 且不被二次编码**，并让批量改写**可安全执行、可校验**；中文注释与文案是本项目的正常内容，不是需要规避的风险。

**适用范围**：源码（Java/TypeScript/Dart/Vue 等）、脚本（`.ps1`/`.bat`/`.sh`）、Markdown、配置文件（`.yml`/`.properties`/`.xml`）的读取、写回与批量替换。

**非目标**：不规定运行期输出流与数据库连接的字符集（属各技术栈规范）；不规定文档内容如何组织。

## 术语与前置假设

| 术语 | 定义 |
| --- | --- |
| UTF-8 无 BOM | 本项目文本文件的统一形态；BOM 会被门禁拦截。 |
| 本地代码页 | Windows 上的 ANSI 代码页（如 GBK/936）。裸 `Get-Content` 默认按它解码。 |
| 乱码（mojibake） | 中文先按错误代码页解码、再按 UTF-8 写回后的二次编码结果，如 `鎺`、`娑`、`濞`。 |
| 可执行脚本 | `.ps1`/`.bat`/`.cmd`：宿主按 ANSI 解码无 BOM 文件，故这类文件必须**纯 ASCII**。 |
| 批量改写 | 对多个文件做机械替换的操作，必须可枚举、可校验、可回滚。 |

**前置假设**：Windows PowerShell 5 是主要执行环境（无 BOM 的 `.ps1` 按 ANSI 解码）；仓库已有编码门禁与 ASCII 门禁。

## 问题背景

本项目源码、脚本、Markdown 和配置文件统一要求使用 UTF-8。

在 Windows PowerShell 5 环境下，**无 BOM 的 UTF-8 文件如果使用裸 `Get-Content` / `Set-Content` / `Out-File` 处理，极易被按本地代码页读取**。  
一旦中文先被错误解码，再按 UTF-8 写回，内容就会变成类似 `鎺`、`娑`、`濞` 这类乱码。

这类问题最容易出现在：

1. 批量替换 import、注解、日志文案时
2. 脚本自动修注释或批量改文档时
3. 临时在终端里写一次性 PowerShell 文本改写命令时

## 根因结论

本次乱码回归的根因不是 Java 编译器，也不是 Git。

根因是：

1. 在 Windows PowerShell 5 下对 UTF-8 无 BOM 文件进行了批量文本改写
2. 读取时没有显式指定 UTF-8，触发了默认代码页解码
3. 后续又按 UTF-8 写回，导致中文内容被二次错误编码

## 强制规则

1. **禁止**在仓库代码批量改写中裸用 `Get-Content` 读取 UTF-8 文本。
2. **禁止**在仓库代码批量改写中裸用 `Set-Content`、`Out-File` 写回文本。
3. 读取源码、文档、配置文件时，必须满足以下任一方式：
   - `Get-Content -Encoding utf8`
   - `[System.IO.File]::ReadAllBytes(...)` 后按 UTF-8 显式解码
4. 写回源码、文档、配置文件时，必须满足以下任一方式：
   - `Set-Content -Encoding utf8`
   - `[System.IO.File]::WriteAllText(..., [System.Text.UTF8Encoding]::new($false))`
5. 对中文内容做批量替换后，必须执行编码校验与编译校验，不能只看 diff。

## 推荐做法

### 读取 UTF-8

```powershell
$text = Get-Content $path -Raw -Encoding utf8
```

或：

```powershell
$bytes = [System.IO.File]::ReadAllBytes($path)
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)
$text = $utf8.GetString($bytes)
```

### 写回 UTF-8 无 BOM

```powershell
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($path, $text, $utf8NoBom)
```

## 禁止示例

```powershell
$text = Get-Content $path -Raw
$text | Out-File $path
Set-Content $path $text
```

以上写法在 Windows PowerShell 5 下会把 UTF-8 无 BOM 文件写坏。**读取同样必须显式指定 UTF-8**：Windows PowerShell 5.1 会把无 BOM 的 UTF-8 文件按 ANSI（GBK）解码，导致中文内容显示或匹配为乱码（本仓库曾因此得出错误结论）；脚本与审查都应使用 `[System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))` 或等效的显式 UTF-8 读取。此外，**`scripts/validate/*.ps1` 必须保持 ASCII-only**：无 BOM 的 `.ps1` 会被按 ANSI 解析，脚本内的中文正则或文案会被破坏。

## 门禁

1. `scripts/validate/validate-text-encoding.ps1 -Strict`
2. `scripts/validate/validate-java-comment-quality.ps1`
3. `scripts/validate/validate-powershell-utf8-safety.ps1`
4. `scripts/validate/validate-powershell-ascii-only.ps1`

第 4 条把上述 ASCII-only 约定变成可执行的检查：默认扫描 `scripts/**/*.ps1`（原因是全仓 `.ps1` 均为无 BOM 文件，Windows CI 用 `powershell.exe` 执行，风险面比本规范原文的 `scripts/validate/*.ps1` 更宽）；既有违规文件登记在 `scripts/validate/powershell-ascii-allowlist.txt`，逐条注明清理批次与原因，且**列表只能缩短**——文件一旦变为纯 ASCII 而未删除条目，门禁同样报错。

发布前统一通过[统一工程校验入口](../governance/ENGINEERING_RULES.md)执行（`scripts/validate/invoke-engineering-validation.ps1`）。

## 可验证的验收标准

| # | 判据 | 证据形式 |
| --- | --- | --- |
| 1 | 全部文本文件为 UTF-8 无 BOM，且无替换字符与 UTF8-GBK 乱码 | `validate-text-encoding.ps1 -Strict` 通过 |
| 2 | 批量改写脚本**不裸用** `Get-Content`/`Set-Content`/`Out-File` | 脚本审查或静态检查结果 |
| 3 | `.ps1`/`.bat`/`.cmd` 为纯 ASCII；allowlist 只减不增 | ASCII 门禁通过，且无陈旧条目 |
| 4 | 批量替换后执行了编码校验（不能只看 diff） | 校验命令与退出码 |
| 5 | 含中文的批量改写抽样若干文件，内容与替换前语义一致 | 抽样 diff 与人工确认 |

**完成定义**：上表逐项有可复现证据，且统一工程校验入口在该范围退出码为 0。

## 与其他规范的关系

| 关系 | 文档 | 说明 |
| --- | --- | --- |
| 引用 | [42_LOGGING_AND_OUTPUTS.md](42_LOGGING_AND_OUTPUTS.md) | 日志与诊断产物的写出同样受本文编码口径约束 |
| 引用 | [41_JAVA_LOGGING_CONVENTIONS.md](41_JAVA_LOGGING_CONVENTIONS.md) | 中文日志文案的编码基础 |
| 引用 | [20_MAVEN_ENGINEERING_CONVENTIONS.md](20_MAVEN_ENGINEERING_CONVENTIONS.md) | 编码异常常在构建/校验阶段暴露，两者的门禁一并执行 |
| 关联 | [ENGINEERING_RULES.md](../governance/ENGINEERING_RULES.md) | 统一工程校验入口与批量改写纪律的项目级要求 |

