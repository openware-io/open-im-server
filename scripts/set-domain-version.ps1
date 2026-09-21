param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('platform', 'user', 'message', 'conversation', 'admin', 'gateway', 'access-ws', 'platform-identity', 'platform-tenant', 'platform-resource', 'platform-order', 'platform-customer', 'platform-marketing', 'platform-admin', 'payment', 'payment-channel', 'media', 'audit', 'sms', 'mail', 'idaas')]
    [string]$Domain,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version,

    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'
$domainDefinitions = @{
    platform = @{ Parent = 'sdk/pom.xml'; Artifact = 'sdk'; Leaves = @('sdk/common/pom.xml', 'sdk/protocol-ws/pom.xml', 'sdk/protocol-mq/pom.xml', 'sdk/infrastructure/pom.xml') }
    user = @{ Parent = 'im-services/user/pom.xml'; Artifact = 'im-user'; Leaves = @('im-services/user/im-user-api/pom.xml', 'im-services/user/im-user-service/pom.xml') }
    message = @{ Parent = 'im-services/message/pom.xml'; Artifact = 'im-message'; Leaves = @('im-services/message/im-message-api/pom.xml', 'im-services/message/im-message-service/pom.xml') }
    conversation = @{ Parent = 'im-services/conversation/pom.xml'; Artifact = 'im-conversation'; Leaves = @('im-services/conversation/im-conversation-api/pom.xml', 'im-services/conversation/im-conversation-service/pom.xml') }
    admin = @{ Parent = 'im-services/admin/pom.xml'; Artifact = 'im-admin'; Leaves = @('im-services/admin/im-admin-api/pom.xml', 'im-services/admin/im-admin-service/pom.xml') }
    gateway = @{ Parent = 'gateways/gateway/pom.xml'; Artifact = 'gateway'; Leaves = @() }
    'access-ws' = @{ Parent = 'gateways/im-access-ws/pom.xml'; Artifact = 'im-access-ws'; Leaves = @() }
    'platform-identity' = @{ Parent = 'platform-services/identity/pom.xml'; Artifact = 'platform-identity'; Leaves = @('platform-services/identity/platform-identity-api/pom.xml', 'platform-services/identity/platform-identity-service/pom.xml') }
    'platform-tenant' = @{ Parent = 'platform-services/tenant/pom.xml'; Artifact = 'platform-tenant'; Leaves = @('platform-services/tenant/platform-tenant-api/pom.xml', 'platform-services/tenant/platform-tenant-service/pom.xml') }
    'platform-resource' = @{ Parent = 'platform-services/resource/pom.xml'; Artifact = 'platform-resource'; Leaves = @('platform-services/resource/platform-resource-api/pom.xml', 'platform-services/resource/platform-resource-service/pom.xml') }
    'platform-order' = @{ Parent = 'platform-services/order/pom.xml'; Artifact = 'platform-order'; Leaves = @('platform-services/order/platform-order-api/pom.xml', 'platform-services/order/platform-order-service/pom.xml') }
    'platform-customer' = @{ Parent = 'platform-services/customer/pom.xml'; Artifact = 'platform-customer'; Leaves = @('platform-services/customer/platform-customer-api/pom.xml', 'platform-services/customer/platform-customer-service/pom.xml') }
    'platform-marketing' = @{ Parent = 'platform-services/marketing/pom.xml'; Artifact = 'platform-marketing'; Leaves = @('platform-services/marketing/platform-marketing-api/pom.xml', 'platform-services/marketing/platform-marketing-service/pom.xml') }
    'platform-admin' = @{ Parent = 'platform-services/admin/pom.xml'; Artifact = 'platform-admin'; Leaves = @('platform-services/admin/platform-admin-api/pom.xml', 'platform-services/admin/platform-admin-service/pom.xml') }
    payment = @{ Parent = 'common-services/payment/pom.xml'; Artifact = 'common-payment'; Leaves = @('common-services/payment/common-payment-api/pom.xml', 'common-services/payment/common-payment-service/pom.xml') }
    'payment-channel' = @{ Parent = 'common-services/payment-channel/pom.xml'; Artifact = 'common-payment-channel'; Leaves = @('common-services/payment-channel/common-payment-channel-api/pom.xml', 'common-services/payment-channel/common-payment-channel-service/pom.xml') }
    media = @{ Parent = 'common-services/media/pom.xml'; Artifact = 'common-media'; Leaves = @('common-services/media/common-media-api/pom.xml', 'common-services/media/common-media-service/pom.xml') }
    audit = @{ Parent = 'common-services/audit/pom.xml'; Artifact = 'audit'; Leaves = @('common-services/audit/common-audit-api/pom.xml', 'common-services/audit/common-audit-service/pom.xml') }
    sms = @{ Parent = 'common-services/sms/pom.xml'; Artifact = 'sms'; Leaves = @('common-services/sms/common-sms-api/pom.xml', 'common-services/sms/common-sms-service/pom.xml') }
    mail = @{ Parent = 'common-services/mail/pom.xml'; Artifact = 'mail'; Leaves = @('common-services/mail/common-mail-api/pom.xml', 'common-services/mail/common-mail-service/pom.xml') }
    idaas = @{ Parent = 'group-services/idaas/pom.xml'; Artifact = 'group-idaas'; Leaves = @('group-services/idaas/group-idaas-api/pom.xml', 'group-services/idaas/group-idaas-service/pom.xml') }
}

function Write-Utf8File {
    param([string]$Path, [string]$Content)

    if ([string]::IsNullOrEmpty($Content)) { throw "Refusing to write empty content to $Path." }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

function Replace-Version {
    param([string]$PomPath, [string]$Pattern, [string]$NewVersion)

    $content = [System.IO.File]::ReadAllText($PomPath, [System.Text.Encoding]::UTF8)
    if ([string]::IsNullOrEmpty($content)) { throw "POM is empty or unreadable: $PomPath." }
    # 必须用 ${1} 而非 $1：后者会与紧随其后的版本号连成 "$11.0.30"，被正则当作第 11 个捕获组。
    $updated = [regex]::Replace($content, $Pattern, "`${1}$NewVersion", 1)
    if ($updated -eq $content) {
        throw "Version node was not found: $PomPath."
    }
    # 护栏：替换结果必须是仍可解析的 XML，且包含预期版本号；否则拒绝落盘（历史上曾出现写空文件）。
    if ($updated.Length -lt ($content.Length / 2)) { throw "Refusing to shrink $PomPath drastically (content $($content.Length) -> $($updated.Length) chars)." }
    try { [xml]$null = $updated } catch { throw "Version replacement produced invalid XML for ${PomPath}: $($_.Exception.Message)" }
    if ($updated -notmatch [regex]::Escape($NewVersion)) { throw "Replacement did not apply version $Version to $PomPath." }
    Write-Utf8File -Path $PomPath -Content $updated
}

$definition = $domainDefinitions[$Domain]
$parentPom = Join-Path $Root $definition.Parent
Replace-Version -PomPath $parentPom -Pattern "(<artifactId>$([regex]::Escape($definition.Artifact))</artifactId>\s*<version>)[^<]+" -NewVersion $Version

foreach ($leaf in $definition.Leaves) {
    $leafPom = Join-Path $Root $leaf
    Replace-Version -PomPath $leafPom -Pattern "(<parent>[\s\S]*?<artifactId>$([regex]::Escape($definition.Artifact))</artifactId>\s*<version>)[^<]+" -NewVersion $Version
}

& (Join-Path $Root 'scripts\validate\validate-maven-version-ownership.ps1') -Root $Root
Write-Host "Updated $Domain domain version to $Version."
