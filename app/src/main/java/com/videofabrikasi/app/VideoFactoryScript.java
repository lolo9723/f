package com.videofabrikasi.app;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class VideoFactoryScript {
    private VideoFactoryScript() {}

    public static String build(String idea, String projectId) {
        String safeIdea = Base64.getEncoder().encodeToString((idea == null ? "" : idea).getBytes(StandardCharsets.UTF_8));
        String safeId = Base64.getEncoder().encodeToString((projectId == null ? "" : projectId).getBytes(StandardCharsets.UTF_8));
        return """
import os, sys, json, subprocess, traceback, base64
from pathlib import Path

PROJECT_ID = base64.b64decode('__PROJECT_ID_B64__').decode('utf-8')
USER_IDEA = base64.b64decode('__USER_IDEA_B64__').decode('utf-8')
WORK = Path('/kaggle/working')
FINAL = WORK / 'FINAL.mp4'
STATUS = WORK / 'status.json'
SCENES = WORK / 'scenes'
SCENES.mkdir(exist_ok=True)

def write_status(**kw):
    data = {'project': PROJECT_ID}
    data.update(kw)
    STATUS.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')

write_status(stage='BOOT', engine='LTX-Video 2B distilled', ai_ok=False)

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

def fallback_video():
    from PIL import Image, ImageDraw
    import imageio.v2 as imageio
    clips=[]
    for i in range(5):
        frames=[]
        for f in range(72):
            img=Image.new('RGB',(576,1024),(32+i*8,36+i*6,42+i*4))
            d=ImageDraw.Draw(img)
            x=40 + int((f/71)*320); y=360 + i*45
            d.rounded_rectangle([x,y,x+170,y+110], radius=15, fill=(245,243,235), outline=(220,220,220), width=4)
            d.ellipse([x+45,y+42,x+57,y+55], fill=(20,20,20))
            d.ellipse([x+110,y+42,x+122,y+55], fill=(20,20,20))
            d.text((28,40), f'SAHNE {i+1}/5', fill=(255,255,255))
            frames.append(img)
        out=SCENES/f'fallback_{i+1}.mp4'
        imageio.mimsave(out, frames, fps=24, codec='libx264', quality=7)
        clips.append(out)
    concat=WORK/'concat.txt'
    concat.write_text(''.join(\"file '%s'\\n\"%p for p in clips))
    subprocess.check_call(['ffmpeg','-y','-f','concat','-safe','0','-i',str(concat),'-c:v','libx264','-pix_fmt','yuv420p','-movflags','+faststart',str(FINAL)])

try:
    repo = WORK/'LTX-Video'
    if not repo.exists():
        subprocess.check_call(['git','clone','--depth','1','https://github.com/Lightricks/LTX-Video.git',str(repo)])
    subprocess.check_call([sys.executable,'-m','pip','install','-q','-e',str(repo)+'[inference]'])
    sys.path.insert(0,str(repo))
    from ltx_video.inference import infer, InferenceConfig

    generated=[]
    for i,prompt in enumerate(PROMPTS):
        ref=SCENES/f'ref_{i+1}.png'
        make_reference(ref,i)
        out=SCENES/f'scene_{i+1}.mp4'
        write_status(stage=f'GENERATING_{i+1}_OF_5', engine='LTX-Video 2B distilled', ai_ok=True)
        cfg=InferenceConfig(
            pipeline_config=str(repo/'configs/ltxv-2b-0.9.6-distilled.yaml'),
            prompt=prompt, height=1024, width=576, num_frames=65, frame_rate=24,
            seed=12400+i, output_path=str(out), offload_to_cpu=True,
            conditioning_media_paths=[str(ref)], conditioning_start_frames=[0],
            conditioning_strengths=[1.0],
        )
        infer(config=cfg)
        if not out.exists() or out.stat().st_size < 10000:
            raise RuntimeError(f'Scene {i+1} did not produce a valid MP4')
        generated.append(out)

    concat=WORK/'concat.txt'
    concat.write_text(''.join(\"file '%s'\\n\"%p for p in generated))
    subprocess.check_call(['ffmpeg','-y','-f','concat','-safe','0','-i',str(concat),
        '-vf','scale=1080:1920:flags=lanczos','-c:v','libx264','-preset','medium','-crf','19',
        '-pix_fmt','yuv420p','-movflags','+faststart',str(FINAL)])
    if FINAL.stat().st_size < 100000: raise RuntimeError('Final MP4 is unexpectedly small')
    write_status(stage='COMPLETE', engine='LTX-Video 2B distilled', ai_ok=True, final='FINAL.mp4')
except Exception as e:
    (WORK/'ai_error.txt').write_text(traceback.format_exc(),encoding='utf-8')
    write_status(stage='AI_FAILED_FALLBACK', engine='fallback renderer', ai_ok=False, error=str(e))
    fallback_video()
    write_status(stage='COMPLETE_FALLBACK', engine='fallback renderer', ai_ok=False, final='FINAL.mp4', error=str(e))

print('VIDEO_FACTORY_DONE', FINAL, FINAL.exists(), FINAL.stat().st_size if FINAL.exists() else 0)
""".replace("__PROJECT_ID_B64__", safeId).replace("__USER_IDEA_B64__", safeIdea);
    }
}
