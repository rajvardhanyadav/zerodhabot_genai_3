#!/bin/sh
# ----------------------------------------------------------------------------
# Maven Wrapper start up script for POSIX sh
# ----------------------------------------------------------------------------

WRAPPER_JAR=".mvn/wrapper/maven-wrapper.jar"
WRAPPER_PROPERTIES=".mvn/wrapper/maven-wrapper.properties"

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi

# Download wrapper jar if missing
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Downloading Maven Wrapper..."
  WRAPPER_URL=$(grep -m1 '^wrapperUrl=' "$WRAPPER_PROPERTIES" | sed -e 's/wrapperUrl=//')
  curl -fsSL -o "$WRAPPER_JAR" "$WRAPPER_URL" || wget -q -O "$WRAPPER_JAR" "$WRAPPER_URL"
fi

exec "$JAVACMD" -classpath "$WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"

