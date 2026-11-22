# Safe additive installer for Ani Games Ready Host Agent.
# Reuses the already-published AniGamesReady.HostAgent.exe.
# Preserves existing Host Agent credentials and all unrelated Sunshine/Firewall settings.
[CmdletBinding()]
param(
    [string]$ApiBaseUrl = "https://ani-games-db-android-stage-gateway.anigamesready.workers.dev",
    [ValidateSet("cloudflare_mesh","wireguard")]
    [string]$MediaTransport
)
$ErrorActionPreference = "Stop"
$serviceName = "AniGamesReadyHostAgent"
$displayName = "Ani Games Ready Remote Host Agent"
$sourceExe = Join-Path $PSScriptRoot "AniGamesReady.HostAgent.exe"
$agentDir = Join-Path $env:ProgramData "AniGamesReady\HostAgent"
$targetExe = Join-Path $agentDir "AniGamesReady.HostAgent.exe"
$configPath = Join-Path $agentDir "config.json"
$meshCidr = "100.96.0.0/12"

function Stop-Safe([string]$message) { throw $message }
function Protect-LocalMachine([string]$value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($value)
    $protected = [Security.Cryptography.ProtectedData]::Protect($bytes, $null, [Security.Cryptography.DataProtectionScope]::LocalMachine)
    return [Convert]::ToBase64String($protected)
}
function Test-StableApiUrl([string]$value) {
    $uri = $null
    if (-not [Uri]::TryCreate($value, [UriKind]::Absolute, [ref]$uri)) { return $false }
    return $uri.Scheme -eq "https" -and -not [string]::IsNullOrWhiteSpace($uri.Host) -and
        [string]::IsNullOrEmpty($uri.UserInfo) -and [string]::IsNullOrEmpty($uri.Query) -and
        [string]::IsNullOrEmpty($uri.Fragment) -and
        ($uri.AbsolutePath -eq "/" -or $uri.AbsolutePath -eq "" -or $uri.AbsolutePath -eq "/functions/v1/ani-games-api") -and
        $uri.Host -ne "localhost" -and $uri.Host -ne "127.0.0.1" -and
        -not $uri.Host.EndsWith(".trycloudflare.com") -and -not $uri.Host.Contains("supabase.co")
}
function Backup-File([string]$source) {
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { return $null }
    $backup = "$source.ani-backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Copy-Item -LiteralPath $source -Destination $backup -ErrorAction Stop
    return $backup
}
function Ensure-MeshFirewallRule([string]$display,[ValidateSet("Allow","Block")][string]$action,[ValidateSet("TCP","UDP")][string]$protocol,[int[]]$ports) {
    # Only Ani-owned rules are replaced. Existing Sunshine app rules stay untouched.
    Get-NetFirewallRule -DisplayName $display -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction Stop
    New-NetFirewallRule -DisplayName $display -Direction Inbound -Action $action -Enabled True -Profile Any -Protocol $protocol -LocalPort $ports -RemoteAddress $meshCidr | Out-Null
}

# Preflight before changing any local file, firewall setting, or service.
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { Stop-Safe "Buka PowerShell dengan Run as Administrator." }
if (-not (Test-Path -LiteralPath $sourceExe -PathType Leaf)) { Stop-Safe "AniGamesReady.HostAgent.exe tidak ditemukan. Ekstrak paket Host Agent lengkap." }
$sunshineExe = Join-Path $env:ProgramFiles "Sunshine\sunshine.exe"
if (-not (Test-Path -LiteralPath $sunshineExe -PathType Leaf)) { Stop-Safe "Sunshine belum ditemukan di $sunshineExe. Install Sunshine x64 terlebih dahulu." }
$ApiBaseUrl = $ApiBaseUrl.Trim().TrimEnd("/")
if (-not (Test-StableApiUrl $ApiBaseUrl)) { Stop-Safe "Gunakan gateway HTTPS stabil DB Android. Jangan gunakan localhost, Quick Tunnel, atau Supabase." }

$oldConfig = $null
if (Test-Path -LiteralPath $configPath -PathType Leaf) {
    try { $oldConfig = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json -ErrorAction Stop }
    catch { Stop-Safe "Config Host Agent lama tidak dapat dibaca. File dibiarkan utuh; jangan hapus manual." }
}
$alreadyEnrolled = $null -ne $oldConfig -and [bool]$oldConfig.Enrolled -and -not [string]::IsNullOrWhiteSpace([string]$oldConfig.HostTokenProtected)

$existingService = Get-CimInstance Win32_Service -Filter "Name='$serviceName'" -ErrorAction SilentlyContinue
if ($existingService -and $existingService.PathName -notmatch '(?i)AniGamesReady\.HostAgent\.exe') {
    Stop-Safe "Service bernama $serviceName sudah ada tetapi bukan Host Agent yang dikenal. Tidak dihentikan atau diubah."
}

