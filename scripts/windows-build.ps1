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
    # PROGRAMW6432 is always the native 64-bit Program Files, even in a WOW64 process.
    $pf = if ($env:PROGRAMW6432) { $env:PROGRAMW6432 } else { $env:ProgramFiles }

    $homes = @()
    if ($env:JAVA_HOME) { $homes += $env:JAVA_HOME }
    $homes += (Join-Path $pf 'Android\Android Studio\jbr')
    $homes += (Join-Path $pf 'Android\Android Studio\jre')

    $adoptium = Join-Path $pf 'Eclipse Adoptium'
    if (Test-Path $adoptium) {
        $homes += @(Get-ChildItem $adoptium -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object FullName)
    }

    # Registry fallback using an explicit 64-bit view so it works in both 32-bit and
    # 64-bit PowerShell, and covers cases where Get-ChildItem silently fails on
    # installer-restricted Program Files subdirectories.
    try {
        $reg = [Microsoft.Win32.RegistryKey]::OpenBaseKey(
            [Microsoft.Win32.RegistryHive]::LocalMachine,
            [Microsoft.Win32.RegistryView]::Registry64)
        $jdkKey = $reg.OpenSubKey('SOFTWARE\JavaSoft\JDK')
        if ($jdkKey) {
            foreach ($subName in $jdkKey.GetSubKeyNames()) {
                try {
                    if ([int]($subName -split '[.\-]')[0] -ge 17) {
                        $sub = $jdkKey.OpenSubKey($subName)
                        $p = $sub.GetValue('JavaHome')
                        if ($p -and (Test-Path $p)) { $homes += $p }
                        $sub.Close()
                    }
                } catch {}
            }
            $jdkKey.Close()
        }
        $reg.Close()
    } catch {}

    foreach ($javaHome in ($homes | Where-Object { $_ } | Select-Object -Unique)) {
        $java = Join-Path $javaHome 'bin\java.exe'
        if ((Test-Path $java) -and ((Get-JavaMajor $java) -ge 17)) { return $javaHome }
    }
    return $null
}

