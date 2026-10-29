# Local Kind lifecycle helpers: system recovery, staged health checks and diagnostics.
#
# This file is dot-sourced by kind-local.ps1. It intentionally contains no
# build/deploy logic; it only repairs an existing local cluster and reports
# actionable diagnostics when recovery cannot reach a healthy state.

function Get-KindNodeContainers {
  param([Parameter(Mandatory)][string]$ClusterName)
  return @(& docker ps -a --filter "label=io.x-k8s.kind.cluster=$ClusterName" --format '{{.Names}}')
}

function Test-KubectlApi {
  $previous = $ErrorActionPreference
  try {
    $ErrorActionPreference = 'SilentlyContinue'
    & kubectl get --raw /readyz 2>$null | Out-Null
    return $LASTEXITCODE -eq 0
  } finally {
    $ErrorActionPreference = $previous
  }
}

function Wait-KubectlApi {
  param([int]$TimeoutSeconds = 120)
  $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  do {
    if (Test-KubectlApi) { return }
    Start-Sleep -Seconds 2
  } while ([DateTime]::UtcNow -lt $deadline)
  throw "Kubernetes API did not become ready within ${TimeoutSeconds}s."
}

function Repair-KindSystemImages {
  param([Parameter(Mandatory)][string]$ClusterName)

  $nodes = Get-KindNodeContainers -ClusterName $ClusterName
  if ($nodes.Count -eq 0) { return }
  $components = @('kube-apiserver', 'kube-controller-manager', 'kube-scheduler', 'kube-proxy')
  foreach ($node in $nodes) {
    $refs = @(& docker exec $node ctr -n k8s.io images ls -q 2>$null)
    foreach ($component in $components) {
      $archRef = $refs | Where-Object { $_ -match "^registry\.k8s\.io/$component-amd64:(.+)$" } | Select-Object -First 1
      if (-not $archRef) { continue }
      $null = $archRef -match "^registry\.k8s\.io/$component-amd64:(.+)$"
      $target = "registry.k8s.io/${component}:$($Matches[1])"
      if ($refs -contains $target) { continue }
      Write-Host "Repairing Kind system image alias $target on $node..."
      & docker exec $node ctr -n k8s.io images tag $archRef $target | Out-Null
      if ($LASTEXITCODE -ne 0) {
        throw "Unable to tag $archRef as $target on $node. Run 'kind load docker-image $target --name $ClusterName' or re-create the Kind cluster."
      }
    }
  }
}

function Wait-KindSystemReady {
  param([int]$TimeoutSeconds = 180)

  & kubectl wait --for=condition=Ready nodes --all --timeout="$($TimeoutSeconds)s" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Kind nodes did not become Ready.' }

  $rollouts = @(
    @('deployment', 'coredns'),
    @('daemonset', 'kindnet'),
    @('daemonset', 'kube-proxy')
  )
  foreach ($resource in $rollouts) {
    & kubectl -n kube-system rollout status "$($resource[0])/$($resource[1])" --timeout="$($TimeoutSeconds)s" | Out-Null
    if ($LASTEXITCODE -ne 0) {
      throw "kube-system $($resource[0])/$($resource[1]) did not become ready."
    }
  }
  Wait-KubectlApi -TimeoutSeconds $TimeoutSeconds
}

function Get-StatefulDeploymentNames {
  param([Parameter(Mandatory)][string]$Namespace)
  $names = @(& kubectl -n $Namespace get deployments -o name 2>$null | ForEach-Object { ($_ -split '/')[-1] })
  return @($names | Where-Object { $_ -match '^(mysql|mongodb|redis|minio|rocketmq)' })
}

function Wait-KindStatefulReady {
  param(
    [Parameter(Mandatory)][string]$Namespace,
    [int]$TimeoutSeconds = 240
  )

  foreach ($name in (Get-StatefulDeploymentNames -Namespace $Namespace)) {
    & kubectl -n $Namespace rollout status "deployment/$name" --timeout="$($TimeoutSeconds)s" | Out-Null
    if ($LASTEXITCODE -ne 0) {
      throw "Stateful deployment/$name did not become ready. Data-bearing components are never restarted automatically."
    }
  }

  foreach ($job in @('mysql-saas-init', 'minio-init')) {
    $exists = & kubectl -n $Namespace get job $job --ignore-not-found -o name 2>$null
    if ([string]::IsNullOrWhiteSpace(($exists | Out-String).Trim())) { continue }
    & kubectl -n $Namespace wait --for=condition=complete "job/$job" --timeout="$($TimeoutSeconds)s" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Initialization job/$job did not complete." }
  }
}

