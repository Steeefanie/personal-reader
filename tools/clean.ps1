# 默认只列出清理目标；加 -Apply 才执行。始终保留源码、签名和本机工具链。
param([switch]$Apply, [switch]$Build, [switch]$History)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Split-Path $PSScriptRoot -Parent)).Path.TrimEnd([IO.Path]::DirectorySeparatorChar)
$targets = [Collections.Generic.List[string]]::new()
if ($Build) {
    @('app/build','build/reports','.gradle','.kotlin') | ForEach-Object { $targets.Add((Join-Path $projectRoot $_)) }
}
if ($History) {
    @('outputs/archive','outputs/previews','work/backups','work/font-candidates','work/qr-code-generator') | ForEach-Object { $targets.Add((Join-Path $projectRoot $_)) }
    Get-ChildItem (Join-Path $projectRoot 'outputs') -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -in @('.apk','.zip') -or $_.Name -eq 'SHA256SUMS.txt' } | ForEach-Object { $targets.Add($_.FullName) }
    # 按版本归档的验证目录会保留，根目录中为未归档的开发日志。
    Get-ChildItem (Join-Path $projectRoot 'work/validation') -File -ErrorAction SilentlyContinue | ForEach-Object { $targets.Add($_.FullName) }
    Get-ChildItem (Join-Path $projectRoot 'work/signing') -File -Filter '*mapping*.txt' -ErrorAction SilentlyContinue | ForEach-Object { $targets.Add($_.FullName) }
}
if (-not $Build -and -not $History) { throw '请选择 -Build 或 -History；默认预览，加 -Apply 执行。' }
foreach ($target in $targets) {
    if (-not (Test-Path -LiteralPath $target)) { continue }
    $resolved = (Resolve-Path -LiteralPath $target).Path
    $prefix = $projectRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)) { throw "清理目标超出项目目录：$resolved" }
    $item = Get-Item -LiteralPath $resolved -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "不清理链接目录：$resolved" }
    if ($item.PSIsContainer -and (Get-ChildItem -LiteralPath $resolved -Recurse -Force -Attributes ReparsePoint | Select-Object -First 1)) { throw "目录包含链接，请人工核对：$resolved" }
    Write-Output $resolved
    if ($Apply) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
if (-not $Apply) { Write-Output '以上仅预览，没有删除文件。' }