function Ensure-Java {
    $javaHome = Find-JavaHome
    if (-not $javaHome) {
        $winget = Get-Command winget.exe -ErrorAction SilentlyContinue
        if (-not $winget) { Fail 'JDK 17 not found. Install JDK 17 or Android Studio with its bundled JBR.' }
        Step 'Installing JDK 17 via winget'
        Write-Host 'Review any package/source license prompts shown by winget.' -ForegroundColor Yellow
        & $winget.Source install --id EclipseAdoptium.Temurin.17.JDK -e
        # winget returns non-zero when already installed or when UAC elevation causes a deferred install;
        # rely on Find-JavaHome to confirm rather than the exit code.
        $javaHome = Find-JavaHome
    }
    if (-not $javaHome) { Fail 'JDK 17 could not be located. Install JDK 17 or Android Studio with its bundled JBR.' }
    $env:JAVA_HOME = $javaHome
    $env:Path = "$(Join-Path $javaHome 'bin');$env:Path"
    Write-Host "JAVA_HOME=$javaHome"
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
    & $winget.Source install --id Git.Git -e | Out-Host
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
    & $winget.Source install --id Python.Python.3.12 -e | Out-Host
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
        & $Python -m venv $venv | Out-Host
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
        & $venvPython -m pip install --disable-pip-version-check --upgrade "conan==$ConanVersion" | Out-Host
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
    $conanHomeDir = if ($env:CONAN_HOME) { $env:CONAN_HOME } else { Join-Path $env:USERPROFILE '.conan2' }
    $defaultProfile = Join-Path $conanHomeDir 'profiles\default'
    if (-not (Test-Path $defaultProfile)) {
        Step 'Generating Conan default host profile (one-time setup)'
        & $Conan profile detect
        if ($LASTEXITCODE -ne 0) { Fail 'Conan build profile detection failed.' }
    }

    $tools = Join-Path $Root '.tools'
    New-Item -ItemType Directory -Force $tools | Out-Null

    $profile = Join-Path $tools 'conan-android-arm64.profile'
    $ndkForConan = $ndk.Replace('\', '/')
    $ndkBin     = "$ndkForConan/toolchains/llvm/prebuilt/windows-x86_64/bin"
    $ndkSysroot = "$ndkForConan/toolchains/llvm/prebuilt/windows-x86_64/sysroot"
    $apiTarget  = "aarch64-linux-android26"

    # Create bash wrapper scripts for the NDK clang compiler/linker.
    #
    # Problem: OpenSSL links ~1000 .o files; the command line is ~40,000 chars —
    # over both Windows limits (cmd.exe: 8191, CreateProcess: 32767).  The Conan
    # OpenSSL recipe uses AutotoolsToolchain which hardcodes CC/CXX to the .cmd
    # wrappers in conanautotoolstoolchain.sh; those .cmd files must go through
    # cmd.exe and cannot handle the long link command.
    #
    # Fix: A Conan pre_build hook patches conanautotoolstoolchain.sh (written by
    # generate(), read by build()) to replace the .cmd paths with these bash
    # wrappers.  MSYS2 make exec()s the bash script via POSIX IPC (no Windows
    # length limit), and the script moves .o input files to a @response_file so
    # the final clang.exe call stays within 32767 chars.
    $wrapperDir = Join-Path $tools 'ndk-clang-wrappers'
    New-Item -ItemType Directory -Force $wrapperDir | Out-Null

    $wrapperBody = @'
#!/bin/bash
REAL="__REAL__"
CMDLEN=0; for a in "$@"; do CMDLEN=$((CMDLEN + ${#a} + 1)); done
if [[ $CMDLEN -le 25000 ]]; then exec "$REAL" "$@"; fi
# clang.exe is a Windows binary and needs a Windows path for @response_file.
# mktemp gives a POSIX path; cygpath -w converts it to a Windows path.
RSP_POSIX=$(mktemp /tmp/lnk_XXXXXXXX.rsp)
RSP_WIN=$(cygpath -w "$RSP_POSIX")
NEWARGS=(); SKIP=false
for a in "$@"; do
    if $SKIP; then NEWARGS+=("$a"); SKIP=false
    elif [[ "$a" == "-o" ]]; then NEWARGS+=("$a"); SKIP=true
    elif [[ "$a" == *.o ]]; then printf '%s\n' "$a" >> "$RSP_POSIX"
    else NEWARGS+=("$a")
    fi
done
"$REAL" "${NEWARGS[@]}" "@$RSP_WIN"; STATUS=$?; rm -f "$RSP_POSIX"; exit $STATUS
'@

    $wrapperCFile   = Join-Path $wrapperDir 'ndk-clang.sh'
    $wrapperCPPFile = Join-Path $wrapperDir 'ndk-clang++.sh'
    ($wrapperBody -replace '__REAL__', "$ndkBin/clang.exe")   | Set-Content $wrapperCFile   -Encoding UTF8 -NoNewline
    ($wrapperBody -replace '__REAL__', "$ndkBin/clang++.exe") | Set-Content $wrapperCPPFile -Encoding UTF8 -NoNewline

    # Helper: Windows path → MSYS2 POSIX path  (C:\foo\bar → /c/foo/bar)
    function toPosix([string]$p) {
        $p = $p -replace '\\', '/'
        if ($p -match '^([A-Za-z]):(.*)') { return '/' + $Matches[1].ToLower() + $Matches[2] }
        return $p
    }

    # chmod +x so MSYS2 make can exec the wrappers directly (NTFS files created
    # by PowerShell don't have the POSIX execute bit set in MSYS2's metadata).
    $posixC   = toPosix $wrapperCFile
    $posixCPP = toPosix $wrapperCPPFile
    $gitRoot  = Split-Path (Split-Path $Git -Parent) -Parent
    $gitBash  = Join-Path $gitRoot 'bin\bash.exe'
    if (-not (Test-Path $gitBash)) { $gitBash = Join-Path $gitRoot 'usr\bin\bash.exe' }
    if (Test-Path $gitBash) {
        & $gitBash -c "chmod +x '$posixC' '$posixCPP'" 2>&1 | Out-Null
    }

    # POSIX paths embedded in conanautotoolstoolchain.sh; pass to the hook via env vars.
    $env:NDK_CLANG_WRAPPER_C   = $posixC
    $env:NDK_CLANG_WRAPPER_CPP = $posixCPP

    # Install Conan pre_build hook: patches conanautotoolstoolchain.sh for openssl
    # right before build() runs, replacing .cmd compiler paths with our wrappers.
    # Conan 2 requires the file name to start with hook_ for auto-discovery.
    $hooksDir = Join-Path $env:CONAN_HOME 'extensions\hooks'
    New-Item -ItemType Directory -Force $hooksDir | Out-Null
    @'
import os, re

def pre_build(conanfile):
    if getattr(conanfile, "name", None) != "openssl":
        return
    sh = os.path.join(conanfile.build_folder, "conan", "conanautotoolstoolchain.sh")
    if not os.path.isfile(sh):
        return
    wc  = os.environ.get("NDK_CLANG_WRAPPER_C",   "")
    wpp = os.environ.get("NDK_CLANG_WRAPPER_CPP",  "")
    if not wc or not wpp:
        return
    txt = open(sh).read()
    txt = re.sub(r'[^"\']*aarch64-linux-android\d+-clang\.cmd',   wc,  txt)
    txt = re.sub(r'[^"\']*aarch64-linux-android\d+-clang\+\+\.cmd', wpp, txt)
    open(sh, "w").write(txt)
'@ | Set-Content (Join-Path $hooksDir 'hook_openssl_clang_wrapper.py') -Encoding UTF8

    # Point the boost b2 build (clang-linux toolset) at the raw NDK .exe binaries.
    # b2.exe on Windows can invoke .exe files directly; .cmd wrappers cannot be executed
    # via CreateProcess without going through cmd.exe, which b2 does not do.
    # The target triple and sysroot are supplied separately via the conf keys so that
    # b2's user-config.jam includes them in <compileflags> and <linkflags>.
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
build_type=Release

[conf]
tools.android:ndk_path=$ndkForConan
tools.cmake.cmaketoolchain:generator=Ninja
tools.build:compiler_executables={"c": "$ndkBin/clang.exe", "cpp": "$ndkBin/clang++.exe", "ar": "$ndkBin/llvm-ar.exe", "ranlib": "$ndkBin/llvm-ranlib.exe"}
tools.build:cflags=["--target=$apiTarget"]
tools.build:cxxflags=["--target=$apiTarget"]
tools.build:sharedlinkflags=["--target=$apiTarget"]
tools.build:sysroot=$ndkSysroot
tools.cmake.cmaketoolchain:extra_variables={"BUILD_TESTING": "OFF"}
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

    # Patch samples/basic out of the build — it links -lpcscreaders which is desktop-only.
    # BUILD_TESTING=OFF (set via Conan extra_variables) already skips tests; we reuse
    # the same guard here.  The patch is committed and the tag force-updated so that
    # conan export's revision_mode='scm' check keeps passing on subsequent runs.
    $cmakeLists = Join-Path $source 'CMakeLists.txt'
    $cmakeContent = Get-Content $cmakeLists -Raw
    if ($cmakeContent -match '(?m)^add_subdirectory\(samples/basic\)') {
        $patched = $cmakeContent -replace '(?m)^add_subdirectory\(samples/basic\)',
            "if (NOT BUILD_TESTING STREQUAL OFF)`r`n    add_subdirectory(samples/basic)`r`nendif ()"
        [System.IO.File]::WriteAllText($cmakeLists, $patched, [System.Text.Encoding]::UTF8)
        & $Git -C $source add CMakeLists.txt
        & $Git -C $source -c user.email='build@local' -c user.name='build' `
            commit -m "Disable samples/basic for Android cross-compile (no PCSC)"
        & $Git -C $source tag -f $LlaVersion
        if ($LASTEXITCODE -ne 0) { Fail "Failed to patch liblogicalaccess CMakeLists.txt." }
    }

    # Build only the public Android subset. PKCS/libusb are intentionally disabled;
    # they are not needed for phone NFC Quick Check and would add unrelated dependencies.
    #
    # Boost option rationale (Android cross-compilation on Windows):
    #   without_stacktrace    : boost recipe validates addr2line_location is absolute when
    #                           os != Windows; addr2line is unresolved in this cross-compile.
    #   without_locale        : pulls in libiconv/1.17 which builds via autotools and fails
    #                           because MSYS2 bash cannot execute NDK's .cmd compiler wrappers.
    #   without_iostreams     : pulls in bzip2/1.0.8, same autotools/.cmd issue.
    #   The rest below        : liblogicalaccess only needs atomic, chrono, container,
    #                           date_time, exception, filesystem, regex, system, thread.
    #                           Disabling everything else cuts the build from 20+ to 9
    #                           compiled libraries, avoiding several Android-incompatible
    #                           components (url, wave, fiber/context assembly, log cmake
    #                           generation quirks, etc.) and keeps the build short enough
    #                           to fit inside a typical privileged-session window.
    $boostAndroidOpts = @(
        '-o:h', 'boost/*:without_stacktrace=True',
        '-o:h', 'boost/*:without_locale=True',
        '-o:h', 'boost/*:without_iostreams=True',
        '-o:h', 'boost/*:without_charconv=True',
        '-o:h', 'boost/*:without_cobalt=True',
        '-o:h', 'boost/*:without_context=True',
        '-o:h', 'boost/*:without_contract=True',
        '-o:h', 'boost/*:without_coroutine=True',
        '-o:h', 'boost/*:without_fiber=True',
        '-o:h', 'boost/*:without_graph=True',
        '-o:h', 'boost/*:without_graph_parallel=True',
        '-o:h', 'boost/*:without_json=True',
        '-o:h', 'boost/*:without_log=True',
        '-o:h', 'boost/*:without_math=True',
        '-o:h', 'boost/*:without_mpi=True',
        '-o:h', 'boost/*:without_nowide=True',
        '-o:h', 'boost/*:without_process=True',
        '-o:h', 'boost/*:without_program_options=True',
        '-o:h', 'boost/*:without_python=True',
        '-o:h', 'boost/*:without_random=True',
        '-o:h', 'boost/*:without_serialization=True',
        '-o:h', 'boost/*:without_test=True',
        '-o:h', 'boost/*:without_timer=True',
        '-o:h', 'boost/*:without_type_erasure=True',
        '-o:h', 'boost/*:without_url=True',
        '-o:h', 'boost/*:without_wave=True'
    )

    # The Boost recipe's build() method patches files inside the shared Conan source
    # cache.  If a previous build ran under a different security context (e.g. via a
    # privileged-session tool like "Admin By Request"), those cache files are owned by
    # that elevated account and become read-only to the normal user.  Granting the
    # current user full control before invoking Conan prevents PermissionError on
    # the replace_in_file calls, and also ensures files created during this run
    # remain accessible to the normal user after the elevated session ends.
    # Use CONAN_HOME if set (short path to avoid command-line length limits), else fall back.
    $conanHome = if ($env:CONAN_HOME) { $env:CONAN_HOME } else { Join-Path $env:USERPROFILE '.conan2' }
    if (Test-Path $conanHome) {
        $grantee = "$env:USERDOMAIN\$env:USERNAME"
        & icacls $conanHome /grant "${grantee}:(OI)(CI)F" /T /C 2>&1 | Out-Null
    }

    # Remove any partial/stale boost and openssl binaries from the Conan cache, but only
    # when a full build is actually needed (i.e. the deployed .so files are not yet present).
    # This avoids discarding a valid cached package on every run while still clearing the
    # broken partial state left by a previously-interrupted build.
    # OpenSSL is cleared alongside boost because it was previously built with build_type=Debug
    # (which caused '-MDd' unknown-argument failures with the NDK clang).  Now that the
    # profile uses build_type=Release we must discard the stale Debug package.
    $deployCheck = Join-Path $tools 'conan-deploy\android-arm64'
    $boostDeploySoFiles = @(Get-ChildItem $deployCheck -Recurse -Filter 'libboost_system.so' `
        -ErrorAction SilentlyContinue)
    if ($boostDeploySoFiles.Count -eq 0) {
        Step 'Clearing any partial Boost and OpenSSL Conan cache before fresh build'
        & $Conan remove 'boost/*' --confirm 2>&1 | Out-Null
        & $Conan remove 'openssl/*' --confirm 2>&1 | Out-Null
    }
    & $Conan create $source `
        '-pr:h' $profile '-pr:b' default `
        '-o:h' "logicalaccess/$LlaVersion`:LLA_BUILD_PKCS=False" `
        '-o:h' "logicalaccess/$LlaVersion`:LLA_BUILD_LIBUSB=False" `
        @boostAndroidOpts `
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
        @boostAndroidOpts `
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
    $gradleDir = Join-Path $tools "gradle-$GradleVersion"
    $gradle = Join-Path $gradleDir 'bin\gradle.bat'
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

    # Use a short Conan home path to avoid the Windows command-line length limit
    # (8191 chars for cmd.exe, 32767 for CreateProcess) when linking OpenSSL's
    # libcrypto.so.3.  That link command lists hundreds of .o files; with the
    # default ~/.conan2 path each entry is ~80 chars, which blows the limit.
    # C:\c2 keeps each entry short enough to fit within the limit.
    $env:CONAN_HOME = 'C:\c2'

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
