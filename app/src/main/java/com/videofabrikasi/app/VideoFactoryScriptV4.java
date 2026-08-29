package com.videofabrikasi.app;

/**
 * V4 adds a measurable semantic/visual integrity quality gate on top of V3.
 * The renderer remains V3; this layer rejects only extreme scene failures and
 * feeds them back into the existing bounded per-scene retry loop.
 */
final class VideoFactoryScriptV4 {
    private VideoFactoryScriptV4() {}

    static String build(String idea, String projectId) {
        String script = VideoFactoryScriptV3.build(idea, projectId);

        script = requireReplace(script,
                "TRANSLATION_REVISION = '19c65427cc2af5f191337d4899e0348c4af25902'\n",
                "TRANSLATION_REVISION = '19c65427cc2af5f191337d4899e0348c4af25902'\n"
                        + "QC_MODEL = 'google/siglip-base-patch16-224'\n"
                        + "QC_REVISION = '8d307961cfc45a1bbceecac290bec0a07e9a48db'\n"
                        + "QC_MIN_PROMPT_COSINE = 0.04\n"
                        + "QC_MIN_CONTINUITY_COSINE = 0.05\n"
                        + "QC_MAX_NEAR_BLACK_RATIO = 0.65\n"
                        + "QC_MIN_FRAME_CHANGE = 0.0015\n");

        String qualityFunctions = """
_qc_cache = {}

def quality_gate_decision(prompt_cosine, continuity_cosine, near_black_ratio, frame_change):
    reasons=[]
    if prompt_cosine < QC_MIN_PROMPT_COSINE:
        reasons.append(f'prompt_cosine={prompt_cosine:.4f}<{QC_MIN_PROMPT_COSINE:.4f}')
    if continuity_cosine is not None and continuity_cosine < QC_MIN_CONTINUITY_COSINE:
        reasons.append(f'continuity_cosine={continuity_cosine:.4f}<{QC_MIN_CONTINUITY_COSINE:.4f}')
    if near_black_ratio > QC_MAX_NEAR_BLACK_RATIO:
        reasons.append(f'near_black_ratio={near_black_ratio:.4f}>{QC_MAX_NEAR_BLACK_RATIO:.4f}')
    if frame_change < QC_MIN_FRAME_CHANGE:
        reasons.append(f'frame_change={frame_change:.5f}<{QC_MIN_FRAME_CHANGE:.5f}')
    return {'pass': not reasons, 'reasons': reasons}

def _load_qc_model():
    if 'model' in _qc_cache:
        return _qc_cache['model'], _qc_cache['processor']
    from transformers import AutoModel, AutoProcessor
    model = AutoModel.from_pretrained(QC_MODEL, revision=QC_REVISION).to('cpu')
    model.eval()
    processor = AutoProcessor.from_pretrained(QC_MODEL, revision=QC_REVISION)
    _qc_cache['model'] = model
    _qc_cache['processor'] = processor
    return model, processor

def _sample_scene_frames(video_path, sample_dir):
    from PIL import Image
    duration = media_duration(video_path)
    if duration <= 0:
        raise RuntimeError('QC cannot sample a zero-duration scene')
    if sample_dir.exists():
        shutil.rmtree(sample_dir)
    sample_dir.mkdir(parents=True)
    times = [min(duration * 0.12, max(0.02, duration-0.03)), duration * 0.50, max(0.02, duration * 0.88)]
    images=[]
    paths=[]
    for idx, at in enumerate(times):
        p = sample_dir/f'frame_{idx}.png'
        subprocess.check_call([
            'ffmpeg','-y','-ss',f'{at:.4f}','-i',str(video_path),'-frames:v','1',
            '-vf','scale=224:224:force_original_aspect_ratio=decrease,pad=224:224:(ow-iw)/2:(oh-ih)/2',
            str(p)
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if not p.is_file() or p.stat().st_size < 700:
            raise RuntimeError('QC frame extraction failed')
        images.append(Image.open(p).convert('RGB'))
        paths.append(p)
    return images, paths

def _cosine(a, b):
    a = a / a.norm(dim=-1, keepdim=True).clamp_min(1e-8)
    b = b / b.norm(dim=-1, keepdim=True).clamp_min(1e-8)
    return float((a * b).sum(dim=-1).mean().item())

def semantic_scene_qc(video_path, semantic_text, previous_frame, scene_index, attempt):
    import numpy as np
    import torch
    from PIL import Image
    sample_dir = TEMP/f'qc_scene_{scene_index}_attempt_{attempt}'
    images, _ = _sample_scene_frames(video_path, sample_dir)

    arrays=[np.asarray(img.convert('L'), dtype=np.float32) for img in images]
    near_black_ratio=float(np.mean([np.mean(a < 8.0) for a in arrays]))
    frame_change=float(np.mean(np.abs(arrays[-1]-arrays[0])) / 255.0)

    model, processor = _load_qc_model()
    image_inputs = processor(images=images, return_tensors='pt')
    text_inputs = processor(
        text=[semantic_text], padding='max_length', truncation=True, max_length=64, return_tensors='pt'
    )
    with torch.inference_mode():
        image_features = model.get_image_features(pixel_values=image_inputs['pixel_values'])
        text_features = model.get_text_features(
            input_ids=text_inputs['input_ids'], attention_mask=text_inputs.get('attention_mask')
        )
    image_features = image_features / image_features.norm(dim=-1, keepdim=True).clamp_min(1e-8)
    text_features = text_features / text_features.norm(dim=-1, keepdim=True).clamp_min(1e-8)
    prompt_cosine=float((image_features @ text_features.T).mean().item())

    continuity_cosine=None
    if previous_frame is not None and previous_frame.is_file():
        previous_image=Image.open(previous_frame).convert('RGB')
        pair_inputs=processor(images=[previous_image, images[0]], return_tensors='pt')
        with torch.inference_mode():
            pair_features=model.get_image_features(pixel_values=pair_inputs['pixel_values'])
        continuity_cosine=_cosine(pair_features[0:1], pair_features[1:2])

    decision=quality_gate_decision(
        prompt_cosine, continuity_cosine, near_black_ratio, frame_change
    )
    return {
        'scene':scene_index,
        'attempt':attempt,
        'pass':bool(decision['pass']),
        'reasons':decision['reasons'],
        'prompt_cosine':round(prompt_cosine,5),
        'continuity_cosine':None if continuity_cosine is None else round(continuity_cosine,5),
        'near_black_ratio':round(near_black_ratio,5),
        'frame_change':round(frame_change,6),
        'model':QC_MODEL,
        'revision':QC_REVISION,
    }

""";
        script = requireReplace(script,
                "def split_translation_chunks(text, max_chars=700):",
                qualityFunctions + "def split_translation_chunks(text, max_chars=700):");

        script = requireReplace(script,
                "    generated=[]\n    continuity_frame=None\n",
                "    generated=[]\n    continuity_frame=None\n    scene_qc_report=[]\n");

        script = requireReplace(script,
                "                validate_scene_media(out)\n\n                next_frame=SCENES/f'continuity_{i+1}.png'",
                "                validate_scene_media(out)\n"
                        + "                semantic_text = f'{SCENE_ROLES[i][0]} animated scene. {LTX_STORY[:480]}'\n"
                        + "                qc = semantic_scene_qc(\n"
                        + "                    out, semantic_text, continuity_frame, i+1, attempt\n"
                        + "                )\n"
                        + "                if not qc['pass']:\n"
                        + "                    raise RuntimeError('Semantic/visual QC failed: ' + '; '.join(qc['reasons']))\n"
                        + "                scene_qc_report.append(qc)\n"
                        + "                shutil.copy2(out, WORK/f'scene_{i+1}.mp4')\n\n"
                        + "                next_frame=SCENES/f'continuity_{i+1}.png'");

        script = requireReplace(script,
                "            continuity_frame=continuity_frame.name\n        )",
                "            continuity_frame=continuity_frame.name, quality=scene_qc_report[-1]\n        )");

        script = requireReplace(script,
                "    validate_final_media(FINAL)\n    write_status(",
                "    validate_final_media(FINAL)\n"
                        + "    (WORK/'quality_report.json').write_text(\n"
                        + "        json.dumps({'model':QC_MODEL,'revision':QC_REVISION,'scenes':scene_qc_report}, ensure_ascii=False, indent=2),\n"
                        + "        encoding='utf-8'\n"
                        + "    )\n"
                        + "    write_status(");

        script = requireReplace(script,
                "gpu=gpu_name, dtype='float16', audio='procedural_generic_emotion_sfx_aac', prompt_language='English', translation=TRANSLATION_INFO",
                "gpu=gpu_name, dtype='float16', audio='procedural_generic_emotion_sfx_aac', "
                        + "prompt_language='English', translation=TRANSLATION_INFO, "
                        + "quality_gate='siglip_semantic_plus_visual_integrity', quality=scene_qc_report");

        script = script.replace("story-v3", "story-v4");
        return script;
    }

    private static String requireReplace(String source, String marker, String replacement) {
        if (!source.contains(marker)) {
            throw new IllegalStateException("V4 patch marker missing; V3 contract changed: " + marker);
        }
        return source.replace(marker, replacement);
    }
}
