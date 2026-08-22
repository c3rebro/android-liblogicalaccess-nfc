param(
    [switch]$SkipDeploy,
    [switch]$SkipLaunch
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent $PSScriptRoot
$GradleVersion = '8.11.1'
$GradleSha256 = 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'
$CmdlineToolsVersion = '15859902'
$CmdlineToolsSha256 = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
$CompileSdk = '35'
$BuildToolsVersion = '35.0.0'
$NdkVersion = '27.0.12077973'
$CmakeVersion = '3.22.1'
$ApplicationId = 'de.shansen.liblogicalaccessnfc'
$Activity = '.MainActivity'

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Fail([string]$Message) {
    throw $Message
}

function Get-JavaMajorVersion([string]$JavaExe) {
    try {
        $versionText = (& $JavaExe -version 2>&1 | Select-Object -First 1).ToString()
        if ($versionText -match 'version "(?<v>[0-9]+)') {
            return [int]$Matches.v
        }
    } catch {}
    return 0
}

function Find-JavaHome {
    $candidates = New-Object System.Collections.Generic.List[string]

    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }

    $studioJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path $studioJbr) { $candidates.Add($studioJbr) }

    $studioJre = Join-Path $env:ProgramFiles 'Android\Android Studio\jre'
    if (Test-Path $studioJre) { $candidates.Add($studioJre) }

    $adoptiumRoot = Join-Path $env:ProgramFiles 'Eclipse Adoptium'
    if (Test-Path $adoptiumRoot) {
        Get-ChildItem $adoptiumRoot -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    foreach ($home in $candidates | Select-Object -Unique) {
        $java = Join-Path $home 'bin\java.exe'
        if ((Test-Path $java) -and ((Get-JavaMajorVersion $java) -ge 17)) {
            return $home
        }
    }

    $javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCmd -and ((Get-JavaMajorVersion $javaCmd.Source) -ge 17)) {
        return (Split-Path -Parent (Split-Path -Parent $javaCmd.Source))
    }

    return $null
}

function Install-Jdk17 {
    $winget = Get-Command winget.exe -ErrorAction SilentlyContinue
    if (-not $winget) {
        Fail 'JDK 17 was not found and winget is unavailable. Install JDK 17 or Android Studio with its bundled JBR.'
    }

    Write-Step 'JDK 17 missing - starting winget installation'
    Write-Host 'winget may show package/source license prompts. Review them before accepting.' -ForegroundColor Yellow
    & $winget.Source install --id EclipseAdoptium.Temurin.17.JDK -e
    if ($LASTEXITCODE -ne 0) { Fail 'JDK 17 installation failed.' }
}

function Read-LocalPropertiesSdkDir {
    $localProperties = Join-Path $RepoRoot 'local.properties'
    if (-not (Test-Path $localProperties)) { return $null }

    foreach ($line in Get-Content $localProperties) {
        if ($line -match '^sdk\.dir=(.+)$') {
            return $Matches[1].Trim().Replace('\\:', ':').Replace('\\', '\')
        }
    }
    return $null
}

function Find-AndroidSdk {
    $candidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Read-LocalPropertiesSdkDir),
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    ) | Where-Object { $_ } | Select-Object -Unique

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
    }

    $default = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    New-Item -ItemType Directory -Force -Path $default | Out-Null
    return $default
}

function Find-SdkManager([string]$SdkRoot) {
    $preferred = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (Test-Path $preferred) { return $preferred }

    $root = Join-Path $SdkRoot 'cmdline-tools'
    if (Test-Path $root) {
        $found = Get-ChildItem $root -Recurse -Filter sdkmanager.bat -File -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

function Install-CommandLineTools([string]$SdkRoot) {
    Write-Step 'Android SDK Command-Line Tools missing'
    Write-Host 'Google requires acceptance of the Android SDK license terms. The package will only be downloaded after your confirmation.' -ForegroundColor Yellow
    $answer = Read-Host 'Download and install the official Android Command-Line Tools now? [y/N]'
    if ($answer -notmatch '^(y|yes|j|ja)$') {
        Fail 'Android SDK Command-Line Tools are required.'
    }

    $url = "https://dl.google.com/android/repository/commandlinetools-win-$($CmdlineToolsVersion)_latest.zip"
    $tempRoot = Join-Path $env:TEMP "android-cmdline-tools-$CmdlineToolsVersion"
    $zip = "$tempRoot.zip"
    Remove-Item $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $zip -Force -ErrorAction SilentlyContinue

    Write-Host "Downloading $url"
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip
    $actual = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $CmdlineToolsSha256) {
        Remove-Item $zip -Force -ErrorAction SilentlyContinue
        Fail "Command-Line Tools checksum mismatch. Expected $CmdlineToolsSha256, got $actual."
    }

    Expand-Archive -Path $zip -DestinationPath $tempRoot -Force
    $source = Join-Path $tempRoot 'cmdline-tools'
    if (-not (Test-Path (Join-Path $source 'bin\sdkmanager.bat'))) {
        Fail 'Unexpected Android Command-Line Tools archive layout.'
    }

    $latest = Join-Path $SdkRoot 'cmdline-tools\latest'
    Remove-Item $latest -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $latest) | Out-Null
    Move-Item $source $latest
    Remove-Item $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
}

function Ensure-AndroidPackages([string]$SdkRoot, [string]$SdkManager) {
    Write-Step 'Checking Android SDK licenses'
    Write-Host 'If licenses are not accepted yet, sdkmanager will ask you to review and accept them.' -ForegroundColor Yellow
    & $SdkManager "--sdk_root=$SdkRoot" --licenses
    if ($LASTEXITCODE -ne 0) { Fail 'Android SDK license step failed or was declined.' }

    Write-Step 'Installing/verifying Android build packages'
    $packages = @(
        'platform-tools',
        "platforms;android-$CompileSdk",
        "build-tools;$BuildToolsVersion",
        "ndk;$NdkVersion",
        "cmake;$CmakeVersion"
    )
    & $SdkManager "--sdk_root=$SdkRoot" @packages
    if ($LASTEXITCODE -ne 0) { Fail 'Android SDK package installation failed.' }
}

