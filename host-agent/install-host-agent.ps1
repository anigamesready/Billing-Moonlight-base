# Run from an elevated PowerShell prompt after publishing the Host Agent.
$ErrorActionPreference = "Stop"
$serviceName = "AniGamesReadyHostAgent"
$exe = Join-Path $PSScriptRoot "AniGamesReady.HostAgent.exe"
if (-not (Test-Path $exe)) { throw "AniGamesReady.HostAgent.exe not found. Publish the Host Agent first." }

$deviceToken = Read-Host "Existing Ani Games Ready Windows device token"
if ([string]::IsNullOrWhiteSpace($deviceToken) -or $deviceToken.Length -lt 32) { throw "Device token is invalid." }

$tunnelAddress = Read-Host "Reachable host address (WireGuard IP or public DNS/IP with Sunshine ports routed)"
if ([string]::IsNullOrWhiteSpace($tunnelAddress)) { throw "Tunnel address is required." }

$sunshineBase = Read-Host "Sunshine local API base URL [https://127.0.0.1:47990]"
if ([string]::IsNullOrWhiteSpace($sunshineBase)) { $sunshineBase = "https://127.0.0.1:47990" }
if ($sunshineBase -notmatch '^https://(127\.0\.0\.1|localhost):47990/?$') { throw "Sunshine API must use https://127.0.0.1:47990 only." }

$sunshineUser = Read-Host "Sunshine Web UI username [admin]"
if ([string]::IsNullOrWhiteSpace($sunshineUser)) { $sunshineUser = "admin" }
$secure = Read-Host "Sunshine Web UI password" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $sunshinePassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }

Add-Type -AssemblyName System.Security
function Protect-LocalMachine([string]$value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($value)
    $protected = [Security.Cryptography.ProtectedData]::Protect($bytes, $null, [Security.Cryptography.DataProtectionScope]::LocalMachine)
    [Convert]::ToBase64String($protected)
}

$folder = Join-Path $env:ProgramData "AniGamesReady\HostAgent"
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$config = @{
    ApiBaseUrl = "https://wzkjpycjizexxcxrfdvr.supabase.co/functions/v1/moonlight-api"
    DeviceTokenProtected = Protect-LocalMachine $deviceToken
    TunnelAddress = $tunnelAddress.Trim()
    TunnelPort = 47989
    SunshineApiBaseUrl = $sunshineBase.TrimEnd('/')
    SunshineUsername = $sunshineUser
    SunshinePasswordProtected = Protect-LocalMachine $sunshinePassword
    PollIntervalSeconds = 5
} | ConvertTo-Json -Depth 5
$configPath = Join-Path $folder "config.json"
[IO.File]::WriteAllText($configPath, $config, [Text.UTF8Encoding]::new($false))
icacls $folder /inheritance:r /grant:r "SYSTEM:(OI)(CI)F" "Administrators:(OI)(CI)F" | Out-Null
icacls $configPath /inheritance:r /grant:r "SYSTEM:F" "Administrators:F" | Out-Null

if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
    Stop-Service $serviceName -Force -ErrorAction SilentlyContinue
    sc.exe delete $serviceName | Out-Null
    Start-Sleep -Seconds 2
}
$serviceBin = '"' + $exe + '"'
sc.exe create $serviceName binPath= $serviceBin start= auto DisplayName= "Ani Games Ready Remote Host Agent" | Out-Null
sc.exe description $serviceName "Connects Ani Games Ready Remote Play sessions to local Sunshine securely." | Out-Null
Start-Service $serviceName
Write-Host "Ani Games Ready Host Agent installed and started." -ForegroundColor Green
Write-Host "PC appears only when its Billing heartbeat and Host Agent heartbeat are fresh."
