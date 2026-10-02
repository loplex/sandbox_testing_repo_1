# Installs the MSI, upgrades it to a later one and removes it, checking each step: the windows'
# launchers with their shortcuts in the Start menu and on the desktop, and the command line's with
# none but the installation folder on the system's PATH. The MSI also removes the folder ffmpeg is
# downloaded into, %ProgramData%\<the app's name>, which the script makes before the upgrade, as a
# window would, and which has to stay over that.
#
# The MSIs are tools/package_msi_on_windows.ps1's or tools/package_msi_on_linux.sh's: -Msi of
# -Version, and -UpgradeMsi of the later -UpgradeVersion. Each check says whether it held, and every
# check runs, so that one failing does not hide the others; the script fails if any did. msiexec's
# logs go to -LogDir.
#
# It installs for every user and changes the machine, so it is for one that is thrown away after,
# such as a CI runner, run as an administrator in PowerShell 7 (pwsh). WIX names WiX Toolset 3,
# whose smoke.exe validates the MSIs.
param(
    [Parameter(Mandatory)] [string]$Msi,
    [Parameter(Mandatory)] [string]$Version,
    [Parameter(Mandatory)] [string]$UpgradeMsi,
    [Parameter(Mandatory)] [string]$UpgradeVersion,
    [Parameter(Mandatory)] [string]$LogDir
)

$ErrorActionPreference = "Stop"

# msiexec runs apart from this script, so it is given whole paths.
$Msi = (Resolve-Path $Msi).Path
$UpgradeMsi = (Resolve-Path $UpgradeMsi).Path
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$LogDir = (Resolve-Path $LogDir).Path

$failures = [System.Collections.Generic.List[string]]::new()

function Test-Check([string]$What, [bool]$Holds) {
    if ($Holds) {
        Write-Host "ok: $What"
    } else {
        Write-Host "FAILED: $What"
        $failures.Add($What)
    }
}

# The product's name, as packaging/windows/dog-vision.wxs gives it: the app's English name, which
# the list of installed programs, the Start menu's group and the Compose window's shortcuts show.
$root = Split-Path -Parent $PSScriptRoot
$strings = Get-Content (Join-Path $root "texts\strings\values\strings.xml") -Raw
if ($strings -notmatch '<string name="app_name">([^<]+)</string>') {
    throw "texts\strings\values\strings.xml has no app_name"
}
$product = $Matches[1]

$installDir = Join-Path $env:ProgramFiles "dog-vision"
$startMenu = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\$product"
$desktop = Join-Path $env:PUBLIC "Desktop"
# The windows' shortcuts, each in the Start menu's group and on the desktop.
$shortcuts = @("$product.lnk", "$product (Swing).lnk")
# Where a window downloads ffmpeg, which the MSI removes with the product, but not over an upgrade.
$data = Join-Path $env:ProgramData $product

