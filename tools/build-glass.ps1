param([Parameter(Mandatory = $true)][string]$Output)
$ErrorActionPreference = 'Stop'
$locator = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
$installation = & $locator -latest -products '*' -property installationPath
if (-not $installation) { throw 'Visual Studio C++ tools are required to build desktop capture.' }
$compilerRoot = Get-ChildItem -LiteralPath (Join-Path $installation 'VC\Tools\MSVC') -Directory |
    Sort-Object Name -Descending | Select-Object -First 1
$sdkRoot = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10'
$sdk = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'Include') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'cppwinrt\winrt\Windows.Graphics.Capture.h') } |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $compilerRoot -or -not $sdk) { throw 'MSVC and Windows SDK with C++/WinRT are required.' }
$runtimeRoot = $compilerRoot.FullName
if (-not (Test-Path -LiteralPath (Join-Path $runtimeRoot 'include\vcruntime.h'))) {
    # A compiler-only VS installation can use matching CRT/STL build dependencies locally.
    $runtimeRoot = Join-Path $PSScriptRoot ('..\build\msvc\VC\Tools\MSVC\' + $compilerRoot.Name)
}
if (-not (Test-Path -LiteralPath (Join-Path $runtimeRoot 'include\vcruntime.h'))) {
    throw 'MSVC CRT/STL headers and x64 desktop libraries are required.'
}
$target = [System.IO.Path]::GetFullPath($Output)
$targetDirectory = [System.IO.Path]::GetDirectoryName($target)
[System.IO.Directory]::CreateDirectory($targetDirectory) | Out-Null
$arguments = @('/nologo', '/std:c++20', '/EHsc', '/O2', '/MT', '/LD', '/W4', '/permissive-', '/utf-8',
    '/DWIN32_LEAN_AND_MEAN', '/DNOMINMAX', '/D_WIN32_WINNT=0x0A00', '/DWINVER=0x0A00',
    ('/I' + (Join-Path $runtimeRoot 'include')))
foreach ($part in @('ucrt', 'shared', 'um', 'winrt', 'cppwinrt')) {
    $arguments += '/I' + (Join-Path $sdk.FullName $part)
}
$arguments += @((Join-Path $PSScriptRoot '..\native\glass\DesktopCapture.cpp'),
    ('/Fo' + (Join-Path $targetDirectory 'DesktopCapture.obj')), ('/Fe' + $target), '/link', '/INCREMENTAL:NO',
    ('/LIBPATH:' + (Join-Path $runtimeRoot 'lib\x64')),
    ('/LIBPATH:' + (Join-Path $compilerRoot.FullName 'lib\onecore\x64')),
    ('/LIBPATH:' + (Join-Path $sdkRoot ('Lib\' + $sdk.Name + '\um\x64'))),
    ('/LIBPATH:' + (Join-Path $sdkRoot ('Lib\' + $sdk.Name + '\ucrt\x64'))),
    'windowsapp.lib', 'd3d11.lib', 'd3d12.lib', 'dxgi.lib', 'user32.lib')
& (Join-Path $compilerRoot.FullName 'bin\Hostx64\x64\cl.exe') @arguments
if ($LASTEXITCODE -ne 0) { throw "Desktop capture build failed: $LASTEXITCODE" }
