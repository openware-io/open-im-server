# 文本编码与批量改写规范

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

以上写法在 Windows PowerShell 5 下会把 UTF-8 无 BOM 文件写坏。

## 门禁

1. `scripts/validate/validate-text-encoding.ps1 -Strict`
2. `scripts/validate/validate-java-comment-quality.ps1`
3. `scripts/validate/validate-powershell-utf8-safety.ps1`

发布前统一通过 `release_manager/verify.bat` 执行。