$enrollmentCode = ""
if (-not $alreadyEnrolled) {
    $enrollmentCode = Read-Host "Kode enrollment Host Agent sekali pakai dari menu Remote Play Admin"
    if ($enrollmentCode -notmatch "^[0-9a-fA-F]{24,128}$") { Stop-Safe "Kode enrollment tidak valid. Kode Admin berlaku 15 menit dan hanya dapat dipakai sekali." }
}
if ([string]::IsNullOrWhiteSpace($MediaTransport)) {
    if ($oldConfig -and $oldConfig.MediaTransport -in @("cloudflare_mesh","wireguard")) { $MediaTransport = [string]$oldConfig.MediaTransport }
    else { $MediaTransport = "cloudflare_mesh" }
}
$mediaAddress = ""
$mediaPort = 47989
if ($MediaTransport -eq "wireguard") {
    if ($oldConfig -and -not [string]::IsNullOrWhiteSpace([string]$oldConfig.MediaAddress)) { $mediaAddress = [string]$oldConfig.MediaAddress }
    else { $mediaAddress = Read-Host "IP privat WireGuard PC yang dapat dijangkau HP" }
    $parsedAddress = $null
    if (-not [Net.IPAddress]::TryParse($mediaAddress, [ref]$parsedAddress)) { Stop-Safe "IP WireGuard tidak valid." }
    if ($oldConfig -and $oldConfig.MediaPort) { $mediaPort = [int]$oldConfig.MediaPort }
}

$sunshineUser = if ($oldConfig -and $oldConfig.SunshineUsername) { [string]$oldConfig.SunshineUsername } else { "admin" }
if ($null -eq $oldConfig -or [string]::IsNullOrWhiteSpace([string]$oldConfig.SunshinePasswordProtected)) {
    $secure = Read-Host "Sunshine Web UI password" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $sunshinePassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    if ([string]::IsNullOrWhiteSpace($sunshinePassword)) { Stop-Safe "Password Sunshine tidak boleh kosong." }
    $sunshinePasswordProtected = Protect-LocalMachine $sunshinePassword
} else { $sunshinePasswordProtected = [string]$oldConfig.SunshinePasswordProtected }

# Verify live DB gateway before making Windows changes.
try {
    $health = Invoke-RestMethod -Uri "$ApiBaseUrl/health" -Method Get -TimeoutSec 15
    if ($health.api -ne "online" -or $health.database -ne "online") { Stop-Safe "API/database gateway belum online. Host Agent belum dipasang." }
} catch { Stop-Safe "Gateway DB Android belum dapat dihubungi: $($_.Exception.Message). Tidak ada perubahan Host Agent dibuat." }

# Configure Sunshine Web UI as PC-local only; preserve bind_address, apps, and all existing firewall app rules.
$configDir = Join-Path (Split-Path -Parent $sunshineExe) "config"
New-Item -ItemType Directory -Force -Path $configDir | Out-Null
$sunshineConfigPath = Join-Path $configDir "sunshine.conf"
$sunshineConfig = ""
if (Test-Path -LiteralPath $sunshineConfigPath -PathType Leaf) { $sunshineConfig = [IO.File]::ReadAllText($sunshineConfigPath) }
$policyPattern = '(?m)^[ \t]*origin_web_ui_allowed[ \t]*=.*$'
$updatedSunshineConfig = $sunshineConfig
if ([regex]::IsMatch($updatedSunshineConfig, $policyPattern)) {
    $updatedSunshineConfig = [regex]::Replace($updatedSunshineConfig, $policyPattern, 'origin_web_ui_allowed = pc')
} else {
    if ($updatedSunshineConfig.Length -gt 0 -and -not $updatedSunshineConfig.EndsWith([Environment]::NewLine)) { $updatedSunshineConfig += [Environment]::NewLine }
    $updatedSunshineConfig += "origin_web_ui_allowed = pc" + [Environment]::NewLine
}
$sunshineConfigChanged = $updatedSunshineConfig -cne $sunshineConfig
if ($sunshineConfigChanged) {
    $sunshineBackup = Backup-File $sunshineConfigPath
    [IO.File]::WriteAllText($sunshineConfigPath, $updatedSunshineConfig, [Text.UTF8Encoding]::new($false))
    Write-Host "Sunshine config backed up to: $sunshineBackup" -ForegroundColor Yellow
    $sunshineService = Get-Service -Name "SunshineService" -ErrorAction SilentlyContinue
    if ($sunshineService) {
        Write-Warning "Sunshine config diperbarui. Restart Sunshine hanya jika tidak ada sesi stream aktif."
        if ($sunshineService.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Running) { Restart-Service -Name "SunshineService" -Force }
        else { Start-Service -Name "SunshineService" }
        (Get-Service -Name "SunshineService").WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Running,[TimeSpan]::FromSeconds(30))
    } else {
        Write-Warning "Sunshine bukan Windows Service. Restart Sunshine secara manual agar kebijakan Web UI lokal aktif."
    }
}

