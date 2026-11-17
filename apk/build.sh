#!/data/data/com.termux/files/usr/bin/sh
# Puppeteer build, stage 1 — runs inside Termux: javac + script-api.jar + dx.
# (Stage 2 — aapt packaging — runs in the proot: see apk/assemble.sh.
#  Stage 3 — apksigner — back in Termux.)
set -e
cd "$(dirname "$0")"

AJ=$HOME/usq/android.jar
DXJ=$HOME/dxx/data/data/com.termux/files/usr/share/dex/dx.jar

rm -rf build
mkdir -p build/classes build/apkroot/assets

echo "== javac =="
if ! javac -nowarn -source 8 -target 8 -cp "$AJ" -d build/classes \
     $(find src -name '*.java'); then
  echo "JAVAC FAILED"
  exit 1
fi

echo "== script-api.jar =="
jar cf build/apkroot/assets/script-api.jar -C build/classes uq/script

echo "== docs in assets =="
mkdir -p build/apkroot/assets/docs
cp ../docs/*.md build/apkroot/assets/docs/ 2>/dev/null || true

echo "== dx =="
if ! dalvikvm -Xmx256m -cp "$DXJ" dx.dx.command.Main --dex \
     --output=build/apkroot/classes.dex build/classes; then
  echo "DX FAILED"
  exit 1
fi

echo "== stage 1 done =="
ls -la build/apkroot build/apkroot/assets
