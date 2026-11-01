#!/usr/bin/env bash
# =============================================================================
# Patchright Java - JitPack Build Entry
#
# JitPack runs this script from jitpack.yml and serves whatever lands in
# ~/.m2/repository under the root groupId (cc.marbro.patchright).
#
# Tags like v1.64.0 pick the Playwright version; commits and branch
# snapshots build the version pinned by the playwright-java submodule.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# JITPACK EXPORTS THE REQUESTED TAG OR COMMIT AS $VERSION
PLAYWRIGHT_VERSION=""
if [[ "${VERSION:-}" =~ ^v?([0-9]+\.[0-9]+\.[0-9]+) ]]; then
    PLAYWRIGHT_VERSION="${BASH_REMATCH[1]}"
fi

# THE JAVADOC OF JDK 17 AND 21 CRASHES WITH --release 8 AND WOULD PUBLISH AN EMPTY JAVADOC JAR
JAVA_MAJOR=$(java -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.specification.version/ {print $2}')
if [[ ! "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || [ "$JAVA_MAJOR" -lt 25 ]; then
    echo "JDK 25 or newer is required, found '$JAVA_MAJOR'" >&2
    exit 1
fi

# THE JITPACK CHECKOUT MAY BELONG TO ANOTHER USER, AND GIT THEN REFUSES THE SUBMODULE UPDATE AS "DUBIOUS OWNERSHIP"
# ONLY INSIDE THE THROWAWAY JITPACK CONTAINER: A LOCAL RUN MUST NOT RELAX THE DEVELOPER'S GLOBAL GIT CONFIG
if [ "${JITPACK:-}" = "true" ]; then
    git config --global --add safe.directory '*'
fi

# PLAYWRIGHT 1.63+ ASSEMBLES THE DRIVER WITH node AND npm; THE IMAGE MAY LACK THEM OR SHIP AN OLD ONE,
# SO THE LATEST NODE.JS LTS IS FETCHED FOR THE BUILD WHEN NO node 24+ IS ON THE PATH
NODE_MAJOR=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)
if [ "$(uname -s)" = "Linux" ] && { ! command -v npm > /dev/null 2>&1 || [ "$NODE_MAJOR" -lt 24 ]; }; then
    # index.json LISTS RELEASES NEWEST FIRST; THE FIRST ENTRY WITH AN "lts" CODENAME IS THE LATEST LTS
    NODE_VERSION=$(curl -fsSL https://nodejs.org/dist/index.json | awk -F'"' '/"lts":"/ && !v {v=substr($4, 2)} END {print v}')
    if [ -z "$NODE_VERSION" ]; then
        echo "Cannot resolve the latest Node.js LTS" >&2
        exit 1
    fi
    case "$(uname -m)" in
        aarch64|arm64) NODE_ARCH=arm64 ;;
        *) NODE_ARCH=x64 ;;
    esac
    NODE_ROOT="$HOME/.cache/patchright-node"
    NODE_FILE="node-v$NODE_VERSION-linux-$NODE_ARCH.tar.gz"
    mkdir -p "$NODE_ROOT"
    echo "Fetching Node.js v$NODE_VERSION ($NODE_ARCH) for the driver build..."
    curl -fsSL -o "$NODE_ROOT/$NODE_FILE" "https://nodejs.org/dist/v$NODE_VERSION/$NODE_FILE"
    (cd "$NODE_ROOT" && curl -fsSL "https://nodejs.org/dist/v$NODE_VERSION/SHASUMS256.txt" | grep " $NODE_FILE\$" | sha256sum -c -)
    tar -xzf "$NODE_ROOT/$NODE_FILE" -C "$NODE_ROOT"
    rm -f "$NODE_ROOT/$NODE_FILE"
    export PATH="$NODE_ROOT/node-v$NODE_VERSION-linux-$NODE_ARCH/bin:$PATH"
fi

# JAVA 8 BYTECODE LINKED AGAINST THE JAVA 8 API, AS THE OFFICIAL JDK 8 BUILDS DO
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dmaven.compiler.release=8"

# BUILD ENVIRONMENT, SO A FAILED JITPACK LOG SHOWS WHAT IT RAN ON (awk DRAINS THE PIPE: head WOULD SIGPIPE UNDER pipefail)
java -version 2>&1 | awk 'NR == 1'
mvn -v 2>&1 | awk 'NR == 1'
echo "node $(node -v), npm $(npm -v)"

# JITPACK HAS NO GIT IDENTITY, SO THE DIFF COMMIT INSIDE patchright-java-release IS SKIPPED
bash "$SCRIPT_DIR/build.sh" "$PLAYWRIGHT_VERSION" "cc.marbro.patchright" "true"
