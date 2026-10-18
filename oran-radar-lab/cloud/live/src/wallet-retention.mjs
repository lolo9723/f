// Keep immutable closed orders in a paginated archive. The frequently read
// wallet document stays small without losing cash, order history or daily P&L.
export async function archiveWallet(client,user,w,now=Date.now()){
 const day=Math.floor((now+10800000)/86400000)*86400000-10800000;
 const closed=w.orders.filter(o=>!['TRACKING','WAITING_ENTRY'].includes(o.state)).sort((a,b)=>b.created_at-a.created_at);
 const archive=closed.slice(20).filter(o=>o.closed_at<day);if(!archive.length)return;
 for(const o of archive)await client.query('INSERT INTO wallet_archive(user_id,order_id,closed_at,document) VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING',[user,o.id,o.closed_at,JSON.stringify(o)]);
 const ids=new Set(archive.map(o=>o.id));w.orders=w.orders.filter(o=>!ids.has(o.id));w.archived_orders=(w.archived_orders||0)+ids.size;
}
