[CmdletBinding()]
param(
  [ValidateSet('windows-msvc-release', 'windows-mingw-release')]
  [string]$Preset = 'windows-msvc-release'
)

$ErrorActionPreference = 'Stop'
if (-not $env:VCPKG_ROOT) {
  throw 'VCPKG_ROOT is not set. Point it to your vcpkg checkout.'
}

cmake --preset $Preset
cmake --build --preset $Preset
ctest --preset $Preset

