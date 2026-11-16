package com.oh.hanako

import android.content.Context
import com.mnn.sdk.MNNEngine

/**
 * Initializes the pinned MNN 3.6.1 Android runtime and reports its actual version.
 * This verifies the native engine only; Irodori model orchestration is a separate stage.
 */
object MnnRuntime {
    data class Status(val available: Boolean, val detail: String)

    fun check(context: Context): Status = try {
        val version = MNNEngine.initialize(context.applicationContext).getVersion()
        if (version == "3.6.1") {
            Status(true, "MNN $version native runtime loaded")
        } else {
            Status(false, "Unexpected MNN runtime version: $version (expected 3.6.1)")
        }
    } catch (error: UnsatisfiedLinkError) {
        Status(false, "MNN native runtime unavailable: ${error.message ?: "linker error"}")
    } catch (error: Exception) {
        Status(false, "MNN runtime initialization failed: ${error.message ?: error.javaClass.simpleName}")
    }
}
