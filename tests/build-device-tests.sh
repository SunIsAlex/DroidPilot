#!/data/data/com.termux/files/usr/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/probe/classes build/probe/dex
aapt2 link -I .cache/android-35.jar --manifest tests/AndroidManifest.xml --min-sdk-version 29 --target-sdk-version 35 -o build/probe/unsigned.apk
javac --release 8 -cp .cache/android-35.jar:build/classes -d build/probe/classes app/src/main/java/dev/navix/agent/Wire.java app/src/main/java/dev/navix/agent/CodexBackend.java app/src/main/java/dev/navix/agent/Protocol.java app/src/main/java/dev/navix/agent/AmCommand.java app/src/main/java/dev/navix/agent/TaskPolicy.java app/src/main/java/dev/navix/agent/Secrets.java app/src/main/java/dev/navix/agent/DeepSeek.java tests/AppProbe.java tests/ServiceGoalProbe.java
jar --create --file build/probe/classes.jar -C build/probe/classes .
d8 --lib .cache/android-35.jar --min-api 29 --output build/probe/dex build/probe/classes.jar
(cd build/probe/dex && zip -q -j ../unsigned.apk classes.dex)
apksigner sign --ks .cache/debug.keystore --ks-pass pass:android --out build/probe/tests.apk build/probe/unsigned.apk
