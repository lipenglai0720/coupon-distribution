#!/usr/bin/env bash
set -euo pipefail
# Dedicated disposable integration-test schema only. Never accept a database parameter.
runtime="$HOME/quanshu-runtime"
export LD_LIBRARY_PATH="$runtime/root/usr/lib/x86_64-linux-gnu"
"$runtime/root/usr/bin/mysql" --defaults-extra-file="$runtime/config/mysql-client.cnf" <<'SQL'
DROP DATABASE IF EXISTS quanshu_test;
CREATE DATABASE quanshu_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON quanshu_test.* TO 'quanshu'@'localhost';
SQL
