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

$mediaChoice = Read-Host "Media route [M] Cloudflare Mesh (default) / [W] WireGuard manual"
if ([string]::IsNullOrWhiteSpace($mediaChoice) -or $mediaChoice.Trim().ToUpperInvariant() -eq "M") {
    $mediaTransport = "cloudflare_mesh"
    $mediaAddress = ""
    $mediaPort = 47989
    $warpService = Get-Service -Name "CloudflareWARP" -ErrorAction SilentlyContinue
    if (-not $warpService) {
        Write-Warning "Cloudflare One Client belum terdeteksi. Install client resmi, lalu enroll PC ini pada organisasi yang sama dengan HP."
        Write-Host "Unduhan resmi: https://developers.cloudflare.com/cloudflare-one/team-and-resources/devices/cloudflare-one-client/download/" -ForegroundColor Yellow
        Write-Host "Host Agent akan tetap terpasang, tetapi PC belum tampil tersedia sampai adapter Mesh aktif." -ForegroundColor Yellow
    } else {
        Write-Host "Cloudflare One Client terdeteksi. IP Mesh akan dicari otomatis oleh Host Agent." -ForegroundColor Green
    }
} elseif ($mediaChoice.Trim().ToUpperInvariant() -eq "W") {
    $mediaTransport = "wireguard"
    $mediaAddress = Read-Host "Alamat IP privat WireGuard PC ini yang dapat dijangkau HP"
    if ([string]::IsNullOrWhiteSpace($mediaAddress) -or $mediaAddress.Contains("/") -or $mediaAddress.Contains("\")) {
        throw "Alamat rute WireGuard tidak valid."
    }
    $mediaPortText = Read-Host "Port host yang dipakai Moonlight [47989]"
    if ([string]::IsNullOrWhiteSpace($mediaPortText)) { $mediaPortText = "47989" }
    $mediaPort = 0
    if (-not [int]::TryParse($mediaPortText, [ref]$mediaPort) -or $mediaPort -lt 1 -or $mediaPort -gt 65535) {
        throw "Port tidak valid."
    }
} else {
    throw "Pilihan media route tidak dikenal. Pilih M atau W."
}

$sunshineBase = "https://127.0.0.1:47990"
$sunshineUser = Read-Host "Sunshine Web UI username [admin]"
if ([string]::IsNullOrWhiteSpace($sunshineUser)) { $sunshineUser = "admin" }
$secure = Read-Host "Sunshine Web UI password" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $sunshinePassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }

Add-Type -AssemblyName System.Security
# Sunshine Web UI/API policy is independent from GameStream media binding.
# Keep bind_address unset so Sunshine still listens on the Mesh adapter for streaming.
# Only localhost may access the HTTPS Web UI/API (including its /api endpoints).
$sunshineExe = Join-Path (Join-Path $env:ProgramFiles "Sunshine") "sunshine.exe"
if (-not (Test-Path $sunshineExe)) {
    throw "Sunshine tidak ditemukan di $sunshineExe. Install Sunshine x64 terlebih dahulu."
}
$sunshineConfigDir = Join-Path (Split-Path -Parent $sunshineExe) "config"
New-Item -ItemType Directory -Force -Path $sunshineConfigDir | Out-Null
$sunshineConfigPath = Join-Path $sunshineConfigDir "sunshine.conf"
$sunshineConfig = ""
if (Test-Path $sunshineConfigPath) {
    $sunshineConfig = [IO.File]::ReadAllText($sunshineConfigPath)
    $backupPath = $sunshineConfigPath + ".ani-remote-play.bak"
    if (-not (Test-Path $backupPath)) { Copy-Item -LiteralPath $sunshineConfigPath -Destination $backupPath }
}
$webUiPolicyPattern = '(?m)^[ \\t]*origin_web_ui_allowed[ \\t]*=.*$'
if ([regex]::IsMatch($sunshineConfig, $webUiPolicyPattern)) {
    # Replace every active duplicate because Sunshine parses the first active setting.
    $sunshineConfig = [regex]::Replace($sunshineConfig, $webUiPolicyPattern, 'origin_web_ui_allowed = pc')
} else {
    if ($sunshineConfig.Length -gt 0 -and -not $sunshineConfig.EndsWith("`n")) {
        $sunshineConfig += "`r`n"
    }
    $sunshineConfig += "origin_web_ui_allowed = pc`r`n"
}
[IO.File]::WriteAllText($sunshineConfigPath, $sunshineConfig, [Text.UTF8Encoding]::new($false))

