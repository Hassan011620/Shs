#!/bin/bash
# ============================================================
# NAMPlayer - Auto Setup & Build Script
# يعمل على Linux / macOS / WSL
# ============================================================

set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

info()  { echo -e "${GREEN}[✓]${NC} $*"; }
warn()  { echo -e "${YELLOW}[!]${NC} $*"; }
error() { echo -e "${RED}[✗]${NC} $*"; exit 1; }

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  NAMPlayer Build Script"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# 1. Java check
java -version 2>/dev/null || error "Java not found. Install JDK 17: https://adoptium.net"
JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
[ "$JAVA_VER" -ge 17 ] 2>/dev/null || warn "Java $JAVA_VER found, Java 17+ recommended"
info "Java OK"

# 2. Android SDK check
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    # Common locations
    for p in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "/opt/android-sdk"; do
        if [ -d "$p" ]; then ANDROID_HOME="$p"; break; fi
    done
fi
[ -n "$ANDROID_HOME" ] && info "Android SDK: $ANDROID_HOME" || \
    error "ANDROID_HOME not set. Install Android Studio or SDK."

# 3. Fix gradle-wrapper.jar
JAR="gradle/wrapper/gradle-wrapper.jar"
JAR_SIZE=$(wc -c < "$JAR" 2>/dev/null || echo 0)

if [ "$JAR_SIZE" -lt 10000 ]; then
    warn "gradle-wrapper.jar missing or stub ($JAR_SIZE bytes), downloading..."
    
    # Try curl
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$JAR" \
            "https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradle/wrapper/gradle-wrapper.jar" \
            && info "Downloaded via curl"
    # Try wget  
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$JAR" \
            "https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradle/wrapper/gradle-wrapper.jar" \
            && info "Downloaded via wget"
    fi
    
    # Verify
    JAR_SIZE=$(wc -c < "$JAR" 2>/dev/null || echo 0)
    if [ "$JAR_SIZE" -lt 10000 ]; then
        warn "Download failed, trying local gradle..."
        gradle wrapper --gradle-version 8.4 || \
            error "Cannot generate wrapper. Install Gradle: https://gradle.org/install/"
    fi
else
    info "gradle-wrapper.jar OK ($JAR_SIZE bytes)"
fi

# 4. local.properties
if [ ! -f "local.properties" ]; then
    echo "sdk.dir=$ANDROID_HOME" > local.properties
    info "Created local.properties"
fi

# 5. Make executable
chmod +x gradlew
info "gradlew permissions set"

# 6. Build
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Building Debug APK..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

./gradlew assembleDebug --no-daemon

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
    SIZE=$(du -h "$APK" | cut -f1)
    echo ""
    info "✅ APK built successfully!"
    info "   Path: $APK"
    info "   Size: $SIZE"
    echo ""
    echo "Install on device:"
    echo "  adb install $APK"
else
    error "Build failed - APK not found"
fi
