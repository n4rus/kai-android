//! Kai Bridge — Rust cdylib for Android JNI
//! Exposes kai-core physics to Kotlin via JNI shim (native-lib.cpp).
//! Day 3-4: wire to rust/kai-fusion Loader + VFE + g_ij.

use std::ffi::{CStr, CString};
use std::os::raw::c_char;

/// Stub: load GGUF at path → 0 ok, -1 err
#[no_mangle]
pub extern "C" fn kai_load_gguf(path: *const c_char) -> i32 {
    if path.is_null() { return -1; }
    let cstr = unsafe { CStr::from_ptr(path) };
    let _p = cstr.to_string_lossy();
    // TODO: call crate::loader::load_gguf(&p) and cache handle
    0
}

/// VFE = surprise + KL — mirrors physics-dialect calculate_vfe
#[no_mangle]
pub extern "C" fn kai_calculate_vfe(surprise: f32, kl: f32) -> f32 {
    surprise + kl
}

/// T' = T * (1 + alpha * curvature) — mirrors curvature.rs
#[no_mangle]
pub extern "C" fn kai_curvature_to_temp(base_temp: f32, curvature: f32, alpha: f32) -> f32 {
    base_temp * (1.0 + alpha * curvature)
}

/// Version string — caller must not free
#[no_mangle]
pub extern "C" fn kai_version() -> *const c_char {
    static S: &[u8] = b"kai-bridge 0.1.0 (stub)\0";
    S.as_ptr() as *const c_char
}
