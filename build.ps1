param([switch]$Package,[string]$OutputDir="$PSScriptRoot/build/packages",[switch]$Installer,[string]$Version=$(if($env:AEROMON_VERSION){$env:AEROMON_VERSION}else{'1.0.0'}))
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$jdk=Split-Path (Split-Path (Get-Command javac).Source)
New-Item -ItemType Directory -Force "$root/build/deps","$root/build/classes","$root/build/dist" | Out-Null
$gson="$root/build/deps/gson.jar"
if(!(Test-Path $gson)){Invoke-WebRequest -UseBasicParsing 'https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar' -OutFile $gson}
$expected=(Invoke-WebRequest -UseBasicParsing 'https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar.sha1').Content.Trim()
if((Get-FileHash $gson -Algorithm SHA1).Hash.ToLower() -ne $expected){throw 'Gson checksum mismatch'}
$sources=Get-ChildItem "$root/src/main/java" -Filter *.java -Recurse | ForEach-Object FullName
& "$jdk/bin/javac.exe" -encoding UTF-8 --release 21 -cp $gson -d "$root/build/classes" @sources
if($LASTEXITCODE){throw 'Compilation failed'}
Copy-Item $gson "$root/build/dist/gson.jar" -Force
Set-Content -Encoding Ascii "$root/build/MANIFEST.MF" "Class-Path: gson.jar`nImplementation-Version: $Version`n"
& "$jdk/bin/jar.exe" --create --file "$root/build/dist/aeromon-launcher.jar" --main-class cc.aeromon.launcher.Main --manifest "$root/build/MANIFEST.MF" -C "$root/build/classes" . -C "$root/src/main/resources" .
if($LASTEXITCODE){throw 'JAR creation failed'}
if($Package){
  if(!(Test-Path "$root/build/runtime/bin/java.exe")){
    & "$jdk/bin/jlink.exe" --add-modules ALL-MODULE-PATH --output "$root/build/runtime" --no-header-files --no-man-pages --compress=2
    if($LASTEXITCODE){throw 'Runtime creation failed'}
  }
  $OutputDir=[IO.Path]::GetFullPath($OutputDir)
  $buildRoot=[IO.Path]::GetFullPath("$root/build")+[IO.Path]::DirectorySeparatorChar
  if(!$OutputDir.StartsWith($buildRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Package output must be inside the launcher build directory'}
  $image=[IO.Path]::GetFullPath((Join-Path $OutputDir 'Aeromon'))
  if(Test-Path $image){Remove-Item -LiteralPath $image -Recurse -Force}
  New-Item -ItemType Directory -Force $OutputDir | Out-Null
  & "$jdk/bin/jpackage.exe" --type app-image --name Aeromon --app-version $Version --vendor Aeromon --description 'Aeromon community modpack launcher' --icon "$root/src/main/resources/branding/icon.ico" --input "$root/build/dist" --main-jar aeromon-launcher.jar --main-class cc.aeromon.launcher.Main --dest $OutputDir --runtime-image "$root/build/runtime" --java-options '-Xmx512m'
  if($LASTEXITCODE){throw 'Packaging failed'}
  if($Installer){
    New-Item -ItemType Directory -Force "$root/build/installers" | Out-Null
    Get-ChildItem "$root/build/installers" -Filter 'Aeromon-*.msi' -ErrorAction SilentlyContinue | Remove-Item -Force
    & "$jdk/bin/jpackage.exe" --type msi --win-per-user-install --install-dir Aeromon --win-menu --win-shortcut --name Aeromon --app-version $Version --vendor Aeromon --description 'Aeromon community modpack launcher' --icon "$root/src/main/resources/branding/icon.ico" --input "$root/build/dist" --main-jar aeromon-launcher.jar --main-class cc.aeromon.launcher.Main --dest "$root/build/installers" --runtime-image "$root/build/runtime" --java-options '-Xmx512m'
    if($LASTEXITCODE){throw 'MSI installer creation failed'}
  }
}
Write-Host 'Built Aeromon Launcher'

