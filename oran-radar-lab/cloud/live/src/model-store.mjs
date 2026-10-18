// Full learning vectors are compressed objects, never hot-path Postgres rows.
// Branch credentials are injected by the platform; no secret is in the APK.
import {S3Client,GetObjectCommand,PutObjectCommand} from '@aws-sdk/client-s3';
import {gzipSync,gunzipSync} from 'node:zlib';
let client;
const storage=()=>process.env.AWS_ENDPOINT_URL_S3?(client??=new S3Client({forcePathStyle:true})):null;
export async function storedModels(){
 const s3=storage();if(!s3)return null;
 try{const r=await s3.send(new GetObjectCommand({Bucket:'research-models',Key:'current.json.gz'}));return JSON.parse(gunzipSync(await r.Body.transformToByteArray()).toString());}
 catch(e){if(e.name==='NoSuchKey'||e.$metadata?.httpStatusCode===404)return null;throw e;}
}
export async function saveModels(models){const s3=storage();if(!s3)return {status:'storage_unavailable'};await s3.send(new PutObjectCommand({Bucket:'research-models',Key:'current.json.gz',Body:gzipSync(Buffer.from(JSON.stringify({generated_at:Date.now(),models}))),ContentType:'application/gzip',CacheControl:'private,max-age=3600'}));return {status:'saved'};}
