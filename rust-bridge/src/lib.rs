//! Kai Bridge — Rust cdylib for Android JNI
//! REAL inference: loads GGUF via llama.cpp, generates with VFE-modulated temperature.
//! No canned answers: if a model is loaded, the model answers. If not, Kai says so.

use std::ffi::{CStr, CString};
use std::fs::File;
use std::os::raw::c_char;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

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

/// Two model slots: 0 = FAST (small, instant), 1 = DEEP (bigger, quality).
/// Soul-fusion: the router in Kotlin picks the slot per task.
static SLOTS: [Mutex<Option<ModelSlot>>; 2] = [Mutex::new(None), Mutex::new(None)];

/// Cancellation flags per slot — set by kai_cancel, checked each decode iteration.
static CANCEL_FLAGS: [AtomicBool; 2] = [AtomicBool::new(false), AtomicBool::new(false)];

#[no_mangle]
pub extern "C" fn kai_cancel(slot: i32) {
    CANCEL_FLAGS[slot.clamp(0, 1) as usize].store(true, Ordering::Relaxed);
}

fn slot_mutex(slot: i32) -> &'static Mutex<Option<ModelSlot>> {
    &SLOTS[(slot.clamp(0, 1)) as usize]
}

/// Info string for Kotlin UI (kept even while model is in a slot)
#[derive(Clone, Debug)]
struct LoadedInfo {
    path: String,
    size: u64,
    is_gguf: bool,
    version: u32,
}
static LAST_INFO: Mutex<Option<LoadedInfo>> = Mutex::new(None);

fn info_of(path: &str, size: u64) -> LoadedInfo {
    LoadedInfo { path: path.to_string(), size, is_gguf: true, version: 3 }
}

/// Load GGUF at path → 0 ok, -1 err (slot 0 default)
#[no_mangle]
pub extern "C" fn kai_load_gguf(path: *const c_char) -> i32 {
    kai_load_gguf_slot(0, path)
}

/// Load GGUF into a slot (0=fast, 1=deep) → 0 ok, -1 err
/// Validates magic quickly via mmap, then hands the file to llama.cpp for a full load.
#[no_mangle]
pub extern "C" fn kai_load_gguf_slot(slot: i32, path: *const c_char) -> i32 {
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

    let slot_data = ModelSlot { model, path: p.to_string(), size };
    if slot == 0 {
        if let Ok(mut g) = LAST_INFO.lock() {
            *g = Some(info_of(p, size));
        }
    }
    if let Ok(mut g) = slot_mutex(slot).lock() { *g = Some(slot_data); }
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
    static S: &[u8] = b"kai-bridge 0.5.0-soul-fusion\0";
    S.as_ptr() as *const c_char
}

/// Info about loaded GGUF — "path|size|is_gguf|version" (slot 0)
#[no_mangle]
pub extern "C" fn kai_last_gguf_info() -> *mut c_char {
    kai_slot_info(0)
}

/// Info for a specific slot — "path|size|is_gguf|version" or null if empty
#[no_mangle]
pub extern "C" fn kai_slot_info(slot: i32) -> *mut c_char {
    if let Ok(g) = slot_mutex(slot).lock() {
        if let Some(ref s) = *g {
            let info = info_of(&s.path, s.size);
            let out = format!("{}|{}|{}|{}", info.path, info.size, info.is_gguf as u32, info.version);
            return match CString::new(out) { Ok(c) => c.into_raw(), Err(_) => std::ptr::null_mut() };
        }
    }
    // Fall back to last validation result (slot 0 only, e.g. invalid file)
    if slot == 0 {
        if let Ok(g) = LAST_INFO.lock() {
            if let Some(info) = g.clone() {
                let s = format!("{}|{}|{}|{}", info.path, info.size, info.is_gguf as u32, info.version);
                return match CString::new(s) { Ok(c) => c.into_raw(), Err(_) => std::ptr::null_mut() };
            }
        }
    }
    std::ptr::null_mut()
}

