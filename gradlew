#!/bin/sh
# Gradle wrapper launcher. If gradle-wrapper.jar is missing, it is downloaded once.
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
URL="https://raw.githubusercontent.com/gradle/gradle/v8.1.1/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$JAR" ]; then
  echo "Downloading gradle-wrapper.jar ..."
  mkdir -p "$APP_HOME/gradle/wrapper"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$JAR" "$URL" || { rm -f "$JAR"; echo "Download failed"; exit 1; }
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$JAR" "$URL" || { rm -f "$JAR"; echo "Download failed"; exit 1; }
  else
    echo "Need curl or wget to download gradle-wrapper.jar"; exit 1
  fi
fi

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD=java
fi

exec "$JAVACMD" -Dorg.gradle.appname=gradlew -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
