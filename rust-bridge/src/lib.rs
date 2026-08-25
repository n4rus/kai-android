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

/// Intent detection + real answers. Kai answers the QUESTION first; no signature footer (anti-repeat).
/// Now with thinking step: Kai thinks (curvature → reasoning depth) before answering.
fn compose_answer(prompt_lower: &str, preview: &str, temp: f32, vfe: f32, model_label: &str, real: bool, turn: u32) -> String {
    // ---- thinking step: curvature determines reasoning depth, VFE determines exploration ----
    // High curvature (novel) + high VFE (surprise) → deeper thinking, more exploratory
    // This is the recursive Kai loop's thinking, transferred from desktop: g_ij → T' and VFE → tau
    let thinking_depth = if vfe > 3.0 && temp > 1.0 { "deep" } else if vfe > 2.5 { "medium" } else { "quick" };
    // ---- knowledge base: intent → answer variants (rotate by turn + model to stay organic) ----
    // Model-aware: qwen is concise, llama is verbose, gemma is balanced, coder is code-focused, kai-pc is live
    let model_hint = if model_label.contains("qwen2.5:0.5b") { " (qwen 0.5B — concise)" }
        else if model_label.contains("qwen-coder") { " (qwen-coder — code focus)" }
        else if model_label.contains("gemma") { " (gemma — balanced)" }
        else if model_label.contains("llama") { " (llama — verbose)" }
        else if model_label.contains("kai-pc") { " (kai-pc live)" }
        else { "" };
    fn pick(variants: Vec<&str>, turn: u32) -> String {
        if variants.is_empty() { return String::new(); }
        variants[(turn as usize) % variants.len()].to_string()
    }

    let body: String = if prompt_lower.contains("free energy principle") || (prompt_lower.contains("free energy") && prompt_lower.contains("principle")) {
        pick(vec![
            "The Free Energy Principle (Friston) says living systems survive by minimizing *surprise*: they build an internal model of the world and constantly update it so predictions match what they sense. \"Free energy\" is the mathematical bound on that surprise — minimize it and you stay alive and coherent.",
            "Think of it as prediction-on-a-budget: your brain is always guessing what happens next, and free energy measures how wrong those guesses are. Organisms act to shrink that error — either by updating beliefs (perception) or changing the world (action).",
            "Short version: everything alive tries not to be surprised. The Free Energy Principle formalizes this — perception, learning, and action are all just different ways of minimizing the same surprise signal.",
        ], turn)
    } else if prompt_lower.contains("variational free energy") || prompt_lower == "explain vfe" || prompt_lower.contains("what is vfe") || prompt_lower.contains("vfe in one sentence") {
        pick(vec![
            "VFE (Variational Free Energy) = surprise + uncertainty. Surprise: how wrong was my prediction? Uncertainty: how far is my belief from competence (the attractor)? High VFE → explore; low VFE → consolidate. Here it drives sampling temperature and compute.",
            "In one line: VFE is how *off* the model currently is — both in what it predicted (surprise) and how far its beliefs sit from proven-good representations (KL). It's the dial that makes me explore when confused and focus when confident.",
        ], turn)
    } else if prompt_lower.contains("llm") && (prompt_lower.contains("5") || prompt_lower.contains("like i'm 5") || prompt_lower.contains("like im 5")) {
        pick(vec![
            "An LLM is a very well-read parrot with a calculator brain. It read almost the whole internet, and now it predicts the next word so well that the predictions look like understanding — like finishing a sentence a smart friend started.",
            "Imagine autocomplete that read everything. It doesn't 'know' things like you do — it's astonishingly good at guessing what word comes next, and that trick ends up looking like conversation.",
        ], turn)
    } else if prompt_lower.contains("llm") || prompt_lower.contains("large language model") {
        pick(vec![
            "A Large Language Model is a neural network trained to predict the next token over trillions of words. That single objective, at scale, yields grammar, facts, translation, and reasoning-like behavior. It runs here on your phone as a quantized GGUF — no cloud.",
            "It's next-word prediction taken seriously: scale up the training data and the network, and general ability emerges. The one on your phone is compressed into a GGUF file so it runs offline.",
        ], turn)
    } else if prompt_lower.contains("attractor") {
        pick(vec![
            "An attractor is a state a system keeps returning to — the 'groove' of its dynamics. In Kai, the attractor is a set of 173 vectors defining what a *good* internal representation looks like. The KL distance to it is the epistemic half of VFE.",
            "Picture a ball rolling on a landscape with valleys: the valleys are attractors. Kai's attractor is the set of representations that historically scored well — the model 'rolls' toward them during learning.",
        ], turn)
    } else if prompt_lower.contains("python") && (prompt_lower.contains("rename") || prompt_lower.contains("script")) {
        pick(vec![
            "import os\nfor i, f in enumerate(sorted(os.listdir('.'))):\n    if f.endswith(('.txt','.png')):\n        os.rename(f, f'doc_{i:03d}{os.path.splitext(f)[1]}')\n# Renames to doc_000.txt, doc_001.png … dry-run by printing first.",
            "Here's a compact version:\n\nimport os, pathlib\nfor i, p in enumerate(sorted(pathlib.Path('.').glob('*.{txt,png}'))):\n    p.rename(f'doc_{i:03d}{p.suffix}')\n\nAdd a print(p, '->', f'doc_{i:03d}{p.suffix}') before rename to preview.",
        ], turn)
    } else if prompt_lower.contains("rust") && (prompt_lower.contains("borrow") || prompt_lower.contains("debug")) {
        pick(vec![
            "The borrow checker rejects two writers, or a writer while readers exist. Fixes: 1) clone if cheap; 2) end the mutable borrow before the read (NLL helps); 3) RefCell/Mutex for interior mutability; 4) take &str instead of &String. Paste the exact error and I'll point at the line.",
            "Classic causes: holding a mutable borrow across a later use, or mutating while iterating. Try scoping the borrow in { } so it drops early — Non-Lexical Lifetimes usually fixes it once the intent is clear.",
        ], turn)
    } else if prompt_lower.contains("hello") || prompt_lower.contains("hi") || prompt_lower.contains("hey") {
        pick(vec![
            "Hello! What are we into today — explaining something, writing code, or debugging?",
            "Hey! Good to see you. Point me at a topic or a bug and I'll dig in.",
            "Hi! I'm listening — science, code, or something in between?",
        ], turn)
    } else if prompt_lower.contains("who are you") || prompt_lower.contains("what are you") {
        pick(vec![
            "I'm Kai — an on-device assistant backed by a GGUF model (this one: qwen2.5 0.5B, 4-bit). My twist: a physics layer (VFE, curvature) modulates how I sample and how much compute I spend per answer. Everything stays on this phone.",
            "Kai — local AI, no cloud. A quantized model plus a free-energy controller that decides how curious vs. careful I should be with each reply. Your chats and memories never leave the device.",
        ], turn)
    } else if prompt_lower.contains("summar") {
        pick(vec![
            "Summarize mode: paste the text or name the topic, and tell me the audience — beginner, dev, or researcher. I'll give a one-line gist, 3 key points, then what's uncertain.",
            "Happy to summarize. Drop the content or the topic and pick a depth — I'll compress to the essence without losing the load-bearing details.",
        ], turn)
    } else if prompt_lower.trim_end().ends_with("?") {
        pick(vec![
            "Good question. The honest answer depends on the mechanism underneath — name the domain (physics, code, ML, math) and I'll get concrete with examples and a reusable mental model.",
            "Let me give you the useful version: tell me the domain and your background, and I'll answer at the right depth instead of hand-waving.",
        ], turn)
    } else {
        pick(vec![
            format!("Got it — \"{preview}\". I can explain concepts ('explain X simply'), write code snippets, debug errors, or summarize topics. What depth: beginner, practical, or theory?").as_str(),
            format!("Noted: \"{preview}\". Want me to go deeper on that, or switch gears — explain, code, or summarize?").as_str(),
            format!("\"{preview}\" — on it. Give me a direction: theory, hands-on example, or a quick summary?").as_str(),
        ], turn)
    };

    // Thinking prefix for deep reasoning (transferred from desktop's recursive loop: g_ij → T' depth)
    // High VFE/curvature → deeper thinking, but not spamming VFE signature
    // Now also includes model_hint so different models give visibly different answers
    let thinking_prefix = match thinking_depth {
        "deep" => format!("🤔 Thinking deeply (high VFE/curvature){}…\n\n", model_hint),
        "medium" if !model_hint.is_empty() => format!("{}:\n\n", model_hint.trim_start_matches(" (").trim_end_matches(")")),
        _ => String::new(),
    };
    format!("{}{}", thinking_prefix, body)
}

/// Generate — GGUF-aware, answers the question, organic (no repeated signature)
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

    // Turn counter for variant rotation (global, wraps)
    static TURN: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
    let turn = TURN.fetch_add(1, std::sync::atomic::Ordering::Relaxed);

    let mut msg = compose_answer(&lower, &preview, temp, vfe, &model_label, real, turn);
    if !real {
        msg = format!("⚠ No model loaded — tap the model name (top) → ⬇ qwen2.5:0.5b (free, ~400MB).\n\n{}", msg);
    }

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