# Restart Sunshine so the new local-only API policy takes effect before the Host Agent starts.
$sunshineService = Get-Service -Name "SunshineService" -ErrorAction SilentlyContinue
if ($sunshineService) {
    if ($sunshineService.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Running) {
        Restart-Service -Name "SunshineService" -Force
    } else {
        Start-Service -Name "SunshineService"
    }
    (Get-Service -Name "SunshineService").WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromSeconds(30))
} else {
    $runningSunshine = Get-Process -Name "sunshine" -ErrorAction SilentlyContinue
    if ($runningSunshine) {
        $runningSunshine | Stop-Process -Force
        Start-Sleep -Seconds 2
    }
    Start-Process -FilePath $sunshineExe
    Start-Sleep -Seconds 5
}
Write-Host "Sunshine Web UI/API sekarang hanya boleh diakses dari PC host (origin_web_ui_allowed=pc)." -ForegroundColor Green

# Restrict Sunshine GameStream inbound traffic to enrolled Cloudflare Mesh peers.
# GameStream ports match the pinned Moonlight Android client: TCP 47984/47989/48010,
# UDP 47998/47999/48000/48002/48010. The Sunshine Web UI/API port 47990 is never opened.
if ($mediaTransport -eq "cloudflare_mesh") {
    $meshCidr = "100.96.0.0/12"
    try {
        # Narrow existing Sunshine application allow-rules so an older broad
        # installer exception does not accidentally expose the host to the WAN.
        $sunshineExe = Join-Path $env:ProgramFiles "Sunshine\sunshine.exe"
        if (Test-Path $sunshineExe) {
            $candidates = Get-NetFirewallRule -Direction Inbound -Action Allow -Enabled True -ErrorAction SilentlyContinue
            foreach ($rule in $candidates) {
                $appFilter = $rule | Get-NetFirewallApplicationFilter -ErrorAction SilentlyContinue
                if ($appFilter -and $appFilter.Program -and
                    [string]::Equals($appFilter.Program, $sunshineExe, [StringComparison]::OrdinalIgnoreCase)) {
                    $rule | Get-NetFirewallAddressFilter | Set-NetFirewallAddressFilter -RemoteAddress $meshCidr
                }
            }
        }

        foreach ($name in @(
            "Ani Remote Play Cloudflare Mesh TCP",
            "Ani Remote Play Cloudflare Mesh UDP",
            "Ani Remote Play Block Sunshine Web UI from Mesh"
        )) {
            Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
        }
        New-NetFirewallRule -DisplayName "Ani Remote Play Cloudflare Mesh TCP" -Direction Inbound -Action Allow -Enabled True -Profile Any -Protocol TCP -LocalPort 47984,47989,48010 -RemoteAddress $meshCidr | Out-Null
        New-NetFirewallRule -DisplayName "Ani Remote Play Cloudflare Mesh UDP" -Direction Inbound -Action Allow -Enabled True -Profile Any -Protocol UDP -LocalPort 47998,47999,48000,48002,48010 -RemoteAddress $meshCidr | Out-Null
        New-NetFirewallRule -DisplayName "Ani Remote Play Block Sunshine Web UI from Mesh" -Direction Inbound -Action Block -Enabled True -Profile Any -Protocol TCP -LocalPort 47990 -RemoteAddress $meshCidr | Out-Null
        Write-Host "Firewall GameStream dibatasi ke Cloudflare Mesh 100.96.0.0/12. Port Web UI/API Sunshine 47990 tidak dibuka." -ForegroundColor Green
    } catch {
        throw "Gagal mengatur Windows Firewall Cloudflare Mesh dengan aman: $($_.Exception.Message)"
    }
}

function Protect-LocalMachine([string]$value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($value)
    $protected = [Security.Cryptography.ProtectedData]::Protect(
        $bytes, $null, [Security.Cryptography.DataProtectionScope]::LocalMachine)
    [Convert]::ToBase64String($protected)
}

$folder = Join-Path $env:ProgramData "AniGamesReady\HostAgent"
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$config = @{
    ApiBaseUrl = $apiBase
    HostTokenProtected = ""
    EnrollmentCodeProtected = Protect-LocalMachine $enrollmentCode.Trim()
    Enrolled = $false
    MediaAddress = $mediaAddress.Trim()
    MediaPort = $mediaPort
    MediaTransport = $mediaTransport
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
# Restart the Agent after an unexpected service crash so the DB host watchdog and
# stop-command queue can recover automatically. This does not restart Sunshine.
sc.exe failure $serviceName reset= 86400 actions= restart/5000/restart/15000/restart/60000 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Gagal mengatur Windows Service Recovery Host Agent." }
sc.exe failureflag $serviceName 1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Gagal mengaktifkan failure flag Host Agent." }
Start-Service $serviceName
Write-Host "Ani Games Ready Host Agent installed. Admin enrollment and live heartbeat must succeed before the PC is shown as available." -ForegroundColor Green
Write-Host "Note: Cloudflare Mesh requires both devices enrolled and connected to the same Zero Trust organization; WireGuard requires its private route to be configured. The DB HTTPS gateway does not carry game traffic."
