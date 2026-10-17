const {chromium}=require('playwright');
const fs=require('node:fs');
(async()=>{
 const secrets=Object.fromEntries(fs.readFileSync('cloudflare/.dev.vars','utf8').trim().split('\n').map(l=>l.split('=')));
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 try {
  const page=await browser.newPage({viewport:{width:1440,height:1000}});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://127.0.0.1:8787');
  await page.locator('#token').fill(secrets.ORAN_ADMIN_TOKEN);await page.locator('#loginForm button').click();
  await page.locator('#login').waitFor({state:'hidden'});
  await page.screenshot({path:'/tmp/oran-panel-desktop.png',fullPage:true});
  for(const label of ['Geçmiş veri bankası','Erken ralli avcısı','Örüntü hafızası','Performans laboratuvarı','Model merkezi','Veri içe aktarma','Sistem sağlığı']){
   await page.getByRole('button',{name:label,exact:true}).click();
   await page.locator('#title').filter({hasText:label}).waitFor();
  }
  await page.setViewportSize({width:390,height:844});
  await page.getByRole('button',{name:'Genel durum',exact:true}).click();
  await page.screenshot({path:'/tmp/oran-panel-mobile.png',fullPage:true});
  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth);
  fs.writeFileSync('reports/panel-checks.json',JSON.stringify({pages:8,javascript_errors:errors,mobile_page_overflow:overflow,viewports:['1440x1000','390x844']},null,2));
  if(errors.length||overflow)throw new Error('UI check failed');
  console.log('8 pages; desktop/mobile; no JS errors or page overflow');
 }finally{await browser.close();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
