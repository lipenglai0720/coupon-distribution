#!/usr/bin/env bash
set -euo pipefail
runtime="$HOME/quanshu-runtime"
prefix="$runtime/root"
export PATH="$prefix/usr/lib/erlang/bin:$prefix/usr/bin:$prefix/usr/sbin:$PATH"
export LD_LIBRARY_PATH="$prefix/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
mkdir -p "$runtime"/{data/mysql,data/mysql-files,data/redis,data/rabbitmq,logs,run,config}
chmod 700 "$runtime/config"
if [[ ! -f "$runtime/config/secrets.env" ]]; then
  umask 077
  printf 'MYSQL_ROOT_PASSWORD=%s\nDB_PASSWORD=%s\nRABBIT_PASSWORD=%s\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 24)" "$(openssl rand -hex 24)" > "$runtime/config/secrets.env"
fi
source "$runtime/config/secrets.env"
umask 077
cat > "$runtime/config/mysql-client.cnf" <<EOF
[client]
user=root
password=$MYSQL_ROOT_PASSWORD
socket=$runtime/run/mysql.sock
EOF
if [[ ! -d "$runtime/data/mysql/mysql" ]]; then
  mysqld --no-defaults --basedir="$prefix/usr" --datadir="$runtime/data/mysql" --initialize-insecure --log-error="$runtime/logs/mysql-init.log"
fi
if ! mysqladmin --defaults-file="$runtime/config/mysql-client.cnf" ping --silent >/dev/null 2>&1; then
  nohup mysqld --no-defaults --basedir="$prefix/usr" --datadir="$runtime/data/mysql" \
    --bind-address=127.0.0.1 --port=3306 --mysqlx=0 --socket="$runtime/run/mysql.sock" --secure-file-priv="$runtime/data/mysql-files" \
    --pid-file="$runtime/run/mysql.pid" --log-error="$runtime/logs/mysql.log" \
    --innodb-buffer-pool-size=128M --max-connections=100 \
    > "$runtime/logs/mysql-console.log" 2>&1 < /dev/null &
  for attempt in {1..60}; do
    [[ -S "$runtime/run/mysql.sock" ]] && break
    sleep 1
  done
fi
if mysql --no-defaults --socket="$runtime/run/mysql.sock" -u root -e 'SELECT 1' >/dev/null 2>&1; then
  mysql --no-defaults --socket="$runtime/run/mysql.sock" -u root <<SQL
ALTER USER 'root'@'localhost' IDENTIFIED BY '$MYSQL_ROOT_PASSWORD';
SQL
fi
mysql --defaults-file="$runtime/config/mysql-client.cnf" <<SQL
CREATE DATABASE IF NOT EXISTS quanshu CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS quanshu_test CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'quanshu'@'localhost' IDENTIFIED BY '$DB_PASSWORD';
GRANT ALL PRIVILEGES ON quanshu.* TO 'quanshu'@'localhost';
GRANT ALL PRIVILEGES ON quanshu_test.* TO 'quanshu'@'localhost';
SQL
if ! redis-cli -h 127.0.0.1 -p 6379 ping >/dev/null 2>&1; then
  redis-server --bind 127.0.0.1 --port 6379 --protected-mode yes --daemonize yes \
    --dir "$runtime/data/redis" --appendonly yes --maxmemory 256mb --maxmemory-policy noeviction \
    --pidfile "$runtime/run/redis.pid" --logfile "$runtime/logs/redis.log"
fi
export RABBITMQ_HOME="$runtime/rabbitmq_server-4.4.0"
export RABBITMQ_NODENAME=quanshu@localhost
export RABBITMQ_MNESIA_BASE="$runtime/data/rabbitmq"
export RABBITMQ_LOG_BASE="$runtime/logs"
export RABBITMQ_PID_FILE="$runtime/run/rabbitmq.pid"
export RABBITMQ_CONFIG_FILE="$runtime/config/rabbitmq"
export RABBITMQ_ENABLED_PLUGINS_FILE="$runtime/config/enabled_plugins"
export RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS='+S 2:2 +A 4'
export ERL_FLAGS='+S 2:2'
export LC_ALL=C.UTF-8
# The extracted Erlang package's compiled epmd path targets /usr. Start the relocated daemon explicitly.
epmd_binary=("$prefix"/usr/lib/erlang/erts-*/bin/epmd)
"${epmd_binary[0]}" -daemon
cat > "$runtime/config/rabbitmq.conf" <<EOF
listeners.tcp.1 = 127.0.0.1:5672
management.tcp.ip = 127.0.0.1
management.tcp.port = 15672
default_user = quanshu
default_pass = $RABBIT_PASSWORD
default_vhost = quanshu
EOF
printf '[rabbitmq_management].\n' > "$RABBITMQ_ENABLED_PLUGINS_FILE"
if ! "$RABBITMQ_HOME/sbin/rabbitmq-diagnostics" -q ping >/dev/null 2>&1; then
  "$RABBITMQ_HOME/sbin/rabbitmq-server" -detached
fi
for attempt in {1..60}; do
  if "$RABBITMQ_HOME/sbin/rabbitmq-diagnostics" -q ping >/dev/null 2>&1; then break; fi
  sleep 1
done
mysqladmin --defaults-file="$runtime/config/mysql-client.cnf" ping
redis-cli ping
"$RABBITMQ_HOME/sbin/rabbitmq-diagnostics" -q ping
mysql --version
redis-server --version
printf 'Native user services ready. Credentials remain in the private config directory.\n'
