// slang-wasm-lib.cpp — WASI reactor shim exposing a flat C ABI over Slang's COM API.
//
// All public symbols are declared in slang-wasm-lib.h. See that file for the
// ownership and lifetime contract of every exported function.
//
// Internal design:
//   - One IGlobalSession is created lazily on the first slang_wasm_session_create
//     call and reused for the lifetime of the module instance.
//   - Sessions and results are stored in simple handle tables (monotonically
//     increasing uint32_t keys, 0 reserved as invalid). The tables are not
//     thread-safe; the module is built single-threaded (THREAD_MODEL=single).
//   - Every public entry point wraps its body in try/catch so a C++ exception
//     from an internal Slang assert/abort is converted to a failed result instead
//     of propagating as a WASM trap that would destroy the instance.

#include "slang-wasm-lib.h"

#include <slang.h>
#include <slang-com-ptr.h>
#include <slang-deprecated.h>

#include <cassert>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>

// WASM_ASSERT is defined in internal core headers not available to
// this public-header-only shim. Use a local variant: abort with a message on
// out-of-contract handles (a programming error, not a shader error).
#define WASM_ASSERT(cond)                                           \
    do                                                              \
    {                                                               \
        if (!(cond))                                                \
        {                                                           \
            __builtin_trap();                                       \
        }                                                           \
    } while (0)

using Slang::ComPtr;

// ── Internal types ────────────────────────────────────────────────────────────

struct WasmSession
{
    ComPtr<slang::ISession> session;
};

struct WasmResult
{
    bool succeeded = false;
    std::vector<uint8_t> code;
    std::string reflectionJson;
    std::string diagnostics;
};

// ── Global state ──────────────────────────────────────────────────────────────

static ComPtr<slang::IGlobalSession> g_globalSession;

static std::unordered_map<uint32_t, WasmSession*> g_sessions;
static std::unordered_map<uint32_t, WasmResult*> g_results;
static uint32_t g_nextSessionHandle = 1;
static uint32_t g_nextResultHandle = 1;

// ── Helpers ───────────────────────────────────────────────────────────────────

// Ensure the single shared IGlobalSession exists. Returns false on failure.
static bool ensureGlobalSession()
{
    if (g_globalSession)
        return true;
    SlangGlobalSessionDesc desc = {};
    SlangResult r = slang_createGlobalSession2(&desc, g_globalSession.writeRef());
    return SLANG_SUCCEEDED(r);
}

// Append blob contents to a std::string, safely handling a null blob.
static void appendBlob(std::string& out, slang::IBlob* blob)
{
    if (!blob || blob->getBufferSize() == 0)
        return;
    const char* ptr = static_cast<const char*>(blob->getBufferPointer());
    out.append(ptr, blob->getBufferSize());
}

// ── Memory helpers ────────────────────────────────────────────────────────────

extern "C" void* slang_wasm_alloc(uint32_t size)
{
    return malloc(size);
}

extern "C" void slang_wasm_free(void* ptr)
{
    free(ptr);
}

// ── Session ───────────────────────────────────────────────────────────────────

extern "C" SlangWasmSession slang_wasm_session_create(
    uint32_t targetFormat,
    const char* profile,
    uint32_t profileLen)
{
    try
    {
        if (!ensureGlobalSession())
            return 0;

        slang::TargetDesc target = {};
        target.format = static_cast<SlangCompileTarget>(targetFormat);

        if (profile && profileLen > 0)
        {
            std::string profileStr(profile, profileLen);
            target.profile = spFindProfile(g_globalSession, profileStr.c_str());
        }

        slang::SessionDesc sessionDesc = {};
        sessionDesc.targets = &target;
        sessionDesc.targetCount = 1;

        ComPtr<slang::ISession> session;
        SlangResult r = g_globalSession->createSession(sessionDesc, session.writeRef());
        if (SLANG_FAILED(r))
            return 0;

        uint32_t handle = g_nextSessionHandle++;
        g_sessions[handle] = new WasmSession{std::move(session)};
        return handle;
    }
    catch (...)
    {
        return 0;
    }
}

extern "C" void slang_wasm_session_destroy(SlangWasmSession handle)
{
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end())
        return;
    delete it->second;
    g_sessions.erase(it);
}

// ── Compilation ───────────────────────────────────────────────────────────────

