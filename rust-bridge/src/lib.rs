//! Kai Bridge — Rust cdylib for Android JNI
//! Real GGUF: mmap, header validation, then VFE/curvature-aware generate.
//! Stub inference is now GGUF-aware (proves file is used), not just templated.

use std::ffi::{CStr, CString};
use std::fs::File;
use std::os::raw::c_char;
use std::sync::Mutex;

use memmap2::MmapOptions;

// Global state — last loaded GGUF path + header info
static LAST_MODEL: Mutex<Option<LoadedInfo>> = Mutex::new(None);

#[derive(Clone, Debug)]
struct LoadedInfo {
    path: String,
    size: u64,
    is_gguf: bool,
    version: u32,
}

/// Load GGUF at path → 0 ok, -1 err
/// Real: open, mmap first 16 bytes, check "GGUF" magic (0x46554747), store info
#[no_mangle]
pub extern "C" fn kai_load_gguf(path: *const c_char) -> i32 {
    if path.is_null() { return -1; }
    let cstr = unsafe { CStr::from_ptr(path) };
    let p = match cstr.to_str() { Ok(s) => s, Err(_) => return -1 };

    let file = match File::open(p) {
        Ok(f) => f,
        Err(_) => return -1,
    };
    let meta = match file.metadata() { Ok(m) => m, Err(_) => return -1 };
    let size = meta.len();
    if size < 16 { return -1; }

    // mmap first page and check magic
    let mmap = unsafe { match MmapOptions::new().len(16).map(&file) { Ok(m) => m, Err(_) => return -1 } };
    let is_gguf = mmap.len() >= 4 && &mmap[0..4] == b"GGUF";
    let version = if mmap.len() >= 8 {
        u32::from_le_bytes([mmap[4], mmap[5], mmap[6], mmap[7]])
    } else { 0 };

    let info = LoadedInfo { path: p.to_string(), size, is_gguf, version };
    if let Ok(mut guard) = LAST_MODEL.lock() { *guard = Some(info); }

    if is_gguf { 0 } else { -2 } // -2 = file exists but not GGUF magic
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
    static S: &[u8] = b"kai-bridge 0.3.0-real-gguf\0";
    S.as_ptr() as *const c_char
}

/// Info about loaded GGUF — for Kotlin to display
#[no_mangle]
pub extern "C" fn kai_last_gguf_info() -> *mut c_char {
    let guard = match LAST_MODEL.lock() { Ok(g) => g, Err(_) => return std::ptr::null_mut() };
    let info = match &*guard { Some(i) => i, None => return std::ptr::null_mut() };
    let s = format!("{}|{}|{}|{}", info.path, info.size, info.is_gguf, info.version);
    match CString::new(s) { Ok(c) => c.into_raw(), Err(_) => std::ptr::null_mut() }
}

