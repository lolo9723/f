package com.videofabrikasi.app;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class VideoFactoryScriptV2 {
    private VideoFactoryScriptV2() {}

    static String build(String idea, String projectId) {
        String safeIdea = Base64.getEncoder().encodeToString((idea == null ? "" : idea).getBytes(StandardCharsets.UTF_8));
        String safeId = Base64.getEncoder().encodeToString((projectId == null ? "" : projectId).getBytes(StandardCharsets.UTF_8));
        return """
import os, sys, json, subprocess, traceback, base64, shutil, wave, gc, tarfile, urllib.request, time, socket
from pathlib import Path

PROJECT_ID = base64.b64decode('__PROJECT_ID_B64__').decode('utf-8')
USER_IDEA = base64.b64decode('__USER_IDEA_B64__').decode('utf-8').strip()
LTX_COMMIT = '4b2d053057623ddd4d0a1d3e9cd28890e9ef487f'
MAX_SCENE_ATTEMPTS = 3
CONTINUITY_STRENGTH = 0.65
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

write_status(stage='BOOT', engine='LTX-Video 2B distilled 0.9.6 T4-FP16 story-v2', ai_ok=False)

SCENE_ROLES = [
    (
        'HOOK',
        'Start with the most emotionally explosive visible action from the creator story immediately. '
        'The first half-second must contain strong readable emotion or urgent physical motion. '
        'Do not explain first. Show one dominant action only.'
    ),
    (
        'ESCALATION',
        'Continue the exact same visual world and advance to the next causal action in the creator story. '
        'Escalate the conflict or urgency. Show one dominant action only.'
    ),
    (
        'TURNING_POINT',
        'Continue the exact same visual world and show the clearest reversal, behavioral clue, choice, or reveal setup '
        'that changes how the viewer understands the story. Show one dominant action only.'
    ),
    (
        'CONSEQUENCE',
        'Continue the exact same visual world and show the immediate consequence or emotionally important reaction '
        'caused by the turning point. Show one dominant action only.'
    ),
    (
        'PAYOFF',
        'Continue the exact same visual world and deliver the creator story ending or reveal. '
        'Finish on a strong readable emotional pose or image that makes the earlier behavior make sense. '
        'Show one dominant action only.'
    ),
]

STYLE_RULES = (
    'Vertical 9:16 cinematic animated short, clean stylized 3D, expressive body language, clear silhouettes, '
    'stable character and object design, stable colors and proportions, natural cinematic lighting, '
    'no subtitles, no captions, no logos, no readable text. '
    'Use only characters, objects, setting and events supported by the creator story; infer missing details minimally. '
    'Do not invent a different premise. If the story contains an unseen trait or secret, hint at it through behavior before the reveal.'
)

def build_scene_prompts(story):
    story = (story or '').strip()
    if len(story) < 20:
        raise ValueError('Creator story is too short for five-scene generation')
    prompts = []
    for index, (role, direction) in enumerate(SCENE_ROLES, start=1):
        continuity = (
            'This is the opening shot; establish the recurring subjects clearly.'
            if index == 1 else
            'Continue directly from the supplied previous-scene final frame. Preserve the same subjects, faces, shapes, clothing, '
            'materials, scale, environment, camera-side continuity and visual style unless the creator story explicitly requires a change.'
        )
        prompts.append(
            f'{STYLE_RULES} Creator story: {story} '
            f'Scene {index}/5 role: {role}. {direction} {continuity}'
        )
    return prompts

PROMPTS = build_scene_prompts(USER_IDEA)

def extract_last_frame(video_path, image_path):
    if image_path.exists():
        image_path.unlink()
    subprocess.check_call([
        'ffmpeg','-y','-sseof','-0.08','-i',str(video_path),
        '-frames:v','1','-vf','scale=448:800:flags=lanczos',str(image_path)
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not image_path.is_file() or image_path.stat().st_size < 1000:
        raise RuntimeError('Continuity frame extraction failed')

def build_soundtrack(path, duration):
    import numpy as np
    sr = 44100
    count = max(1, int(sr * duration))
    audio = np.zeros(count, dtype=np.float32)
    rng = np.random.default_rng(12400)

    def put(start, signal, gain=1.0):
        idx = int(max(0.0, start) * sr)
        if idx >= len(audio):
            return
        end = min(len(audio), idx + len(signal))
        audio[idx:end] += signal[:end-idx] * gain

    def env(n, attack=0.03, release=0.12):
        e = np.ones(n, dtype=np.float32)
        a = min(n, max(1, int(sr*attack)))
        r = min(n, max(1, int(sr*release)))
        e[:a] *= np.linspace(0.0, 1.0, a, dtype=np.float32)
        e[-r:] *= np.linspace(1.0, 0.0, r, dtype=np.float32)
        return e

    # High-arousal opening vocal-like scream.
    n = int(sr * min(1.15, duration))
    tt = np.arange(n, dtype=np.float32) / sr
    f0 = 380.0 + 125.0*np.sin(2*np.pi*4.1*tt) + 180.0*tt
    phase = 2*np.pi*np.cumsum(f0)/sr
    scream = (
        0.56*np.sin(phase) + 0.28*np.sin(2*phase) + 0.15*np.sin(3*phase)
        + 0.07*rng.normal(0,1,n).astype(np.float32)
    ) * env(n, 0.015, 0.16)
    put(0.03, scream, 0.70)

    # Generic movement whoosh.
    n = int(sr * 0.55)
    tt = np.arange(n, dtype=np.float32) / sr
    noise = rng.normal(0,1,n).astype(np.float32)
    whoosh = np.concatenate(([0.0], np.diff(noise)))
    whoosh *= np.sin(np.pi*np.clip(tt/0.55,0,1))**2
    put(0.82, whoosh, 0.12)

    # Scene-boundary impacts keep pace without assuming any specific object.
    for hit_time in (1.85, 3.90, 5.95, 8.00):
        if hit_time >= duration:
            continue
        n = int(sr * 0.32)
        tt = np.arange(n, dtype=np.float32) / sr
        impact = (
            np.sin(2*np.pi*70*tt) + 0.38*rng.normal(0,1,n).astype(np.float32)
        ) * np.exp(-tt*16.0)
        put(hit_time, impact, 0.32)

    # Low tension bed through the middle.
    drone_duration = max(0.0, min(duration - 2.0, 6.2))
    if drone_duration > 0.2:
        n = int(sr * drone_duration)
        tt = np.arange(n, dtype=np.float32) / sr
        drone = (
            np.sin(2*np.pi*146.8*tt) + 0.42*np.sin(2*np.pi*174.6*tt)
        ) * env(n,0.35,0.70)
        put(2.0, drone, 0.075)

    # Final emotional sting.
    ending = max(0.0, duration - 1.35)
    n = int(sr * min(1.10, duration))
    tt = np.arange(n, dtype=np.float32) / sr
    sting = (
        np.sin(2*np.pi*196.0*tt) + 0.36*np.sin(2*np.pi*293.7*tt)
    ) * np.exp(-tt*2.8)
    put(ending, sting, 0.12)

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
        'ffprobe','-v','error',
        '-show_entries','stream=codec_name,codec_type,width,height:format=duration',
        '-of','json',str(path)
    ], text=True)
    return json.loads(raw)

def media_duration(path):
    return float(probe_media(path).get('format', {}).get('duration', '0') or '0')

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
    if video.get('codec_name') != 'h264':
        raise RuntimeError("Final video codec is not H.264: " + str(video.get('codec_name')))
    if audio is None:
        raise RuntimeError('Final audio stream is missing')
    if audio.get('codec_name') != 'aac':
        raise RuntimeError("Final audio codec is not AAC: " + str(audio.get('codec_name')))
    duration = float(info.get('format', {}).get('duration', '0') or '0')
    if duration < 8.0:
        raise RuntimeError(f'Final video duration is too short: {duration:.2f}s')

def fallback_video():
    from PIL import Image, ImageDraw
    import imageio.v2 as imageio
    import numpy as np
    clips=[]
    for i in range(5):
        frames=[]
        for f in range(48):
            img=Image.new('RGB',(448,800),(34+i*7,38+i*5,44+i*4))
            d=ImageDraw.Draw(img)
            progress = f/47
            radius = 32 + int(42*progress)
            cx = 80 + int(progress*280)
            cy = 390 + int(28*np.sin(progress*np.pi*2))
            d.ellipse([cx-radius,cy-radius,cx+radius,cy+radius], outline=(235,235,235), width=5)
            d.text((20,30), f'FALLBACK {i+1}/5', fill=(255,255,255))
            frames.append(img)
        out=SCENES/f'fallback_{i+1}.mp4'
        imageio.mimsave(out, frames, fps=24, codec='libx264', quality=7)
        clips.append(out)
    concat=TEMP/'concat_fallback.txt'
    concat.write_text(''.join("file '%s'\\n"%p for p in clips))
    subprocess.check_call([
        'ffmpeg','-y','-f','concat','-safe','0','-i',str(concat),
        '-vf','scale=1080:1920:flags=lanczos','-c:v','libx264','-pix_fmt','yuv420p',
        '-movflags','+faststart',str(FINAL)
    ])

class InternetUnavailableError(RuntimeError):
    pass

def external_internet_preflight():
    hosts = ('github.com', 'huggingface.co', 'pypi.org')
    failures = []
    for host in hosts:
        try:
            answers = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
            if not answers:
                raise OSError('no DNS answers')
        except OSError as e:
            failures.append(f'{host}: {e}')
    if failures:
        raise InternetUnavailableError(
            'Kaggle external Internet/DNS is unavailable. '
            'Enable Internet access for the Kaggle account/session, then retry. '
            + ' | '.join(failures)
        )

def materialize_ltx_source(repo):
    archive = TEMP/'ltx-source.tar.gz'
    unpack_root = TEMP/'ltx-source-unpacked'
    urls = [
        f'https://codeload.github.com/Lightricks/LTX-Video/tar.gz/{LTX_COMMIT}',
        f'https://github.com/Lightricks/LTX-Video/archive/{LTX_COMMIT}.tar.gz',
    ]
    errors=[]

    def clean_attempt():
        if archive.exists():
            archive.unlink()
        if unpack_root.exists():
            shutil.rmtree(unpack_root)
        if repo.exists():
            shutil.rmtree(repo)

    for attempt in range(1, 4):
        for url in urls:
            clean_attempt()
            try:
                req = urllib.request.Request(
                    url,
                    headers={
                        'User-Agent':'VideoFabrikasi-Kaggle/1.0',
                        'Accept':'application/octet-stream',
                    },
                )
                with urllib.request.urlopen(req, timeout=120) as response, open(archive, 'wb') as out:
                    shutil.copyfileobj(response, out)
                if not archive.is_file() or archive.stat().st_size < 100000:
                    raise RuntimeError(
                        f'Pinned LTX archive is unexpectedly small: '
                        f'{archive.stat().st_size if archive.exists() else 0} bytes'
                    )

                unpack_root.mkdir(parents=True)
                with tarfile.open(archive, 'r:gz') as tf:
                    members = tf.getmembers()
                    if not members:
                        raise RuntimeError('Pinned LTX archive is empty')
                    for member in members:
                        p = Path(member.name)
                        if p.is_absolute() or '..' in p.parts:
                            raise RuntimeError('Unsafe path in pinned LTX archive: ' + member.name)
                    tf.extractall(unpack_root)

                candidates = [
                    p for p in unpack_root.iterdir()
                    if p.is_dir()
                    and (p/'ltx_video'/'inference.py').is_file()
                    and (p/'configs'/'ltxv-2b-0.9.6-distilled.yaml').is_file()
                ]
                if len(candidates) != 1:
                    raise RuntimeError(
                        f'Pinned LTX archive layout unexpected: {len(candidates)} source roots'
                    )
                shutil.move(str(candidates[0]), str(repo))
                if not (repo/'ltx_video'/'inference.py').is_file():
                    raise RuntimeError('Pinned LTX source verification failed')
                return repo
            except Exception as e:
                errors.append(f'archive attempt {attempt} {url}: {type(e).__name__}: {e}')
                time.sleep(min(6, attempt * 2))

    # Last-resort GitHub path: force HTTP/1.1 and fetch only the exact pinned commit.
    clean_attempt()
    try:
        subprocess.check_call(['git','init',str(repo)])
        subprocess.check_call([
            'git','-C',str(repo),'-c','http.version=HTTP/1.1',
            'fetch','--depth','1','https://github.com/Lightricks/LTX-Video.git',LTX_COMMIT
        ])
        subprocess.check_call(['git','-C',str(repo),'checkout','--detach','FETCH_HEAD'])
        if not (repo/'ltx_video'/'inference.py').is_file():
            raise RuntimeError('Git fallback source verification failed')
        return repo
    except Exception as e:
        errors.append(f'git HTTP/1.1 fallback: {type(e).__name__}: {e}')
        raise RuntimeError(
            'Pinned LTX source could not be materialized. ' + ' | '.join(errors[-5:])
        ) from e

def patch_ltx_for_t4_fp16(repo):
    inference_file = repo/'ltx_video'/'inference.py'
    pipeline_file = repo/'ltx_video'/'pipelines'/'pipeline_ltx_video.py'
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
    placement_old = '''    transformer = transformer.to(device)
    vae = vae.to(device)
    text_encoder = text_encoder.to(device)
'''
    placement_new = '''    low_vram_t4 = device == "cuda" and get_total_gpu_memory() < 24
    if low_vram_t4:
        # T4-safe bootstrap: do not materialize the 2B transformer, T5-XXL text
        # encoder and VAE on the GPU at the same time before offload is active.
        transformer = transformer.to("cpu")
        vae = vae.to("cpu")
        text_encoder = text_encoder.to("cpu")
    else:
        transformer = transformer.to(device)
        vae = vae.to(device)
        text_encoder = text_encoder.to(device)
'''
    cast_old = '''    vae = vae.to(torch.bfloat16)
    text_encoder = text_encoder.to(torch.bfloat16)
'''
    cast_new = '''    compute_dtype = torch.float16 if precision == "float16" else torch.bfloat16
    vae = vae.to(compute_dtype)
    text_encoder = text_encoder.to(compute_dtype)
'''
    pipeline_old = '''    pipeline = LTXVideoPipeline(**submodel_dict)
    pipeline = pipeline.to(device)
    return pipeline
'''
    pipeline_new = '''    pipeline = LTXVideoPipeline(**submodel_dict)
    if low_vram_t4:
        # Diffusers' model CPU offload keeps only the active whole model on T4.
        # The pipeline's model_cpu_offload_seq is text_encoder->transformer->vae.
        pipeline.enable_model_cpu_offload(gpu_id=0, device=device)
    else:
        pipeline = pipeline.to(device)
    return pipeline
'''
    if transformer_old not in src:
        raise RuntimeError('Pinned LTX transformer precision block changed unexpectedly')
    if placement_old not in src:
        raise RuntimeError('Pinned LTX initial device placement block changed unexpectedly')
    if cast_old not in src:
        raise RuntimeError('Pinned LTX VAE/text encoder precision block changed unexpectedly')
    if pipeline_old not in src:
        raise RuntimeError('Pinned LTX pipeline device block changed unexpectedly')
    src = (
        src.replace(transformer_old, transformer_new, 1)
           .replace(placement_old, placement_new, 1)
           .replace(cast_old, cast_new, 1)
           .replace(pipeline_old, pipeline_new, 1)
    )
    inference_file.write_text(src, encoding='utf-8')

    pipeline_src = pipeline_file.read_text(encoding='utf-8')
    text_offload_old = '''        if offload_to_cpu and self.text_encoder is not None:
            self.text_encoder = self.text_encoder.cpu()

        self.transformer = self.transformer.to(self._execution_device)
'''
    text_offload_new = '''        if offload_to_cpu and self.text_encoder is not None:
            self.text_encoder = self.text_encoder.cpu()
            if self._execution_device == "cuda":
                torch.cuda.empty_cache()

        self.transformer = self.transformer.to(self._execution_device)
'''
    if text_offload_old not in pipeline_src:
        raise RuntimeError('Pinned LTX text-encoder offload block changed unexpectedly')
    pipeline_src = pipeline_src.replace(text_offload_old, text_offload_new, 1)
    pipeline_file.write_text(pipeline_src, encoding='utf-8')

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
    external_internet_preflight()
    write_status(
        stage='INTERNET_READY',
        engine='LTX-Video 2B distilled 0.9.6 T4-FP16 story-v2',
        ai_ok=False
    )
    repo = materialize_ltx_source(TEMP/'LTX-Video')
    patch_ltx_for_t4_fp16(repo)
    subprocess.check_call([sys.executable,'-m','pip','install','-q','transformers==4.49.0','diffusers==0.33.1','accelerate==1.6.0'])
    subprocess.check_call([sys.executable,'-m','pip','install','-q','-e',str(repo)+'[inference]'])

    gpu_name, gpu_vram_gb, torch_version = gpu_preflight()
    write_status(
        stage='GPU_READY', engine='LTX-Video 2B distilled 0.9.6 T4-FP16 story-v2', ai_ok=False,
        gpu=gpu_name, vram_gb=gpu_vram_gb, torch=torch_version, dtype='float16'
    )

    sys.path.insert(0,str(repo))
    import torch
    import yaml
    import ltx_video.inference as ltx_inference
    InferenceConfig = ltx_inference.InferenceConfig

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
    continuity_frame=None

    for i,prompt in enumerate(PROMPTS):
        out=SCENES/f'scene_{i+1}.mp4'
        scene_dir=SCENES/f'generated_{i+1}'
        scene_ready=False
        last_scene_error=None

        for attempt in range(1, MAX_SCENE_ATTEMPTS + 1):
            if scene_dir.exists():
                shutil.rmtree(scene_dir)
            scene_dir.mkdir(parents=True)
            write_status(
                stage=f'GENERATING_{i+1}_OF_5_ATTEMPT_{attempt}',
                engine='LTX-Video 2B distilled 0.9.6 T4-FP16 story-v2',
                ai_ok=False, gpu=gpu_name, dtype='float16',
                scene=i+1, scene_role=SCENE_ROLES[i][0], attempt=attempt,
                continuity='text_to_video' if i == 0 else 'previous_scene_last_frame'
            )
            try:
                cfg_kwargs = dict(
                    pipeline_config=str(custom_cfg),
                    prompt=prompt, height=800, width=448, num_frames=49, frame_rate=24,
                    seed=12400+(i*10)+attempt, output_path=str(scene_dir), offload_to_cpu=True,
                )
                if i > 0:
                    if continuity_frame is None or not continuity_frame.is_file():
                        raise RuntimeError('Previous-scene continuity frame is missing')
                    cfg_kwargs.update(
                        conditioning_media_paths=[str(continuity_frame)],
                        conditioning_start_frames=[0],
                        conditioning_strengths=[CONTINUITY_STRENGTH],
                    )

                cfg=InferenceConfig(**cfg_kwargs)
                ltx_inference.infer(config=cfg)
                candidates=sorted(
                    scene_dir.glob('*.mp4'),
                    key=lambda p:p.stat().st_mtime,
                    reverse=True
                )
                if not candidates:
                    raise RuntimeError(f'Scene {i+1} produced no MP4')
                shutil.copy2(candidates[0], out)
                validate_scene_media(out)

                next_frame=SCENES/f'continuity_{i+1}.png'
                extract_last_frame(out, next_frame)
                continuity_frame=next_frame

                scene_ready=True
                last_scene_error=None
                break
            except Exception as scene_error:
                last_scene_error=scene_error
                write_status(
                    stage=f'SCENE_{i+1}_ATTEMPT_{attempt}_FAILED',
                    engine='LTX-Video 2B distilled 0.9.6 T4-FP16 story-v2',
                    ai_ok=False, gpu=gpu_name, dtype='float16',
                    scene=i+1, scene_role=SCENE_ROLES[i][0], attempt=attempt,
                    error=str(scene_error)
                )
                reset_pipeline_cache()

        if not scene_ready:
            raise RuntimeError(
                f'Scene {i+1} failed after {MAX_SCENE_ATTEMPTS} attempts: {last_scene_error}'
            )
        generated.append(out)
        write_status(
            stage=f'SCENE_{i+1}_READY',
            engine='LTX-Video 2B distilled 0.9.6 T4-FP16 story-v2',
            ai_ok=False, scene=i+1, scene_role=SCENE_ROLES[i][0],
            scene_file=out.name, gpu=gpu_name, dtype='float16',
            continuity_frame=continuity_frame.name
        )

    concat=TEMP/'concat.txt'
    concat.write_text(''.join("file '%s'\\n"%p for p in generated))
    video_only=TEMP/'final_video_only.mp4'
    subprocess.check_call([
        'ffmpeg','-y','-f','concat','-safe','0','-i',str(concat),
        '-vf','scale=1080:1920:flags=lanczos',
        '-c:v','libx264','-preset','medium','-crf','19',
        '-pix_fmt','yuv420p','-movflags','+faststart',str(video_only)
    ])
    soundtrack_duration = max(8.0, media_duration(video_only))
    audio_path=TEMP/'soundtrack.wav'
    build_soundtrack(audio_path, soundtrack_duration)
    subprocess.check_call([
        'ffmpeg','-y','-i',str(video_only),'-i',str(audio_path),
        '-c:v','copy','-c:a','aac','-b:a','160k',
        '-shortest','-movflags','+faststart',str(FINAL)
    ])
    validate_final_media(FINAL)
    write_status(
        stage='COMPLETE',
        engine='LTX-Video 2B distilled 0.9.6 T4-FP16 story-v2',
        ai_ok=True, final='FINAL.mp4', scenes=5,
        scene_roles=[r[0] for r in SCENE_ROLES],
        continuity='previous_scene_last_frame',
        continuity_strength=CONTINUITY_STRENGTH,
        gpu=gpu_name, dtype='float16', audio='procedural_generic_emotion_sfx_aac'
    )
except InternetUnavailableError as e:
    (WORK/'ai_error.txt').write_text(traceback.format_exc(),encoding='utf-8')
    write_status(
        stage='INTERNET_REQUIRED',
        engine='LTX-Video 2B distilled 0.9.6 T4-FP16 story-v2',
        ai_ok=False,
        error=str(e)
    )
    print('VIDEO_FACTORY_INTERNET_REQUIRED', str(e))
except Exception as e:
    (WORK/'ai_error.txt').write_text(traceback.format_exc(),encoding='utf-8')
    write_status(stage='AI_FAILED_FALLBACK', engine='fallback renderer', ai_ok=False, error=str(e))
    fallback_video()
    write_status(
        stage='COMPLETE_FALLBACK', engine='fallback renderer',
        ai_ok=False, final='FINAL.mp4', error=str(e)
    )

print('VIDEO_FACTORY_DONE', FINAL, FINAL.exists(), FINAL.stat().st_size if FINAL.exists() else 0)
""".replace("__PROJECT_ID_B64__", safeId).replace("__USER_IDEA_B64__", safeIdea);
    }
}
