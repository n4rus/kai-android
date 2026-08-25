//! Kai Bridge — Rust cdylib for Android JNI
//! REAL inference: loads GGUF via llama.cpp, generates with VFE-modulated temperature.
//! No canned answers: if a model is loaded, the model answers. If not, Kai says so.

use std::ffi::{CStr, CString};
use std::fs::File;
use std::os::raw::c_char;
use std::sync::Mutex;

use memmap2::MmapOptions;

use llama_cpp_2::context::params::LlamaContextParams;
use llama_cpp_2::llama_backend::LlamaBackend;
use llama_cpp_2::llama_batch::LlamaBatch;
use llama_cpp_2::model::{AddBos, LlamaModel, Special};
use llama_cpp_2::sampling::LlamaSampler;
use llama_cpp_2::token::LlamaToken;

/// Loaded llama.cpp model + metadata
struct ModelSlot {
    model: LlamaModel,
    path: String,
    size: u64,
}

static BACKEND: Mutex<Option<LlamaBackend>> = Mutex::new(None);
static MODEL: Mutex<Option<ModelSlot>> = Mutex::new(None);

/// Info string for Kotlin UI (kept even while model is in MODEL slot)
#[derive(Clone, Debug)]
struct LoadedInfo {
    path: String,
    size: u64,
    is_gguf: bool,
    version: u32,
}
static LAST_INFO: Mutex<Option<LoadedInfo>> = Mutex::new(None);

fn info_of(slot: Option<&ModelSlot>) -> Option<LoadedInfo> {
    slot.map(|s| LoadedInfo {
        path: s.path.clone(),
        size: s.size,
        is_gguf: true,
        version: 3,
    })
}

/// Load GGUF at path → 0 ok, -1 err
/// Validates magic quickly via mmap, then hands the file to llama.cpp for a full load.
#[no_mangle]
pub extern "C" fn kai_load_gguf(path: *const c_char) -> i32 {
    if path.is_null() { return -1; }
    let cstr = unsafe { CStr::from_ptr(path) };
    let p = match cstr.to_str() { Ok(s) => s, Err(_) => return -1 };

    // Fast-fail: magic check before paying for a full load
    let file = match File::open(p) { Ok(f) => f, Err(_) => return -1 };
    let meta = match file.metadata() { Ok(m) => m, Err(_) => return -1 };
    let size = meta.len();
    if size < 16 { return -1; }
    let mmap = unsafe { match MmapOptions::new().len(16).map(&file) { Ok(m) => m, Err(_) => return -1 } };
    let is_gguf = mmap.len() >= 4 && &mmap[0..4] == b"GGUF";
    let version = if mmap.len() >= 8 {
        u32::from_le_bytes([mmap[4], mmap[5], mmap[6], mmap[7]])
    } else { 0 };
    drop(mmap); drop(file);
    if !is_gguf {
        if let Ok(mut g) = LAST_INFO.lock() {
            *g = Some(LoadedInfo { path: p.to_string(), size, is_gguf: false, version });
        }
        return -2;
    }

    // Init backend once per process
    {
        let mut be = match BACKEND.lock() { Ok(g) => g, Err(_) => return -1 };
        if be.is_none() {
            *be = Some(match LlamaBackend::init() { Ok(b) => b, Err(_) => return -1 });
        }
    }

    // Full load (weights are mmapped by llama.cpp — cheap to open, pages in on use)
    let model = {
        let be_guard = match BACKEND.lock() { Ok(g) => g, Err(_) => return -1 };
        let backend = match be_guard.as_ref() { Some(b) => b, None => return -1 };
        match LlamaModel::load_from_file(backend, p, &Default::default()) { Ok(m) => m, Err(_) => return -3 }
    };

    let slot = ModelSlot { model, path: p.to_string(), size };
    if let Ok(mut g) = LAST_INFO.lock() {
        *g = info_of(Some(&slot));
    }
    if let Ok(mut g) = MODEL.lock() { *g = Some(slot); }
    0
}

/// VFE = surprise + KL — mirrors physics-dialect calculate_vfe
#[no_mangle]
pub extern "C" fn kai_calculate_vfe(surprise: f32, kl: f32) -> f32 {
    surprise + kl
}

/// T' = T * (1 + alpha * curvature)
#[no_mangle]
pub extern "C" fn kai_curvature_to_temp(base_temp: f32, curvature: f32, alpha: f32) -> f32 {
    base_temp * (1.0 + alpha * curvature)
}

/// Version — caller must NOT free
#[no_mangle]
pub extern "C" fn kai_version() -> *const c_char {
    static S: &[u8] = b"kai-bridge 0.4.0-real-inference\0";
    S.as_ptr() as *const c_char
}