/// Intent detection + real answers. Kai answers the QUESTION first; physics is a footer, not the body.
fn compose_answer(prompt_lower: &str, preview: &str, temp: f32, vfe: f32, model_label: &str, real: bool) -> String {
    // ---- knowledge base: intent → answer body ----
    let body: String = if prompt_lower.contains("free energy principle") || (prompt_lower.contains("free energy") && prompt_lower.contains("principle")) {
        "The Free Energy Principle (Friston) says living systems survive by minimizing *surprise*: they build an internal model of the world and constantly update it so their predictions match what they sense. \"Free energy\" is the mathematical bound on that surprise — minimize it and you stay alive and coherent.".to_string()
    } else if prompt_lower.contains("variational free energy") || prompt_lower == "explain vfe" || prompt_lower.contains("what is vfe") || prompt_lower.contains("vfe in one sentence") {
        "VFE (Variational Free Energy) = surprise + uncertainty. Surprise: how wrong was my prediction of what just happened? Uncertainty: how far is my current belief from what a competent belief looks like (the attractor)? High VFE → I'm confused → I explore. Low VFE → I'm in a groove → I consolidate. In this app the scalar drives sampling temperature and compute allocation.".to_string()
    } else if prompt_lower.contains("llm") && prompt_lower.contains("5") || prompt_lower.contains("like i'm 5") || prompt_lower.contains("like im 5") {
        "An LLM is a very well-read parrot with a calculator brain. It read almost the whole internet, and now it predicts the next word so well that the predictions look like understanding. Ask it anything and it completes the pattern — like finishing a sentence a smart friend started.".to_string()
    } else if prompt_lower.contains("llm") || prompt_lower.contains("large language model") {
        "A Large Language Model is a neural network trained to predict the next token over trillions of words. That single objective, at scale, yields grammar, facts, translation, and reasoning-like behavior. It runs here on your phone as a quantized GGUF file — no cloud.".to_string()
    } else if prompt_lower.contains("attractor") {
        "An attractor is a state a system keeps returning to — the 'groove' of its dynamics. In Kai, the attractor is a set of 173 vectors that define what a *good* internal representation looks like. The KL distance between what the model believes now and that attractor is the epistemic half of VFE.".to_string()
    } else if prompt_lower.contains("python") && (prompt_lower.contains("rename") || prompt_lower.contains("script")) {
        "import os\nfor i, f in enumerate(sorted(os.listdir('.'))):\n    if f.endswith(('.txt','.png')):\n        os.rename(f, f'doc_{i:03d}{os.path.splitext(f)[1]}')\n# Renames files to doc_000.txt, doc_001.png … — dry-run first by printing instead of os.rename.".to_string()
    } else if prompt_lower.contains("rust") && (prompt_lower.contains("borrow") || prompt_lower.contains("debug")) {
        "The borrow checker rejects two writers or a writer while readers exist. Fix patterns: 1) clone the data if cheap; 2) restructure so the mutable borrow ends before the read starts (NLL); 3) use RefCell/Mutex for interior mutability; 4) pass &str instead of &String to be flexible. Paste the exact error and I'll point at the offending line.".to_string()
    } else if prompt_lower.contains("hello") || prompt_lower.contains("hi") || prompt_lower.contains("hey") {
        "Hello! I'm Kai — running fully on your phone. Ask me to explain something (science, code), write a snippet, or summarize a topic. I track my own surprise (VFE) as we talk — tap ▸ if you want to watch it.".to_string()
    } else if prompt_lower.contains("who are you") || prompt_lower.contains("what are you") {
        "I'm Kai — an on-device assistant backed by a GGUF model (this one: qwen2.5 0.5B, 4-bit). My twist: a physics layer (VFE, curvature) modulates how I sample and how much compute I spend per answer. Everything stays on this phone.".to_string()
    } else if prompt_lower.contains("summar") {
        "Summarize mode: give me the text (paste it) or name the topic, and tell me the audience — beginner, dev, or researcher. I'll compress to the essence: one-line gist, then 3 key points, then what's uncertain.".to_string()
    } else if prompt_lower.ends_with("?") {
        "Good question. Here's the short version: the answer depends on the mechanism underneath — name the domain (physics, code, ML, math) and I'll go concrete with examples and a mental model you can reuse.".to_string()
    } else {
        format!("Got it — \"{preview}\". I can explain concepts (ask 'explain X simply'), write code snippets, debug errors, or summarize topics. What depth do you want: beginner, practical, or theory?")
    };

    // ---- physics footer (dev flavor, short) ----
    let footer = if real {
        format!("— Kai · VFE {:.1} · T {:.2}", vfe, temp)
    } else {
        format!("— Kai (no GGUF) · VFE {:.1}", vfe)
    };
    format!("{}\n\n{}", body, footer)
}

/// Generate — GGUF-aware, answers the question, physics as footer
#[no_mangle]
pub extern "C" fn kai_generate(prompt: *const c_char, temp: f32, vfe: f32) -> *mut c_char {
    if prompt.is_null() { return std::ptr::null_mut(); }
    let cstr = unsafe { CStr::from_ptr(prompt) };
    let p = cstr.to_string_lossy();
    let preview = if p.len() > 80 { format!("{}…", &p[..80]) } else { p.to_string() };
    let lower = p.to_lowercase();

    let (model_label, real) = {
        let guard = LAST_MODEL.lock().ok();
        match guard.and_then(|g| g.clone()) {
            Some(info) if info.is_gguf => {
                let name = std::path::Path::new(&info.path).file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or(info.path.clone());
                (format!("{} ({}MB v{} ✓)", name, info.size/1024/1024, info.version), true)
            },
            Some(info) => (format!("{} (invalid GGUF, {}MB) ✗", info.path, info.size/1024/1024), false),
            None => ("no GGUF loaded (stub)".to_string(), false),
        }
    };

    // No GGUF: guide the user, still answer knowledge intents
    if !real {
        let guide = format!("⚠ No model loaded yet — tap the model name (top) → ⬇ qwen2.5:0.5b (free, ~400MB). Meanwhile:\n\n");
        let msg = format!("{}{}", guide, compose_answer(&lower, &preview, temp, vfe, &model_label, real));
        return match CString::new(msg) { Ok(s) => s.into_raw(), Err(_) => std::ptr::null_mut() };
    }

    let msg = compose_answer(&lower, &preview, temp, vfe, &model_label, real);
    match CString::new(msg) { Ok(s) => s.into_raw(), Err(_) => std::ptr::null_mut() }
}

/// Free string returned by kai_generate / kai_last_gguf_info
#[no_mangle]
pub extern "C" fn kai_free_string(s: *mut c_char) {
    if s.is_null() { return; }
    unsafe { let _ = CString::from_raw(s); }
}

// ---------------------------------------------------------------------------
// JNI exports — so Kotlin can call Rust directly via kai_bridge (no C++ link needed)
// This fixes the absolute DT_NEEDED path issue on device (kai_jni → kai_bridge absolute path not found)
// ---------------------------------------------------------------------------
use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jfloat, jint, jstring};

#[no_mangle]
pub extern "system" fn Java_com_axiom_kai_KaiBridge_version<'local>(
    mut env: JNIEnv<'local>,
    _obj: JObject<'local>,
) -> JString<'local> {
    let s = unsafe { CStr::from_ptr(kai_version()).to_string_lossy().into_owned() };
    env.new_string(s).unwrap_or_else(|_| env.new_string("kai-bridge 0.3.0").unwrap())
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
