#!/usr/bin/env bash
set -euo pipefail
runtime="$HOME/quanshu-runtime"
export JAVA_HOME="$runtime/root/usr/lib/jvm/java-17-openjdk-amd64"
export CUSTOM_NACOS_MEMORY='-Xms128m -Xmx384m -Xmn64m -XX:ActiveProcessorCount=2'
config="$runtime/nacos/conf/application.properties"
if ! grep -q '^# quanshu local settings' "$config"; then
  umask 077
  {
    printf '\n# quanshu local settings\nserver.address=127.0.0.1\nnacos.inetutils.ip-address=127.0.0.1\nnacos.console.port=18848\n'
    printf 'nacos.core.auth.server.identity.key=quanshu\nnacos.core.auth.server.identity.value=%s\n' "$(head -c 24 /dev/urandom | base64)"
    printf 'nacos.core.auth.plugin.nacos.token.secret.key=%s\n' "$(head -c 48 /dev/urandom | base64 -w0)"
  } >> "$config"
fi
sed -i '/^nacos\.core\.auth\.server\.identity\.key=$/d; /^nacos\.core\.auth\.server\.identity\.value=$/d; /^nacos\.core\.auth\.plugin\.nacos\.token\.secret\.key=$/d' "$config"
if ! curl --fail --silent http://127.0.0.1:8848/nacos/v3/admin/core/state > /dev/null; then
  bash "$runtime/nacos/bin/startup.sh" -m standalone
fi
for attempt in {1..60}; do
  if curl --fail --silent http://127.0.0.1:8848/nacos/v3/admin/core/state; then printf '\n'; exit 0; fi
  sleep 1
done
tail -n 30 "$runtime/nacos/logs/start.out"
exit 1
