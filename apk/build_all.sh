#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"

echo "== Stage 1: compile in Termux =="
sh build.sh

echo "== Syncing build artifacts to proot staging =="
ST=$HOME/ubuntu22-fs/root/.build-uq/st
mkdir -p "$ST/assets"
cp AndroidManifest.xml "$ST/"
cp build/apkroot/classes.dex "$ST/"
cp -r build/apkroot/assets/* "$ST/assets/"
rm -f "$ST/base.apk"

# Update local .uq cache for usq buildpush
mkdir -p "$HOME/.uq"
cp build/apkroot/assets/script-api.jar "$HOME/.uq/api.jar"

echo "== Stage 2: aapt packaging in proot =="
$HOME/start-ubuntu22.sh "sh /root/uq-shell/apk/assemble.sh /root/.build-uq/st"

echo "== Stage 3: apksigner in Termux =="
KS=$HOME/uq-shell.keystore
apksigner sign --ks "$KS" --ks-pass pass:usqkey --key-pass pass:usqkey --ks-key-alias usq --out uq-shell.apk "$ST/base.apk"

cp uq-shell.apk /storage/emulated/0/Download/UQShell.apk
echo "== BUILD COMPLETE: v0.3.0 is ready in Downloads =="
ls -lh uq-shell.apk /storage/emulated/0/Download/UQShell.apk
