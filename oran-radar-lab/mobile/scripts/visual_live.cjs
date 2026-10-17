// Same React Native components rendered via react-native-web. Fixtures never write to production.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const directory=path.resolve(process.env.ORAN_PREVIEW_DIR||'web-preview'),out=path.resolve('../downloads/screenshots');
const report=JSON.parse(fs.readFileSync('../cloud/live/reports/initial-live-snapshot.json','utf8'));
const defaults={execution_mode:'MANUAL',enabled:false,threshold:70,score_source:'FINAL',amount_mode:'AUTO',entry_mode:'MARKET',fixed_try:1000,coin_cap_try:1000,total_cap_try:null,risk_pct:1,portfolio_risk_pct:3,max_coin_pct:30,max_positions:5,cooldown_hours:12,target_pct:null,stop_pct:null,entry_prices:{}};
const market={quotes:Object.fromEntries(report.signals.map(s=>[s.symbol_id.split(':').at(-1),{price:s.entry*1.01}])),candidates:report.signals.map(s=>({...s,pair:s.symbol_id.split(':').at(-1)}))};
let initialized=false,revision=0,manualCount=0,currentSettings={...defaults};const wallet=()=>({initialized,revision,settings:currentSettings,orders:[],decisions:[],last_cycle_at:null,daily:{realized_pnl_try:0,closed_trades:0},balances:{initial_try:1000,cash_try:1000,available_try:1000,reserved_try:0,invested_try:0,equity_try:1000,realized_pnl_try:0,unrealized_pnl_try:0,planned_risk_try:0}});
(async()=>{
 const server=http.createServer((req,res)=>{let target=path.join(directory,new URL(req.url,'http://local').pathname);if(!target.startsWith(directory)){res.writeHead(403).end();return;}if(!fs.existsSync(target)||fs.statSync(target).isDirectory())target=path.join(directory,'index.html');const mime={'.html':'text/html','.js':'application/javascript','.css':'text/css','.png':'image/png','.ttf':'font/ttf','.ico':'image/x-icon'};res.setHeader('Content-Type',mime[path.extname(target)]||'application/octet-stream');fs.createReadStream(target).pipe(res);});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({headless:true,executablePath:process.env.ORAN_CHROME||'/usr/bin/google-chrome',args:['--no-sandbox']});
 try{
  fs.mkdirSync(out,{recursive:true});const errors=[];
  for(const width of [390,360]){
   initialized=false;revision=0;currentSettings={...defaults};
   const context=await browser.newContext({viewport:{width,height:844},deviceScaleFactor:1});
   const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
   await page.route('**/*.neon.tech/**',async route=>{const request=route.request(),p=new URL(request.url()).pathname;const headers={'access-control-allow-origin':'*','access-control-allow-headers':'content-type,authorization','access-control-allow-methods':'GET,POST,PUT,OPTIONS'};if(request.method()==='OPTIONS')return route.fulfill({status:204,headers});let body=report,status=200;if(p==='/v1/session'){body={token:'visual-test-device-key-'.repeat(3)};status=201;}else if(p==='/v1/wallet/init'){initialized=true;body=wallet();status=201;}else if(p==='/v1/market')body=market;else if(p==='/v1/wallet/settings'){const d=request.postDataJSON();assert.equal(d.settings.execution_mode,'OPTIMAL');assert.equal(d.settings.total_cap_try,500);currentSettings=d.settings;revision++;body=wallet();}else if(p==='/v1/wallet/manual-order'){const d=request.postDataJSON();assert(d.pair.endsWith('TRY'));assert.equal(d.amount_try,25);assert(d.stop<d.entry&&d.entry<d.target);manualCount++;revision++;body=wallet();}else if(p.startsWith('/v1/wallet'))body=wallet();return route.fulfill({status,headers:{...headers,'content-type':'application/json'},body:JSON.stringify(body)});});
   await page.goto('http://127.0.0.1:'+server.address().port+'/cloud');await page.getByText('Bugünün radarı',{exact:true}).waitFor();await page.getByText('TR fiyatı',{exact:true}).waitFor();await page.waitForTimeout(1000);
   assert.equal(await page.getByRole('tab').count(),3);assert(await page.getByText('Neden bu puan? +').count()>0);
   await page.screenshot({path:path.join(out,'radar-'+width+'.png'),fullPage:false});
   await page.getByText('Neden bu puan? +').first().click();await page.getByText(/Temel puan .*geçmiş araştırma/).first().waitFor();
   await page.getByRole('tab',{name:/Kasa/}).click();if(!initialized){await page.getByText('Sanal kasanı oluştur',{exact:true}).waitFor();await page.getByText('Sanal kasayı başlat',{exact:true}).click();}
   await page.getByText('Buluttaki sanal kasa',{exact:true}).waitFor();await page.screenshot({path:path.join(out,'kasa-'+width+'.png'),fullPage:false});
   await page.getByText('Manuel sanal alış +',{exact:true}).click();await page.getByText('Manuel sanal alış',{exact:true}).waitFor();
   const selected=Object.keys(market.quotes)[0];await page.getByLabel('Manuel coin ara').fill(selected.replace(/TRY$/,''));await page.getByRole('button',{name:selected.replace(/TRY$/,''),exact:true}).click();await page.getByLabel('Alış tutarı (TL)',{exact:true}).fill('25');
   await page.screenshot({path:path.join(out,'manuel-'+width+'.png'),fullPage:false});await page.getByRole('button',{name:'Sanal emri oluştur',exact:true}).click();await page.getByText('Manuel sanal alış',{exact:true}).waitFor({state:'hidden'});
   await page.getByRole('tab',{name:/Ayarlar/}).click();await page.getByText('İşlem modu ve bütçe',{exact:true}).waitFor();await page.getByRole('button',{name:/Optimal Kararı buluta bırak/}).click();await page.getByLabel('Çalışma bütçesi (TL)',{exact:true}).fill('500');
   await page.getByText('Ayarları buluta kaydet',{exact:true}).click();await page.getByText('Ayarlar bulutta kaydedildi.',{exact:true}).waitFor();await page.getByText('İşlem modu ve bütçe',{exact:true}).scrollIntoViewIfNeeded();await page.screenshot({path:path.join(out,'ayarlar-'+width+'.png'),fullPage:false});
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth),false,'horizontal overflow');
   await context.close();
  }
  assert.deepEqual(errors,[]);assert.equal(manualCount,2);console.log('Visual checks passed: 3 tabs, 2 widths, real components, manual order form, explicit optimal mode, persisted budget, decision expansion and no overflow.');
 }finally{await browser.close();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
