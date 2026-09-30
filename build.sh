#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
version=${AEROMON_VERSION:-1.0.0}
mkdir -p build/deps build/classes build/dist
url=https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar
curl -fL "$url" -o build/deps/gson.jar
expected=$(curl -fsSL "$url.sha1")
actual=$(openssl dgst -sha1 build/deps/gson.jar | awk '{print $NF}')
test "$actual" = "$expected"
find src/main/java -name '*.java' > build/sources.txt
javac -encoding UTF-8 --release 21 -cp build/deps/gson.jar -d build/classes @build/sources.txt
cp build/deps/gson.jar build/dist/
printf 'Class-Path: gson.jar\nImplementation-Version: %s\n' "$version" > build/MANIFEST.MF
jar --create --file build/dist/aeromon-launcher.jar --main-class cc.aeromon.launcher.Main --manifest build/MANIFEST.MF -C build/classes . -C src/main/resources .
if [[ ${1:-} == --package ]]; then
 rm -rf build/runtime build/packages/Aeromon
 mkdir -p build/packages
 jlink --add-modules ALL-MODULE-PATH --output build/runtime --no-header-files --no-man-pages --compress=2
 icon=src/main/resources/branding/icon.png
 if [[ $(uname) == Darwin ]]; then icon=src/main/resources/branding/icon.icns; fi
 jpackage --type app-image --name Aeromon --app-version "$version" --vendor Aeromon --description 'Aeromon community modpack launcher' --icon "$icon" --input build/dist --main-jar aeromon-launcher.jar --main-class cc.aeromon.launcher.Main --dest build/packages --runtime-image build/runtime --java-options -Xmx512m
fi