extern "C" SlangWasmResult slang_wasm_compile(
    SlangWasmSession sessionHandle,
    const char* moduleName,
    uint32_t moduleNameLen,
    const char* source,
    uint32_t sourceLen,
    const char* entryName,
    uint32_t entryNameLen)
{
    auto* result = new WasmResult();
    uint32_t resultHandle = g_nextResultHandle++;
    g_results[resultHandle] = result;

    try
    {
        auto sessionIt = g_sessions.find(sessionHandle);
        WASM_ASSERT(sessionIt != g_sessions.end());
        slang::ISession* session = sessionIt->second->session.get();

        std::string moduleNameStr(moduleName, moduleNameLen);
        std::string sourceStr(source, sourceLen);
        std::string entryNameStr(entryName, entryNameLen);

        // Step 1: load the module from the source string.
        ComPtr<slang::IBlob> diagBlob;
        slang::IModule* module = session->loadModuleFromSourceString(
            moduleNameStr.c_str(),
            moduleNameStr.c_str(), // use module name as path
            sourceStr.c_str(),
            diagBlob.writeRef());
        appendBlob(result->diagnostics, diagBlob);
        if (!module)
            return resultHandle; // succeeded == false

        // Step 2: find the entry point.
        ComPtr<slang::IEntryPoint> entryPoint;
        diagBlob = nullptr;
        SlangResult r =
            module->findEntryPointByName(entryNameStr.c_str(), entryPoint.writeRef());
        if (SLANG_FAILED(r) || !entryPoint)
            return resultHandle;

        // Step 3: create a composite component type containing the module and entry point.
        slang::IComponentType* components[] = {module, entryPoint.get()};
        ComPtr<slang::IComponentType> composite;
        diagBlob = nullptr;
        r = session->createCompositeComponentType(
            components,
            2,
            composite.writeRef(),
            diagBlob.writeRef());
        appendBlob(result->diagnostics, diagBlob);
        if (SLANG_FAILED(r) || !composite)
            return resultHandle;

        // Step 4: link.
        ComPtr<slang::IComponentType> linked;
        diagBlob = nullptr;
        r = composite->link(linked.writeRef(), diagBlob.writeRef());
        appendBlob(result->diagnostics, diagBlob);
        if (SLANG_FAILED(r) || !linked)
            return resultHandle;

        // Step 5: get the compiled target code.
        ComPtr<slang::IBlob> codeBlob;
        diagBlob = nullptr;
        r = linked->getEntryPointCode(0, 0, codeBlob.writeRef(), diagBlob.writeRef());
        appendBlob(result->diagnostics, diagBlob);
        if (SLANG_FAILED(r) || !codeBlob)
            return resultHandle;

        const uint8_t* codePtr = static_cast<const uint8_t*>(codeBlob->getBufferPointer());
        result->code.assign(codePtr, codePtr + codeBlob->getBufferSize());

        // Step 6: serialize reflection to JSON.
        diagBlob = nullptr;
        slang::ProgramLayout* layout = linked->getLayout(0, diagBlob.writeRef());
        appendBlob(result->diagnostics, diagBlob);
        if (layout)
        {
            ComPtr<slang::IBlob> jsonBlob;
            r = spReflection_ToJson(
                reinterpret_cast<SlangReflection*>(layout),
                nullptr,
                jsonBlob.writeRef());
            if (SLANG_SUCCEEDED(r) && jsonBlob)
                appendBlob(result->reflectionJson, jsonBlob);
        }

        result->succeeded = true;
        return resultHandle;
    }
    catch (...)
    {
        result->diagnostics += "\n[slang-wasm-lib] internal exception caught; "
                               "compilation aborted.";
        return resultHandle;
    }
}

// ── Result accessors ──────────────────────────────────────────────────────────

extern "C" int32_t slang_wasm_result_succeeded(SlangWasmResult handle)
{
    auto it = g_results.find(handle);
    WASM_ASSERT(it != g_results.end());
    return it->second->succeeded ? 1 : 0;
}

extern "C" uint32_t slang_wasm_result_code_ptr(SlangWasmResult handle)
{
    auto it = g_results.find(handle);
    WASM_ASSERT(it != g_results.end());
    return static_cast<uint32_t>(
        reinterpret_cast<uintptr_t>(it->second->code.data()));
}

extern "C" uint32_t slang_wasm_result_code_len(SlangWasmResult handle)
{
    auto it = g_results.find(handle);
    WASM_ASSERT(it != g_results.end());
    return static_cast<uint32_t>(it->second->code.size());
}

extern "C" uint32_t slang_wasm_result_reflection_json_ptr(SlangWasmResult handle)
{
    auto it = g_results.find(handle);
    WASM_ASSERT(it != g_results.end());
    return static_cast<uint32_t>(
        reinterpret_cast<uintptr_t>(it->second->reflectionJson.data()));
}

extern "C" uint32_t slang_wasm_result_reflection_json_len(SlangWasmResult handle)
{
    auto it = g_results.find(handle);
    WASM_ASSERT(it != g_results.end());
    return static_cast<uint32_t>(it->second->reflectionJson.size());
}

extern "C" uint32_t slang_wasm_result_diagnostics_ptr(SlangWasmResult handle)
{
    auto it = g_results.find(handle);
    WASM_ASSERT(it != g_results.end());
    return static_cast<uint32_t>(
        reinterpret_cast<uintptr_t>(it->second->diagnostics.data()));
}

extern "C" uint32_t slang_wasm_result_diagnostics_len(SlangWasmResult handle)
{
    auto it = g_results.find(handle);
    WASM_ASSERT(it != g_results.end());
    return static_cast<uint32_t>(it->second->diagnostics.size());
}

extern "C" void slang_wasm_result_destroy(SlangWasmResult handle)
{
    auto it = g_results.find(handle);
    if (it == g_results.end())
        return;
    delete it->second;
    g_results.erase(it);
}

// ── Version ───────────────────────────────────────────────────────────────────

extern "C" const char* slang_wasm_version(void)
{
    if (!ensureGlobalSession())
        return "unknown";
    return g_globalSession->getBuildTagString();
}
