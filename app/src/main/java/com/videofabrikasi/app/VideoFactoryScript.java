package com.videofabrikasi.app;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class VideoFactoryScript {
    private VideoFactoryScript() {}

    public static String build(String idea, String projectId) {
        String safeIdea = Base64.getEncoder().encodeToString((idea == null ? "" : idea).getBytes(StandardCharsets.UTF_8));
        String safeId = Base64.getEncoder().encodeToString((projectId == null ? "" : projectId).getBytes(StandardCharsets.UTF_8));
        return """
import os, sys, json, subprocess, traceback, base64, shutil, wave, gc
from pathlib import Path

PROJECT_ID = base64.b64decode('__PROJECT_ID_B64__').decode('utf-8')
USER_IDEA = base64.b64decode('__USER_IDEA_B64__').decode('utf-8')
LTX_COMMIT = '4b2d053057623ddd4d0a1d3e9cd28890e9ef487f'
MAX_SCENE_ATTEMPTS = 3
WORK = Path('/kaggle/working')
TEMP = Path('/tmp/video-factory')
if TEMP.exists():
    shutil.rmtree(TEMP)
TEMP.mkdir(parents=True)
FINAL = WORK / 'FINAL.mp4'
STATUS = WORK / 'status.json'
SCENES = TEMP / 'scenes'
SCENES.mkdir(exist_ok=True)

def write_status(**kw):
    data = {'project': PROJECT_ID}
    data.update(kw)
    STATUS.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')

write_status(stage='BOOT', engine='LTX-Video 2B distilled 0.9.6 T4-FP16', ai_ok=False)

PROMPTS = [
    \"\"\"Vertical 3D animated short. Two visually identical anthropomorphic white envelope characters with tiny black arms, legs, eyes and mouths. A confident happy envelope runs eagerly toward a red street mailbox. Behind it a frightened sad envelope screams in panic and sprints desperately to catch it. Very strong emotion in the first second, fast physical comedy, clean cinematic lighting, stable character design, no readable text.\"\"\",
    \"\"\"Same two identical white envelope characters at the red mailbox. The frightened envelope catches the confident one and forcefully pushes it from behind through the mail slot. The confident envelope is shocked and tumbles inside, then the frightened envelope falls in immediately after. Clear single action, exaggerated cartoon physics, stable characters, no text.\"\"\",
    \"\"\"Inside a dark metal mailbox, the same two white envelope characters wait together. The confident envelope stands upright with a proud smile, while the other trembles and looks down sadly. A narrow beam of light enters through the slot. Strong readable body language, cinematic close framing, no text.\"\"\",
    \"\"\"A human hand opens the mailbox and lifts the same two envelope characters. The confident happy envelope expects to be chosen first, but the person unexpectedly opens the worried envelope first. The happy envelope reacts with surprise. Clear staging, emotional animation, no readable writing.\"\"\",
    \"\"\"The person reads the worried envelope and their shoulders collapse in grief. The confident happy envelope waits hopefully in the other hand. The person, emotionally overwhelmed, lets the unopened happy envelope fall to the floor. Its confident face freezes and becomes heartbroken. Quiet powerful ending, close-up, no text.\"\"\"
]
PROMPTS[0] += ' Story intent supplied by creator: ' + USER_IDEA

def make_reference(path, scene):
    from PIL import Image, ImageDraw
    W,H = 576,1024
    img = Image.new('RGB',(W,H),(222,232,238))
    d = ImageDraw.Draw(img)
    d.rectangle([0,790,W,H], fill=(182,197,181))
    if scene <= 1:
        d.rounded_rectangle([370,180,535,610], radius=26, fill=(180,38,45), outline=(80,20,25), width=6)
        d.rectangle([388,285,520,325], fill=(55,25,25))
        d.rectangle([442,605,468,910], fill=(85,85,85))
    else:
        d.rectangle([0,0,W,H], fill=(45,48,52))
    def env(x,y,happy):
        d.rounded_rectangle([x,y,x+145,y+98], radius=12, fill=(248,246,238), outline=(65,65,65), width=4)
        d.line([x,y,x+72,y+54,x+145,y], fill=(150,145,135), width=3)
        d.ellipse([x+40,y+34,x+50,y+46], fill=(20,20,20))
        d.ellipse([x+93,y+34,x+103,y+46], fill=(20,20,20))
        if happy: d.arc([x+53,y+42,x+91,y+75], 5, 175, fill=(20,20,20), width=4)
        else: d.arc([x+53,y+56,x+91,y+84], 185, 355, fill=(20,20,20), width=4)
        d.line([x+6,y+61,x-28,y+32], fill=(25,25,25), width=5)
        d.line([x+139,y+61,x+173,y+31], fill=(25,25,25), width=5)
        d.line([x+50,y+96,x+36,y+139], fill=(25,25,25), width=5)
        d.line([x+96,y+96,x+112,y+139], fill=(25,25,25), width=5)
    if scene <= 1:
        env(205,650,True); env(40,700,False)
    else:
        env(195,520,True); env(355,600,False)
    img.save(path)

def build_soundtrack(path, duration):
    import numpy as np
    sr = 44100
    count = max(1, int(sr * duration))
    audio = np.zeros(count, dtype=np.float32)
    rng = np.random.default_rng(12400)

    def put(start, signal, gain=1.0):
        idx = int(max(0.0, start) * sr)
        if idx >= len(audio): return
        end = min(len(audio), idx + len(signal))
        audio[idx:end] += signal[:end-idx] * gain

    def envelope(n, attack=0.03, release=0.12):
        env = np.ones(n, dtype=np.float32)
        a = min(n, max(1, int(sr*attack)))
        r = min(n, max(1, int(sr*release)))
        env[:a] *= np.linspace(0.0, 1.0, a, dtype=np.float32)
        env[-r:] *= np.linspace(1.0, 0.0, r, dtype=np.float32)
        return env

    # First-second cartoon scream: formant-like harmonics + breath noise.
    n = int(sr * 1.20)
    tt = np.arange(n, dtype=np.float32) / sr
    f0 = 390.0 + 120.0*np.sin(2*np.pi*4.2*tt) + 210.0*tt
    phase = 2*np.pi*np.cumsum(f0)/sr
    scream = (0.58*np.sin(phase) + 0.30*np.sin(2*phase) + 0.17*np.sin(3*phase)
              + 0.08*rng.normal(0,1,n).astype(np.float32))
    scream *= envelope(n, 0.015, 0.18)
    put(0.04, scream, 0.72)

    n = int(sr * 0.55)
    tt = np.arange(n, dtype=np.float32) / sr
    whoosh_noise = rng.normal(0,1,n).astype(np.float32)
    whoosh = np.concatenate(([0.0], np.diff(whoosh_noise)))
    whoosh *= np.sin(np.pi*np.clip(tt/0.55,0,1))**2
    put(1.05, whoosh, 0.13)

    n = int(sr * 0.38)
    tt = np.arange(n, dtype=np.float32) / sr
    impact = (np.sin(2*np.pi*72*tt) + 0.45*rng.normal(0,1,n).astype(np.float32)) * np.exp(-tt*15.0)
    put(2.00, impact, 0.42)

    n = int(sr * 0.50)
    tt = np.arange(n, dtype=np.float32) / sr
    metal = (np.sin(2*np.pi*1180*tt) + 0.55*np.sin(2*np.pi*1830*tt)) * np.exp(-tt*8.5)
    put(6.15, metal, 0.18)

    n = int(sr * 2.30)
    tt = np.arange(n, dtype=np.float32) / sr
    drone = (np.sin(2*np.pi*146.8*tt) + 0.42*np.sin(2*np.pi*174.6*tt)) * envelope(n,0.35,0.70)
    put(7.65, drone, 0.10)

    n = int(sr * 0.75)
    paper_noise = rng.normal(0,1,n).astype(np.float32)
    paper = np.concatenate(([0.0], np.diff(paper_noise))) * envelope(n,0.02,0.35)
    put(9.20, paper, 0.09)

    peak = float(np.max(np.abs(audio))) if len(audio) else 1.0
    if peak > 0.94:
        audio *= 0.94 / peak
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype(np.int16)
    with wave.open(str(path), 'wb') as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(pcm.tobytes())

def probe_media(path):
    raw = subprocess.check_output([
        'ffprobe','-v','error','-show_entries','stream=codec_type,width,height:format=duration','-of','json',str(path)
    ], text=True)
    return json.loads(raw)

def validate_scene_media(path):
    if not path.is_file() or path.stat().st_size < 10000:
        raise RuntimeError('Scene MP4 is unexpectedly small or missing')
    info = probe_media(path)
    streams = info.get('streams', [])
    video = next((s for s in streams if s.get('codec_type') == 'video'), None)
    if video is None or int(video.get('width',0)) != 448 or int(video.get('height',0)) != 800:
        raise RuntimeError('Scene video stream is missing or not 448x800')
    duration = float(info.get('format', {}).get('duration', '0') or '0')
    if duration < 1.5:
        raise RuntimeError(f'Scene duration is too short: {duration:.2f}s')

def validate_final_media(path):
    if not path.is_file() or path.stat().st_size < 100000:
        raise RuntimeError('Final MP4 is unexpectedly small or missing')
    info = probe_media(path)
    streams = info.get('streams', [])
    video = next((s for s in streams if s.get('codec_type') == 'video'), None)
    audio = next((s for s in streams if s.get('codec_type') == 'audio'), None)
    if video is None or int(video.get('width',0)) != 1080 or int(video.get('height',0)) != 1920:
        raise RuntimeError('Final video stream is missing or not 1080x1920')
    if audio is None:
        raise RuntimeError('Final audio stream is missing')
    duration = float(info.get('format', {}).get('duration', '0') or '0')
    if duration < 8.0:
        raise RuntimeError(f'Final video duration is too short: {duration:.2f}s')

def fallback_video():
    from PIL import Image, ImageDraw
    import imageio.v2 as imageio
    clips=[]
    for i in range(5):
        frames=[]
        for f in range(48):
            img=Image.new('RGB',(448,800),(32+i*8,36+i*6,42+i*4))
            d=ImageDraw.Draw(img)
            x=30 + int((f/47)*240); y=290 + i*34
            d.rounded_rectangle([x,y,x+135,y+88], radius=12, fill=(245,243,235), outline=(220,220,220), width=3)
            d.ellipse([x+36,y+34,x+46,y+44], fill=(20,20,20))
            d.ellipse([x+88,y+34,x+98,y+44], fill=(20,20,20))
            d.text((20,30), f'SAHNE {i+1}/5', fill=(255,255,255))
            frames.append(img)
        out=SCENES/f'fallback_{i+1}.mp4'
        imageio.mimsave(out, frames, fps=24, codec='libx264', quality=7)
        clips.append(out)
    concat=TEMP/'concat_fallback.txt'
    concat.write_text(''.join(\"file '%s'\\n\"%p for p in clips))
    subprocess.check_call(['ffmpeg','-y','-f','concat','-safe','0','-i',str(concat),'-vf','scale=1080:1920:flags=lanczos','-c:v','libx264','-pix_fmt','yuv420p','-movflags','+faststart',str(FINAL)])

def patch_ltx_for_t4_fp16(repo):
    inference_file = repo/'ltx_video'/'inference.py'
    src = inference_file.read_text(encoding='utf-8')
    transformer_old = '''    elif precision == "bfloat16":
        return Transformer3DModel.from_pretrained(ckpt_path).to(torch.bfloat16)
    else:
        return Transformer3DModel.from_pretrained(ckpt_path)
'''
    transformer_new = '''    elif precision == "bfloat16":
        return Transformer3DModel.from_pretrained(ckpt_path).to(torch.bfloat16)
    elif precision == "float16":
        return Transformer3DModel.from_pretrained(ckpt_path).to(torch.float16)
    else:
        return Transformer3DModel.from_pretrained(ckpt_path)
'''
    cast_old = '''    vae = vae.to(torch.bfloat16)
    text_encoder = text_encoder.to(torch.bfloat16)
'''
    cast_new = '''    compute_dtype = torch.float16 if precision == "float16" else torch.bfloat16
    vae = vae.to(compute_dtype)
    text_encoder = text_encoder.to(compute_dtype)
'''
    if transformer_old not in src:
        raise RuntimeError('Pinned LTX transformer precision block changed unexpectedly')
    if cast_old not in src:
        raise RuntimeError('Pinned LTX VAE/text encoder precision block changed unexpectedly')
    src = src.replace(transformer_old, transformer_new, 1).replace(cast_old, cast_new, 1)
    inference_file.write_text(src, encoding='utf-8')

def gpu_preflight():
    import torch
    if not torch.cuda.is_available():
        raise RuntimeError('Kaggle GPU is not available')
    gpu_name = torch.cuda.get_device_name(0)
    total_gb = torch.cuda.get_device_properties(0).total_memory / (1024**3)
    if total_gb < 12:
        raise RuntimeError(f'GPU VRAM is too small: {total_gb:.1f} GiB')
    probe = torch.ones((64,64), device='cuda', dtype=torch.float16)
    probe_result = (probe @ probe).sum().item()
    if probe_result <= 0:
        raise RuntimeError('FP16 CUDA preflight returned an invalid result')
    del probe
    torch.cuda.empty_cache()
    return gpu_name, round(total_gb, 1), torch.__version__

try:
    repo = TEMP/'LTX-Video'
    subprocess.check_call(['git','clone','--filter=blob:none','https://github.com/Lightricks/LTX-Video.git',str(repo)])
    subprocess.check_call(['git','-C',str(repo),'fetch','--depth','1','origin',LTX_COMMIT])
    subprocess.check_call(['git','-C',str(repo),'checkout','--detach','FETCH_HEAD'])
    patch_ltx_for_t4_fp16(repo)
    subprocess.check_call([sys.executable,'-m','pip','install','-q','transformers==4.49.0','diffusers==0.33.1'])
    subprocess.check_call([sys.executable,'-m','pip','install','-q','-e',str(repo)+'[inference]'])
    gpu_name, gpu_vram_gb, torch_version = gpu_preflight()
    write_status(stage='GPU_READY', engine='LTX-Video 2B distilled 0.9.6 T4-FP16', ai_ok=False,
                 gpu=gpu_name, vram_gb=gpu_vram_gb, torch=torch_version, dtype='float16')
    sys.path.insert(0,str(repo))
    import torch
    import yaml
    import ltx_video.inference as ltx_inference
    InferenceConfig = ltx_inference.InferenceConfig

    # The upstream infer() constructs a new pipeline every call. Cache that factory so
    # successful scenes reuse the loaded model; a failed attempt explicitly resets it.
    _original_create_pipeline = ltx_inference.create_ltx_video_pipeline
    _pipeline_cache = {}
    def cached_create_ltx_video_pipeline(*args, **kwargs):
        if 'pipeline' not in _pipeline_cache:
            _pipeline_cache['pipeline'] = _original_create_pipeline(*args, **kwargs)
        return _pipeline_cache['pipeline']
    ltx_inference.create_ltx_video_pipeline = cached_create_ltx_video_pipeline

    def reset_pipeline_cache():
        _pipeline_cache.clear()
        gc.collect()
        torch.cuda.empty_cache()

    base_cfg = repo/'configs/ltxv-2b-0.9.6-distilled.yaml'
    custom_cfg = TEMP/'ltx_t4_config.yaml'
    cfg_data = yaml.safe_load(base_cfg.read_text(encoding='utf-8'))
    cfg_data['precision'] = 'float16'
    cfg_data['prompt_enhancement_words_threshold'] = 0
    custom_cfg.write_text(yaml.safe_dump(cfg_data, sort_keys=False), encoding='utf-8')

    generated=[]
    for i,prompt in enumerate(PROMPTS):
        ref=SCENES/f'ref_{i+1}.png'
        make_reference(ref,i)
        out=SCENES/f'scene_{i+1}.mp4'
        scene_dir=SCENES/f'generated_{i+1}'
        scene_ready=False
        last_scene_error=None

        for attempt in range(1, MAX_SCENE_ATTEMPTS + 1):
            if scene_dir.exists(): shutil.rmtree(scene_dir)
            scene_dir.mkdir(parents=True)
            write_status(stage=f'GENERATING_{i+1}_OF_5_ATTEMPT_{attempt}',
                         engine='LTX-Video 2B distilled 0.9.6 T4-FP16', ai_ok=False,
                         gpu=gpu_name, dtype='float16', scene=i+1, attempt=attempt)
            try:
                cfg=InferenceConfig(
                    pipeline_config=str(custom_cfg),
                    prompt=prompt, height=800, width=448, num_frames=49, frame_rate=24,
                    seed=12400+(i*10)+attempt, output_path=str(scene_dir), offload_to_cpu=True,
                    conditioning_media_paths=[str(ref)], conditioning_start_frames=[0],
                    conditioning_strengths=[1.0],
                )
                ltx_inference.infer(config=cfg)
                candidates=sorted(scene_dir.glob('*.mp4'), key=lambda p:p.stat().st_mtime, reverse=True)
                if not candidates:
                    raise RuntimeError(f'Scene {i+1} produced no MP4')
                shutil.copy2(candidates[0], out)
                validate_scene_media(out)
                scene_ready=True
                last_scene_error=None
                break
            except Exception as scene_error:
                last_scene_error=scene_error
                write_status(stage=f'SCENE_{i+1}_ATTEMPT_{attempt}_FAILED',
                             engine='LTX-Video 2B distilled 0.9.6 T4-FP16', ai_ok=False,
                             gpu=gpu_name, dtype='float16', scene=i+1, attempt=attempt,
                             error=str(scene_error))
                reset_pipeline_cache()

        if not scene_ready:
            raise RuntimeError(f'Scene {i+1} failed after {MAX_SCENE_ATTEMPTS} attempts: {last_scene_error}')
        generated.append(out)
        write_status(stage=f'SCENE_{i+1}_READY', engine='LTX-Video 2B distilled 0.9.6 T4-FP16', ai_ok=False,
                     scene=i+1, scene_file=out.name, gpu=gpu_name, dtype='float16')

    concat=TEMP/'concat.txt'
    concat.write_text(''.join(\"file '%s'\\n\"%p for p in generated))
    video_only=TEMP/'final_video_only.mp4'
    subprocess.check_call(['ffmpeg','-y','-f','concat','-safe','0','-i',str(concat),
        '-vf','scale=1080:1920:flags=lanczos','-c:v','libx264','-preset','medium','-crf','19',
        '-pix_fmt','yuv420p','-movflags','+faststart',str(video_only)])
    audio_path=TEMP/'soundtrack.wav'
    build_soundtrack(audio_path, 10.4)
    subprocess.check_call(['ffmpeg','-y','-i',str(video_only),'-i',str(audio_path),
        '-c:v','copy','-c:a','aac','-b:a','160k','-shortest','-movflags','+faststart',str(FINAL)])
    validate_final_media(FINAL)
    write_status(stage='COMPLETE', engine='LTX-Video 2B distilled 0.9.6 T4-FP16', ai_ok=True,
                 final='FINAL.mp4', scenes=5, gpu=gpu_name, dtype='float16', audio='procedural_sfx_aac')
except Exception as e:
    (WORK/'ai_error.txt').write_text(traceback.format_exc(),encoding='utf-8')
    write_status(stage='AI_FAILED_FALLBACK', engine='fallback renderer', ai_ok=False, error=str(e))
    fallback_video()
    write_status(stage='COMPLETE_FALLBACK', engine='fallback renderer', ai_ok=False, final='FINAL.mp4', error=str(e))

print('VIDEO_FACTORY_DONE', FINAL, FINAL.exists(), FINAL.stat().st_size if FINAL.exists() else 0)
""".replace("__PROJECT_ID_B64__", safeId).replace("__USER_IDEA_B64__", safeIdea);
    }
}
