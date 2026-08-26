package com.videofabrikasi.app;

/**
 * Story V3 keeps the proven V2 rendering engine intact and adds a fail-closed
 * Turkish-to-English prompt preparation layer. LTX-Video documents English as
 * its prompt language, so Turkish creator stories are translated on CPU before
 * the five scene prompts are built. The translation model is revision-pinned.
 */
final class VideoFactoryScriptV3 {
    private VideoFactoryScriptV3() {}

    static String build(String idea, String projectId) {
        String script = VideoFactoryScriptV2.build(idea, projectId);

        script = requireReplace(script,
                "CONTINUITY_STRENGTH = 0.65\n",
                "CONTINUITY_STRENGTH = 0.65\n"
                        + "TRANSLATION_MODEL = 'Helsinki-NLP/opus-mt-tr-en'\n"
                        + "TRANSLATION_REVISION = '8f0734f08b3e19c8ef655c26625f725bc9b73d10'\n");

        script = requireReplace(script,
                "PROMPTS = build_scene_prompts(USER_IDEA)\n",
                """
PROMPTS = None
LTX_STORY = None
TRANSLATION_INFO = {'mode':'pending'}

TURKISH_CHARS = set('çğıöşüÇĞİÖŞÜ')
TURKISH_HINT_WORDS = {
    'bir','ve','bu','şu','için','ile','ama','sonra','önce','gibi','çok','daha','olan','olarak',
    'kadar','de','da','mi','mı','mu','mü','neden','çünkü','kapı','kutu','mektup','koşuyor','kaçıyor',
    'üzgün','mutlu','kötü','iyi','haber','arkasında','içinde','dışında','finalde','ortaya','çıkıyor'
}

def looks_turkish(text):
    text = text or ''
    if any(ch in TURKISH_CHARS for ch in text):
        return True
    words = [w.strip('.,!?;:\"\\'()[]{}').lower() for w in text.split()]
    hits = sum(1 for w in words if w in TURKISH_HINT_WORDS)
    return hits >= 2

def split_translation_chunks(text, max_chars=700):
    import re
    pieces = [p.strip() for p in re.split(r'(?<=[.!?;])\\s+', text or '') if p.strip()]
    if not pieces:
        return []
    chunks=[]
    current=''
    for piece in pieces:
        if len(piece) > max_chars:
            if current:
                chunks.append(current)
                current=''
            for start in range(0, len(piece), max_chars):
                chunks.append(piece[start:start+max_chars].strip())
            continue
        candidate = piece if not current else current + ' ' + piece
        if len(candidate) <= max_chars:
            current = candidate
        else:
            chunks.append(current)
            current = piece
    if current:
        chunks.append(current)
    return chunks

def prepare_story_for_ltx(story):
    story = (story or '').strip()
    if len(story) < 20:
        raise ValueError('Creator story is too short for translation')
    if not looks_turkish(story):
        return story, {'mode':'not_needed', 'source_language':'non_turkish', 'target_language':'en'}

    from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
    import torch
    tokenizer = AutoTokenizer.from_pretrained(
        TRANSLATION_MODEL, revision=TRANSLATION_REVISION
    )
    model = AutoModelForSeq2SeqLM.from_pretrained(
        TRANSLATION_MODEL, revision=TRANSLATION_REVISION
    ).to('cpu')
    model.eval()

    chunks = split_translation_chunks(story)
    if not chunks:
        raise RuntimeError('Translation produced no source chunks')
    translated=[]
    with torch.inference_mode():
        for chunk in chunks:
            encoded = tokenizer(
                chunk, return_tensors='pt', truncation=True, max_length=512
            )
            generated = model.generate(
                **encoded, num_beams=4, do_sample=False, max_new_tokens=256
            )
            part = tokenizer.decode(generated[0], skip_special_tokens=True).strip()
            if not part:
                raise RuntimeError('Turkish-to-English translation returned an empty chunk')
            translated.append(part)

    result = ' '.join(translated).strip()
    del model, tokenizer
    gc.collect()
    if len(result) < 20:
        raise RuntimeError('Turkish-to-English translation is unexpectedly short')
    if looks_turkish(result):
        raise RuntimeError('Translation still appears Turkish; refusing low-quality LTX prompt')
    return result, {
        'mode':'tr_to_en', 'source_language':'tr', 'target_language':'en',
        'model':TRANSLATION_MODEL, 'revision':TRANSLATION_REVISION, 'chunks':len(chunks)
    }

""");

        script = requireReplace(script,
                "subprocess.check_call([sys.executable,'-m','pip','install','-q','transformers==4.49.0','diffusers==0.33.1'])",
                "subprocess.check_call([sys.executable,'-m','pip','install','-q','transformers==4.49.0','diffusers==0.33.1','sentencepiece==0.2.0'])");

        script = requireReplace(script,
                "    subprocess.check_call([sys.executable,'-m','pip','install','-q','-e',str(repo)+'[inference]'])\n\n    gpu_name, gpu_vram_gb, torch_version = gpu_preflight()",
                """
    subprocess.check_call([sys.executable,'-m','pip','install','-q','-e',str(repo)+'[inference]'])

    LTX_STORY, TRANSLATION_INFO = prepare_story_for_ltx(USER_IDEA)
    PROMPTS = build_scene_prompts(LTX_STORY)
    write_status(
        stage='PROMPTS_READY', engine='LTX-Video 2B distilled 0.9.6 T4-FP16 story-v3', ai_ok=False,
        prompt_language='English', translation=TRANSLATION_INFO
    )

    gpu_name, gpu_vram_gb, torch_version = gpu_preflight()""");

        script = requireReplace(script,
                "gpu=gpu_name, dtype='float16', audio='procedural_generic_emotion_sfx_aac'",
                "gpu=gpu_name, dtype='float16', audio='procedural_generic_emotion_sfx_aac', "
                        + "prompt_language='English', translation=TRANSLATION_INFO");

        // Make status/engine diagnostics identify the active layer without changing
        // the pinned V2 model configuration underneath it.
        script = script.replace("story-v2", "story-v3");
        return script;
    }

    private static String requireReplace(String source, String marker, String replacement) {
        if (!source.contains(marker)) {
            throw new IllegalStateException("V3 patch marker missing; V2 contract changed: " + marker);
        }
        return source.replace(marker, replacement);
    }
}
