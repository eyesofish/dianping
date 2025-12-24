$jdkHome = "D:\tools\jdk-21.0.9+10"
if (-not (Test-Path $jdkHome)) {
    Write-Error "JDK 未找到：$jdkHome（请按需修改脚本中的路径）"
    exit 1
}
$env:JAVA_HOME = $jdkHome
$env:Path = "$jdkHome\bin;$env:Path"
mvn @args
