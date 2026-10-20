// SPDX-License-Identifier: MPL-2.0
// Compiled into libandroidapp against the exact COKit headers used by the editor.
#include <config.h>
#include <jni.h>
#include <memory>
#include <mutex>
#include <string>
#include <stdexcept>
#include <cstdlib>
#define LIBO_INTERNAL_ONLY
#include <COKit/COKit.hxx>
#include <Kit.hpp>
#include <SetupKitEnvironment.hpp>

extern "C" COKit* cokit_hook_2(const char*, const char*);
extern "C" jboolean cokit_initialize(JNIEnv*, jstring, jstring, jstring, jobject);

namespace {
class Utf {
    JNIEnv* env; jstring text; const char* value;
public:
    Utf(JNIEnv* e, jstring s) : env(e), text(s), value(e->GetStringUTFChars(s, nullptr)) {}
    ~Utf() { env->ReleaseStringUTFChars(text, value); }
    const char* get() const { return value; }
};
void fail(JNIEnv* env, const std::string& message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}
std::mutex conversionMutex;
COKit* conversionKit = nullptr; // Owned by the disposable :conversion process.
}

extern "C" JNIEXPORT void JNICALL
Java_dev_diogo_officelocal_OfficeBridge_convert(JNIEnv* env, jclass, jstring data,
    jstring cache, jstring apk, jobject assets, jstring profile, jstring input, jstring output) {
    std::lock_guard<std::mutex> lock(conversionMutex);
    try {
        if (!conversionKit) {
            setupKitEnvironment("classic");
            // Conversion has its own event loop. The editor's unipoll loop is in :editor.
            setenv("SAL_KIT_OPTIONS", "compact_fonts", 1);
            setenv("KIT_DISABLE_SHARED_LIBRARY_LOADER", "1", 1);
            if (!cokit_initialize(env, data, cache, apk, assets))
                throw std::runtime_error("Falha ao preparar os recursos locais do motor Office.");
            // Android resolves bootstrap resources through its APK VFS and
            // APP_DATA_DIR. Match Kit.cpp's mobile initialization exactly.
            conversionKit = cokit_hook_2(nullptr, nullptr);
            if (!conversionKit) throw std::runtime_error("Não foi possível iniciar o motor Office local.");
            conversionKit->setOptionalFeatures(COKitOptionalFeatures::DOCUMENT_PASSWORD);
            conversionKit->registerCallback([](COKitCallbackType type, const char* url, void* ctx) {
                if (type == COKitCallbackType::DOCUMENT_PASSWORD || type == COKitCallbackType::DOCUMENT_PASSWORD_TO_MODIFY)
                    static_cast<COKit*>(ctx)->setDocumentPassword(url, nullptr);
            }, conversionKit);
        }
        Utf source(env, input), destination(env, output);
        std::unique_ptr<COKitDocument> document(conversionKit->documentLoadWithOptions(
            source.get(), "Language=pt-BR,MacroSecurityLevel=3,EnableMacrosExecution=false"));
        if (!document) throw std::runtime_error("Documento protegido ou inválido: " + conversionKit->getError());
        if (!document->saveAs(destination.get(), "pdf", "{\"ExportHiddenSlides\":{\"type\":\"boolean\",\"value\":\"true\"}}"))
            throw std::runtime_error("Falha ao exportar PDF: " + conversionKit->getError());
    } catch (const std::exception& error) { fail(env, error.what()); }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_diogo_officelocal_OfficeBridge_exportCurrent(JNIEnv* env, jclass, jstring output, jstring format) {
    try {
        Utf destination(env, output), extension(env, format);
        auto document = getLOKDocumentForAndroidOnly();
        if (!document || !document->saveAs(destination.get(), extension.get(), nullptr))
            throw std::runtime_error("O editor não conseguiu exportar o estado atual.");
    } catch (const std::exception& error) { fail(env, error.what()); }
}
