//! Kai Bridge — Rust cdylib for Android JNI
//! Recursive Kai chat: exposes VFE, curvature, GGUF load, and generate.
//! Day 1 stub → later wires to rust/kai-fusion Loader + VFE + g_ij.

use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::sync::Mutex;

// Simple in-mem chat stub state
static LAST_MODEL: Mutex<String> = Mutex::new(String::new());

/// Load GGUF at path → 0 ok, -1 err
#[no_mangle]
pub extern "C" fn kai_load_gguf(path: *const c_char) -> i32 {
    if path.is_null() { return -1; }
    let cstr = unsafe { CStr::from_ptr(path) };
    if let Ok(s) = cstr.to_str() {
        if let Ok(mut m) = LAST_MODEL.lock() { *m = s.to_string(); }
        // TODO: call kai-fusion loader::load_gguf(&s) and cache handle
        return 0;
    }
    -1
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
    static S: &[u8] = b"kai-bridge 0.2.0-recursive\0";
    S.as_ptr() as *const c_char
}

/// Generate — stub recursive Kai response
/// prompt *const c_char, temp f32, vfe f32 → *mut c_char (caller must free via kai_free_string)
#[no_mangle]
pub extern "C" fn kai_generate(prompt: *const c_char, temp: f32, vfe: f32) -> *mut c_char {
    if prompt.is_null() { return std::ptr::null_mut(); }
    let cstr = unsafe { CStr::from_ptr(prompt) };
    let p = cstr.to_string_lossy();
    let preview = if p.len() > 80 { format!("{}…", &p[..80]) } else { p.to_string() };

    // Recursive Kai personality: echoes VFE/curvature-aware, suggests next
    let msg = if vfe > 3.0 {
        format!("[Kai VFE {:.1} T{:.2}] You said: \"{preview}\" — high surprise (novel). Curvature suggests exploration. What if we ask: \"What prior would minimize KL here?\" (VFE {:.1} → temp {:.2} consolidates next)", vfe, temp, vfe*0.8, temp*0.9)
    } else if temp > 1.0 {
        format!("[Kai T{:.2} VFE {:.1}] \"{preview}\" — warm sampling due to curvature. I hear novelty. Try: \"State the attractor vector this resembles.\" → VFE would drop to {:.1}", temp, vfe, vfe*0.7)
    } else {
        format!("[Kai VFE {:.1}] \"{preview}\" — in groove (low VFE). Consolidating. Next micro-step: distill this into a test: `assert_eq!(kai_calculate_vfe(s,kl), s+kl)`", vfe)
    };

    match CString::new(msg) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Free string returned by kai_generate
#[no_mangle]
pub extern "C" fn kai_free_string(s: *mut c_char) {
    if s.is_null() { return; }
    unsafe { let _ = CString::from_raw(s); }
}
