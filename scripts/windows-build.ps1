param(
    [switch]$SkipDeploy,
    [switch]$SkipLaunch
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$GradleVersion = '8.11.1'
$GradleSha256 = 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'
$CmdToolsVersion = '15859902'
$CmdToolsSha256 = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
$SdkPackages = @(
    'platform-tools',
    'platforms;android-35',
    'build-tools;35.0.0',
    'ndk;27.0.12077973',
    'cmake;3.22.1'
)
$AppId = 'de.shansen.liblogicalaccessnfc'
$Activity = '.MainActivity'

function Step([string]$Text) { Write-Host "`n==> $Text" -ForegroundColor Cyan }
function Stop-WithError([string]$Text) { throw $Text }

function Get-JavaMajor([string]$JavaExe) {
    try {
        $first = (& $JavaExe -version 2>&1 | Select-Object -First 1).ToString()
        if ($first -match 'version "([0-9]+)') { return [int]$Matches[1] }
    } catch {}
    return 0
}

function Find-JavaHome {
    $homes = @()
    if ($env:JAVA_HOME) { $homes += $env:JAVA_HOME }
    $homes += (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr')
    $homes += (Join-Path $env:ProgramFiles 'Android\Android Studio\jre')

    $adoptium = Join-Path $env:ProgramFiles 'Eclipse Adoptium'
    if (Test-Path $adoptium) {
        $homes += @(Get-ChildItem $adoptium -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object FullName)
    }

    foreach ($home in ($homes | Where-Object { $_ } | Select-Object -Unique)) {
        $java = Join-Path $home 'bin\java.exe'
        if ((Test-Path $java) -and ((Get-JavaMajor $java) -ge 17)) { return $home }
    }
    return $null
}

function Ensure-Java {
    $home = Find-JavaHome
    if (-not $home) {
        $winget = Get-Command winget.exe -ErrorAction SilentlyContinue
        if (-not $winget) { Stop-WithError 'JDK 17 not found. Install JDK 17 or Android Studio with its bundled JBR.' }
        Step 'Installing JDK 17 via winget'
        Write-Host 'Review any package/source license prompts shown by winget.' -ForegroundColor Yellow
        & $winget.Source install --id EclipseAdoptium.Temurin.17.JDK -e
        if ($LASTEXITCODE -ne 0) { Stop-WithError 'JDK 17 installation failed.' }
        $home = Find-JavaHome
    }
    if (-not $home) { Stop-WithError 'JDK 17 could not be located after installation.' }
    $env:JAVA_HOME = $home
    $env:Path = "$(Join-Path $home 'bin');$env:Path"
    Write-Host "JAVA_HOME=$home"
}

function Get-SdkRoot {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
        if ($candidate -and (Test-Path $candidate)) { return (Resolve-Path $candidate).Path }
    }
    $path = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    New-Item -ItemType Directory -Force $path | Out-Null
    return $path
}

function Find-SdkManager([string]$Sdk) {
    $preferred = Join-Path $Sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (Test-Path $preferred) { return $preferred }
    $cmdRoot = Join-Path $Sdk 'cmdline-tools'
    if (Test-Path $cmdRoot) {
        $item = Get-ChildItem $cmdRoot -Recurse -Filter sdkmanager.bat -File -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($item) { return $item.FullName }
    }
    return $null
}

function Install-CmdTools([string]$Sdk) {
    Step 'Android Command-Line Tools are missing'
    Write-Host 'Google Android SDK license terms apply. Review them before continuing.' -ForegroundColor Yellow
    $answer = Read-Host 'Download the official Command-Line Tools now? [y/N]'
    if ($answer -notmatch '^(y|yes|j|ja)$') { Stop-WithError 'Android Command-Line Tools are required.' }

    $zip = Join-Path $env:TEMP "android-cmdtools-$CmdToolsVersion.zip"
    $tmp = Join-Path $env:TEMP "android-cmdtools-$CmdToolsVersion"
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    $url = "https://dl.google.com/android/repository/commandlinetools-win-$($CmdToolsVersion)_latest.zip"
    Invoke-WebRequest -UseBasicParsing $url -OutFile $zip

    $hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $CmdToolsSha256) { Stop-WithError "Android Command-Line Tools checksum mismatch: $hash" }

    Expand-Archive $zip $tmp -Force
    $source = Join-Path $tmp 'cmdline-tools'
    if (-not (Test-Path (Join-Path $source 'bin\sdkmanager.bat'))) { Stop-WithError 'Unexpected Command-Line Tools archive layout.' }
    $target = Join-Path $Sdk 'cmdline-tools\latest'
    Remove-Item $target -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
    Move-Item $source $target
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

function Ensure-AndroidSdk([string]$Sdk) {
    $manager = Find-SdkManager $Sdk
    if (-not $manager) {
        Install-CmdTools $Sdk
        $manager = Find-SdkManager $Sdk
    }
    if (-not $manager) { Stop-WithError 'sdkmanager.bat not found.' }

    Step 'Checking Android SDK licenses'
    Write-Host 'sdkmanager may ask you to review and accept missing SDK licenses.' -ForegroundColor Yellow
    & $manager "--sdk_root=$Sdk" --licenses
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'Android SDK licenses were not completed.' }

    Step 'Installing/verifying Android SDK, ADB, NDK and CMake'
    & $manager "--sdk_root=$Sdk" @SdkPackages
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'Android SDK package installation failed.' }

    $env:ANDROID_HOME = $Sdk
    $env:ANDROID_SDK_ROOT = $Sdk
    Set-Content (Join-Path $Root 'local.properties') "sdk.dir=$($Sdk.Replace('\','/'))" -Encoding ASCII
}

