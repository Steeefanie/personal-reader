param([switch]$Release, [switch]$Offline, [switch]$WithAndroidTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$workRoot = Join-Path $projectRoot 'work'
if (-not $env:JAVA_HOME) {
    $bundledJava = Get-ChildItem (Join-Path $workRoot 'toolchain/jdk-ready') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($bundledJava) { $env:JAVA_HOME = $bundledJava.FullName }
}
if (-not $env:ANDROID_HOME -and (Test-Path (Join-Path $workRoot 'android-sdk'))) { $env:ANDROID_HOME = Join-Path $workRoot 'android-sdk' }
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $workRoot 'gradle-home' }
if (-not $env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME = Join-Path $workRoot 'android-user' }
if (-not $env:JAVA_HOME) { throw '请设置 JAVA_HOME 为 JDK 17 路径。' }
if (-not $env:ANDROID_HOME -and -not (Test-Path (Join-Path $projectRoot 'local.properties'))) { throw '请设置 ANDROID_HOME，或在 Android Studio 配置 Android SDK。' }
$keytool = Join-Path $env:JAVA_HOME 'bin/keytool'
if (Test-Path ($keytool + '.exe')) { $keytool += '.exe' }
New-Item -ItemType Directory -Force (Join-Path $projectRoot 'build') | Out-Null
$debugKey = Join-Path $projectRoot 'build/debug.keystore'
$existingKey = Join-Path $workRoot 'signing/personal-reader.keystore'
if (-not (Test-Path $existingKey)) { $existingKey = Join-Path $workRoot 'signing/leaf-reader-test.keystore' }
if (-not (Test-Path $debugKey) -and (Test-Path $existingKey)) { Copy-Item -LiteralPath $existingKey -Destination $debugKey }
if (-not (Test-Path $debugKey)) {
    & $keytool -genkeypair -keystore $debugKey -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw '开发测试签名生成失败。' }
}
if ($Release) {
    foreach ($name in @('READER_KEYSTORE','READER_STORE_PASSWORD','READER_KEY_ALIAS','READER_KEY_PASSWORD')) {
        if (-not [Environment]::GetEnvironmentVariable($name)) { throw "发布签名缺少环境变量 $name，见 docs/releasing.md。" }
    }
    if (-not (Test-Path -LiteralPath $env:READER_KEYSTORE -PathType Leaf)) { throw '发布签名文件不存在。' }
}
Push-Location $projectRoot
try {
    $bundledGradle = Join-Path $workRoot 'toolchain/gradle-ready/gradle-8.14.3/bin/gradle.bat'
    $gradleCommand = if (Test-Path $bundledGradle) { $bundledGradle } elseif ($IsLinux -or $IsMacOS) { Join-Path $projectRoot 'gradlew' } else { Join-Path $projectRoot 'gradlew.bat' }
    $tasks = if ($Release) { @('assembleRelease','testDebugUnitTest','lintRelease') } else { @('assembleDebug','testDebugUnitTest','lintDebug') }
    if ($WithAndroidTests) { $tasks += @('assembleDebug','assembleDebugAndroidTest') }
    $arguments = $tasks + @('--console=plain','--max-workers=4','--no-daemon','-Pkotlin.compiler.execution.strategy=in-process')
    if ($Offline) { $arguments += '--offline' }
    & $gradleCommand @arguments
    if ($LASTEXITCODE -ne 0) { throw '构建或检查失败。' }
} finally { Pop-Location }
