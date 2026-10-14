#!/bin/sh
set -eu
# Maven refuses to start without the multi-module project directory:
#   "-Dmaven.multiModuleProjectDirectory system property is not set."
# The standard Maven Wrapper script exports it; this minimal launcher did not, which is why every
# Linux CI run through ./mvnw failed within seconds while mvnw.cmd still worked locally.
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"

WRAPPER_JAR="$BASE_DIR/.mvn/wrapper/maven-wrapper.jar"
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
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" \
  "-Dmaven.multiModuleProjectDirectory=$BASE_DIR" \
  -classpath "$WRAPPER_JAR" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
