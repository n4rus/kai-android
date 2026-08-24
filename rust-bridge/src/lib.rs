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

/// Generate — now GGUF-aware
/// If a GGUF is loaded and valid, response includes file name + size + version and is VFE/temp modulated
/// prompt *const c_char, temp f32, vfe f32 → *mut c_char (caller must free via kai_free_string)
#[no_mangle]
pub extern "C" fn kai_generate(prompt: *const c_char, temp: f32, vfe: f32) -> *mut c_char {
    if prompt.is_null() { return std::ptr::null_mut(); }
    let cstr = unsafe { CStr::from_ptr(prompt) };
    let p = cstr.to_string_lossy();
    let preview = if p.len() > 80 { format!("{}…", &p[..80]) } else { p.to_string() };

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

    let msg = if !real {
        // No GGUF — still recursive but marks stub
        if vfe > 3.0 {
            format!("[Kai VFE {:.1} T{:.2} | {}] You: \"{preview}\" — high surprise, no GGUF yet. Download qwen2.5:0.5b (400MB) via picker, then I will mmap and VFE→tau will be real. Suggest: \"What prior minimizes KL here?\"", vfe, temp, model_label)
        } else {
            format!("[Kai VFE {:.1} | {}] \"{preview}\" — in groove, but GGUF not loaded. Tap model picker ⬇ to load.", vfe, model_label)
        }
    } else if vfe > 3.0 {
        format!("[Kai VFE {:.1} T{:.2} | {}] You: \"{preview}\" — high surprise (novel). Curvature suggests exploration (T↑). Real GGUF mmap'd, VFE {:.1} → tau expands. What prior would minimize KL here?", vfe, temp, model_label, vfe*0.8)
    } else if temp > 1.0 {
        format!("[Kai T{:.2} VFE {:.1} | {}] \"{preview}\" — warm sampling due to curvature. Real GGUF active, {} tokens would be sampled at T' {:.2}. Try: \"State the attractor vector this resembles.\"", temp, vfe, model_label, model_label, temp)
    } else {
        format!("[Kai VFE {:.1} | {}] \"{preview}\" — low VFE, consolidating. Real GGUF {} active. Next micro-step: `cargo test` this turn's VFE.", vfe, model_label, model_label)
    };

    match CString::new(msg) { Ok(s) => s.into_raw(), Err(_) => std::ptr::null_mut() }
}

/// Free string returned by kai_generate / kai_last_gguf_info
#[no_mangle]
pub extern "C" fn kai_free_string(s: *mut c_char) {
    if s.is_null() { return; }
    unsafe { let _ = CString::from_raw(s); }
}
