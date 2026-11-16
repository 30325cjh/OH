package com.oh.hanako

import com.taobao.android.mnn.MNNNetNative

/** Small health-check boundary for the bundled MNN Android runtime. */
object MnnRuntime {
    data class Status(val available: Boolean, val detail: String)

    fun check(): Status = try {
        if (MNNNetNative.isRuntimeReady()) {
            Status(true, "MNN native runtime loaded")
        } else {
            val cause = MNNNetNative.getLoadError()
            Status(false, cause?.let { it.javaClass.simpleName + ": " + it.message }
                ?: "MNN native runtime did not initialize")
        }
    } catch (error: Throwable) {
        Status(false, error.javaClass.simpleName + ": " + error.message)
    }
}
