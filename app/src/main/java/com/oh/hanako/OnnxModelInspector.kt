package com.oh.hanako

import android.app.ActivityManager
import android.content.Context
import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File

/**
 * Opens each small conditioning ONNX graph one at a time and reports its
 * declared input/output metadata. This is a graph inspection, not synthesis.
 */
object OnnxModelInspector {
    fun inspect(context: Context): String {
        val root = File(
            context.getExternalFilesDir("models") ?: context.filesDir,
            "irodori-small-mf-w4"
        )
        val manifestFile = File(root, "pack.json")
        if (!manifestFile.isFile) return "pack.json을 찾지 못했어요. 먼저 모델을 설치해 주세요."

        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val freeMb = memory.availMem / (1024L * 1024L)
        if (freeMb < 450L) {
            return "검사를 잠시 보류했어요. 현재 여유 RAM이 ${freeMb}MB뿐이에요. 백그라운드 앱을 종료한 뒤 다시 실행해 주세요."
        }

        return try {
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val files = manifest.optJSONObject("files")
                ?: return "pack.json에 files 목록이 없어요."
            val paths = mutableListOf<String>()
            val keys = files.keys()
            while (keys.hasNext()) {
                val path = keys.next()
                if (path.endsWith(".onnx", ignoreCase = true)) paths.add(path)
            }
            if (paths.isEmpty()) return "검사할 ONNX 파일이 없어요."

            val env = OrtEnvironment.getEnvironment()
            val report = mutableListOf<String>()
            for (relative in paths.sorted()) {
                val modelFile = File(root, relative)
                if (!modelFile.isFile) return "ONNX 파일 누락: $relative"
                var options: OrtSession.SessionOptions? = null
                var session: OrtSession? = null
                try {
                    options = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(1)
                    }
                    session = env.createSession(modelFile.absolutePath, options)
                    val inputs = session.inputInfo.entries.joinToString("; ") { entry ->
                        "${entry.key}: ${entry.value.info}"
                    }
                    val outputs = session.outputInfo.entries.joinToString("; ") { entry ->
                        "${entry.key}: ${entry.value.info}"
                    }
                    report.add(
                        "${relative.substringAfterLast('/')}\n" +
                            "입력(${session.inputInfo.size}): ${inputs.ifBlank { "없음" }}\n" +
                            "출력(${session.outputInfo.size}): ${outputs.ifBlank { "없음" }}"
                    )
                } catch (e: Throwable) {
                    report.add("${relative.substringAfterLast('/')} 검사 실패: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    try { session?.close() } catch (_: Exception) {}
                    try { options?.close() } catch (_: Exception) {}
                }
                System.gc()
            }
            "ONNX 구조 검사 완료 (실제 음성 생성은 아직 아님)\n\n" + report.joinToString("\n\n")
        } catch (e: Throwable) {
            "ONNX 검사 초기화 실패: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
