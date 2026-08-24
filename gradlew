#!/usr/bin/env sh
##############################################################################
##
##  Gradle start up script for UN*X
##
##############################################################################
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$HOME/.gradle"}
# Add default JVM options here
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"
warn () { echo "$*"; }
die () { echo; echo "$*"; echo; exit 1; }
# OS specific support
cygwin=false
msys=false
darwin=false
nonstop=false
case "`uname`" in
  CYGWIN* ) cygwin=true ;;
  Darwin* ) darwin=true ;;
  MINGW* ) msys=true ;;
  NONSTOP* ) nonstop=true ;;
esac
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found"
fi
# Collect all arguments for the java command
set -- \
        "-Dorg.gradle.appname=$APP_BASE_NAME" \
        -classpath "\"$CLASSPATH\"" \
        org.gradle.wrapper.GradleWrapperMain \
        "$@"
exec "$JAVACMD" "$@"