/// Info about loaded GGUF — "path|size|is_gguf|version"
#[no_mangle]
pub extern "C" fn kai_last_gguf_info() -> *mut c_char {
    // Prefer live slot; fall back to last validation result (e.g. invalid file)
    let guard = MODEL.lock().ok();
    let info = guard.as_ref().and_then(|s| info_of(s.as_ref()))
        .or_else(|| LAST_INFO.lock().ok().and_then(|g| g.clone()));
    let info = match info { Some(i) => i, None => return std::ptr::null_mut() };
    let s = format!("{}|{}|{}|{}", info.path, info.size, info.is_gguf as u32, info.version);
    match CString::new(s) { Ok(c) => c.into_raw(), Err(_) => std::ptr::null_mut() }
}

/// Chat template per model family — returns (template_fn_name applied prompt)
const KAI_SYSTEM: &str = "You are Kai, an offline AI companion running directly on the user's Android phone. You HAVE persistent memory: a local database stores facts the user told you (shown to you inside [Memory] blocks — treat them as things you remember) and your chat history survives restarts, so never claim you have no memory. Answer the question asked — direct and honest.";

fn apply_chat_template(model_name_lower: &str, user_text: &str) -> String {
    if model_name_lower.contains("qwen") {
        format!(
            "<|im_start|>system\n{KAI_SYSTEM}<|im_end|>\n<|im_start|>user\n{user_text}<|im_end|>\n<|im_start|>assistant\n"
        )
    } else if model_name_lower.contains("gemma") {
        format!(
            "<bos><start_of_turn>user\n{KAI_SYSTEM}\n\n{user_text}<end_of_turn>\n<start_of_turn>model\n"
        )
    } else if model_name_lower.contains("llama") {
        format!(
            "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n{KAI_SYSTEM}<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n{user_text}<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        )
    } else {
        // Generic fallback: raw text
        format!("{user_text}\n")
    }
}

/// Stop sequences per family (rendered tokens we cut out of output)
fn turn_ends_for(model_name_lower: &str) -> Vec<String> {
    if model_name_lower.contains("qwen") {
        vec!["<|im_end|>".into(), "<|im_start|>".into()]
    } else if model_name_lower.contains("gemma") {
        vec!["<end_of_turn>".into()]
    } else if model_name_lower.contains("llama") {
        vec!["<|eot_id|>".into(), "<|end_of_text|>".into()]
    } else {
        vec![]
    }
}

/// Generate with the loaded model. Real token-by-token decoding.
/// temp/vfe modulate sampling: high VFE → slightly more exploratory temperature.
#[no_mangle]
pub extern "C" fn kai_generate(prompt: *const c_char, temp: f32, vfe: f32) -> *mut c_char {
    if prompt.is_null() { return std::ptr::null_mut(); }
    let cstr = unsafe { CStr::from_ptr(prompt) };
    let p = match cstr.to_str() { Ok(s) => s, Err(_) => return std::ptr::null_mut() };

    // Honest no-model answer — never fake content
    let mut model_guard = match MODEL.lock() { Ok(g) => g, Err(_) => return std::ptr::null_mut() };
    let slot = match model_guard.as_mut() {
        Some(s) => s,
        None => {
            let msg = "⚠ No model loaded yet.\nTap the model name at the top → download qwen2.5:0.5b (free, ~400MB), then ask me anything.";
            return match CString::new(msg) { Ok(s) => s.into_raw(), Err(_) => std::ptr::null_mut() };
        }
    };

    let model_name = std::path::Path::new(&slot.path)
        .file_name().map(|s| s.to_string_lossy().to_lowercase()).unwrap_or_default();
    let rendered = apply_chat_template(&model_name, p);
    let turn_ends = turn_ends_for(&model_name);

    // Physics: VFE nudges temperature (curious when surprised, focused when confident)
    let vfe_norm = (vfe / 5.0).clamp(0.0, 1.5);
    let eff_temp = (temp * (1.0 + 0.15 * vfe_norm)).clamp(0.1, 1.6);

    // Backend + context
    let result = (|| -> Result<String, Box<dyn std::error::Error>> {
        let be_guard = BACKEND.lock()?;
        let backend = be_guard.as_ref().ok_or("backend not init")?;

        let n_ctx_train = slot.model.n_ctx_train();
        let n_ctx = n_ctx_train.min(2048);
        let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(4).min(4) as i32;
        let mut ctx = slot.model.new_context(
            backend,
            LlamaContextParams::default()
                .with_n_ctx(std::num::NonZeroU32::new(n_ctx))
                .with_n_threads(threads),
        )?;

        // Tokenize WITHOUT extra BOS (templates carry their own control tokens)
        let tokens = slot.model.str_to_token(&rendered, AddBos::Never)?;
        if tokens.is_empty() { return Ok(String::new()); }
        let max_total = n_ctx as usize;
        let tokens = if tokens.len() > max_total / 2 { tokens[tokens.len() - max_total / 2..].to_vec() } else { tokens };

        let new_tokens_cap = 320usize;
        let mut batch = LlamaBatch::new(tokens.len().max(512), 1);

        // Prime with the full prompt
        let last_idx = tokens.len() as i32 - 1;
        for (i, t) in tokens.iter().enumerate() {
            let is_last = i as i32 == last_idx;
            batch.add(*t, i as i32, &[0], is_last)?;
        }
        ctx.decode(&mut batch)?;

        let mut sampler = LlamaSampler::chain_simple(vec![
            LlamaSampler::penalties(64, 1.12, 0.0, 0.0),
            LlamaSampler::top_p(0.92, 16),
            LlamaSampler::temp(eff_temp),
            LlamaSampler::dist(1234),
        ]);

        let mut out = String::new();
        let mut n_cur = tokens.len() as i32;
        for _ in 0..new_tokens_cap {
            let tok: LlamaToken = sampler.sample(&ctx, -1);
            sampler.accept(tok);
            if slot.model.is_eog_token(tok) { break; }

            let piece = slot.model.token_to_str(tok, Special::Tokenize)?;
            // Cut turn-end markers if tokenizer emits them as text
            if turn_ends.iter().any(|e| piece.contains(e.as_str())) { break; }
            out.push_str(&piece);

            if n_cur >= max_total as i32 - 1 { break; }
            batch.clear();
            batch.add(tok, n_cur, &[0], true)?;
            ctx.decode(&mut batch)?;
            n_cur += 1;
        }

        Ok(out.trim().to_string())
    })();

    let msg = match result {
        Ok(t) if t.trim().is_empty() => "…(empty generation — try rephrasing or downloading a bigger model)".to_string(),
        Ok(t) => t,
        Err(e) => format!("(inference error: {e})"),
    };

    match CString::new(msg) { Ok(s) => s.into_raw(), Err(_) => std::ptr::null_mut() }
}

/// Free string returned by kai_generate / kai_last_gguf_info
#[no_mangle]
pub extern "C" fn kai_free_string(s: *mut c_char) {
    if s.is_null() { return; }
    unsafe { let _ = CString::from_raw(s); }
}

// ---------------------------------------------------------------------------
// JNI exports — Kotlin calls Rust directly via kai_bridge
// ---------------------------------------------------------------------------
use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::{jfloat, jint, jstring};

#[no_mangle]
pub extern "system" fn Java_com_axiom_kai_KaiBridge_version<'local>(
    mut env: JNIEnv<'local>,
    _obj: JObject<'local>,
) -> jstring {
    let s = unsafe { CStr::from_ptr(kai_version()).to_string_lossy().into_owned() };
    env.new_string(s).unwrap_or_else(|_| env.new_string("kai-bridge").unwrap()).into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_axiom_kai_KaiBridge_calculateVFE<'local>(
    _env: JNIEnv<'local>,
    _obj: JObject<'local>,
    surprise: jfloat,
    kl: jfloat,
) -> jfloat {
    kai_calculate_vfe(surprise, kl)
}

