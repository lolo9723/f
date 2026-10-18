// Deterministic wall-clock rotation survives cold starts and server failover.
// It needs no database counter and remains stable within a 15-minute window.
export function selectUniverse(quotes,now=Date.now()){
 const all=Object.keys(quotes).sort(),slot=Math.floor(now/900000),cursor=all.length?(slot*8)%all.length:0;
 const liquid=[...all].sort((a,b)=>quotes[b].quote_volume-quotes[a].quote_volume||a.localeCompare(b));
 const rotating=[...all.slice(cursor),...all.slice(0,cursor)].slice(0,8);
 return {pairs:[...new Set([...liquid.slice(0,16),...rotating])].slice(0,24),deep_scan_at:slot*900000};
}
