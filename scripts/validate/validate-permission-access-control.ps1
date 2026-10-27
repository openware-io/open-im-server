param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$errors = [System.Collections.Generic.List[string]]::new()

$controllerDir = Join-Path $root 'platform-services/admin/platform-admin-service/src/main/java/io/openware/platform/admin/api/controller'
$authOnly = @(
  'AdminLoginController.java',
  'AdminSessionController.java',
  'SsoAuthController.java',
  'ContextController.java',
  'AdminMenuController.java',
  'AdminBackendController.java'
)

if (!(Test-Path -LiteralPath $controllerDir)) {
  $errors.Add("Admin controller directory is missing: $controllerDir")
} else {
  foreach ($file in Get-ChildItem -LiteralPath $controllerDir -Filter '*.java' -File) {
    if ($authOnly -contains $file.Name) { continue }
    $source = Get-Content -LiteralPath $file.FullName -Raw
    $guarded = $source -match 'PermissionGuard\.require' `
      -or $source -match 'requirePlatformOperator' `
      -or $source -match 'requireManageContext' `
      -or (($source -match 'requireContext') -and ($source -match 'iam\.role\.manage')) `
      -or $source -match 'tenant\.overview\.view'
    if (!$guarded) {
      $errors.Add("Controller has no explicit permission guard: $($file.Name)")
    }
  }
}

$filterRegistrations = @(
  'platform-services/admin/platform-admin-service/src/main/java/io/openware/platform/admin/config/TenantFilterConfig.java',
  'platform-services/order/platform-order-service/src/main/java/io/openware/platform/order/config/TenantFilterConfig.java'
)
foreach ($relative in $filterRegistrations) {
  $path = Join-Path $root $relative
  if (!(Test-Path -LiteralPath $path)) {
    $errors.Add("Store scope filter config is missing: $relative")
    continue
  }
  $source = Get-Content -LiteralPath $path -Raw
  if ($source -notmatch 'StoreScopeRequestFilter' -or $source -notmatch 'addUrlPatterns\("/admin/\*", "/business/\*"\)') {
    $errors.Add("StoreScopeRequestFilter is not registered for admin/business routes: $relative")
  }
}

$businessGuards = @(
  @{ Path = 'platform-services/admin/platform-admin-service/src/main/java/io/openware/platform/admin/api/controller/KtvConfigController.java'; Marker = 'BusinessScopeGuard.require\("KTV"\)' },
  @{ Path = 'platform-services/admin/platform-admin-service/src/main/java/io/openware/platform/admin/api/controller/ReservationController.java'; Marker = 'BusinessScopeGuard.require\("KTV"\)' }
)
foreach ($guard in $businessGuards) {
  $path = Join-Path $root $guard.Path
  if (!(Test-Path -LiteralPath $path)) {
    $errors.Add("Business-scoped controller is missing: $($guard.Path)")
    continue
  }
  $source = Get-Content -LiteralPath $path -Raw
  if ($source -notmatch $guard.Marker) {
    $errors.Add("Missing KTV business scope guard: $($guard.Path)")
  }
}

$permissionCodes = [System.Collections.Generic.HashSet[string]]::new()
foreach ($file in Get-ChildItem -LiteralPath $controllerDir -Filter '*.java' -File) {
  $source = Get-Content -LiteralPath $file.FullName -Raw
  foreach ($match in [regex]::Matches($source, 'PermissionGuard\.require\("([^"]+)"\)')) {
    [void]$permissionCodes.Add($match.Groups[1].Value)
  }
}
$migrationFiles = Get-ChildItem -LiteralPath (Join-Path $root 'platform-services') -Recurse -Filter '*.sql' -File |
  Where-Object { $_.FullName -match '[\\/]db[\\/]migration[\\/]' }
$migrationText = ($migrationFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
foreach ($code in $permissionCodes) {
  if ($migrationText -notmatch ("'" + [regex]::Escape($code) + "'")) {
    $errors.Add("Permission code is used in a controller but not declared in a Flyway migration: $code")
  }
}

if ($errors.Count -gt 0) {
  $errors | ForEach-Object { Write-Error $_ }
  exit 1
}

Write-Output "Permission access-control validation passed ($($permissionCodes.Count) controller permission codes)."