function ConvertTo-NormalizedImageReference {
  param([Parameter(Mandatory)][string]$Image)
  $reference = $Image.Trim()
  if ($reference -notmatch '/') { return "docker.io/library/$reference" }
  $first = ($reference -split '/', 2)[0]
  if ($first -notmatch '[.:]' -and $first -ne 'localhost') {
    return "docker.io/$reference"
  }
  return $reference
}

function Get-KindNodeImageRefs {
  param([Parameter(Mandatory)][string]$ClusterName)

  $refs = [System.Collections.Generic.HashSet[string]]::new()
  foreach ($node in (Get-KindNodeContainers -ClusterName $ClusterName)) {
    $lines = @(& docker exec $node crictl images 2>$null)
    foreach ($line in $lines) {
      $parts = ($line -split '\s+') | Where-Object { $_ }
      if ($parts.Count -lt 2 -or $parts[0] -eq 'IMAGE') { continue }
      [void]$refs.Add((ConvertTo-NormalizedImageReference -Image "$($parts[0]):$($parts[1])"))
    }
  }
  return $refs
}

function Ensure-KindDeploymentImages {
  param(
    [Parameter(Mandatory)][string]$ClusterName,
    [Parameter(Mandatory)][string]$Namespace
  )

  $deployments = @(& kubectl -n $Namespace get deployments -o json 2>$null | ConvertFrom-Json).items
  $nodeRefs = @(Get-KindNodeImageRefs -ClusterName $ClusterName)
  $missing = [System.Collections.Generic.List[string]]::new()
  foreach ($deployment in $deployments) {
    if ([int]$deployment.spec.replicas -lt 1) { continue }
    foreach ($container in @($deployment.spec.template.spec.containers)) {
      $image = [string]$container.image
      if ([string]::IsNullOrWhiteSpace($image)) { continue }
      $normalized = ConvertTo-NormalizedImageReference -Image $image
      if ($nodeRefs -contains $normalized) { continue }
      $hostImage = & docker image inspect $image --format '{{.Id}}' 2>$null
      if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($hostImage | Out-String).Trim())) {
        $missing.Add("$($deployment.metadata.name): $image")
        continue
      }
      Write-Host "Loading missing image $image into Kind..."
      & kind load docker-image $image --name $ClusterName | Out-Null
      if ($LASTEXITCODE -ne 0) { throw "Unable to load $image into Kind." }
      $nodeRefs = Get-KindNodeImageRefs -ClusterName $ClusterName
    }
  }
  if ($missing.Count -gt 0) {
    throw "Images are missing from both Docker and Kind:`n$($missing -join "`n")`nRebuild with '.\deploy-k8s.ps1 -ForceRebuild' or provide a release manifest."
  }
}

function Get-UnhealthyDeployments {
  param(
    [Parameter(Mandatory)][string]$Namespace,
    [Parameter(Mandatory)][string[]]$StatefulNames
  )

  $deployments = @(& kubectl -n $Namespace get deployments -o json 2>$null | ConvertFrom-Json).items
  $unhealthy = [System.Collections.Generic.List[string]]::new()
  foreach ($deployment in $deployments) {
    $name = [string]$deployment.metadata.name
    if ($StatefulNames -contains $name) { continue }
    $desired = if ($null -eq $deployment.spec.replicas) { 1 } else { [int]$deployment.spec.replicas }
    $availableProperty = if ($null -eq $deployment.status) { $null } else { $deployment.status.PSObject.Properties['availableReplicas'] }
    $available = if ($null -eq $availableProperty) { 0 } else { [int]$availableProperty.Value }
    if ($desired -gt 0 -and $available -lt $desired) { $unhealthy.Add($name) }
  }
  return @($unhealthy)
}

