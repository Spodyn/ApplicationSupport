#!/bin/sh
set -eu
# Some VPS networks advertise IPv6 DNS records without usable container IPv6 egress.
# Keep the choice instance-specific; TLS and host validation remain unchanged.
if [ "${USI_JAVA_PREFER_IPV4:-false}" = true ]; then
  set -- -Djava.net.preferIPv4Stack=true "$@"
fi
# The private MinIO CA augments the normal public roots, never disables TLS checks.
if [ -f /run/usi-trust/CAs/minio-ca.crt ]; then
  cp "$JAVA_HOME/lib/security/cacerts" /tmp/usi-cacerts
  keytool -importcert -noprompt -alias usi-minio -file /run/usi-trust/CAs/minio-ca.crt \
    -keystore /tmp/usi-cacerts -storepass changeit >/dev/null 2>&1
  set -- -Djavax.net.ssl.trustStore=/tmp/usi-cacerts -Djavax.net.ssl.trustStorePassword=changeit "$@"
fi
exec java -XX:MaxRAMPercentage=65 "$@" -jar /opt/usi/app.jar
