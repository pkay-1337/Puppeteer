#!/bin/sh
# Puppeteer build, stage 2 — runs in the proot.
# Uses the device's own framework-res.apk as the resource table.
set -e
ST="${1:?staging dir}"
cd "$ST"

AAPT=/root/.build-uq/deb/x/usr/bin/aapt
LD_LIBRARY_PATH=/root/.build-uq/deb/x/usr/lib/aarch64-linux-gnu/android:/root/.build-uq/deb/x/usr/lib
export LD_LIBRARY_PATH

echo "== aapt package =="
$AAPT package -f -M AndroidManifest.xml -I framework-res.apk -F base.apk

echo "== aapt add =="
mkdir -p apkroot/assets
cp -r assets/* apkroot/assets/
cp classes.dex apkroot/
(cd apkroot && $AAPT add ../base.apk classes.dex && find assets -type f -exec $AAPT add ../base.apk {} +)

echo "== stage 2 done =="
ls -la base.apk
