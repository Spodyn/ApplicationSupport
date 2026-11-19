#!/bin/sh
set -eu
# The private MinIO CA augments the normal public roots, never disables TLS checks.
if [ -f /run/usi-trust/CAs/minio-ca.crt ]; then
  cp "$JAVA_HOME/lib/security/cacerts" /tmp/usi-cacerts
  keytool -importcert -noprompt -alias usi-minio -file /run/usi-trust/CAs/minio-ca.crt \
    -keystore /tmp/usi-cacerts -storepass changeit >/dev/null 2>&1
  set -- -Djavax.net.ssl.trustStore=/tmp/usi-cacerts -Djavax.net.ssl.trustStorePassword=changeit "$@"
fi
exec java -XX:MaxRAMPercentage=65 "$@" -jar /opt/usi/app.jar
