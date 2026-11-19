#!/usr/bin/env bash
set -euo pipefail
runtime="$HOME/quanshu-runtime"
source "$runtime/config/secrets.env"
export DB_URL='jdbc:mysql://127.0.0.1:3306/quanshu?serverTimezone=UTC'
export DB_USER=quanshu DB_PASSWORD
export RABBIT_USER=quanshu RABBIT_PASSWORD MQ_ENABLED=true
export RABBIT_VHOST=quanshu REDIS_ENABLED=true
export SPRING_RABBITMQ_VIRTUAL_HOST=quanshu
export LC_ALL=C.UTF-8
mkdir -p "$runtime/app" "$runtime/run" "$runtime/logs"
if [[ -f "$runtime/run/app.pid" ]]; then
  previous=$(cat "$runtime/run/app.pid")
  if [[ -r "/proc/$previous/cmdline" ]] && grep -zFq "$runtime/app/quanshu.jar" "/proc/$previous/cmdline"; then
    kill "$previous"
    for attempt in {1..30}; do
      kill -0 "$previous" 2>/dev/null || break
      sleep 1
    done
  fi
fi
if [[ -f "$runtime/app/quanshu.next.jar" ]]; then
  mv "$runtime/app/quanshu.next.jar" "$runtime/app/quanshu.jar"
fi
cd "$runtime/app"
nohup "$runtime/root/usr/lib/jvm/java-17-openjdk-amd64/bin/java" \
  -Xms128m -Xmx512m -Dfile.encoding=UTF-8 -jar "$runtime/app/quanshu.jar" \
  --server.address=127.0.0.1 --server.port=8080 \
  > "$runtime/logs/app.log" 2>&1 < /dev/null &
echo "$!" > "$runtime/run/app.pid"
for attempt in {1..60}; do
  if curl --fail --silent http://127.0.0.1:8080/actuator/health; then printf '\n'; exit 0; fi
  sleep 1
done
printf 'Application did not become healthy; inspect %s/logs/app.log\n' "$runtime" >&2
exit 1
