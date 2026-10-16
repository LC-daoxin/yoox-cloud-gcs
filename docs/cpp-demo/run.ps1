[CmdletBinding(PositionalBinding = $false)]
param(
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^demo_\d\d_[a-z0-9_]+$')]
  [string]$Demo,
  [ValidateSet('windows-msvc-release', 'windows-mingw-release')]
  [string]$Preset = 'windows-msvc-release',
  [string]$EnvFile,
  [switch]$Execute,
  [string[]]$DemoArgs = @()
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $EnvFile) {
  $cppEnv = Join-Path $root '.env'
  $pythonEnv = Join-Path (Split-Path -Parent $root) 'python-demo/.env'
  if (Test-Path $cppEnv) {
    $EnvFile = $cppEnv
  } elseif (Test-Path $pythonEnv) {
    $EnvFile = $pythonEnv
  } else {
    throw 'No .env found. Use -EnvFile or create docs/cpp-demo/.env.'
  }
}

$relativeExe = if ($Preset -eq 'windows-msvc-release') {
  "build/$Preset/Release/$Demo.exe"
} else {
  "build/$Preset/$Demo.exe"
}
$exe = Join-Path $root $relativeExe
if (-not (Test-Path $exe)) {
  throw "Executable not found: $exe. Run .\build.ps1 first."
}

$argsList = @('--env-file', (Resolve-Path $EnvFile).Path)
if ($Execute) { $argsList += '--execute' }
$argsList += $DemoArgs
& $exe @argsList
exit $LASTEXITCODE

