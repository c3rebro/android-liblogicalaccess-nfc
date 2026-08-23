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
$ConanVersion = '2.31.1'
$LlaVersion = '3.7.0'
$NdkVersion = '27.0.12077973'
$CMakeVersion = '3.22.1'
$SdkPackages = @(
    'platform-tools',
    'platforms;android-35',
    'build-tools;35.0.0',
    "ndk;$NdkVersion",
    "cmake;$CMakeVersion"
)
$AppId = 'de.shansen.liblogicalaccessnfc'
$Activity = '.MainActivity'

function Step([string]$Text) { Write-Host "`n==> $Text" -ForegroundColor Cyan }
function Fail([string]$Text) { throw $Text }

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
        if (-not $winget) { Fail 'JDK 17 not found. Install JDK 17 or Android Studio with its bundled JBR.' }
        Step 'Installing JDK 17 via winget'
        Write-Host 'Review any package/source license prompts shown by winget.' -ForegroundColor Yellow
        & $winget.Source install --id EclipseAdoptium.Temurin.17.JDK -e
        if ($LASTEXITCODE -ne 0) { Fail 'JDK 17 installation failed.' }
        $home = Find-JavaHome
    }
    if (-not $home) { Fail 'JDK 17 could not be located after installation.' }
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
    if ($answer -notmatch '^(y|yes|j|ja)$') { Fail 'Android Command-Line Tools are required.' }

    $zip = Join-Path $env:TEMP "android-cmdtools-$CmdToolsVersion.zip"
    $tmp = Join-Path $env:TEMP "android-cmdtools-$CmdToolsVersion"
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

    $url = "https://dl.google.com/android/repository/commandlinetools-win-$($CmdToolsVersion)_latest.zip"
    Invoke-WebRequest -UseBasicParsing $url -OutFile $zip
    $hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $CmdToolsSha256) { Fail "Android Command-Line Tools checksum mismatch: $hash" }

    Expand-Archive $zip $tmp -Force
    $source = Join-Path $tmp 'cmdline-tools'
    if (-not (Test-Path (Join-Path $source 'bin\sdkmanager.bat'))) { Fail 'Unexpected Command-Line Tools archive layout.' }

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
    if (-not $manager) { Fail 'sdkmanager.bat not found.' }

    Step 'Checking Android SDK licenses'
    Write-Host 'sdkmanager may ask you to review and accept missing SDK licenses.' -ForegroundColor Yellow
    & $manager "--sdk_root=$Sdk" --licenses
    if ($LASTEXITCODE -ne 0) { Fail 'Android SDK licenses were not completed.' }

    Step 'Installing/verifying Android SDK, ADB, NDK and CMake'
    & $manager "--sdk_root=$Sdk" @SdkPackages
    if ($LASTEXITCODE -ne 0) { Fail 'Android SDK package installation failed.' }

    $env:ANDROID_HOME = $Sdk
    $env:ANDROID_SDK_ROOT = $Sdk
    Set-Content (Join-Path $Root 'local.properties') "sdk.dir=$($Sdk.Replace('\','/'))" -Encoding ASCII
}

function Find-Git {
    $cmd = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($candidate in @(
        (Join-Path $env:ProgramFiles 'Git\cmd\git.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Git\cmd\git.exe')
    )) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    return $null
}

function Ensure-Git {
    $git = Find-Git
    if ($git) { return $git }

    $winget = Get-Command winget.exe -ErrorAction SilentlyContinue
    if (-not $winget) { Fail 'Git is required to fetch liblogicalaccess 3.7.0.' }
    Step 'Installing Git via winget'
    & $winget.Source install --id Git.Git -e
    if ($LASTEXITCODE -ne 0) { Fail 'Git installation failed.' }
    $git = Find-Git
    if (-not $git) { Fail 'Git could not be located after installation.' }
    return $git
}

function Find-Python {
    $cmd = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($cmd) {
        try {
            $version = (& $cmd.Source -c 'import sys; print(sys.version_info.major)').Trim()
            if ($version -eq '3') { return $cmd.Source }
        } catch {}
    }

    $py = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($py) {
        try {
            $path = (& $py.Source -3 -c 'import sys; print(sys.executable)').Trim()
            if ($path -and (Test-Path $path)) { return $path }
        } catch {}
    }

    $pythonRoot = Join-Path $env:LOCALAPPDATA 'Programs\Python'
    if (Test-Path $pythonRoot) {
        $candidate = Get-ChildItem $pythonRoot -Directory -Filter 'Python3*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'python.exe' } |
            Where-Object { Test-Path $_ } |
            Select-Object -First 1
        if ($candidate) { return $candidate }
    }
    return $null
}