#[no_mangle]
pub extern "system" fn Java_com_axiom_kai_KaiBridge_curvatureToTemp<'local>(
    _env: JNIEnv<'local>,
    _obj: JObject<'local>,
    base: jfloat,
    curvature: jfloat,
    alpha: jfloat,
) -> jfloat {
    kai_curvature_to_temp(base, curvature, alpha)
}

#[no_mangle]
pub extern "system" fn Java_com_axiom_kai_KaiBridge_loadGguf<'local>(
    mut env: JNIEnv<'local>,
    _obj: JObject<'local>,
    path: JString<'local>,
) -> jint {
    let p: String = env.get_string(&path).map(|s| s.into()).unwrap_or_default();
    let c = match CString::new(p) { Ok(c) => c, Err(_) => return -1 };
    kai_load_gguf(c.as_ptr()) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_axiom_kai_KaiBridge_lastGgufInfo<'local>(
    mut env: JNIEnv<'local>,
    _obj: JObject<'local>,
) -> jstring {
    let ptr = kai_last_gguf_info();
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    let cstr = unsafe { CStr::from_ptr(ptr) };
    let s = cstr.to_string_lossy().into_owned();
    unsafe { let _ = CString::from_raw(ptr); }
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_axiom_kai_KaiBridge_generate<'local>(
    mut env: JNIEnv<'local>,
    _obj: JObject<'local>,
    prompt: JString<'local>,
    temp: jfloat,
    vfe: jfloat,
) -> jstring {
    let p: String = env.get_string(&prompt).map(|s| s.into()).unwrap_or_default();
    let c = match CString::new(p) { Ok(c) => c, Err(_) => return std::ptr::null_mut() };
    let ptr = kai_generate(c.as_ptr(), temp, vfe);
    if ptr.is_null() { return std::ptr::null_mut(); }
    let cstr = unsafe { CStr::from_ptr(ptr) };
    let s = cstr.to_string_lossy().into_owned();
    unsafe { let _ = CString::from_raw(ptr); }
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}
