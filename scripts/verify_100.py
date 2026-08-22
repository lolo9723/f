#!/usr/bin/env python3
import hashlib, sys, zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
APK=Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'app/build/outputs/apk/debug/app-debug.apk'
MODEL='assets/models/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm'
MODEL_SIZE=347_251_840
MODEL_SHA='2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139'
passed=[]

def src(p):
    q=ROOT/p
    return q.read_text(encoding='utf-8') if q.exists() else ''

def ck(cond,name):
    n=len(passed)+1
    if not cond:
        raise SystemExit(f'FAIL {n}/100: {name}')
    passed.append(name)
    print(f'PASS {n:03d}/100 {name}')

def hasenc(data,s):
    return s.encode('utf-8') in data or s.encode('utf-16le') in data

build=src('app/build.gradle')
manifest_src=src('app/src/main/AndroidManifest.xml')
registry=src('app/src/main/java/tr/edu/balikesir/anketrapor/LocalModelRegistry.java')
installer=src('app/src/main/java/tr/edu/balikesir/anketrapor/BundledModelInstaller.java')
setup=src('app/src/main/java/tr/edu/balikesir/anketrapor/ModelSetupActivityV2.java')
planner=src('app/src/main/java/tr/edu/balikesir/anketrapor/LocalPlannerActivity.kt')
mainv3=src('app/src/main/java/tr/edu/balikesir/anketrapor/MainActivityV3.java')
mainbase=src('app/src/main/java/tr/edu/balikesir/anketrapor/MainActivity.java')
service=src('app/src/main/java/tr/edu/balikesir/anketrapor/AgentAccessibilityServiceV4.java')
starter=src('app/src/main/java/tr/edu/balikesir/anketrapor/AgentScriptStarter.java')
engine=src('app/src/main/java/tr/edu/balikesir/anketrapor/AgentScriptEngineV3.java')
runtime=src('app/src/main/java/tr/edu/balikesir/anketrapor/AgentScriptRuntimeV5.java')
safety=src('app/src/main/java/tr/edu/balikesir/anketrapor/SafetyPolicy.java')
web=src('app/src/main/java/tr/edu/balikesir/anketrapor/WebResearchActivity.java')
xlsx=src('app/src/main/java/tr/edu/balikesir/anketrapor/SimpleXlsxWriter.java')
complex_test=src('app/src/main/java/tr/edu/balikesir/anketrapor/AgentComplexSelfTest.java')
a11y=src('app/src/main/res/xml/accessibility_service_config.xml')
workflow=src('.github/workflows/build-apk.yml')

# 1-20: build/model source integrity
ck(APK.is_file(),'APK oluştu')
ck(APK.stat().st_size>350_000_000,'APK gömülü model boyutunda')
ck("versionName '1.0.4'" in build,'versionName 1.0.4')
ck('versionCode 10' in build,'versionCode 10')
ck("applicationId 'tr.edu.balikesir.yerelajan'" in build,'doğru applicationId')
ck('minSdk 31' in build,'minSdk 31')
ck('targetSdk 35' in build,'targetSdk 35')
ck('debuggable false' in build,'debuggable false')
ck('jniDebuggable false' in build,'jniDebuggable false')
ck("noCompress += ['litertlm']" in build,'litertlm sıkıştırılmıyor')
ck('litertlm-android:0.13.1' in build,'LiteRT-LM 0.13.1')
ck(MODEL_SHA in registry,'model SHA kaynakta sabit')
ck(str(MODEL_SIZE).replace('_','') in registry.replace('_',''),'model boyutu kaynakta sabit')
ck('Qwen3 0.6B No-Think INT4' in registry,'no-think model seçili')
ck('https://' not in registry,'runtime model URL içermiyor')
ck('QWEN3_17B' not in registry,'1.7B runtime seçimi kaldırıldı')
ck('QWEN3_4B' not in registry,'4B runtime seçimi kaldırıldı')
ck('BUNDLED_ASSET' in registry and 'BUNDLED_SHA256' in registry,'gömülü model kayıtları var')
ck('DownloadManager' not in setup,'model kurulumunda DownloadManager yok')
ck('internetten indirilmiyor' in setup,'model ekranı ağ indirmediğini bildiriyor')