function Write-LocalProperties([string]$SdkRoot) {
    $escaped = $SdkRoot.Replace('\', '/')
    Set-Content -Path (Join-Path $RepoRoot 'local.properties') -Encoding ASCII -Value "sdk.dir=$escaped"
}

function Ensure-Gradle {
    $toolRoot = Join-Path $RepoRoot '.tools'
    $gradleHome = Join-Path $toolRoot "gradle-$GradleVersion"
    $gradleBat = Join-Path $gradleHome 'bin\gradle.bat'
    if (Test-Path $gradleBat) { return $gradleBat }

    Write-Step "Installing project-local Gradle $GradleVersion"
    New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
    $zip = Join-Path $toolRoot "gradle-$GradleVersion-bin.zip"
    $url = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"

    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip
    $actual = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $GradleSha256) {
        Remove-Item $zip -Force -ErrorAction SilentlyContinue
        Fail "Gradle checksum mismatch. Expected $GradleSha256, got $actual."
    }

    Expand-Archive -Path $zip -DestinationPath $toolRoot -Force
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    if (-not (Test-Path $gradleBat)) { Fail 'Gradle extraction failed.' }
    return $gradleBat
}

function Get-AuthorizedDevices([string]$Adb) {
    $lines = & $Adb devices
    return @($lines | Select-Object -Skip 1 | ForEach-Object {
        if ($_ -match '^(?<serial>\S+)\s+device$') { $Matches.serial }
    } | Where-Object { $_ })
}

try {
    Write-Step 'Checking Java'
    $javaHome = Find-JavaHome
    if (-not $javaHome) {
        Install-Jdk17
        $javaHome = Find-JavaHome
    }
    if (-not $javaHome) { Fail 'A usable JDK 17+ could not be found after installation.' }
    $env:JAVA_HOME = $javaHome
    $env:Path = "$(Join-Path $javaHome 'bin');$env:Path"
    Write-Host "JAVA_HOME=$javaHome"

    Write-Step 'Locating Android SDK'
    $sdkRoot = Find-AndroidSdk
    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_SDK_ROOT = $sdkRoot
    Write-Host "ANDROID_HOME=$sdkRoot"

    $sdkManager = Find-SdkManager $sdkRoot
    if (-not $sdkManager) {
        Install-CommandLineTools $sdkRoot
        $sdkManager = Find-SdkManager $sdkRoot
    }
    if (-not $sdkManager) { Fail 'sdkmanager.bat could not be located.' }

    Ensure-AndroidPackages $sdkRoot $sdkManager
    Write-LocalProperties $sdkRoot

    $adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) { Fail 'adb.exe is missing after installing platform-tools.' }

    $gradle = Ensure-Gradle

    Write-Step 'Building Debug APK'
    Push-Location $RepoRoot
    try {
        & $gradle --no-daemon clean assembleDebug
        if ($LASTEXITCODE -ne 0) { Fail 'Gradle build failed.' }
    } finally {
        Pop-Location
    }

    $apk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apk)) { Fail "Build completed but APK was not found at $apk" }
    Write-Host "APK: $apk" -ForegroundColor Green

    if ($SkipDeploy) {
        Write-Host 'Deployment skipped by parameter.' -ForegroundColor Yellow
        exit 0
    }

    Write-Step 'Checking physical Android device'
    & $adb start-server | Out-Null
    $devices = Get-AuthorizedDevices $adb
    if ($devices.Count -eq 0) {
        Write-Host ''
        Write-Host 'No authorized physical Android device found.' -ForegroundColor Yellow
        Write-Host 'Connect the phone by USB, enable Developer Options + USB debugging, unlock it, and accept the RSA authorization dialog.'
        Write-Host 'Then run build-and-deploy.bat again.'
        exit 2
    }
    if ($devices.Count -gt 1) {
        Fail "More than one authorized device is connected: $($devices -join ', '). Disconnect all but the target device."
    }

    $serial = $devices[0]
    Write-Host "Target device: $serial"
    $abi = (& $adb -s $serial shell getprop ro.product.cpu.abi).Trim()
    Write-Host "Device ABI: $abi"
    if ($abi -ne 'arm64-v8a') {
        Fail "This PoC currently builds only arm64-v8a, but the connected device reports '$abi'."
    }

    Write-Step 'Installing APK via ADB'
    & $adb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) { Fail 'ADB installation failed.' }

    if (-not $SkipLaunch) {
        Write-Step 'Launching application'
        & $adb -s $serial shell am force-stop $ApplicationId | Out-Null
        & $adb -s $serial shell am start -W -n "$ApplicationId/$Activity"
        if ($LASTEXITCODE -ne 0) { Fail 'Application launch failed.' }

        Start-Sleep -Seconds 1
        $pid = (& $adb -s $serial shell pidof $ApplicationId).Trim()
        if (-not $pid) { Fail 'The app was installed but is not running after launch.' }
        Write-Host "App process is running (PID $pid)." -ForegroundColor Green
    }

    Write-Host ''
    Write-Host 'SUCCESS: prerequisites verified, APK built, installed, and launch test completed.' -ForegroundColor Green
    Write-Host 'You can now present an ISO-DEP/NFC card to the physical device.' -ForegroundColor Green
    exit 0
}
catch {
    Write-Host ''
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
