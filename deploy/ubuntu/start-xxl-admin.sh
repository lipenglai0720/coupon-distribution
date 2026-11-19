#!/usr/bin/env bash
set -euo pipefail
runtime="$HOME/quanshu-runtime"
source "$runtime/config/secrets.env"
export LD_LIBRARY_PATH="$runtime/root/usr/lib/x86_64-linux-gnu"
mysql=("$runtime/root/usr/bin/mysql" "--defaults-extra-file=$runtime/config/mysql-client.cnf" --default-character-set=utf8mb4)
umask 077
if ! grep -q '^XXL_TOKEN=' "$runtime/config/secrets.env"; then
  printf 'XXL_TOKEN=%s\nXXL_ADMIN_PASSWORD=%s\n' "$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')" "$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')" >> "$runtime/config/secrets.env"
fi
source "$runtime/config/secrets.env"
if [[ $("${mysql[@]}" -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='xxl_job' AND table_name='xxl_job_info'") == 0 ]]; then
  "${mysql[@]}" < "$runtime/app/tables_xxl_job.sql"
fi
printf "GRANT ALL PRIVILEGES ON xxl_job.* TO 'quanshu'@'localhost'; UPDATE xxl_job.xxl_job_user SET password=MD5('%s') WHERE username='admin';\n" "$XXL_ADMIN_PASSWORD" | "${mysql[@]}"
"${mysql[@]}" <<'SQL'
INSERT INTO xxl_job.xxl_job_group(app_name,title,address_type,address_list,update_time)
SELECT 'quanshu-worker','优惠券后台任务',1,'http://127.0.0.1:9999/',NOW()
WHERE NOT EXISTS(SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='quanshu-worker');
INSERT INTO xxl_job.xxl_job_info(job_group,job_desc,add_time,update_time,author,alarm_email,schedule_type,schedule_conf,misfire_strategy,executor_route_strategy,executor_handler,executor_param,executor_block_strategy,executor_timeout,executor_fail_retry_count,glue_type,glue_remark,glue_updatetime,child_jobid,trigger_status,trigger_last_time,trigger_next_time)
SELECT g.id,j.title,NOW(),NOW(),'quanshu','','CRON',j.cron,'DO_NOTHING','FIRST',j.handler,'','SERIAL_EXECUTION',120,1,'BEAN','',NOW(),'',1,0,0
FROM xxl_job.xxl_job_group g CROSS JOIN (
 SELECT '过期优惠券及模板处理' title,'couponExpiry' handler,'0/10 * * * * ?' cron UNION ALL
 SELECT '领取补偿与预占回收','claimRepair','0/5 * * * * ?' UNION ALL
 SELECT '分块读取发券名单','fileImport','0/2 * * * * ?'
) j WHERE g.app_name='quanshu-worker' AND NOT EXISTS(SELECT 1 FROM xxl_job.xxl_job_info i WHERE i.job_group=g.id AND i.executor_handler=j.handler);
SQL
if [[ -f "$runtime/run/xxl-admin.pid" ]] && kill -0 "$(cat "$runtime/run/xxl-admin.pid")" 2>/dev/null; then
  curl --fail --silent http://127.0.0.1:18080/xxl-job-admin/toLogin > /dev/null && exit 0
fi
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/xxl_job?serverTimezone=Asia/Shanghai'
export SPRING_DATASOURCE_USERNAME=quanshu SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD"
export XXL_JOB_ACCESSTOKEN="$XXL_TOKEN"
cat > "$runtime/config/xxl-logback.xml" <<'XML'
<configuration><appender name="console" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%date %-5level %logger{36} - %msg%n</pattern></encoder></appender><root level="INFO"><appender-ref ref="console"/></root></configuration>
XML
nohup "$runtime/root/usr/lib/jvm/java-17-openjdk-amd64/bin/java" -Xms64m -Xmx192m -XX:ActiveProcessorCount=2 \
  -Dlogback.configurationFile="$runtime/config/xxl-logback.xml" -jar "$runtime/app/xxl-job-admin.jar" --server.address=127.0.0.1 --server.port=18080 \
  --logging.config="$runtime/config/xxl-logback.xml" \
  --spring.datasource.hikari.minimum-idle=1 --spring.datasource.hikari.maximum-pool-size=5 \
  --logging.file.path="$runtime/logs/xxl-admin" > "$runtime/logs/xxl-admin.log" 2>&1 < /dev/null &
echo "$!" > "$runtime/run/xxl-admin.pid"
for attempt in {1..60}; do
  if curl --fail --silent http://127.0.0.1:18080/xxl-job-admin/toLogin > /dev/null; then printf 'XXL-Job admin ready\n';exit 0;fi
  sleep 1
done
tail -n 30 "$runtime/logs/xxl-admin.log";exit 1
