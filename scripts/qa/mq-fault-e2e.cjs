const fs=require('fs'),cp=require('child_process');
const sshArgs=[...require('./vm-ssh.cjs')()];
const props=Object.fromEntries(fs.readFileSync('.local/vm.properties','utf8').trim().split(/\r?\n/).map(l=>{const n=l.indexOf('=');return[l.slice(0,n),l.slice(n+1)]}));
const auth='Basic '+Buffer.from(props['spring.rabbitmq.username']+':'+props['spring.rabbitmq.password']).toString('base64');
async function api(path,user,data){const r=await fetch('http://127.0.0.1:8080/api'+path,{method:data?'POST':'GET',headers:{'Content-Type':'application/json','X-User-Id':String(user)},body:data?JSON.stringify(data):undefined});const b=await r.json();if(!r.ok)throw Error(path+': '+JSON.stringify(b));return b}
async function mq(path,data){const r=await fetch('http://127.0.0.1:15672/api'+path,{method:data?'POST':'GET',headers:{Authorization:auth,'Content-Type':'application/json'},body:data?JSON.stringify(data):undefined});if(!r.ok)throw Error('Management '+r.status);return r.json()}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function remote(command,input){return cp.execFileSync('ssh',sshArgs.concat(command),{input,encoding:'utf8',timeout:60000});}
(async()=>{
 const report={environment:'native Ubuntu VM',checks:{}};
 const now=Date.now(),user=880001;
 const t=await api('/templates',user,{merchantId:1,name:'MQ中断恢复验收',stock:1,thresholdCents:10000,discountCents:2000,validFrom:now-1000,validTo:now+3600000});
 let stopped=false;
 try{
   remote('export PATH=$HOME/quanshu-runtime/root/usr/lib/erlang/bin:$PATH; export LD_LIBRARY_PATH=$HOME/quanshu-runtime/root/usr/lib/x86_64-linux-gnu; export LC_ALL=C.UTF-8; export ERL_FLAGS="+S 2:2"; $HOME/quanshu-runtime/rabbitmq_server-4.4.0/sbin/rabbitmqctl -n quanshu@localhost shutdown');stopped=true;
   const request=await api('/claims/async',user,{templateId:t.id,requestId:'broker-recovery-'+now});
   if(request.state!=='PENDING')throw Error('Expected durable pending intent during outage');
   report.checks.pendingDuringBrokerOutage=true;
 }finally{if(stopped)remote('bash ~/quanshu-runtime/start-user-services.sh');}
 let result;
 for(let i=0;i<60;i++){result=await api('/claims/broker-recovery-'+now,user);if(result.state==='SUCCESS')break;await sleep(500);}
 if(result.state!=='SUCCESS')throw Error('Recovery did not complete '+JSON.stringify(result));report.checks.completedAfterBrokerRestart=true;
 const sample=JSON.parse(fs.readFileSync('.local/async-e2e.json'));
 if(!/^[a-z0-9-]+$/.test(sample.sampleRequest))throw Error('Unsafe fixture identifier');
 const event=remote('env LD_LIBRARY_PATH=$HOME/quanshu-runtime/root/usr/lib/x86_64-linux-gnu $HOME/quanshu-runtime/root/usr/bin/mysql --defaults-file=$HOME/quanshu-runtime/config/mysql-client.cnf -N -B quanshu',"SELECT id FROM outbox WHERE kind='ASYNC_CLAIM' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.requestId'))='"+sample.sampleRequest+"' LIMIT 1;\n").trim();
 if(!/^[a-f0-9-]{36}$/.test(event))throw Error('Missing original event');
 for(let i=0;i<5;i++){
   const published=await mq('/exchanges/quanshu/quanshu.events/publish',{properties:{message_id:event,headers:{kind:'ASYNC_CLAIM'}},routing_key:'work',payload:JSON.stringify({requestId:sample.sampleRequest}),payload_encoding:'string'});
   if(!published.routed)throw Error('Duplicate did not route');
 }
 await sleep(1000);const wallet=await api('/wallet',sample.sampleUser);
 if(wallet.filter(x=>x.template_id===sample.templateId).length!==1)throw Error('Duplicate created extra coupon');report.checks.fiveDuplicateDeliveriesDidNotDuplicateCoupon=true;
 const failedBefore=await mq('/queues/quanshu/quanshu.failed');
 await mq('/exchanges/quanshu/quanshu.events/publish',{properties:{message_id:'poison-'+Date.now(),headers:{kind:'UNKNOWN'}},routing_key:'work',payload:'{}',payload_encoding:'string'});
 let dead;for(let i=0;i<30;i++){dead=await mq('/queues/quanshu/quanshu.failed');if(dead.messages>(failedBefore.messages||0))break;await sleep(500);}
 if(dead.messages<=(failedBefore.messages||0))throw Error('Poison message did not dead-letter');report.checks.poisonMessageDeadLettered=true;
 const unrouted=await mq('/exchanges/quanshu/quanshu.events/publish',{properties:{},routing_key:'unbound-test-route',payload:'{}',payload_encoding:'string'});
 if(unrouted.routed!==false)throw Error('Unexpected routing of unbound message');report.checks.unroutableMessageDetectedByBroker=true;
 report.result='PASS';fs.writeFileSync('.local/mq-fault-e2e.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));
})().catch(e=>{console.error(e);process.exit(1)});