# msiexec with [Arguments], waited for; 3010 is success that asks for a restart.
function Invoke-Msiexec([string]$Log, [string[]]$Arguments) {
    $all = $Arguments + @("/qn", "/l*v", "`"$(Join-Path $LogDir $Log)`"")
    $process = Start-Process "msiexec.exe" -ArgumentList $all -Wait -PassThru
    return $process.ExitCode -in @(0, 3010)
}

# The products of the product's name in the list of installed programs, as Windows Installer
# registers them.
function Get-Installed {
    @(
        "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*"
    ) | ForEach-Object { Get-ItemProperty $_ -ErrorAction SilentlyContinue } |
        Where-Object { $_.PSObject.Properties["DisplayName"] -and $_.DisplayName -eq $product }
}

# The MSI against Windows Installer's rules (ICE).
function Test-Ice([string]$Package) {
    & (Join-Path $env:WIX "bin\smoke.exe") -nologo $Package
    Test-Check "$(Split-Path -Leaf $Package) passes ICE validation" ($LASTEXITCODE -eq 0)
}

# How often the installation folder is on the system's PATH, as the registry holds it for the
# processes started after.
function Get-OnPath {
    $entries = [Environment]::GetEnvironmentVariable("Path", "Machine") -split ";"
    @($entries | Where-Object { $_.TrimEnd("\") -eq $installDir }).Count
}

# The installed product: one, of [Expected] version, with the three launchers, and its folder on
# the PATH once.
function Test-Installed([string]$Expected) {
    $installed = @(Get-Installed)
    Test-Check "one $product is installed" ($installed.Count -eq 1)
    Test-Check "the installed version is $Expected" (@($installed | Where-Object DisplayVersion -eq $Expected).Count -eq 1)
    foreach ($launcher in @("dog-vision.exe", "dog-vision-swing.exe", "dog-vision-cli.exe")) {
        Test-Check "$launcher is installed" (Test-Path (Join-Path $installDir $launcher))
    }
    Test-Check "the installation folder is on the PATH once" ((Get-OnPath) -eq 1)
}


# The first version.

# The system's PATH as it was before, which removing the product is to leave as it was.
$pathBefore = [Environment]::GetEnvironmentVariable("Path", "Machine")
Test-Ice $Msi
Test-Check "the MSI of $Version installs" (Invoke-Msiexec "install-$Version.log" @("/i", "`"$Msi`""))
Test-Installed $Version

# The command line converts a photo, red on the left and blue on the right, with the runtime in the MSI.
$work = Join-Path ([System.IO.Path]::GetTempPath()) "dog-vision-msi-test"
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
$converted = New-Item -ItemType Directory -Path (Join-Path $work "converted")
$photo = Join-Path $work "photo.png"
$png = "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAECAIAAAA8r+mnAAAAFElEQVR4nGP4z8AAR0jM/wzUkwAAOn4f4QhCQp4AAAAASUVORK5CYII="
[System.IO.File]::WriteAllBytes($photo, [System.Convert]::FromBase64String($png))
& (Join-Path $installDir "dog-vision-cli.exe") --species cat --output-dir $converted.FullName $photo
Test-Check "dog-vision-cli.exe converts a photo" (
    $LASTEXITCODE -eq 0 -and @(Get-ChildItem $converted.FullName -Filter "*.png").Count -eq 1
)

# Each window has a shortcut in the Start menu and on the desktop; the command line, which only
# prints its usage when started from one, has none.
Write-Host "Start menu: $(@(Get-ChildItem $startMenu -ErrorAction SilentlyContinue).Name -join ', ')"
Write-Host "Desktop: $(@(Get-ChildItem $desktop -Filter "$product*" -ErrorAction SilentlyContinue).Name -join ', ')"
foreach ($shortcut in $shortcuts) {
    Test-Check "the Start menu has $shortcut" (Test-Path (Join-Path $startMenu $shortcut))
    Test-Check "the desktop has $shortcut" (Test-Path (Join-Path $desktop $shortcut))
}
Test-Check "the Start menu's group holds the windows' shortcuts alone" (
    @(Get-ChildItem $startMenu -ErrorAction SilentlyContinue).Count -eq $shortcuts.Count
)

# dog-vision-cli runs by its name alone, from the PATH a process started after the installation
# has, from the registry, as this script's own is the one it started with.
$env:PATH = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
    [Environment]::GetEnvironmentVariable("Path", "User")
$found = Get-Command "dog-vision-cli" -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
Test-Check "dog-vision-cli on the PATH is the installed one" (
    $found -and $found.Source -eq (Join-Path $installDir "dog-vision-cli.exe")
)
if ($found) {
    dog-vision-cli --help | Out-Null
}
Test-Check "dog-vision-cli runs from the PATH" ($found -and $LASTEXITCODE -eq 0)


# The later version, over it, with a file in the folder a window downloads ffmpeg into, as if it had.

New-Item -ItemType Directory -Force -Path (Join-Path $data "ffmpeg\bin") | Out-Null
Set-Content (Join-Path $data "ffmpeg\bin\ffmpeg.exe") "downloaded"
Test-Ice $UpgradeMsi
Test-Check "the MSI of $UpgradeVersion installs over $Version" (
    Invoke-Msiexec "install-$UpgradeVersion.log" @("/i", "`"$UpgradeMsi`"")
)
Test-Installed $UpgradeVersion
Test-Check "the folder ffmpeg is downloaded into stays over the upgrade" (
    Test-Path (Join-Path $data "ffmpeg\bin\ffmpeg.exe")
)


# Removing it.

Test-Check "the MSI of $UpgradeVersion uninstalls" (
    Invoke-Msiexec "uninstall-$UpgradeVersion.log" @("/x", "`"$UpgradeMsi`"")
)
Test-Check "no $product is installed after" (@(Get-Installed).Count -eq 0)
Test-Check "the installation folder is gone" (-not (Test-Path $installDir))
Test-Check "the Start menu folder is gone" (-not (Test-Path $startMenu))
foreach ($shortcut in $shortcuts) {
    Test-Check "the desktop's $shortcut is gone" (-not (Test-Path (Join-Path $desktop $shortcut)))
}
Test-Check "the folder ffmpeg is downloaded into is gone" (-not (Test-Path $data))
Test-Check "the installation folder is not on the PATH" ((Get-OnPath) -eq 0)
Test-Check "the PATH is as it was before, separators included" (
    [Environment]::GetEnvironmentVariable("Path", "Machine") -eq $pathBefore
)


if ($failures.Count -gt 0) {
    Write-Host "$($failures.Count) check(s) failed:"
    $failures | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host "Every check held."
