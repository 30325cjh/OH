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

    /**
     * Loads each packaged MNN graph by path, one at a time, and releases it immediately.
     * This is a format/load smoke test, not a TTS synthesis test.
     */
    fun checkModelFiles(context: Context): String {
        val root = java.io.File(
            context.getExternalFilesDir("models") ?: context.filesDir,
            "irodori-small-mf-w4"
        )
        val engine = try {
            MNNEngine.initialize(context.applicationContext)
        } catch (e: Exception) {
            return "MNN 엔진 초기화 실패: ${e.message ?: e.javaClass.simpleName}"
        }
        val names = listOf("encoder.mnn", "denoiser.mnn", "decoder.mnn")
        val results = mutableListOf<String>()
        for (name in names) {
            val file = java.io.File(root, "models/$name")
            if (!file.isFile) return "모델 파일 누락: $name"
            try {
                val model = engine.loadModelFromPath(file.absolutePath)
                model.close()
                results.add("$name OK")
            } catch (e: Throwable) {
                return "MNN 로딩 실패: $name\\n${e.message ?: e.javaClass.simpleName}\\n앞서 확인된 항목: ${results.joinToString()}"
            }
        }
        return "MNN 모델 3종 로딩 성공!\\n${results.joinToString("\\n")}\\n다음은 ONNX 인코더와 TTS 연결 단계예요."
    }
}
