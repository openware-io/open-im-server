# Hotel OTA channel v1 request helper (reused by the C-side / OTA E2E script).
#
# Usage:
#   . (Join-Path $PSScriptRoot 'ota-channel-client.ps1')
#   $resp = Invoke-OtaChannelRequest -Gateway http://127.0.0.1:30080 -Channel sandbox `
#       -Secret $secret -Method POST -Path /api/v1/open/hotel/v1/bookings `
#       -BodyJson $jsonString
#   $resp.StatusCode / $resp.Body
#
# Compatible with Windows PowerShell 5.1 (run-e2e-domain.ps1 starts it with powershell -File)
# and pwsh 7.
#
# Contract (see docs/50-services/platform/platform-order-service/HOTEL_OTA_CHANNEL_V1.md):
#   canonical = code\nMETHOD\npath\nquery\ntimestamp\nsha256hex(bodyBytes)
#   The signature is computed over the exact bytes that are sent. This helper converts the
#   body to UTF-8 once and uses the same bytes for signing and for -Body.

function Get-OtaChannelBodyHash {
    param([Parameter(Mandatory = $true)][byte[]]$Body)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return (-join ($sha.ComputeHash($Body) | ForEach-Object { $_.ToString('x2') }))
    } finally {
        $sha.Dispose()
    }
}

function New-OtaChannelHeaders {
    param(
        [Parameter(Mandatory = $true)][string]$Channel,
        [Parameter(Mandatory = $true)][string]$Secret,
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Query = '',
        [byte[]]$Body = [byte[]]@(),
        [long]$Timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    )
    $canonical = "$Channel`n$($Method.ToUpperInvariant())`n$Path`n$Query`n$Timestamp`n$(Get-OtaChannelBodyHash -Body $Body)"
    $hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
    try {
        $signature = -join ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)) | ForEach-Object { $_.ToString('x2') })
    } finally {
        $hmac.Dispose()
    }
    return @{
        'X-Channel-Code'      = $Channel
        'X-Channel-Timestamp' = "$Timestamp"
        'X-Channel-Signature' = $signature
    }
}

function Invoke-OtaChannelRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Gateway,
        [Parameter(Mandatory = $true)][string]$Channel,
        [Parameter(Mandatory = $true)][string]$Secret,
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$BodyJson = ''
    )
    $bytes = if ([string]::IsNullOrEmpty($BodyJson)) { [byte[]]@() } else { [Text.Encoding]::UTF8.GetBytes($BodyJson) }
    $headers = New-OtaChannelHeaders -Channel $Channel -Secret $Secret -Method $Method -Path $Path -Body $bytes
    $params = @{
        Uri         = ($Gateway.TrimEnd('/') + $Path)
        Method      = $Method
        Headers     = $headers
        ContentType = 'application/json'
        UseBasicParsing = $true
    }
    if ($bytes.Length -gt 0) {
        $params['Body'] = $bytes
    }
    try {
        $response = Invoke-WebRequest @params
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Body = $response.Content }
    } catch {
        $webResponse = $_.Exception.Response
        if ($null -eq $webResponse) {
            throw
        }
        $status = [int]$webResponse.StatusCode
        $stream = $webResponse.GetResponseStream()
        if ($null -eq $stream) {
            return [pscustomobject]@{ StatusCode = $status; Body = '' }
        }
        $reader = New-Object System.IO.StreamReader($stream, [Text.Encoding]::UTF8)
        try {
            return [pscustomobject]@{ StatusCode = $status; Body = $reader.ReadToEnd() }
        } finally {
            $reader.Dispose()
        }
    }
}
