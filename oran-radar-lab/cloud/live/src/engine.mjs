// Compatibility entry point. Market scans and research no longer use Postgres.
import {getLiveMarket,refreshResearch} from './market-memory.mjs';
export {getJSON} from './market-data.mjs';
export async function refresh(){const m=await getLiveMarket();return {status:'complete',scanned:m.candidates.filter(c=>c.live_valid).length,failed:m.errors.length,research:await refreshResearch(2)};}
