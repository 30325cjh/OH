from pathlib import Path

root = Path("mnn-android-sdk/mnn-sdk/src/main")
kt = root / "kotlin/com/mnn/sdk/MNNInterpreter.kt"
cpp = root / "cpp/mnn_interpreter.cpp"

k = kt.read_text()
needle = """    fun resizeSession() {
        checkNotClosed()
        nativeResizeSession(interpreterPtr, sessionPtr)
    }
"""
addition = needle + """
    /**
     * Describe all graph inputs and outputs, including their current shapes and element types.
     * Intended for model bring-up and diagnostics before inference tensors are allocated.
     */
    fun describeTensors(): String {
        checkNotClosed()
        return nativeDescribeTensors(interpreterPtr, sessionPtr)
    }
"""
if "fun describeTensors()" not in k:
    if needle not in k:
        raise SystemExit("MNNInterpreter.kt insertion point not found")
    k = k.replace(needle, addition)
needle2 = """    private external fun nativeRunWithMetrics(interpreterPtr: Long, sessionPtr: Long): HashMap<String, Any>
"""
addition2 = needle2 + """    private external fun nativeDescribeTensors(interpreterPtr: Long, sessionPtr: Long): String
"""
if "private external fun nativeDescribeTensors" not in k:
    if needle2 not in k:
        raise SystemExit("MNNInterpreter.kt native declaration point not found")
    k = k.replace(needle2, addition2)
kt.write_text(k)

c = cpp.read_text()
if "Java_com_mnn_sdk_MNNInterpreter_nativeDescribeTensors" not in c:
    if '#include <android/log.h>' not in c or '} // extern "C"' not in c:
        raise SystemExit("mnn_interpreter.cpp insertion point not found")
    c = c.replace('#include <android/log.h>', '#include <android/log.h>\n#include <sstream>')
    native = r'''
/**
 * Describe all named input/output tensors in the current MNN session.
 */
JNIEXPORT jstring JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeDescribeTensors(
    JNIEnv* env, jobject thiz, jlong interpreter_ptr, jlong session_ptr) {
    if (interpreter_ptr == 0 || session_ptr == 0) {
        return env->NewStringUTF("Invalid interpreter/session handle");
    }
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    auto* session = reinterpret_cast<MNN::Session*>(session_ptr);
    std::ostringstream out;
    auto append = [&](const char* label, const std::map<std::string, MNN::Tensor*>& tensors) {
        out << label << " (" << tensors.size() << ")\n";
        for (const auto& entry : tensors) {
            const auto* tensor = entry.second;
            if (tensor == nullptr) continue;
            const auto shape = tensor->shape();
            const auto type = tensor->getType();
            out << "  " << entry.first << ": shape=[";
            for (size_t i = 0; i < shape.size(); ++i) {
                if (i) out << ",";
                out << shape[i];
            }
            out << "], type_code=" << static_cast<int>(type.code)
                << ", bits=" << static_cast<int>(type.bits)
                << ", lanes=" << static_cast<int>(type.lanes) << "\n";
        }
    };
    append("INPUTS", interpreter->getSessionInputAll(session));
    append("OUTPUTS", interpreter->getSessionOutputAll(session));
    const std::string result = out.str();
    return env->NewStringUTF(result.c_str());
}

'''
    c = c.replace('} // extern "C"', native + '} // extern "C"')
cpp.write_text(c)