function Restart-KindUnhealthyDeployments {
  param(
    [Parameter(Mandatory)][string]$Namespace,
    [Parameter(Mandatory)][string[]]$Names,
    [int]$TimeoutSeconds = 240
  )

  # Restart order follows service dependency direction; gateway is intentionally last.
  $order = @(
    'platform-identity-service', 'platform-tenant-service', 'platform-order-service',
    'platform-resource-service', 'platform-customer-service', 'platform-marketing-service',
    'common-audit-service', 'common-mail-service', 'common-media-service',
    'common-payment-service', 'common-payment-channel-service', 'common-sms-service',
    'im-user-service', 'im-conversation-service', 'im-message-service', 'im-access-ws',
    'im-admin-service', 'group-idaas-service', 'saas-admin', 'saas-mobile', 'pc-admin',
    'open-website', 'unified-portal', 'gateway'
  )
  $sorted = @($Names | Sort-Object @{ Expression = {
    $index = [Array]::IndexOf($order, $_)
    if ($index -lt 0) { 999 } else { $index }
  } })
  $failed = [System.Collections.Generic.List[string]]::new()
  foreach ($name in $sorted) {
    Write-Host "Restarting unhealthy deployment/$name..."
    & kubectl -n $Namespace rollout restart "deployment/$name" | Out-Null
    if ($LASTEXITCODE -ne 0) { $failed.Add($name); continue }
    & kubectl -n $Namespace rollout status "deployment/$name" --timeout="$($TimeoutSeconds)s" | Out-Null
    if ($LASTEXITCODE -ne 0) { $failed.Add($name) }
  }
  if ($failed.Count -gt 0) { throw "Deployments did not become ready: $($failed -join ', ')" }
}

function Test-KindLocalEndpoints {
  param(
    [string]$BaseUrl = 'http://127.0.0.1:30080',
    [int]$Attempts = 20
  )

  for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
    try {
      $sass = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/" -TimeoutSec 5
      $admin = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/saas/" -TimeoutSec 5
      $health = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/actuator/health" -TimeoutSec 5
      $healthContent = if ($health.Content -is [byte[]]) {
        [System.Text.Encoding]::UTF8.GetString($health.Content)
      } else {
        [string]$health.Content
      }
      $healthJson = $healthContent | ConvertFrom-Json
      if ($sass.StatusCode -eq 200 -and $admin.StatusCode -eq 200 -and $healthJson.status -eq 'UP') { return $true }
    } catch {
      if ($attempt -eq $Attempts) { return $false }
    }
    Start-Sleep -Seconds 3
  }
  return $false
}

