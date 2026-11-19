#!/usr/bin/env bash
set -euo pipefail
runtime="$HOME/quanshu-runtime"
source "$runtime/config/secrets.env"
export DB_URL='jdbc:mysql://127.0.0.1:3306/quanshu?serverTimezone=UTC'
export DB_USER=quanshu DB_PASSWORD
export RABBIT_USER=quanshu RABBIT_PASSWORD MQ_ENABLED=true
export SPRING_RABBITMQ_VIRTUAL_HOST=quanshu REDIS_ENABLED=true
export NACOS_ENABLED=true NACOS_ADDR=127.0.0.1:8848
export XXL_TOKEN XXL_LOG_PATH="$runtime/logs/xxl-executor"
export LC_ALL=C.UTF-8
accounts_file=""
if [[ -f "$runtime/config/accounts.json" ]]; then
  accounts_file="$runtime/config/accounts.json"
fi
mkdir -p "$runtime/app" "$runtime/run" "$runtime/logs"
java="$runtime/root/usr/lib/jvm/java-17-openjdk-amd64/bin/java"
stop_owned() {
  local label="$1" marker="$2" previous
  if [[ -f "$runtime/run/$label.pid" ]];then
    previous=$(cat "$runtime/run/$label.pid")
    if [[ -r "/proc/$previous/cmdline" ]] && grep -zFq "$marker" "/proc/$previous/cmdline";then
      kill "$previous"
      for attempt in {1..30};do
        if ! kill -0 "$previous" 2>/dev/null || [[ $(ps -o stat= -p "$previous") == Z* ]];then break;fi
        sleep 1
      done
      if kill -0 "$previous" 2>/dev/null && [[ $(ps -o stat= -p "$previous") != Z* ]];then echo "Old $label process did not stop" >&2;exit 1;fi
    fi
  fi
}
stop_owned app "$runtime/app/quanshu.jar"
for label in gateway worker settlement coupon template merchant;do stop_owned "$label" "$runtime/app/";done
mkdir -p "$runtime/backups"
export LD_LIBRARY_PATH="$runtime/root/usr/lib/x86_64-linux-gnu"
umask 077
mysql=("$runtime/root/usr/bin/mysql" "--defaults-extra-file=$runtime/config/mysql-client.cnf")
backup_databases=(quanshu)
while IFS= read -r database; do
  if [[ "$database" == quanshu_s0 || "$database" == quanshu_s1 ]]; then backup_databases+=("$database"); fi
done < <("${mysql[@]}" -Nse "SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('quanshu_s0','quanshu_s1') ORDER BY schema_name")
"$runtime/root/usr/bin/mysqldump" --defaults-extra-file="$runtime/config/mysql-client.cnf" \
  --single-transaction --no-tablespaces --databases "${backup_databases[@]}" > "$runtime/backups/quanshu-$(date +%Y%m%d-%H%M%S).sql"
for database in quanshu_s0 quanshu_s1;do
  "${mysql[@]}" -e "GRANT ALL PRIVILEGES ON $database.* TO 'quanshu'@'localhost'"
done
for label in engine gateway;do
  if [[ -f "$runtime/app/quanshu-$label.next.jar" ]];then mv "$runtime/app/quanshu-$label.next.jar" "$runtime/app/quanshu-$label.jar";fi
done
cd "$runtime/app"
for role in merchant template coupon settlement worker;do
  heap=256m
  case "$role" in
    merchant) port=8083;name=merchant-service;jobs=false;xxl=false;;
    template) port=8085;name=template-service;jobs=false;xxl=false;heap=160m;;
    coupon) port=8082;name=coupon-service;jobs=false;xxl=false;;
    settlement) port=8086;name=settlement-service;jobs=false;xxl=false;heap=160m;;
    worker) port=8084;name=distribution-service;jobs=true;xxl=true;;
  esac
  role_accounts=""
  if [[ "$role" == merchant ]]; then role_accounts="$accounts_file"; fi
  SERVICE_NAME="$name" SERVICE_ROLE="$role" JOBS_ENABLED="$jobs" APP_PORT="$port" ACCOUNTS_FILE="$role_accounts" \
    nohup "$java" -Xms64m "-Xmx$heap" -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8 \
      -jar "$runtime/app/quanshu-engine.jar" --spring.profiles.active=sharded --server.address=127.0.0.1 \
      --quanshu.xxl-enabled="$xxl" --quanshu.storage-dir="$runtime/app/data/uploads" \
      > "$runtime/logs/$role.log" 2>&1 < /dev/null &
  echo "$!" > "$runtime/run/$role.pid"
  ready=false
  for attempt in {1..90};do
    if curl --fail --silent "http://127.0.0.1:$port/actuator/health" > /dev/null;then ready=true;break;fi
    sleep 1
  done
  if [[ "$ready" != true ]];then tail -n 40 "$runtime/logs/$role.log";exit 1;fi
  printf '%s ready on %s\n' "$name" "$port"
done
nohup "$java" -Xms64m -Xmx192m -XX:ActiveProcessorCount=2 -jar "$runtime/app/quanshu-gateway.jar" \
  --server.port=8080 \
  > "$runtime/logs/gateway.log" 2>&1 < /dev/null &
echo "$!" > "$runtime/run/gateway.pid"
for attempt in {1..60};do
  if curl --fail --silent http://127.0.0.1:8080/api/status;then printf '\nGateway ready\n';exit 0;fi
  sleep 1
done
tail -n 40 "$runtime/logs/gateway.log";exit 1
