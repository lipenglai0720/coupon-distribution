const fs=require('fs'),cp=require('child_process');
const union=Array.from({length:4},(_,i)=>'SELECT * FROM user_coupon_'+i).join(' UNION ALL '),templates=Array.from({length:4},(_,i)=>'SELECT * FROM coupon_template_'+i).join(' UNION ALL ');
const query=`SELECT COUNT(*),SUM(t.total_stock-t.remaining_stock),SUM(COALESCE(c.n,0)),SUM(t.total_stock-t.remaining_stock<>COALESCE(c.n,0)),SUM(t.remaining_stock<0) FROM (${templates}) t LEFT JOIN (SELECT template_id,COUNT(*) n FROM (${union}) all_coupons GROUP BY template_id) c ON c.template_id=t.id; SELECT state,COUNT(*) FROM outbox GROUP BY state;`;
const output=cp.execFileSync('ssh',[...require('./vm-ssh.cjs')(),'env LD_LIBRARY_PATH=$HOME/quanshu-runtime/root/usr/lib/x86_64-linux-gnu $HOME/quanshu-runtime/root/usr/bin/mysql --defaults-file=$HOME/quanshu-runtime/config/mysql-client.cnf -N -B quanshu'],{input:query,encoding:'utf8'}).trim().split('\n');
const [templateCount,inventoryDeducted,physicalCoupons,mismatchedTemplates,negativeStock]=output[0].split('\t').map(Number);
if(mismatchedTemplates||negativeStock||inventoryDeducted!==physicalCoupons)throw Error('Inventory reconciliation failed '+output[0]);
const report={date:'2026-11-16',result:'PASS',templateCount,inventoryDeducted,physicalCoupons,mismatchedTemplates,negativeStock,outboxStates:Object.fromEntries(output.slice(1).map(row=>{const [state,count]=row.split('\t');return[state,Number(count)]}))};
fs.writeFileSync('.local/inventory-audit.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));