# Add only the three Ani-owned firewall rules; do not edit/remove existing Sunshine defaults or custom rules.
New-Item -ItemType Directory -Force -Path $agentDir | Out-Null
Ensure-MeshFirewallRule "Ani Remote Play Cloudflare Mesh TCP" "Allow" "TCP" @(47984,47989,48010)
Ensure-MeshFirewallRule "Ani Remote Play Cloudflare Mesh UDP" "Allow" "UDP" @(47998,47999,48000,48002,48010)
Ensure-MeshFirewallRule "Ani Remote Play Block Sunshine Web UI from Mesh" "Block" "TCP" @(47990)

$configBackup = Backup-File $configPath
$exeBackup = Backup-File $targetExe
$hostTokenProtected = if ($alreadyEnrolled) { [string]$oldConfig.HostTokenProtected } else { "" }
$enrollmentCodeProtected = if ($alreadyEnrolled) { "" } else { Protect-LocalMachine $enrollmentCode.Trim() }
$config = [ordered]@{
    ApiBaseUrl = $ApiBaseUrl
    HostTokenProtected = $hostTokenProtected
    EnrollmentCodeProtected = $enrollmentCodeProtected
    Enrolled = [bool]$alreadyEnrolled
    MediaAddress = $mediaAddress.Trim()
    MediaPort = $mediaPort
    MediaTransport = $MediaTransport
    SunshineApiBaseUrl = "https://127.0.0.1:47990"
    SunshineUsername = $sunshineUser
    SunshinePasswordProtected = $sunshinePasswordProtected
    PollIntervalSeconds = 8
}
$tempConfig = "$configPath.tmp"
[IO.File]::WriteAllText($tempConfig, ($config | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath $tempConfig -Destination $configPath -Force
Copy-Item -LiteralPath $sourceExe -Destination $targetExe -Force
icacls $agentDir /inheritance:r /grant:r "SYSTEM:(OI)(CI)F" "Administrators:(OI)(CI)F" | Out-Null

# Update our existing Agent service in place; never delete/recreate it.
if ($existingService) {
    $service = Get-Service -Name $serviceName
    if ($service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
        Stop-Service -Name $serviceName -Force
        (Get-Service -Name $serviceName).WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped,[TimeSpan]::FromSeconds(20))
    }
    $serviceBin = '"' + $targetExe + '"'
    & sc.exe config $serviceName binPath= $serviceBin start= auto | Out-Null
    if ($LASTEXITCODE -ne 0) { Stop-Safe "Gagal memperbarui service Host Agent." }
} else {
    $serviceBin = '"' + $targetExe + '"'
    & sc.exe create $serviceName binPath= $serviceBin start= auto DisplayName= $displayName | Out-Null
    if ($LASTEXITCODE -ne 0) { Stop-Safe "Gagal membuat service Host Agent." }
}
& sc.exe description $serviceName "Connects Admin-approved Ani Remote Play sessions to local Sunshine." | Out-Null
& sc.exe failure $serviceName reset= 86400 actions= restart/5000/restart/15000/restart/60000 | Out-Null
if ($LASTEXITCODE -ne 0) { Stop-Safe "Gagal mengatur pemulihan otomatis Host Agent." }
& sc.exe failureflag $serviceName 1 | Out-Null
if ($LASTEXITCODE -ne 0) { Stop-Safe "Gagal mengaktifkan failure flag Host Agent." }
Start-Service -Name $serviceName
(Get-Service -Name $serviceName).WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Running,[TimeSpan]::FromSeconds(30))

$warpService = Get-Service -Name "CloudflareWARP" -ErrorAction SilentlyContinue
Write-Host ""
Write-Host "ANI HOST AGENT SERVICE: RUNNING" -ForegroundColor Green
Write-Host "DB Android Gateway: $ApiBaseUrl" -ForegroundColor Cyan
Write-Host "Media route: $MediaTransport"
Write-Host "Sunshine Web UI/API remains local-only; port 47990 is not opened to Mesh."
Write-Host "Existing Sunshine application firewall rules were left unchanged."
if ($alreadyEnrolled) { Write-Host "Existing Host Agent enrollment token preserved." -ForegroundColor Green }
else { Write-Host "Host Agent will redeem the one-time Admin enrollment code on first heartbeat." -ForegroundColor Yellow }
if (-not $warpService) {
    Write-Warning "Cloudflare One Client belum terdeteksi. Enroll PC ke organisasi Ani Games Ready Remote Play; PC belum tersedia sampai Mesh aktif."
} else {
    Write-Host "Cloudflare One Client terdeteksi. Pastikan status Connected ke organisasi yang sama dengan HP." -ForegroundColor Yellow
}
Write-Host "Host Agent executable disimpan permanen di $targetExe."