function Ensure-Python {
    $python = Find-Python
    if ($python) { return $python }

    $winget = Get-Command winget.exe -ErrorAction SilentlyContinue
    if (-not $winget) { Fail 'Python 3 is required for the project-local Conan environment.' }
    Step 'Installing Python 3.12 via winget'
    & $winget.Source install --id Python.Python.3.12 -e
    if ($LASTEXITCODE -ne 0) { Fail 'Python installation failed.' }
    $python = Find-Python
    if (-not $python) { Fail 'Python could not be located after installation.' }
    return $python
}

function Ensure-Conan([string]$Python) {
    $venv = Join-Path $Root '.tools\conan-venv'
    $venvPython = Join-Path $venv 'Scripts\python.exe'
    $conan = Join-Path $venv 'Scripts\conan.exe'

    if (-not (Test-Path $venvPython)) {
        Step 'Creating project-local Python environment for Conan'
        New-Item -ItemType Directory -Force (Split-Path -Parent $venv) | Out-Null
        & $Python -m venv $venv
        if ($LASTEXITCODE -ne 0) { Fail 'Unable to create Conan Python virtual environment.' }
    }

    $installedVersion = $null
    if (Test-Path $conan) {
        try {
            $line = (& $conan --version).Trim()
            if ($line -match 'Conan version ([0-9.]+)') { $installedVersion = $Matches[1] }
        } catch {}
    }

    if ($installedVersion -ne $ConanVersion) {
        Step "Installing Conan $ConanVersion into project-local environment"
        & $venvPython -m pip install --disable-pip-version-check --upgrade "conan==$ConanVersion"
        if ($LASTEXITCODE -ne 0) { Fail 'Conan installation failed.' }
    }

    if (-not (Test-Path $conan)) { Fail 'conan.exe was not created in the project-local environment.' }
    return $conan
}