# 21-40: installer/planner anti-stall
ck('MessageDigest.getInstance("SHA-256")' in installer,'kurulum SHA-256 hesaplıyor')
ck('.part' in installer,'atomik geçici model dosyası')
ck('getFD().sync()' in installer,'model kopyası fsync')
ck('renameTo(out)' in installer,'atomik final rename')
ck('fullVerifyInstalled' in installer,'tam model yeniden doğrulaması')
ck('assetMetadataValid' in installer,'APK asset metadata kontrolü')
ck('openFd(LocalModelRegistry.BUNDLED_ASSET)' in installer,'asset exact length kontrolü')
ck('copied!=m.expectedBytes' in installer,'kopyalanan byte sayısı exact')
ck('BUNDLED_SHA256.equalsIgnoreCase(digest)' in installer,'kopya SHA eşleşmesi zorunlu')
ck('marker' in installer and '.verified' in registry,'doğrulama marker sistemi')
ck('HARD_TIMEOUT_MS = 75_000L' in planner,'planlayıcı 75sn hard timeout')
ck('Backend.CPU()' in planner,'kararlı CPU backend')
ck('Backend.GPU' not in planner,'GPU fallback takılma yolu kaldırıldı')
ck('AtomicBoolean' in planner,'tek sonuç teslim kilidi')
ck('elapsed() > 58_000L' in planner,'geç repair turu engeli')
ck(planner.count('conversation.sendMessage(')==2,'en fazla iki model üretim çağrısı')
ck('killProcess(android.os.Process.myPid())' in planner,'planner process hard kill')
ck('CPU planlıyor… ${sec}s' in planner,'planlama saniye sayacı')
ck('BundledModelInstaller.ensureInstalled' in planner,'planner modeli otomatik hazırlıyor')
ck('setPrimaryClip' not in mainv3,'planlayıcı kullanıcı panosunu ezmiyor')

# 41-60: process/security/permissions
ck('android:name=".ModelSetupActivityV2"' in manifest_src and 'android:process=":planner"' in manifest_src,'model setup planner prosesinde')
ck('android:name=".LocalPlannerActivity"' in manifest_src and 'android:process=":planner"' in manifest_src,'planner ayrı proses')
ck('<process android:process=":planner">' in manifest_src and '<deny-permission android:name="android.permission.INTERNET"' in manifest_src,'planner internet deny')
ck('<process android:process=":web">' in manifest_src and '<allow-permission android:name="android.permission.INTERNET"' in manifest_src,'web internet allow')
ck('<deny-permission android:name="android.permission.INTERNET"' in manifest_src,'ana proses internet deny')
ck('android:name=".WebResearchActivity"' in manifest_src and 'android:process=":web"' in manifest_src,'web araştırma ayrı proses')
ck('android:exported="false"' in manifest_src.split('AgentAccessibilityServiceV4')[1],'accessibility service exported false')
ck('android:name=".MainActivityV3"' in manifest_src and 'android:exported="true"' in manifest_src,'launcher MainActivityV3')
ck('android:allowBackup="false"' in manifest_src,'backup kapalı')
ck('android:usesCleartextTraffic="false"' in manifest_src,'cleartext kapalı')
ck('.ModelSetupActivity"' not in manifest_src,'eski ağ model activity manifestten yok')
ck('android.permission.READ_SMS' not in manifest_src,'READ_SMS yok')
ck('android.permission.RECEIVE_SMS' not in manifest_src,'RECEIVE_SMS yok')
ck('android.permission.READ_CONTACTS' not in manifest_src,'READ_CONTACTS yok')
ck('android.permission.CAMERA' not in manifest_src,'CAMERA yok')
ck('android.permission.RECORD_AUDIO' not in manifest_src,'RECORD_AUDIO yok')
ck('android.permission.MANAGE_EXTERNAL_STORAGE' not in manifest_src,'MANAGE_EXTERNAL_STORAGE yok')
ck('android.permission.READ_EXTERNAL_STORAGE' not in manifest_src,'READ_EXTERNAL_STORAGE yok')
ck('android.permission.POST_NOTIFICATIONS' not in manifest_src,'POST_NOTIFICATIONS yok')
ck('android.permission.SYSTEM_ALERT_WINDOW' not in manifest_src,'SYSTEM_ALERT_WINDOW yok')

