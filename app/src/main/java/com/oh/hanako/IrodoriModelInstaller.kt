package com.oh.hanako

import android.content.Context
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads only the files referenced by the pinned Small-MF W4 pack manifest.
 * Every blob is checked against its published size and SHA-256 before being kept.
 * This installs model data only; it does not claim that speech inference is ready.
 */
object IrodoriModelInstaller {
    private const val REVISION = "7f86a777d6a849f7c2fe6e17d6161e5294ac3f92"
    private const val BASE = "https://huggingface.co/david419kr/Irodori-TTS-v4.1-Mobile-MNN"
    private const val PACK = "packs/small-mf-w4/pack.json"

    fun install(context: Context, progress: (String) -> Unit): String {
        val root = File(context.getExternalFilesDir("models") ?: context.filesDir, "irodori-small-mf-w4")
        if (!root.exists() && !root.mkdirs()) return "모델 폴더를 만들지 못했어요."
        return try {
            progress("모델 목록 확인 중...")
            val manifestFile = File(root, "pack.json")
            if (!manifestFile.isFile || manifestFile.length() == 0L) {
                download("$BASE/resolve/$REVISION/$PACK", manifestFile)
            }
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            if (manifest.optString("status") != "prepared" ||
                manifest.optString("mnn_version") != "3.6.1" ||
                manifest.optString("flow_parameterization") != "meanflow" ||
                manifest.optInt("weight_quant_bits") != 4
            ) return "모델 manifest 검증 실패: 예상한 Small-MF W4 / MNN 3.6.1 구성이 아니에요."

            val files = manifest.getJSONObject("files")
            val entries = ArrayList<Triple<String, Long, String>>()
            var total = 0L
            val keys = files.keys()
            while (keys.hasNext()) {
                val relative = keys.next()
                if (relative.startsWith("/") || relative.split("/").any { it == ".." }) {
                    return "모델 manifest에 안전하지 않은 파일 경로가 있어 설치를 중단했어요."
                }
                val item = files.getJSONObject(relative)
                val size = item.getLong("bytes")
                val hash = item.getString("sha256").lowercase()
                if (size <= 0L || !hash.matches(Regex("[0-9a-f]{64}"))) {
                    return "모델 manifest의 파일 정보가 올바르지 않아요."
                }
                entries.add(Triple(relative, size, hash))
                total += size
            }
            val stat = StatFs(root.absolutePath)
            val required = total + 256L * 1024L * 1024L
            if (stat.availableBytes < required) {
                return "저장 공간이 부족해요. 모델 약 %.2fGB와 여유 공간 256MB가 필요해요."
                    .format(total / 1_000_000_000.0)
            }

            var completed = 0L
            for ((relative, size, hash) in entries) {
                val target = File(root, relative)
                val canonicalRoot = root.canonicalPath + File.separator
                if (!target.canonicalPath.startsWith(canonicalRoot)) {
                    return "잘못된 모델 파일 경로를 감지했어요."
                }
                if (target.isFile && target.length() == size && sha256(target) == hash) {
                    completed += size
                    continue
                }
                target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("폴더 생성 실패: $relative") }
                val temp = File(target.parentFile, target.name + ".part")
                if (temp.exists()) temp.delete()
                val url = "$BASE/resolve/$REVISION/blobs/$hash"
                download(url, temp) { received ->
                    val percent = ((completed + received).toDouble() / total * 100.0).toInt()
                    progress("Irodori 모델 다운로드 중... $percent%")
                }
                if (temp.length() != size) {
                    temp.delete()
                    error("파일 크기 검증 실패: $relative")
                }
                if (sha256(temp) != hash) {
                    temp.delete()
                    error("SHA-256 검증 실패: $relative")
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) error("파일 저장 실패: $relative")
                completed += size
            }
            File(root, "INSTALL_COMPLETE.txt").writeText(
                "Irodori-TTS v4.1 Small-MF W4\nRevision: $REVISION\nModel files verified by SHA-256.\n",
                Charsets.UTF_8
            )
            "모델 파일 설치 및 SHA-256 검증 완료! 단, 음성 추론 연결은 다음 단계예요."
        } catch (e: Exception) {
            "모델 설치 실패: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun download(url: String, destination: File, onProgress: (Long) -> Unit = {}) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "OH-Hanako-Android")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("서버 응답 오류 HTTP $code")
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var received = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        onProgress(received)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