/// Default system prompt (Kotlin's Soul block overrides this when provided)
const KAI_SYSTEM: &str = "You are Kai, an offline AI companion running directly on the user's Android phone. You HAVE persistent memory: a local database stores facts the user told you (shown to you inside [Memory] blocks — treat them as things you remember) and your chat history survives restarts, so never claim you have no memory. Answer the question asked — direct and honest.";


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

/// Multi-turn chat rendering per model family.
/// msgs: (role, content) with roles system|user|assistant.
fn render_chat(model_name_lower: &str, msgs: &[(String, String)]) -> String {
    // Ensure a system prompt exists — Kai's soul must always be present
    let has_system = msgs.iter().any(|(r, _)| r == "system");
    let mut m: Vec<(String, String)> = Vec::new();
    if !has_system {
        m.push(("system".into(), KAI_SYSTEM.into()));
    }
    m.extend(msgs.iter().cloned());

    if model_name_lower.contains("qwen") {
        let mut s = String::new();
        for (role, content) in &m {
            s.push_str(&format!("<|im_start|>{role}\n{content}<|im_end|>\n"));
        }
        s.push_str("<|im_start|>assistant\n");
        s
    } else if model_name_lower.contains("gemma") {
        // gemma has no system role — fold system into first user turn
        let mut s = String::from("<bos>");
        let mut sys_pending = m.iter().find(|(r, _)| r == "system").map(|(_, c)| c.clone());
        for (role, content) in &m {
            match role.as_str() {
                "system" => {}
                "user" => {
                    let mut c = content.clone();
                    if let Some(sys) = sys_pending.take() {
                        c = format!("{sys}\n\n{c}");
                    }
                    s.push_str(&format!("<start_of_turn>user\n{c}<end_of_turn>\n"));
                }
                _ => s.push_str(&format!("<start_of_turn>model\n{content}<end_of_turn>\n")),
            }
        }
        s.push_str("<start_of_turn>model\n");
        s
    } else if model_name_lower.contains("llama") {
        let mut s = String::from("<|begin_of_text|>");
        for (role, content) in &m {
            s.push_str(&format!("<|start_header_id|>{role}<|end_header_id|>\n\n{content}<|eot_id|>"));
        }
        s.push_str("<|start_header_id|>assistant<|end_header_id|>\n\n");
        s
    } else {
        let mut s = String::new();
        for (role, content) in &m {
            s.push_str(&format!("{role}: {content}\n"));
        }
        s.push_str("assistant: ");
        s
    }
}