# 61-80: general runtime and safety
ck(bool(starter),'doğrudan AgentScriptStarter var')
ck('AgentScriptEngineV3.parse' in starter,'starter AGENT/3 parse ediyor')
ck('AgentScriptEngineV2.parse' in starter,'starter AGENT/2 parse ediyor')
ck('AgentScriptEngine.parse' in starter,'starter AGENT/1 parse ediyor')
ck('agent_vm_state_v5' in starter,'starter VM state sıfırlıyor')
ck('agent_loop_state_v5' in starter,'starter loop state sıfırlıyor')
ck('SCRIPT_RUNNING,true' in starter.replace(' ',''),'starter running state açıyor')
ck('AgentScriptStarter.start(s)' in service,'service direct starter kullanıyor')
ck('s.runtime.onEvent(null)' in service,'runtime pump doğrudan tetikleniyor')
ck('BundledModelInstaller.selfTest()' in service,'service bundled installer self-test')
ck('AgentScriptStarter.selfTest()' in service,'service starter self-test')
ck('SafetyPolicy.isBlockedPackage' in service,'hassas uygulama blok kontrolü')
ck('SCRIPT_RUNNING, false' in service,'hassas uygulamada script duruyor')
ck('requestAgentStartFromUi' in mainbase,'20. modül doğrudan bridge çağırıyor')
ck('Metin yerel olarak hazırlandı ve şifreli hafızaya alındı.' not in mainbase,'eski save-only davranış kaldırıldı')
ck('Genel AGENT/1–3' in mainbase,'20. modül genel motor olarak tanımlı')
ck('hideLegacyPlannedCards' in mainv3,'yarım 10–19 kartları görünür değil')
ck('PROTECTED_FINALS' in safety and 'paylas' in safety and 'delete' in safety,'kritik final eylem koruması')
ck('BLOCKED_PACKAGES' in safety and 'com.pozitron.iscep' in safety,'bankacılık paket blok listesi')
ck('otp' in safety.lower() and 'password' in safety.lower(),'OTP/şifre güvenlik terimleri')

# 81-95: AGENT/3, web, XLSX capability
ck('case "if"' in engine,'AGENT if')
ck('case "foreach"' in engine,'AGENT foreach')
ck('case "repeat"' in engine,'AGENT repeat')
ck('case "dataset.filter"' in engine,'AGENT dataset.filter')
ck('case "dataset.sort"' in engine,'AGENT dataset.sort')
ck('case "dataset.dedupe"' in engine,'AGENT dataset.dedupe')
ck('case "dataset.join"' in engine,'AGENT dataset.join')
ck('case "web.search_extract"' in engine,'AGENT web.search_extract')
ck('case "output.xlsx"' in engine,'AGENT output.xlsx')
ck('case "assert"' in engine,'AGENT assert')
ck('case "ui.tap"' in engine and 'case "ui.set_text"' in engine,'AGENT UI tap/set text')
ck('case "web_research"' in runtime and 'launchWebResearch' in runtime,'runtime web research')
ck('case "vm_xlsx"' in runtime and 'SimpleXlsxWriter.write' in runtime,'runtime XLSX output')
ck('"https".equalsIgnoreCase(scheme)' in web,'web yalnız HTTPS')
private_guards=(
    'localhost' in web and
    '127.0.0.1' in web and
    '0.0.0.0' in web and
    '::1' in web and
    'h.matches("10' in web and
    'h.matches("192' in web and
    'h.matches("172' in web and
    'requireAllowed' in web and
    'allowedDomains' in web and
    'hh.endsWith("."+d)' in web
)
ck(private_guards,'web localhost + RFC1918 + allowlist koruması')

# 96-100: actual APK byte-level verification
with zipfile.ZipFile(APK,'r') as z:
    names=set(z.namelist())
    ck('AndroidManifest.xml' in names and any(n.startswith('classes') and n.endswith('.dex') for n in names),'APK manifest + DEX var')
    ck(MODEL in names,'gömülü model APK içinde')
    info=z.getinfo(MODEL)
    ck(info.file_size==MODEL_SIZE and info.compress_type==zipfile.ZIP_STORED,'APK model exact boyut + STORE')
    h=hashlib.sha256()
    with z.open(MODEL) as f:
        for chunk in iter(lambda:f.read(8*1024*1024),b''):h.update(chunk)
    ck(h.hexdigest()==MODEL_SHA,'APK model SHA-256 exact')
    dex=b''.join(z.read(n) for n in sorted(names) if n.startswith('classes') and n.endswith('.dex'))
    man=z.read('AndroidManifest.xml')
    ck(b'Qwen3 0.6B No-Think INT4' in dex and b'AgentScriptStarter' in dex and hasenc(man,'tr.edu.balikesir.yerelajan'),'APK DEX/model/starter/package doğrulandı')

if len(passed)!=100:
    raise SystemExit(f'INTERNAL ERROR: {len(passed)} checks, expected 100')
print('PASS 100/100 — Yerel Ajan 1.0.4 verification gate complete')
