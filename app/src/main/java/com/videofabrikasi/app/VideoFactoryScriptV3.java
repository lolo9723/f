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
                        + "TRANSLATION_REVISION = '19c65427cc2af5f191337d4899e0348c4af25902'\n");

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

def turkish_language_signals(text):
    import re
    text = text or ''
    words = [w.lower() for w in re.findall(r'[A-Za-zÇĞİÖŞÜçğıöşü]+', text)]
    hint_hits = sum(1 for w in words if w in TURKISH_HINT_WORDS)
    special_chars = sum(1 for ch in text if ch in TURKISH_CHARS)
    return hint_hits, special_chars

def looks_turkish(text):
    hint_hits, special_chars = turkish_language_signals(text)
    # Source detection is deliberately permissive for normal Turkish text, but
    # one Turkish proper name inside an English story must not trigger translation.
    return hint_hits >= 2 or special_chars >= 4

def translation_still_turkish(text):
    hint_hits, special_chars = turkish_language_signals(text)
    # Output validation is stricter than source detection. A proper name such as
    # “Çağrı” may legitimately survive English translation; multiple Turkish
    # function/content words are required before rejecting the result.
    return hint_hits >= 2 or (hint_hits >= 1 and special_chars >= 4)

def split_translation_chunks(text, max_chars=700):
    import re
    pieces = [p.strip() for p in re.split(r'(?<=[.!?;])\s+', text or '') if p.strip()]
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

    from huggingface_hub import hf_hub_download
    from transformers import MarianTokenizer, MarianMTModel
    import torch

    tokenizer_dir = TEMP/'translation_tokenizer'
    tokenizer_dir.mkdir(parents=True, exist_ok=True)
    source_spm = hf_hub_download(
        repo_id=TRANSLATION_MODEL, filename='source.spm',
        revision=TRANSLATION_REVISION, local_dir=str(tokenizer_dir)
    )
    target_spm = hf_hub_download(
        repo_id=TRANSLATION_MODEL, filename='target.spm',
        revision=TRANSLATION_REVISION, local_dir=str(tokenizer_dir)
    )
    vocab_json = hf_hub_download(
        repo_id=TRANSLATION_MODEL, filename='vocab.json',
        revision=TRANSLATION_REVISION, local_dir=str(tokenizer_dir)
    )
    for required in (source_spm, target_spm, vocab_json):
        if not required or not Path(required).is_file():
            raise RuntimeError('Pinned Marian tokenizer asset is missing: ' + str(required))

    tokenizer = MarianTokenizer(
        source_spm=source_spm,
        target_spm=target_spm,
        vocab=vocab_json,
        model_max_length=512
    )
    model = MarianMTModel.from_pretrained(
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
    if translation_still_turkish(result):
        raise RuntimeError('Translation still appears Turkish; refusing low-quality LTX prompt')
    return result, {
        'mode':'tr_to_en', 'source_language':'tr', 'target_language':'en',
        'model':TRANSLATION_MODEL, 'revision':TRANSLATION_REVISION, 'chunks':len(chunks)
    }

""");

        script = requireReplace(script,
                "subprocess.check_call([sys.executable,'-m','pip','install','-q','transformers==4.49.0','diffusers==0.33.1','accelerate==1.6.0'])",
                "subprocess.check_call([sys.executable,'-m','pip','install','-q','transformers==4.49.0','diffusers==0.33.1','accelerate==1.6.0','sentencepiece==0.2.0','protobuf==5.29.5','sacremoses==0.1.1','huggingface-hub==0.30.2','safetensors==0.5.3','tokenizers==0.21.4'])");

        String translationBootstrap =
                "    subprocess.check_call([sys.executable,'-m','pip','install','-q','-e',str(repo)+'[inference]'])\n\n"
                + "    LTX_STORY, TRANSLATION_INFO = prepare_story_for_ltx(USER_IDEA)\n"
                + "    PROMPTS = build_scene_prompts(LTX_STORY)\n"
                + "    write_status(\n"
                + "        stage='PROMPTS_READY', engine='LTX-Video 2B distilled 0.9.6 T4-FP16 story-v3', ai_ok=False,\n"
                + "        prompt_language='English', translation=TRANSLATION_INFO\n"
                + "    )\n\n"
                + "    gpu_name, gpu_vram_gb, torch_version = gpu_preflight()";
        script = requireReplace(script,
                "    subprocess.check_call([sys.executable,'-m','pip','install','-q','-e',str(repo)+'[inference]'])\n\n    gpu_name, gpu_vram_gb, torch_version = gpu_preflight()",
                translationBootstrap);

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
