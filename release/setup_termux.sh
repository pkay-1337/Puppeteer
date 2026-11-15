#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# setup_termux.sh — Automated Termux Setup for Puppeteer
# Configures packages, javac+dx compiler toolchain, SSH, and the puppet CLI.
# ==============================================================================
set -euo pipefail

echo "=============================================================================="
echo "🎭 Puppeteer — Termux Environment Setup"
echo "=============================================================================="

# 1. Environment check
if [ -z "${PREFIX:-}" ] || [ ! -d "$PREFIX" ]; then
  echo "Error: This script must be executed inside Termux on Android."
  exit 1
fi
echo "[1/6] Termux environment verified: $PREFIX"

# 2. Package installation
echo "[2/6] Updating packages and installing dependencies..."
pkg update -y
pkg install -y openjdk-17 openssh curl coreutils

# 3. Directory structure
echo "[3/6] Setting up ~/.uq runtime and build cache..."
UQD="$HOME/.uq"
mkdir -p "$UQD/build"

# 4. Install puppet CLI
echo "[4/6] Installing 'puppet' CLI to $PREFIX/bin/puppet..."
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/puppet" ]; then
  cp "$SCRIPT_DIR/puppet" "$PREFIX/bin/puppet"
elif [ -f "$HOME/Puppeteer/tools/puppet" ]; then
  cp "$HOME/Puppeteer/tools/puppet" "$PREFIX/bin/puppet"
elif [ -f "$HOME/uq-shell/tools/puppet" ]; then
  cp "$HOME/uq-shell/tools/puppet" "$PREFIX/bin/puppet"
fi
chmod +x "$PREFIX/bin/puppet"

# Clean up legacy usq alias
rm -f "$PREFIX/bin/usq"

# 5. Compiler Toolchain Verification (javac, android.jar, dx.jar)
echo "[5/6] Verifying javac and Dalvik bytecode compiler toolchain..."

# Check javac
if command -v javac &>/dev/null; then
  echo "  ✓ javac available: $(javac -version 2>&1 | head -1)"
else
  echo "  ✗ javac missing! Re-run: pkg install openjdk-17"
fi

# Locate or copy android.jar
if [ -f "$HOME/usq/android.jar" ]; then
  echo "  ✓ android.jar found in ~/usq/android.jar"
elif [ -f "$UQD/android.jar" ]; then
  echo "  ✓ android.jar found in ~/.uq/android.jar"
else
  echo "  ℹ Note: Ensure android.jar is present in ~/usq/android.jar or ~/.uq/android.jar"
fi

# Locate dx.jar
if [ -f "$HOME/dxx/data/data/com.termux/files/usr/share/dex/dx.jar" ]; then
  echo "  ✓ dx.jar found in ~/dxx/..."
elif [ -f "$PREFIX/share/dex/dx.jar" ]; then
  echo "  ✓ dx.jar found in $PREFIX/share/dex/dx.jar"
elif [ -f "$UQD/dx.jar" ]; then
  echo "  ✓ dx.jar found in ~/.uq/dx.jar"
else
  echo "  ℹ Note: dx compiler binary needed for on-device dexing"
fi

# 6. Test Puppeteer Host Connection & Pull API jar
echo "[6/6] Connecting to Puppeteer control plane (127.0.0.1:7823)..."
if curl -s --max-time 3 http://127.0.0.1:7823/status >/dev/null; then
  echo "  ✓ Puppeteer host application is running!"
  curl -s --max-time 5 http://127.0.0.1:7823/api.jar -o "$UQD/api.jar" && echo "  ✓ Synchronized script API: $UQD/api.jar"
  echo ""
  echo "Current daemon status:"
  puppet status
else
  echo "  ℹ Puppeteer host app is not currently running."
  echo "    Open the 'Puppeteer' app on your phone once to start the background service."
fi

# Ensure sshd is active
if ! pgrep -x sshd >/dev/null; then
  echo "Starting SSH daemon on port 8022..."
  sshd || true
fi

echo "=============================================================================="
echo "✅ Puppeteer Termux environment is ready!"
echo "   Test command: puppet status"
echo "   Build & push: puppet buildpush examples/intent_session.uq.java"
echo "=============================================================================="