function Write-KindDiagnostics {
  param(
    [Parameter(Mandatory)][string]$Root,
    [Parameter(Mandatory)][string]$Namespace,
    [Parameter(Mandatory)][string]$Stage
  )

  $logDir = Join-Path $Root '.outputs\local-deployment'
  if (!(Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
  $logPath = Join-Path $logDir ("recover-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
  $lines = [System.Collections.Generic.List[string]]::new()
  $lines.Add("Recovery failed at stage: $Stage")
  $lines.Add("Generated at: $(Get-Date -Format o)")
  $lines.Add('')
  $lines.Add('=== nodes ===')
  $lines.Add((& kubectl get nodes -o wide 2>&1 | Out-String))
  $lines.Add('=== deployments ===')
  $lines.Add((& kubectl -n $Namespace get deployments 2>&1 | Out-String))
  $lines.Add('=== pods ===')
  $lines.Add((& kubectl -n $Namespace get pods -o wide 2>&1 | Out-String))
  $pods = @(& kubectl -n $Namespace get pods -o json 2>$null | ConvertFrom-Json).items |
    Where-Object {
      $phase = [string]$_.status.phase
      if ($phase -notin @('Running', 'Succeeded')) { return $true }
      if ($phase -eq 'Running') {
        return @($_.status.containerStatuses | Where-Object { -not $_.ready }).Count -gt 0
      }
      return $false
    } |
    Select-Object -First 8
  foreach ($pod in $pods) {
    $name = [string]$pod.metadata.name
    $lines.Add("=== describe $name ===")
    $lines.Add((& kubectl -n $Namespace describe pod $name 2>&1 | Select-Object -Last 40 | Out-String))
    $lines.Add("=== logs $name ===")
    $lines.Add((& kubectl -n $Namespace logs $name --all-containers --tail=60 2>&1 | Out-String))
  }
  [System.IO.File]::WriteAllLines($logPath, [string[]]$lines, [System.Text.UTF8Encoding]::new($false))
  Write-Host "Recovery diagnostics written to $logPath" -ForegroundColor Yellow
  $lines | Select-Object -First 80 | ForEach-Object { Write-Host $_ }
}

function Invoke-KindRecovery {
  param(
    [Parameter(Mandatory)][string]$Root,
    [Parameter(Mandatory)][string]$ClusterName,
    [Parameter(Mandatory)][string]$Namespace,
    [int]$TimeoutSeconds = 600
  )

  $stage = 'system-images'
  try {
    Repair-KindSystemImages -ClusterName $ClusterName
    $stage = 'kubernetes-api'
    Wait-KubectlApi -TimeoutSeconds 120
    $namespaceName = (& kubectl get namespace $Namespace --ignore-not-found -o name 2>$null | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($namespaceName)) {
      throw "Namespace '$Namespace' does not exist. Use -Mode init for first deployment."
    }
    $stage = 'system-components'
    Wait-KindSystemReady -TimeoutSeconds 180
    $stage = 'stateful-infrastructure'
    Wait-KindStatefulReady -Namespace $Namespace -TimeoutSeconds 240
    $stage = 'deployment-images'
    Ensure-KindDeploymentImages -ClusterName $ClusterName -Namespace $Namespace
    $stage = 'application-deployments'
    $stateful = @(Get-StatefulDeploymentNames -Namespace $Namespace)
    $unhealthy = @(Get-UnhealthyDeployments -Namespace $Namespace -StatefulNames $stateful)
    if ($unhealthy.Count -gt 0) {
      Restart-KindUnhealthyDeployments -Namespace $Namespace -Names $unhealthy -TimeoutSeconds 240
    }
    $stage = 'endpoint-smoke'
    if (-not (Test-KindLocalEndpoints)) { throw 'Local endpoints did not become healthy.' }
    Write-Host 'Local Kind recovery completed successfully.' -ForegroundColor Green
    & kubectl -n $Namespace get pods
  } catch {
    Write-KindDiagnostics -Root $Root -Namespace $Namespace -Stage $stage
    throw
  }
}

function Set-KindRuntimeProfile {
  param(
    [Parameter(Mandatory)][string]$Namespace,
    [Parameter(Mandatory)][ValidateSet('full', 'minimal')][string]$Profile
  )

  # Minimal keeps the SaaS administration path plus backend services. Optional
  # public sites are scaled down; image build still covers the full release.
  $optional = @('open-website', 'pc-admin', 'saas-mobile')
  $replicas = if ($Profile -eq 'minimal') { 0 } else { 1 }
  foreach ($name in $optional) {
    $exists = & kubectl -n $Namespace get deployment $name --ignore-not-found -o name 2>$null
    if ([string]::IsNullOrWhiteSpace(($exists | Out-String).Trim())) { continue }
    & kubectl -n $Namespace scale "deployment/$name" --replicas=$replicas | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to apply runtime profile to deployment/$name." }
  }
}

function Get-KindLocalStatus {
  param(
    [Parameter(Mandatory)][string]$ClusterName,
    [Parameter(Mandatory)][string]$Namespace
  )

  Write-Host "Kind cluster: $ClusterName"
  Write-Host "Namespace:    $Namespace"
  & kubectl get nodes -o wide | Out-Host
  if ($LASTEXITCODE -ne 0) { return 2 }
  Write-Host ''
  & kubectl -n $Namespace get deployments | Out-Host
  if ($LASTEXITCODE -ne 0) { return 2 }
  Write-Host ''
  $pods = @(& kubectl -n $Namespace get pods -o json 2>$null | ConvertFrom-Json).items
  $badPods = @($pods | Where-Object {
    $phase = [string]$_.status.phase
    if ($phase -in @('Pending', 'Failed', 'Unknown')) { return $true }
    if ($phase -eq 'Running') {
      return @($_.status.containerStatuses | Where-Object { -not $_.ready }).Count -gt 0
    }
    return $false
  })
  if ($badPods.Count -gt 0) {
    Write-Host 'Unhealthy pods:' -ForegroundColor Yellow
    foreach ($pod in $badPods) {
      Write-Host "  - $($pod.metadata.name) ($($pod.status.phase))"
      foreach ($container in @($pod.status.containerStatuses)) {
        if ($container.state.PSObject.Properties['waiting']) {
          Write-Host "      $($container.name): $($container.state.waiting.reason) $($container.state.waiting.message)"
        }
      }
    }
  }
  $endpointHealthy = Test-KindLocalEndpoints -Attempts 2
  Write-Host "Endpoints healthy: $endpointHealthy"
  if ($badPods.Count -gt 0 -or -not $endpointHealthy) { return 2 }
  return 0
}
