const fs=require('fs'),cp=require('child_process');
const props=Object.fromEntries(fs.readFileSync('.local/vm.properties','utf8').trim().split(/\r?\n/).map(l=>{let n=l.indexOf('=');return[l.slice(0,n),l.slice(n+1)]}));
const headers={Authorization:'Basic '+Buffer.from('quanshu:'+props['spring.rabbitmq.password']).toString('base64'),'Content-Type':'application/json'};
async function management(path,method='GET',data){const r=await fetch('http://127.0.0.1:15672/api'+path,{method,headers,body:data?JSON.stringify(data):undefined});if(!r.ok)throw Error('RabbitMQ management '+r.status);const text=await r.text();return text?JSON.parse(text):null}
async function api(path,user,data){const r=await fetch('http://127.0.0.1:8080/api'+path,{method:data?'POST':'GET',headers:{'Content-Type':'application/json','X-User-Id':String(user)},body:data?JSON.stringify(data):undefined});if(!r.ok)throw Error(await r.text());return r.json()}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const time=Date.now(),request='returns-'+time,user=990001;
 const t=await api('/templates',user,{merchantId:1,name:'Publisher Returns恢复验收',stock:1,thresholdCents:10000,discountCents:2000,validFrom:time-1000,validTo:time+3600000});
 const path='/bindings/quanshu/e/quanshu.events/q/quanshu.worker';const binding=(await management(path)).find(b=>b.routing_key==='work');if(!binding)throw Error('Expected original binding');
 let attempts=0;
 try{
  await management(path+'/'+encodeURIComponent(binding.properties_key),'DELETE');
  const accepted=await api('/claims/async',user,{templateId:t.id,requestId:request});if(accepted.state!=='PENDING')throw Error('Expected accepted pending claim');
  for(let i=0;i<20;i++){
   const sql="SELECT COALESCE(MAX(attempts),0) FROM outbox WHERE kind='ASYNC_CLAIM' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.requestId'))='"+request+"';\n";
   attempts=Number(cp.execFileSync('ssh',[...require('./vm-ssh.cjs')(),'env LD_LIBRARY_PATH=$HOME/quanshu-runtime/root/usr/lib/x86_64-linux-gnu $HOME/quanshu-runtime/root/usr/bin/mysql --defaults-file=$HOME/quanshu-runtime/config/mysql-client.cnf -N -B quanshu'],{input:sql,encoding:'utf8'}).trim());
   if(attempts>0)break;await sleep(300);
  }
  if(attempts===0)throw Error('Application failed to record return/retry');
  if((await api('/claims/'+request,user)).state!=='PENDING')throw Error('Unrouted claim must remain pending');
 }finally{await management(path,'POST',{routing_key:binding.routing_key,arguments:binding.arguments})}
 let final;for(let i=0;i<40;i++){final=await api('/claims/'+request,user);if(final.state==='SUCCESS')break;await sleep(500)}
 if(final.state!=='SUCCESS')throw Error('Did not recover after binding restored');
 const report={result:'PASS',requestId:request,recordedReturnAttempts:attempts,completedAfterBindingRestored:true};fs.writeFileSync('.local/returns-e2e.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));
})().catch(e=>{console.error(e);process.exit(1)});
