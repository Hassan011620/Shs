#!/bin/sh
#
# Copyright 2015 the original author or authors.
# Gradle wrapper script for Unix

##############################################################################
# Environment validation
##############################################################################

die () {
    echo
    echo "ERROR: $*"
    echo
    exit 1
}

warn () {
    echo "WARNING: $*"
}

# OS specific support
cygwin=false
msys=false
darwin=false
nonstop=false

case "`uname`" in
  CYGWIN* ) cygwin=true ;;
  Darwin* ) darwin=true ;;
  MSYS* | MINGW* ) msys=true ;;
  NONSTOP* ) nonstop=true ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' found in PATH."
fi

# Setup APP_HOME
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then PRG="$link"
    else PRG=$(dirname "$PRG")"/$link"; fi
done
SAVED=$(pwd)
cd $(dirname "$PRG")/ >/dev/null
APP_HOME=$(pwd -P)
cd "$SAVED" >/dev/null

# Download gradle-wrapper.jar if missing or too small
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_MIN_SIZE=10000

download_wrapper() {
    echo "Downloading gradle-wrapper.jar..."
    URLS="https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradle/wrapper/gradle-wrapper.jar"
    for URL in $URLS; do
        if command -v curl >/dev/null 2>&1; then
            curl -L -o "$WRAPPER_JAR" "$URL" --max-time 60 --silent && break
        elif command -v wget >/dev/null 2>&1; then
            wget -q -O "$WRAPPER_JAR" "$URL" && break
        fi
    done
}

if [ ! -f "$WRAPPER_JAR" ] || [ $(wc -c < "$WRAPPER_JAR") -lt $WRAPPER_MIN_SIZE ]; then
    download_wrapper
fi

# Still missing? Try gradle command
if [ ! -f "$WRAPPER_JAR" ] || [ $(wc -c < "$WRAPPER_JAR") -lt $WRAPPER_MIN_SIZE ]; then
    if command -v gradle >/dev/null 2>&1; then
        echo "Generating wrapper via local gradle..."
        cd "$APP_HOME"
        gradle wrapper --gradle-version 8.4
    else
        die "gradle-wrapper.jar not found. Run: gradle wrapper --gradle-version 8.4"
    fi
fi

# Default JVM options
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Escape application args
save () {
    for i do printf %s\\n "$i" | sed "s/'/'\\\\''/g;1s/^/'/;\$s/\$/' \\\\/" ; done
    echo " "
}
APP_ARGS=$(save "$@")

eval set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "\"-Dorg.gradle.appname=$APP_BASE_NAME\"" -classpath "\"$CLASSPATH\"" org.gradle.wrapper.GradleWrapperMain "$APP_ARGS"

exec "$JAVACMD" "$@"
