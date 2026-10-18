import {defineConfig} from '@neon/config/v1';
export default defineConfig({functions:{radar:{name:'Oran Radar Cloud 4.0',source:'src/index.mjs'}},buckets:{'research-models':{access:'private'}},triggers:{'market-quarter-hour':{type:'schedule',function:'radar',cron:'*/15 * * * *',functionPath:'/internal/cron'}}});
