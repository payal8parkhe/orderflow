#!/bin/sh
MAVEN_BIN="$HOME/.m2/maven-bin/apache-maven-3.9.8/bin/mvn"
if [ ! -f "$MAVEN_BIN" ]; then
    mkdir -p "$HOME/.m2/maven-bin"
    curl -sL https://archive.apache.org/dist/maven/maven-3/3.9.8/binaries/apache-maven-3.9.8-bin.tar.gz | tar -xz -C "$HOME/.m2/maven-bin/"
fi
export MAVEN_OPTS="${MAVEN_OPTS:--Djava.net.preferIPv4Stack=true}"
exec "$MAVEN_BIN" "$@"