function Prepare-LibLogicalAccess([string]$Sdk, [string]$Git, [string]$Conan) {
    $ndk = Join-Path $Sdk "ndk\$NdkVersion"
    $cmakeBin = Join-Path $Sdk "cmake\$CMakeVersion\bin"
    $clang = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe'
    $ninja = Join-Path $cmakeBin 'ninja.exe'

    if (-not (Test-Path $ndk)) { Fail "Android NDK $NdkVersion not found at $ndk" }
    if (-not (Test-Path $clang)) { Fail "NDK clang++ not found: $clang" }
    if (-not (Test-Path $ninja)) { Fail "Ninja not found in Android CMake package: $ninja" }

    $clangVersionLine = (& $clang --version | Select-Object -First 1).ToString()
    if ($clangVersionLine -notmatch 'clang version ([0-9]+)') {
        Fail "Unable to determine NDK clang version from: $clangVersionLine"
    }
    $clangMajor = $Matches[1]

    $env:Path = "$cmakeBin;$env:Path"

    Step "Preparing liblogicalaccess $LlaVersion for Android arm64"
    & $Conan profile detect --force
    if ($LASTEXITCODE -ne 0) { Fail 'Conan build profile detection failed.' }

    $tools = Join-Path $Root '.tools'
    New-Item -ItemType Directory -Force $tools | Out-Null

    $profile = Join-Path $tools 'conan-android-arm64.profile'
    $ndkForConan = $ndk.Replace('\', '/')
    @"
include(default)

[settings]
os=Android
os.api_level=26
arch=armv8
compiler=clang
compiler.version=$clangMajor
compiler.libcxx=c++_shared
compiler.cppstd=17
build_type=Debug

[conf]
tools.android:ndk_path=$ndkForConan
tools.cmake.cmaketoolchain:generator=Ninja
"@ | Set-Content $profile -Encoding ASCII

    $source = Join-Path $tools "liblogicalaccess-$LlaVersion"
    $needsClone = -not (Test-Path (Join-Path $source '.git'))
    if (-not $needsClone) {
        try {
            $tag = (& $Git -C $source describe --tags --exact-match 2>$null).Trim()
            if ($tag -ne $LlaVersion) { $needsClone = $true }
        } catch { $needsClone = $true }
    }

    if ($needsClone) {
        Remove-Item $source -Recurse -Force -ErrorAction SilentlyContinue
        & $Git clone --depth 1 --branch $LlaVersion https://github.com/liblogicalaccess/liblogicalaccess.git $source
        if ($LASTEXITCODE -ne 0) { Fail "Unable to clone liblogicalaccess tag $LlaVersion." }
    }

    # Build only the public Android subset. PKCS/libusb are intentionally disabled;
    # they are not needed for phone NFC Quick Check and would add unrelated dependencies.
    & $Conan create $source `
        '-pr:h' $profile '-pr:b' default `
        '-o:h' "logicalaccess/$LlaVersion`:LLA_BUILD_PKCS=False" `
        '-o:h' "logicalaccess/$LlaVersion`:LLA_BUILD_LIBUSB=False" `
        '--build=missing' '--test-folder='
    if ($LASTEXITCODE -ne 0) { Fail 'Conan build of liblogicalaccess for Android failed.' }

    $conanOut = Join-Path $tools 'conan\android-arm64'
    $deploy = Join-Path $tools 'conan-deploy\android-arm64'
    Remove-Item $conanOut -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $deploy -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $conanOut | Out-Null
    New-Item -ItemType Directory -Force $deploy | Out-Null

    & $Conan install (Join-Path $Root 'native') `
        '-of' $conanOut `
        '-pr:h' $profile '-pr:b' default `
        '-o:h' "logicalaccess/$LlaVersion`:LLA_BUILD_PKCS=False" `
        '-o:h' "logicalaccess/$LlaVersion`:LLA_BUILD_LIBUSB=False" `
        '--build=missing' `
        '--deployer=full_deploy' '--deployer-folder' $deploy `
        '-c:h' 'tools.deployer:symlinks=False'
    if ($LASTEXITCODE -ne 0) { Fail 'Conan install/deploy for liblogicalaccess failed.' }

    $config = Get-ChildItem $conanOut -Recurse -File -Filter 'logicalaccess-config.cmake' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $config) { Fail "CMakeDeps did not generate logicalaccess-config.cmake under $conanOut" }

    # CMakeDeps can place generators in a nested folder depending on Conan layout.
    # CMake receives the exact folder containing logicalaccess-config.cmake.
    if ($config.Directory.FullName -ne $conanOut) {
        Get-ChildItem $config.Directory.FullName -File | Copy-Item -Destination $conanOut -Force
    }

    $jniRoot = Join-Path $tools 'jniLibs\arm64-v8a'
    Remove-Item $jniRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $jniRoot | Out-Null

    $hostDeploy = Join-Path $deploy 'full_deploy\host'
    if (-not (Test-Path $hostDeploy)) { Fail "Conan host deployment not found: $hostDeploy" }
    $sharedLibraries = @(Get-ChildItem $hostDeploy -Recurse -File -Filter '*.so' -ErrorAction SilentlyContinue)
    if ($sharedLibraries.Count -eq 0) { Fail 'No Android shared libraries were produced by the Conan deployment.' }

    foreach ($library in $sharedLibraries) {
        Copy-Item $library.FullName (Join-Path $jniRoot $library.Name) -Force
    }

    Write-Host "liblogicalaccess $LlaVersion prepared; $($sharedLibraries.Count) Android shared libraries staged." -ForegroundColor Green
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
    if ($hash -ne $GradleSha256) { Fail "Gradle checksum mismatch: $hash" }
    Expand-Archive $zip $tools -Force
    Remove-Item $zip -Force
    if (-not (Test-Path $gradle)) { Fail 'Gradle extraction failed.' }
    return $gradle
}

try {
    Step 'Checking prerequisites'
    Ensure-Java
    $sdk = Get-SdkRoot
    Write-Host "ANDROID_HOME=$sdk"
    Ensure-AndroidSdk $sdk

    $git = Ensure-Git
    $python = Ensure-Python
    $conan = Ensure-Conan $python
    Prepare-LibLogicalAccess $sdk $git $conan

    $gradle = Ensure-Gradle

    Step 'Running RFIDGear project/runtime tests and building app-debug.apk'
    Push-Location $Root
    try {
        & $gradle --no-daemon clean test assembleDebug
        if ($LASTEXITCODE -ne 0) { Fail 'Gradle tests or build failed.' }
    } finally { Pop-Location }

    $apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apk)) { Fail "APK not found: $apk" }
    Write-Host "Built and unit-tested: $apk" -ForegroundColor Green
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
    if ($devices.Count -gt 1) { Fail "Multiple devices connected: $($devices -join ', ')" }

    $serial = $devices[0]
    $abi = (& $adb -s $serial shell getprop ro.product.cpu.abi).Trim()
    Write-Host "Device: $serial ($abi)"
    if ($abi -ne 'arm64-v8a') { Fail "Connected device ABI '$abi' is not supported; current app build is arm64-v8a only." }

    Step 'Installing debug APK'
    & $adb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) { Fail 'ADB install failed.' }
    if ($SkipLaunch) { exit 0 }

    Step 'Launching and verifying app process'
    & $adb -s $serial shell am force-stop $AppId | Out-Null
    & $adb -s $serial shell am start -W -n "$AppId/$Activity"
    if ($LASTEXITCODE -ne 0) { Fail 'ADB launch failed.' }
    Start-Sleep -Seconds 1
    $appProcessId = (& $adb -s $serial shell pidof $AppId).Trim()
    if (-not $appProcessId) { Fail 'App was installed but is not running after launch.' }

    Write-Host "`nSUCCESS - tests passed and app is running on $serial (PID $appProcessId)." -ForegroundColor Green
    Write-Host 'Present a DESFire card to run the read-only Quick Check.' -ForegroundColor Green
    exit 0
}
catch {
    Write-Host "`nERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
