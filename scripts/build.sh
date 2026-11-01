#!/usr/bin/env bash
# =============================================================================
# Patchright Java - Full Build Script
#
# This script:
# 1. Syncs playwright-java submodule to specified version
# 2. Copies sources to patchright-java-release/
# 3. Builds the patcher tool
# 4. Runs the patcher on the copy
# 5. Downloads patched patchright driver
# 6. Builds the patched patchright-java Maven artifacts
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Arguments
PLAYWRIGHT_VERSION="${1:-}"
GROUP_ID="${2:-cc.marbro.patchright}"
SKIP_COMMIT="${3:-false}"

echo "================================================"
echo " Patchright Java Builder"
echo "================================================"

# Step 1: Sync submodule
SUBMODULE_DIR="$PROJECT_DIR/playwright-java"
RELEASE_DIR="$PROJECT_DIR/patchright-java-release"


echo ""
echo "[Step 1] Syncing playwright-java submodule..."
cd "$PROJECT_DIR"
rm -rf "$SUBMODULE_DIR"
git submodule update --init --recursive

if [ -n "$PLAYWRIGHT_VERSION" ]; then
    echo "  Checking out version v${PLAYWRIGHT_VERSION}..."
    cd "$SUBMODULE_DIR"
    git fetch --tags
    git checkout "v${PLAYWRIGHT_VERSION}"
    cd "$PROJECT_DIR"
fi

# Detect playwright version from pom.xml
if [ -z "$PLAYWRIGHT_VERSION" ]; then
    PLAYWRIGHT_VERSION=$(grep -m1 '<version>' "$SUBMODULE_DIR/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | sed 's/-SNAPSHOT//')
fi

echo "  Playwright version: $PLAYWRIGHT_VERSION"
echo "  Group ID: $GROUP_ID"

# Step 2: Copy to release directory
echo ""
echo "[Step 2] Copying sources to patchright-java-release/..."
rm -rf "$RELEASE_DIR"
# PLAIN cp INSTEAD OF rsync, WHICH NEITHER GIT FOR WINDOWS NOR THE JITPACK IMAGE GUARANTEE
mkdir -p "$RELEASE_DIR"
cp -a "$SUBMODULE_DIR/." "$RELEASE_DIR/"
rm -rf "$RELEASE_DIR/.git"
# add .git to make it easier to diff with original sources after patching
if [ "$SKIP_COMMIT" != "true" ]; then
  cd "$RELEASE_DIR/" && git init -q && git add . && git commit -q -m "Initial commit"
fi
echo "  Done."

# Step 3: Build patcher
echo ""
echo "[Step 3] Building patcher tool..."
cd "$PROJECT_DIR/patchright-java-patcher"
mvn -q clean package -DskipTests
cd "$PROJECT_DIR"

# Step 4: Run patcher
echo ""
echo "[Step 4] Running patcher on patchright-java-release/..."
java -jar "$PROJECT_DIR/patchright-java-patcher/target/patchright-java-patcher.jar" \
    "$RELEASE_DIR" \
    "$GROUP_ID" \
    "$PLAYWRIGHT_VERSION"

# Step 5: Download patchright driver
echo ""
echo "[Step 5] Downloading patchright driver..."
cd "$RELEASE_DIR"
bash scripts/download_driver.sh

# Step 6: Build patchright-java
echo ""
echo "[Step 6] Building patchright-java Maven artifacts..."
cd "$PROJECT_DIR"
# THE ASSIST MODULE MUST LINK THE VERSION JUST PATCHED, NOT THE ONE IN THE ROOT POM
mvn -B -ntp install -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpatchright.version="$PLAYWRIGHT_VERSION"

echo ""
echo "================================================"
echo " Build complete!"
echo " Artifacts in: $RELEASE_DIR"
echo "================================================"
