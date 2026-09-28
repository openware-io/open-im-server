function Get-ApprovedReleaseImage {
  param(
    [string]$Name,
    [object]$Entry,
    [string]$Registry,
    [string]$RepositoryNamespace
  )
  if (!$Entry) { throw "Release manifest missing service: $Name" }
  foreach ($field in @('tag', 'moduleVersion', 'image', 'digest')) {
    if (!$Entry.PSObject.Properties[$field] -or !$Entry.$field) { throw "Release manifest missing ${field}: $Name" }
  }
  $tag = [string]$Entry.tag
  $moduleVersion = [string]$Entry.moduleVersion
  $baseVersion = $moduleVersion -replace '-SNAPSHOT$', ''
  $tagPattern = if ($moduleVersion -match '-SNAPSHOT$') { "^$baseVersion-SNAPSHOT$" } else { "^$baseVersion$" }
  if ($baseVersion -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' -or $moduleVersion -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-SNAPSHOT)?$' -or $tag -notmatch $tagPattern) {
    throw "Image tag does not meet the immutable release tag rule: ${Name}:$tag (module $moduleVersion)"
  }
  $repository = if ($RepositoryNamespace) { "$($Registry.TrimEnd('/'))/$($RepositoryNamespace.Trim('/'))/$Name" } else { "$($Registry.TrimEnd('/'))/$Name" }
  $image = "${repository}:$tag"
  $digest = [string]$Entry.digest
  $legacyDigestReference = if ($Entry.PSObject.Properties['imageDigest']) { [string]$Entry.imageDigest } else { '' }
  if ($digest -notmatch '^sha256:[a-f0-9]{64}$' -or $Entry.image -ne $image -or [string]$Entry.image -match '@sha256:' -or $legacyDigestReference -match '@sha256:') {
    throw "Release manifest contains an invalid digest-suffixed image reference: $Name"
  }
  if ($env:OPEN_IM_OFFLINE_LOCAL -eq '1') {
    return $image
  }
  for ($attempt = 1; $attempt -le 3; $attempt++) {
    $savedPreference = $ErrorActionPreference
    try {
      $ErrorActionPreference = 'Continue'
      $manifestJson = & docker buildx imagetools inspect $image --format '{{json .Manifest}}' 2>$null
      $inspectExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($inspectExitCode -eq 0) {
      $actual = [string](($manifestJson | Out-String | ConvertFrom-Json).digest)
      if ($actual -ne $digest) { throw "ACR tag/digest mismatch: $image; expected $digest, got $actual" }
      return $image
    }
    if ($attempt -lt 3) { Start-Sleep -Seconds $attempt }
  }
  if ($env:OPEN_IM_KIND_PRIVATE_LOCAL -eq '1') {
    Write-Warning "GHCR verification unavailable; using the already verified local Kind release manifest for $image."
    return $image
  }
  throw "Cannot verify approved ACR image: $image"
}