/// Core decode loop — rendered prompt → generated text, for the model in `slot`.
fn generate_with_slot(slot: i32, rendered: &str, temp: f32, vfe: f32) -> Result<String, String> {
    // Lock the slot for the whole generation (prevents unload mid-decode).
    // Order: slot → backend. Load path never holds slot while taking backend, so no cycle.
    let model_guard = slot_mutex(slot).lock().map_err(|_| "slot poisoned")?;
    let model: &LlamaModel = match model_guard.as_ref() {
        Some(s) => &s.model,
        None => return Err("no model in slot".into()),
    };

    let model_name = std::path::Path::new(model_guard.as_ref().unwrap().path.as_str())
        .file_name().map(|s| s.to_string_lossy().to_lowercase()).unwrap_or_default();
    let turn_ends = turn_ends_for(&model_name);

    // Physics: VFE nudges temperature (curious when surprised, focused when confident)
    let vfe_norm = (vfe / 5.0).clamp(0.0, 1.5);
    let eff_temp = (temp * (1.0 + 0.15 * vfe_norm)).clamp(0.1, 1.6);

    let be_guard = BACKEND.lock().map_err(|_| "backend poisoned")?;
    let backend = be_guard.as_ref().ok_or("backend not init")?;

    let n_ctx_train = model.n_ctx_train();
    let n_ctx = n_ctx_train.min(2048);
    let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(4).min(4) as i32;
    let mut ctx = model.new_context(
        backend,
        LlamaContextParams::default()
            .with_n_ctx(std::num::NonZeroU32::new(n_ctx))
            .with_n_threads(threads),
    ).map_err(|e| e.to_string())?;

    // Tokenize WITHOUT extra BOS (templates carry their own control tokens)
    let tokens = model.str_to_token(rendered, AddBos::Never).map_err(|e| e.to_string())?;
    if tokens.is_empty() { return Ok(String::new()); }
    let max_total = n_ctx as usize;
    let tokens = if tokens.len() > max_total / 2 { tokens[tokens.len() - max_total / 2..].to_vec() } else { tokens };

    let new_tokens_cap = 384usize;
    let mut batch = LlamaBatch::new(tokens.len().max(512), 1);

    // Prime with the full prompt
    let last_idx = tokens.len() as i32 - 1;
    for (i, t) in tokens.iter().enumerate() {
        let is_last = i as i32 == last_idx;
        batch.add(*t, i as i32, &[0], is_last).map_err(|e| e.to_string())?;
    }
    ctx.decode(&mut batch).map_err(|e| e.to_string())?;

    let mut sampler = LlamaSampler::chain_simple(vec![
        LlamaSampler::penalties(64, 1.12, 0.0, 0.0),
        LlamaSampler::top_p(0.92, 16),
        LlamaSampler::temp(eff_temp),
        LlamaSampler::dist(1234),
    ]);

    // Clear any previous cancel for this slot
    CANCEL_FLAGS[slot.clamp(0, 1) as usize].store(false, Ordering::Relaxed);
    let mut out = String::new();
    let mut n_cur = tokens.len() as i32;
    for _ in 0..new_tokens_cap {
        if CANCEL_FLAGS[slot.clamp(0, 1) as usize].load(Ordering::Relaxed) {
            CANCEL_FLAGS[slot.clamp(0, 1) as usize].store(false, Ordering::Relaxed);
            break;
        }
        let tok: LlamaToken = sampler.sample(&ctx, -1);
        sampler.accept(tok);
        if model.is_eog_token(tok) { break; }

        let piece = model.token_to_str(tok, Special::Tokenize).map_err(|e| e.to_string())?;
        // Cut turn-end markers if tokenizer emits them as text
        if turn_ends.iter().any(|e| piece.contains(e.as_str())) { break; }
        out.push_str(&piece);

        if n_cur >= max_total as i32 - 1 { break; }
        batch.clear();
        batch.add(tok, n_cur, &[0], true).map_err(|e| e.to_string())?;
        ctx.decode(&mut batch).map_err(|e| e.to_string())?;
        n_cur += 1;
    }

    Ok(out.trim().to_string())
}

/// Single-turn generate (compat) — wraps kai_generate_chat with one user message.
#[no_mangle]
pub extern "C" fn kai_generate(prompt: *const c_char, temp: f32, vfe: f32) -> *mut c_char {
    if prompt.is_null() { return std::ptr::null_mut(); }
    let cstr = unsafe { CStr::from_ptr(prompt) };
    let p = match cstr.to_str() { Ok(s) => s, Err(_) => return std::ptr::null_mut() };
    let json = format!("[{{\"role\":\"user\",\"content\":{}}}]",
        serde_json::to_string(p).unwrap_or_else(|_| "\"\"".into()));
    let cj = match CString::new(json) { Ok(c) => c, Err(_) => return std::ptr::null_mut() };
    kai_generate_chat(cj.as_ptr(), temp, vfe, 0)
}

