package com.oh.hanako

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Read-only inventory of the installed Irodori pack.
 * Does not load ONNX graphs or allocate model-sized tensors.
 */
object IrodoriModelInventory {
    fun inspect(context: Context): String {
        val root = File(
            context.getExternalFilesDir("models") ?: context.filesDir,
            "irodori-small-mf-w4"
        )
        val manifestFile = File(root, "pack.json")
        if (!manifestFile.isFile) return "pack.json을 찾지 못했어요. 모델 설치 상태를 확인해 주세요."

        return try {
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val files = manifest.optJSONObject("files")
                ?: return "pack.json에 files 목록이 없어요."
            val onnx = mutableListOf<Pair<String, Long>>()
            val mnn = mutableListOf<Pair<String, Long>>()
            val other = mutableListOf<Pair<String, Long>>()
            val keys = files.keys()
            while (keys.hasNext()) {
                val relative = keys.next()
                val item = files.optJSONObject(relative) ?: continue
                val expected = item.optLong("bytes", -1L)
                val actual = File(root, relative)
                val row = relative to expected
                when {
                    relative.endsWith(".onnx", ignoreCase = true) -> onnx.add(row)
                    relative.endsWith(".mnn", ignoreCase = true) -> mnn.add(row)
                    else -> other.add(row)
                }
                if (expected > 0L && (!actual.isFile || actual.length() != expected)) {
                    return "파일 누락 또는 크기 불일치: $relative"
                }
            }
            fun format(title: String, entries: List<Pair<String, Long>>): String {
                if (entries.isEmpty()) return "$title: 없음"
                val lines = entries.sortedBy { it.first }.map {
                    "• ${it.first} — ${"%.1f".format(it.second / 1_000_000.0)} MB"
                }
                return "$title (${entries.size})\\n" + lines.joinToString("\\n")
            }
            "모델 파일 목록 확인 완료 (파일을 메모리에 올리지는 않았어요)\\n" +
                format("ONNX", onnx) + "\\n" +
                format("MNN", mnn) + "\\n" +
                "기타 파일: ${other.size}개"
        } catch (e: Exception) {
            "모델 파일 목록 확인 실패: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
