package com.oh.hanako

/**
 * Loads the MNN core JNI library shipped by the Android AAR.
 * The MNN Java API is intentionally not referenced here: the published
 * AAR does not expose the MNNNetNative class used by the previous draft.
 */
object MnnRuntime {
    data class Status(val available: Boolean, val detail: String)

    fun check(): Status = try {
        System.loadLibrary("MNN")
        Status(true, "MNN native library loaded")
    } catch (error: UnsatisfiedLinkError) {
        Status(false, "MNN native library unavailable: ${error.message ?: "unknown linker error"}")
    } catch (error: SecurityException) {
        Status(false, "MNN native library blocked: ${error.message ?: "security error"}")
    }
}
