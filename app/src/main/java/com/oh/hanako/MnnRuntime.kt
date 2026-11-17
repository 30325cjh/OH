package com.oh.hanako

import android.content.Context
import com.mnn.sdk.MNNEngine
import com.mnn.sdk.MNNConfig

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
    /**
     * Creates one session at a time and reports tensor names, shapes and types.
     * Metadata inspection only; this does not synthesize audio.
     */
    fun inspectModelGraphs(context: Context): String {
        val root = java.io.File(
            context.getExternalFilesDir("models") ?: context.filesDir,
            "irodori-small-mf-w4"
        )
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val freeMb = memory.availMem / (1024L * 1024L)
        if (freeMb < 500L) {
            return "검사를 보류했어요. 현재 여유 RAM " + freeMb + "MB. 다른 앱을 닫고 다시 시도해 주세요."
        }
        val engine = try {
            MNNEngine.initialize(context.applicationContext)
        } catch (e: Exception) {
            return "MNN 엔진 초기화 실패: " + (e.message ?: e.javaClass.simpleName)
        }
        val results = mutableListOf<String>()
        for (name in listOf("encoder.mnn", "denoiser.mnn", "decoder.mnn")) {
            val file = java.io.File(root, "models/$name")
            if (!file.isFile) return "모델 파일 누락: $name"
            var model: com.mnn.sdk.MNNModel? = null
            var interpreter: com.mnn.sdk.MNNInterpreter? = null
            try {
                model = engine.loadModelFromPath(file.absolutePath)
                interpreter = model.createInterpreter(MNNConfig(numThreads = 1))
                results.add("$name\n${interpreter.describeTensors()}")
            } catch (e: Throwable) {
                return "$name 구조 검사 실패: " + (e.message ?: e.javaClass.simpleName) +
                    "\n앞서 확인한 결과:\n" + results.joinToString("\n\n")
            } finally {
                try { interpreter?.close() } catch (_: Exception) {}
                try { model?.close() } catch (_: Exception) {}
            }
            System.gc()
        }
        return "MNN 그래프 입출력 구조 검사 완료 (음성 생성 전 단계)\n\n" +
            results.joinToString("\n\n")
    }

}
