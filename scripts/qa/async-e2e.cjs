const fs=require('fs');
const base='http://127.0.0.1:8080/api';
async function api(path,user=1,data){const r=await fetch(base+path,{method:data?'POST':'GET',headers:{'Content-Type':'application/json','X-User-Id':String(user)},body:data?JSON.stringify(data):undefined});const b=await r.json();if(!r.ok)throw Error(path+': '+JSON.stringify(b));return b}
async function parallel(items,fn){let cursor=0;const out=new Array(items.length);await Promise.all(Array.from({length:32},async()=>{while(cursor<items.length){const index=cursor++;out[index]=await fn(items[index],index)}}));return out}
(async()=>{
 const time=Date.now(),stock=100,users=1000;
 const t=await api('/templates',1,{merchantId:1,name:'Redis Lua 异步并发验收',stock,thresholdCents:10000,discountCents:2000,validFrom:time-1000,validTo:time+86400000});
 const input=Array.from({length:users},(_,i)=>({user:500001+i,requestId:'e2e-'+time+'-'+i}));
 const responses=await parallel(input,async x=>({user:x.user,...await api('/claims/async',x.user,{templateId:t.id,requestId:x.requestId})}));
 const accepted=responses.filter(x=>x.state==='PENDING');
 let final=[];for(let i=0;i<50;i++){
   final=await parallel(accepted,r=>api('/claims/'+r.request_id,r.user));
   if(final.every(x=>x.state!=='PENDING'))break;
   await new Promise(r=>setTimeout(r,500));
 }
 const successes=final.filter(x=>x.state==='SUCCESS');
 if(successes.length!==stock||responses.filter(x=>x.state==='FAILED').length!==users-stock)throw Error('Async counts mismatch '+JSON.stringify({successes:successes.length,admitted:accepted.length}));
 const first=successes[0];
 const duplicate=await api('/claims/async',first.user_id,{templateId:t.id,requestId:first.request_id});
 if(duplicate.coupon_id!==first.coupon_id)throw Error('Duplicate changed coupon');
 const template=(await api('/templates')).find(x=>x.id===t.id);
 if(template.remaining_stock!==0)throw Error('Inventory mismatch');
 const report={users,stock,successes:successes.length,rejected:users-stock,templateId:t.id,result:'PASS',sampleRequest:first.request_id,sampleUser:first.user_id,sampleCoupon:first.coupon_id,environment:'Application, MySQL, Redis and RabbitMQ run natively on the same Ubuntu VM; driver on Windows via SSH tunnel'};
 fs.writeFileSync('.local/async-e2e.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));
})().catch(e=>{console.error(e);process.exit(1)});
