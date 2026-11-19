const fs=require('fs'),cp=require('child_process');
const ssh=[...require('./vm-ssh.cjs')()];
function remote(command,input){return cp.execFileSync('ssh',ssh.concat(command),{input,encoding:'utf8',timeout:30000}).trim()}
function sql(query){return remote('env LD_LIBRARY_PATH=$HOME/quanshu-runtime/root/usr/lib/x86_64-linux-gnu $HOME/quanshu-runtime/root/usr/bin/mysql --defaults-file=$HOME/quanshu-runtime/config/mysql-client.cnf -N -B quanshu',query+';\n')}
async function api(path,user,data){const r=await fetch('http://127.0.0.1:8080/api'+path,{method:data?'POST':'GET',headers:{'Content-Type':'application/json','X-User-Id':String(user)},body:data?JSON.stringify(data):undefined});if(!r.ok)throw Error(path+': '+await r.text());return {body:await r.json(),role:r.headers.get('x-quanshu-role')}}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const report={result:'PASS',environment:'Nacos + Gateway + four business services + worker; MySQL physical hash tables in two schemas; native Ubuntu VM',checks:{}};
 for(const [path,role] of [['/templates','template'],['/wallet','coupon']]){if((await api(path,1990001)).role!==role)throw Error('Wrong role for '+path)}report.checks.gatewayRoutes=true;
 report.registrations={};for(const name of ['merchant-service','template-service','coupon-service','settlement-service','distribution-service','quanshu-gateway']){
   const data=JSON.parse(remote('curl --fail --silent "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName='+name+'"'));
   if(!data.hosts?.some(x=>x.healthy))throw Error('No healthy Nacos instance: '+name);report.registrations[name]=data.hosts.map(x=>({ip:x.ip,port:x.port,healthy:x.healthy}));
 }
 const now=Date.now(),user=1990001,merchant=1990002;
 const template=(await api('/templates',user,{merchantId:merchant,name:'网关/分片/延迟消息验收',stock:1,thresholdCents:100,discountCents:20,validFrom:now-1000,validTo:now+8000})).body;
 const claim=(await api('/claims/async',user,{templateId:template.id,requestId:'distributed-'+now})).body;
 let result;for(let i=0;i<50;i++){result=(await api('/claims/'+claim.request_id,user)).body;if(result.state==='SUCCESS')break;await sleep(200)}
 if(result.state!=='SUCCESS')throw Error('Claim did not complete');
 const userShard=(BigInt(user)^BigInt(user)>>32n)&3n,merchantShard=(BigInt(merchant)^BigInt(merchant)>>32n)&3n;
 if(Number(sql(`SELECT COUNT(*) FROM quanshu_s${userShard/2n}.user_coupon_${userShard} WHERE user_id=${user} AND template_id=${template.id}`))!==1)throw Error('User shard mismatch');
 if(Number(sql(`SELECT COUNT(*) FROM quanshu_s${merchantShard/2n}.coupon_template_${merchantShard} WHERE shop_number=${merchant} AND id=${template.id}`))!==1)throw Error('Template shard mismatch');report.checks.physicalUserAndMerchantShards=true;
 let expired=false;for(let i=0;i<60;i++){const wallet=(await api('/wallet',user)).body;if(wallet.find(x=>x.template_id===template.id)?.status==='EXPIRED'){expired=true;break}await sleep(250)}
 if(!expired)throw Error('Expiry did not materialize');
 const delayed=Number(sql(`SELECT COUNT(*) FROM outbox o JOIN inbox i ON i.event_id=o.id WHERE o.kind='COUPON_EXPIRE' AND JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.couponId'))='${result.coupon_id}' AND i.processed_at>=CAST(JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.deliveryAt')) AS UNSIGNED)`));
 if(delayed<1)throw Error('No consumed due delayed event');report.checks.rabbitTtlDueEventConsumed=true;
 report.scheduler=sql("SELECT i.executor_handler,COUNT(*) FROM xxl_job.xxl_job_log l JOIN xxl_job.xxl_job_info i ON i.id=l.job_id WHERE i.job_group=(SELECT id FROM xxl_job.xxl_job_group WHERE app_name='quanshu-worker' LIMIT 1) AND l.trigger_code=200 AND l.handle_code=200 GROUP BY i.executor_handler").split('\n').map(x=>x.split('\t'));
 if(report.scheduler.length!==3)throw Error('Not all XXL handlers have successful callbacks');report.checks.xxlSuccessfulCallbacks=true;
 report.templateId=template.id;report.storageMode=sql('SELECT mode FROM storage_mode WHERE id=1');
 fs.writeFileSync('.local/distributed-e2e.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));
})().catch(e=>{console.error(e);process.exit(1)});