function Ensure-Gradle {
    $tools = Join-Path $Root '.tools'
    $home = Join-Path $tools "gradle-$GradleVersion"
    $gradle = Join-Path $home 'bin\gradle.bat'
    if (Test-Path $gradle) { return $gradle }

    Step "Downloading Gradle $GradleVersion"
    New-Item -ItemType Directory -Force $tools | Out-Null
    $zip = Join-Path $tools "gradle-$GradleVersion-bin.zip"
    Invoke-WebRequest -UseBasicParsing "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" -OutFile $zip
    $hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $GradleSha256) { Stop-WithError "Gradle checksum mismatch: $hash" }
    Expand-Archive $zip $tools -Force
    Remove-Item $zip -Force
    if (-not (Test-Path $gradle)) { Stop-WithError 'Gradle extraction failed.' }
    return $gradle
}

try {
    Step 'Checking prerequisites'
    Ensure-Java
    $sdk = Get-SdkRoot
    Write-Host "ANDROID_HOME=$sdk"
    Ensure-AndroidSdk $sdk
    $gradle = Ensure-Gradle

    Step 'Building app-debug.apk'
    Push-Location $Root
    try {
        & $gradle --no-daemon clean assembleDebug
        if ($LASTEXITCODE -ne 0) { Stop-WithError 'Gradle build failed.' }
    } finally { Pop-Location }

    $apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apk)) { Stop-WithError "APK not found: $apk" }
    Write-Host "Built: $apk" -ForegroundColor Green
    if ($SkipDeploy) { exit 0 }

    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    Step 'Checking connected physical device'
    & $adb start-server | Out-Null
    $devices = @(& $adb devices | Select-Object -Skip 1 | ForEach-Object {
        if ($_ -match '^(\S+)\s+device$') { $Matches[1] }
    } | Where-Object { $_ })

    if ($devices.Count -eq 0) {
        Write-Host 'No authorized device found. Enable Developer options + USB debugging, connect/unlock the phone and accept its RSA prompt.' -ForegroundColor Yellow
        exit 2
    }
    if ($devices.Count -gt 1) { Stop-WithError "Multiple devices connected: $($devices -join ', ')" }

    $serial = $devices[0]
    $abi = (& $adb -s $serial shell getprop ro.product.cpu.abi).Trim()
    Write-Host "Device: $serial ($abi)"
    if ($abi -ne 'arm64-v8a') { Stop-WithError "Connected device ABI '$abi' is not supported; current app build is arm64-v8a only." }

    Step 'Installing debug APK'
    & $adb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'ADB install failed.' }
    if ($SkipLaunch) { exit 0 }

    Step 'Launching and verifying app process'
    & $adb -s $serial shell am force-stop $AppId | Out-Null
    & $adb -s $serial shell am start -W -n "$AppId/$Activity"
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'ADB launch failed.' }
    Start-Sleep -Seconds 1
    $appProcessId = (& $adb -s $serial shell pidof $AppId).Trim()
    if (-not $appProcessId) { Stop-WithError 'App was installed but is not running after launch.' }

    Write-Host "`nSUCCESS - app is running on $serial (PID $appProcessId)." -ForegroundColor Green
    Write-Host 'Present an ISO-DEP NFC card to begin the hardware test.' -ForegroundColor Green
    exit 0
}
catch {
    Write-Host "`nERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
