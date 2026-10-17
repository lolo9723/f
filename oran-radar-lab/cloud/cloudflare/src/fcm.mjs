// Optional trusted-server FCM transport. Requires Firebase account setup and secret.
const enc=new TextEncoder();
const b64=bytes=>btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/=/g,'').replace(/\+/g,'-').replace(/\//g,'_');
async function accessToken(sa,fetchImpl) {
  const now=Math.floor(Date.now()/1000);
  const head=b64(enc.encode(JSON.stringify({alg:'RS256',typ:'JWT'})));
  const payload=b64(enc.encode(JSON.stringify({iss:sa.client_email,scope:'https://www.googleapis.com/auth/firebase.messaging',aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3500})));
  const binary=Uint8Array.from(atob(sa.private_key.replace(/-----[^-]+-----/g,'').replace(/\s/g,'')),x=>x.charCodeAt(0));
  const key=await crypto.subtle.importKey('pkcs8',binary,{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['sign']);
  const signed=await crypto.subtle.sign('RSASSA-PKCS1-v1_5',key,enc.encode(head+'.'+payload));
  const response=await fetchImpl('https://oauth2.googleapis.com/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:head+'.'+payload+'.'+b64(signed)})});
  if(!response.ok)throw new Error('FCM authorization failed');
  return (await response.json()).access_token;
}

export async function deliver(env,{deliveryId,signalId,deviceId},fetchImpl=fetch) {
  if(!env.FCM_SERVICE_ACCOUNT_JSON)return {status:'not_configured'};
  const sa=JSON.parse(env.FCM_SERVICE_ACCOUNT_JSON);
  if(!/^[a-z0-9-]+$/.test(sa.project_id)||typeof sa.private_key!=='string')throw new Error('invalid FCM service account');
  const device=await env.DB.prepare('SELECT d.fcm_token,u.settings_json FROM devices d LEFT JOIN user_settings u ON u.user_id=d.user_id WHERE d.device_id=?').bind(deviceId).first();
  if(!device)return {status:'missing_device'};
  if(device.settings_json&&JSON.parse(device.settings_json).notifications===false)return {status:'disabled'};
  // OAuth can fail safely before the send claim: no notification has been attempted yet.
  const token=await accessToken(sa,fetchImpl);
  const claim=await env.DB.prepare("INSERT OR IGNORE INTO notification_outbox VALUES(?,?,?,'sending',1,?)").bind(deliveryId,signalId,deviceId,Date.now()).run();
  if(!claim.meta?.changes)return {status:'already_claimed'};
  let status='unknown';
  try {
    // Data-only message: app service decides whether signal_id has already been displayed.
    const response=await fetchImpl(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify({message:{token:device.fcm_token,data:{signal_id:signalId},android:{priority:'high',collapse_key:signalId}}})});
    status=response.ok?'sent':'rejected';
  }finally {
    // Unknown network outcome is not retried automatically (provider may have accepted it).
    await env.DB.prepare('UPDATE notification_outbox SET status=?,updated_at=? WHERE delivery_id=?').bind(status,Date.now(),deliveryId).run();
  }
  return {status};
}
