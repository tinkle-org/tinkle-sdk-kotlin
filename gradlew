#!/bin/sh
# Apache-2.0. Minimal launcher for the official, checksum-verified Gradle wrapper.
set -eu
APP_HOME=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi
exec "$JAVA" -Dfile.encoding=UTF-8 -Xmx64m -Xms64m \
    -Dorg.gradle.appname=gradlew \
    -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
