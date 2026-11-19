const fs=require('fs'),zlib=require('zlib');
function zip(entries){const parts=[],central=[];let offset=0;for(const [name,text]of Object.entries(entries)){const n=Buffer.from(name),b=Buffer.from(text),crc=zlib.crc32(b);const h=Buffer.alloc(30);h.writeUInt32LE(0x04034b50);h.writeUInt16LE(20,4);h.writeUInt16LE(0x5821,12);h.writeUInt32LE(crc,14);h.writeUInt32LE(b.length,18);h.writeUInt32LE(b.length,22);h.writeUInt16LE(n.length,26);parts.push(h,n,b);const c=Buffer.alloc(46);c.writeUInt32LE(0x02014b50);c.writeUInt16LE(20,4);c.writeUInt16LE(20,6);c.writeUInt16LE(0x5821,14);c.writeUInt32LE(crc,16);c.writeUInt32LE(b.length,20);c.writeUInt32LE(b.length,24);c.writeUInt16LE(n.length,28);c.writeUInt32LE(offset,42);central.push(c,n);offset+=h.length+n.length+b.length}const directory=Buffer.concat(central),end=Buffer.alloc(22);end.writeUInt32LE(0x06054b50);end.writeUInt16LE(central.length/2,8);end.writeUInt16LE(central.length/2,10);end.writeUInt32LE(directory.length,12);end.writeUInt32LE(offset,16);return Buffer.concat([...parts,directory,end])}
const count=5000;
const rows=['user_id',...Array.from({length:count},(_,i)=>String(700001+i))].map((v,i)=>`<row r="${i+1}"><c r="A${i+1}" t="inlineStr"><is><t>${v}</t></is></c></row>`).join('');
const xlsx=zip({
 '[Content_Types].xml':'<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
 '_rels/.rels':'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
 'xl/workbook.xml':'<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="users" sheetId="1" r:id="rId1"/></sheets></workbook>',
 'xl/_rels/workbook.xml.rels':'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>',
 'xl/worksheets/sheet1.xml':'<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'+rows+'</sheetData></worksheet>'
});
async function api(path,data){const r=await fetch('http://127.0.0.1:8080/api'+path,{method:data?'POST':'GET',headers:{'Content-Type':'application/json'},body:data?JSON.stringify(data):undefined});if(!r.ok)throw Error(await r.text());return r.json()}
(async()=>{
 fs.writeFileSync('.local/recipients-5000.xlsx',xlsx);const time=Date.now();
 const t=await api('/templates',{merchantId:1,name:'Excel流式分发5000人验收',stock:count,thresholdCents:10000,discountCents:2000,validFrom:time-1000,validTo:time+86400000});
 const body=new FormData();body.append('templateId',String(t.id));body.append('file',new Blob([xlsx]),'recipients-5000.xlsx');
 const response=await fetch('http://127.0.0.1:8080/api/tasks/file',{method:'POST',body});if(!response.ok)throw Error(await response.text());const task=await response.json();const acceptedMs=Date.now()-time;
 for(let i=0;i<120;i++){
  const status=await api('/tasks/'+task.id);if(i%5===0)console.log(JSON.stringify({parseState:status.parse_state,...status.counts}));
  if(status.status!=='PROCESSING'){
    if(status.status!=='COMPLETED'||status.counts.SUCCESS!==count||status.importErrors!==0)throw Error(JSON.stringify(status));
    const report={users:count,templateId:t.id,taskId:task.id,bytes:xlsx.length,acceptedMs,durationMs:Date.now()-time,checkpoint:status.checkpoint,counts:status.counts,result:'PASS'};
    fs.writeFileSync('.local/file-e2e.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));return;
  }
  await new Promise(r=>setTimeout(r,1000));
 }
 throw Error('File task timeout');
})().catch(e=>{console.error(e);process.exit(1)});
