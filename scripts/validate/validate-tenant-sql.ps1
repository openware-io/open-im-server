# Tenant isolation SQL checker: verify tenant-scoped tables have tenant_id in Flyway.
# Usage: pwsh scripts/validate/validate-tenant-sql.ps1 -Path <flyway-dir>
# Exit: 0 = pass; 1 = tenant table missing tenant_id
param(
    [string]$Path = 'D:\projects\cnb\open_im_server\platform-services'
)
$ErrorActionPreference = 'Stop'

$tenantPrefixes = @('tnt_organization','tnt_store','tnt_tenant_config','tnt_merchant_account',
  'tnt_store_payment_config','iam_role','iam_user_role',
  'res_','ord_order','ord_order_item','ord_ktv_','ord_hotel_','ord_spa_','pay_','cst_','mkt_')
$platformTables = @('tnt_tenant','tnt_business_type','iam_permission','iam_role_permission','idt_')

$sqlFiles = Get-ChildItem -Path $Path -Recurse -Filter 'V*.sql' | Select-Object -ExpandProperty FullName
if (-not $sqlFiles) { Write-Host 'No Flyway SQL found under:' $Path; exit 0 }

$errors = @()
foreach ($f in $sqlFiles) {
    $content = Get-Content -Raw -Encoding UTF8 $f
    $m = [regex]::Matches($content, 'CREATE\s+TABLE\s+[^a-zA-Z0-9_]*([a-zA-Z0-9_]+)')
    foreach ($x in $m) {
        $table = $x.Groups[1].Value
        $isPlatform = $false
        foreach ($p in $platformTables) { if ($table.StartsWith($p)) { $isPlatform = $true; break } }
        if ($isPlatform) { continue }
        $isTenant = $false
        foreach ($p in $tenantPrefixes) { if ($table.StartsWith($p)) { $isTenant = $true; break } }
        if (-not $isTenant) { continue }
        $start = $x.Index
        $nextIdx = $content.IndexOf('CREATE', $start + 1)
        $body = if ($nextIdx -ge 0) { $content.Substring($start, $nextIdx - $start) } else { $content.Substring($start) }
        if ($body -notmatch 'tenant_id') { $errors += ($table + ' missing tenant_id (file ' + [IO.Path]::GetFileName($f) + ')') }
    }
}

if ($errors.Count -gt 0) {
    Write-Host '=== tenant isolation SQL check FAILED ==='
    $errors | ForEach-Object { Write-Host '  ' $_ }
    exit 1
}
Write-Host 'tenant isolation SQL check PASSED'
exit 0