/// History-aware generation.
/// json: [{"role":"system|user|assistant","content":"..."}, ...] (last = user turn)
/// slot: 0 = fast, 1 = deep (falls back to the other slot if empty).
#[no_mangle]
pub extern "C" fn kai_generate_chat(json: *const c_char, temp: f32, vfe: f32, slot: i32) -> *mut c_char {
    if json.is_null() { return std::ptr::null_mut(); }
    let cstr = unsafe { CStr::from_ptr(json) };
    let s = match cstr.to_str() { Ok(s) => s, Err(_) => return std::ptr::null_mut() };

    let parsed: Result<Vec<serde_json::Value>, _> = serde_json::from_str(s);
    let msgs_src = match parsed {
        Ok(v) => v,
        Err(e) => return match CString::new(format!("(bad history json: {e})")) {
            Ok(c) => c.into_raw(), Err(_) => std::ptr::null_mut() },
    };

    let mut msgs: Vec<(String, String)> = Vec::new();
    for m in &msgs_src {
        let role = m.get("role").and_then(|r| r.as_str()).unwrap_or("user").to_string();
        let content = m.get("content").and_then(|c| c.as_str()).unwrap_or("").to_string();
        if !content.is_empty() { msgs.push((role, content)); }
    }
    if msgs.is_empty() || msgs.last().map(|(r, _)| r.as_str()) != Some("user") {
        return match CString::new("(history must end with a user turn)") {
            Ok(c) => c.into_raw(), Err(_) => std::ptr::null_mut() };
    }

    // Pick slot: requested → fallback to the other
    let use_slot = if slot_mutex(slot).lock().map(|g| g.is_some()).unwrap_or(false) {
        slot
    } else {
        1 - slot.clamp(0, 1)
    };

    let model_name = slot_mutex(use_slot).lock().ok()
        .and_then(|g| g.as_ref().map(|s| std::path::Path::new(&s.path)
            .file_name().map(|f| f.to_string_lossy().to_lowercase()).unwrap_or_default()))
        .unwrap_or_default();

    let rendered = render_chat(&model_name, &msgs);

    let msg = match generate_with_slot(use_slot, &rendered, temp, vfe) {
        Ok(t) if t.trim().is_empty() => "…(empty generation — try rephrasing or downloading a bigger model)".to_string(),
        Ok(t) => t,
        Err(e) if e.contains("no model in slot") =>
            "⚠ No model loaded yet.\nTap the model name at the top → download qwen2.5:0.5b (free, ~400MB), then ask me anything.".to_string(),
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
pub extern "system" fn Java_com_axiom_kai_KaiBridge_loadGgufSlot<'local>(
    mut env: JNIEnv<'local>,
    _obj: JObject<'local>,
    slot: jint,
    path: JString<'local>,
) -> jint {
    let p: String = env.get_string(&path).map(|s| s.into()).unwrap_or_default();
    let c = match CString::new(p) { Ok(c) => c, Err(_) => return -1 };
    kai_load_gguf_slot(slot, c.as_ptr()) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_axiom_kai_KaiBridge_slotInfo<'local>(
    mut env: JNIEnv<'local>,
    _obj: JObject<'local>,
    slot: jint,
) -> jstring {
    let ptr = kai_slot_info(slot);
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    let cstr = unsafe { CStr::from_ptr(ptr) };
    let s = cstr.to_string_lossy().into_owned();
    unsafe { let _ = CString::from_raw(ptr); }
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_axiom_kai_KaiBridge_generateChat<'local>(
    mut env: JNIEnv<'local>,
    _obj: JObject<'local>,
    json: JString<'local>,
    temp: jfloat,
    vfe: jfloat,
    slot: jint,
) -> jstring {
    let p: String = env.get_string(&json).map(|s| s.into()).unwrap_or_default();
    let c = match CString::new(p) { Ok(c) => c, Err(_) => return std::ptr::null_mut() };
    let ptr = kai_generate_chat(c.as_ptr(), temp, vfe, slot);
    if ptr.is_null() { return std::ptr::null_mut(); }
    let cstr = unsafe { CStr::from_ptr(ptr) };
    let s = cstr.to_string_lossy().into_owned();
    unsafe { let _ = CString::from_raw(ptr); }
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
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
pub extern "system" fn Java_com_axiom_kai_KaiBridge_cancel<'local>(
    _env: JNIEnv<'local>,
    _obj: JObject<'local>,
    slot: jint,
) {
    kai_cancel(slot);
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
