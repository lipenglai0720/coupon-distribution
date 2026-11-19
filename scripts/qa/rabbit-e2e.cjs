const fs=require('fs');
const base='http://127.0.0.1:8080/api';
async function api(path,data){const r=await fetch(base+path,{method:data?'POST':'GET',headers:{'Content-Type':'application/json'},body:data?JSON.stringify(data):undefined});if(!r.ok)throw Error(path+': '+await r.text());return r.json()}
(async()=>{
 const count=Number(process.env.BATCH_SIZE||100),now=Date.now();
 const template=await api('/templates',{merchantId:1,name:'RabbitMQ真实分发验收 '+count,stock:count,thresholdCents:10000,discountCents:2000,validFrom:now-1000,validTo:now+86400000});
 const begin=Date.now();const task=await api('/tasks',{templateId:template.id,userIds:Array.from({length:count},(_,i)=>100001+i)});
 let final;for(let i=0;i<300;i++){
   final=await api('/tasks/'+task.id);
   const list=final.recipients;
   const summary=final.counts||{SUCCESS:list.filter(x=>x.state==='SUCCESS').length,PENDING:list.filter(x=>x.state==='PENDING').length,FAILED:list.filter(x=>x.state==='FAILED').length};
   if(i%5===0)console.log(JSON.stringify({elapsedMs:Date.now()-begin,...summary}));
   if(summary.PENDING===0){if(summary.SUCCESS!==count||summary.FAILED!==0)throw Error('Counts mismatch '+JSON.stringify(summary));const report={templateId:template.id,taskId:task.id,users:count,durationMs:Date.now()-begin,counts:summary,result:'PASS',environment:process.env.EXECUTION_ENVIRONMENT||'Application and native middleware on Ubuntu; Windows driver over SSH'};fs.writeFileSync('.local/rabbit-e2e-'+count+'.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));return}
   await new Promise(r=>setTimeout(r,2000));
 }
 throw Error('Task did not finish within polling limit')
})().catch(e=>{console.error(e);process.exit(1)});
