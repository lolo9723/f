"""Run workerd and checks in one process network namespace; never deploys."""
from pathlib import Path
import argparse
import json
import os
import secrets
import subprocess
import time
import requests

parser=argparse.ArgumentParser();parser.add_argument('--skip-visual',action='store_true');args=parser.parse_args()
project=Path(__file__).resolve().parents[1];os.chdir(project)
dev=project/'cloudflare/.dev.vars'
if not dev.exists():dev.write_text('ORAN_ADMIN_TOKEN='+secrets.token_urlsafe(40)+'\nORAN_INGEST_TOKEN='+secrets.token_urlsafe(40)+'\n')
keys=dict(line.split('=',1) for line in dev.read_text().splitlines())
log=open('reports/local-runtime.log','w')
process=subprocess.Popen(['node_modules/.bin/wrangler','dev','--local','--ip','127.0.0.1','--inspector-port','0','--port','8787'],cwd=project/'cloudflare',stdout=log,stderr=subprocess.STDOUT,env={**os.environ,'WRANGLER_SEND_METRICS':'false'})
checks=[]
def check(name,condition):
    assert condition,name
    checks.append({'check':name,'passed':True})
base='http://127.0.0.1:8787'
try:
    for _ in range(150):
        if process.poll() is not None:raise RuntimeError('local runtime exited; inspect reports/local-runtime.log')
        try:
            if requests.get(base+'/v1/health',timeout=1).status_code==200:break
        except requests.RequestException:pass
        time.sleep(.2)
    else:raise RuntimeError('local runtime did not become ready')
    admin={'Authorization':'Bearer '+keys['ORAN_ADMIN_TOKEN']};ingest={'Authorization':'Bearer '+keys['ORAN_INGEST_TOKEN']}
    check('health',requests.get(base+'/v1/health').json()['real_trading'] is False)
    check('anonymous denied',requests.get(base+'/v1/status').status_code==401)
    report=json.loads(Path('reports/coverage.json').read_text())
    check('publish measured coverage',requests.put(base+'/v1/ingest/snapshot',json=report,headers=ingest).status_code==200)
    check('real D1 snapshot read',requests.get(base+'/v1/status',headers=admin).json()['totals']['rows']==report['totals']['rows'])
    user=requests.post(base+'/v1/admin/tokens',headers=admin,json={'user_id':'local-test'}).json()['token'];uh={'Authorization':'Bearer '+user}
    check('scoped settings write',requests.put(base+'/v1/settings',headers=uh,json={'watchlist':['binance_tr:spot:BTC_TRY'],'maxHoldingHours':48,'notifications':False}).status_code==200)
    check('user cannot publish',requests.put(base+'/v1/ingest/snapshot',json=report,headers=uh).status_code==403)
    check('CORS origin denied',requests.get(base+'/v1/status',headers={**uh,'Origin':'https://attacker.example'}).status_code==403)
    check('token revocation',requests.post(base+'/v1/admin/revoke',headers=admin,json={'user_id':'local-test'}).status_code==200 and requests.get(base+'/v1/status',headers=uh).status_code==401)
    check('scheduled queue',requests.get(base+'/cdn-cgi/local/scheduled').status_code==200 and len(requests.get(base+'/v1/jobs',headers=ingest).json()['jobs'])>=1)
    Path('reports/local-integration.json').write_text(json.dumps({'runtime':'Wrangler local / workerd + D1','checks':checks},indent=2))
    print('Local API integration:',len(checks),'checks passed')
    if args.skip_visual:
        Path('reports/panel-checks.json').write_text(json.dumps({'status':'not_run','reason':'Visual check explicitly skipped; Chromium required.','source_static_syntax_check':'passed'},indent=2))
        print('Visual browser check: not run (Chromium required)')
    else:
        subprocess.run(['node','scripts/check_panel.cjs'],check=True)
finally:
    process.terminate()
    try:process.wait(timeout=10)
    except subprocess.TimeoutExpired:process.kill()
    log.close()
