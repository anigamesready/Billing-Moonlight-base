# Run in an elevated PowerShell prompt on the Windows PC authorized by Admin.
$ErrorActionPreference = "Stop"
$serviceName = "AniGamesReadyHostAgent"
$exe = Join-Path $PSScriptRoot "AniGamesReady.HostAgent.exe"
if (-not (Test-Path $exe)) { throw "AniGamesReady.HostAgent.exe not found. Extract the complete Host Agent package first." }

$apiBase = $env:ANI_DB_ANDROID_API_BASE_URL
if ([string]::IsNullOrWhiteSpace($apiBase)) {
    $apiBase = Read-Host "Existing stable HTTPS DB Android API gateway URL (same setting used by Ani apps)"
}
$apiBase = $apiBase.Trim().TrimEnd('/')
$apiUri = $null
if (-not [Uri]::TryCreate($apiBase, [UriKind]::Absolute, [ref]$apiUri) -or
    $apiUri.Scheme -ne "https" -or
    $apiUri.Host -eq "localhost" -or $apiUri.Host -eq "127.0.0.1" -or
    $apiUri.Host.EndsWith(".trycloudflare.com") -or
    $apiUri.Host.Contains("supabase.co") -or
    $apiUri.UserInfo -ne "" -or $apiUri.Query -ne "" -or $apiUri.Fragment -ne "" -or
    ($apiUri.AbsolutePath -ne "/" -and $apiUri.AbsolutePath -ne "" -and
     $apiUri.AbsolutePath -ne "/functions/v1/ani-games-api")) {
    throw "URL harus gateway HTTPS DB Android stabil yang sama dengan Ani Apps. Jangan gunakan localhost, Quick Tunnel, atau Supabase."
}

$enrollmentCode = Read-Host "Kode enrollment Host Agent sekali pakai dari Admin"
if ([string]::IsNullOrWhiteSpace($enrollmentCode) -or $enrollmentCode.Length -lt 10) {
    throw "Kode enrollment Admin tidak valid."
}

$mediaAddress = Read-Host "Alamat IP/hostname WireGuard PC ini yang dapat dijangkau HP"
if ([string]::IsNullOrWhiteSpace($mediaAddress) -or $mediaAddress.Contains("/") -or $mediaAddress.Contains("\")) {
    throw "Alamat rute WireGuard tidak valid."
}
$mediaPortText = Read-Host "Port host yang dipakai Moonlight untuk discovery/API [47989]"
if ([string]::IsNullOrWhiteSpace($mediaPortText)) { $mediaPortText = "47989" }
$mediaPort = 0
if (-not [int]::TryParse($mediaPortText, [ref]$mediaPort) -or $mediaPort -lt 1 -or $mediaPort -gt 65535) {
    throw "Port tidak valid."
}

$sunshineBase = "https://127.0.0.1:47990"
$sunshineUser = Read-Host "Sunshine Web UI username [admin]"
if ([string]::IsNullOrWhiteSpace($sunshineUser)) { $sunshineUser = "admin" }
$secure = Read-Host "Sunshine Web UI password" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $sunshinePassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }

Add-Type -AssemblyName System.Security
function Protect-LocalMachine([string]$value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($value)
    $protected = [Security.Cryptography.ProtectedData]::Protect(
        $bytes, $null, [Security.Cryptography.DataProtectionScope]::LocalMachine)
    [Convert]::ToBase64String($protected)
}

$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$tokenBytes = New-Object byte[] 32
$random.GetBytes($tokenBytes)
$hostToken = [Convert]::ToHexString($tokenBytes).ToLowerInvariant()
[Array]::Clear($tokenBytes, 0, $tokenBytes.Length)
$random.Dispose()

$folder = Join-Path $env:ProgramData "AniGamesReady\HostAgent"
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$config = @{
    ApiBaseUrl = $apiBase
    HostTokenProtected = Protect-LocalMachine $hostToken
    EnrollmentCodeProtected = Protect-LocalMachine $enrollmentCode.Trim()
    Enrolled = $false
    MediaAddress = $mediaAddress.Trim()
    MediaPort = $mediaPort
    MediaTransport = "wireguard"
    SunshineApiBaseUrl = $sunshineBase
    SunshineUsername = $sunshineUser
    SunshinePasswordProtected = Protect-LocalMachine $sunshinePassword
    PollIntervalSeconds = 8
} | ConvertTo-Json -Depth 8
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
sc.exe description $serviceName "Connects approved Ani Remote Play sessions to local Sunshine." | Out-Null
Start-Service $serviceName
Write-Host "Ani Games Ready Host Agent installed. Admin enrollment and live heartbeat must succeed before the PC is shown as available." -ForegroundColor Green
Write-Host "Note: WireGuard media routing must already be configured; the DB HTTPS gateway does not carry game traffic."
