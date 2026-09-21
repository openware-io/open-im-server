#!/bin/sh
set -eu
WRAPPER_JAR="$(dirname "$0")/.mvn/wrapper/maven-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar" -o "$WRAPPER_JAR"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar" -O "$WRAPPER_JAR"
  else
    echo "Maven Wrapper requires curl or wget to download $WRAPPER_JAR" >&2
    exit 1
  fi
fi
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -classpath "$WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"
