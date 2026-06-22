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
#include <cctype>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <unordered_map>
#include <utility>
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

// Declared in slang-wasm-enum-metadata.cpp; reused here to back
// slang_wasm_target_from_string / slang_wasm_stage_from_string without
// duplicating the name tables.
extern "C" uint32_t slang_wasm_enum_metadata_ptr(void);
extern "C" uint32_t slang_wasm_enum_metadata_len(void);

// ── Internal types ────────────────────────────────────────────────────────────

struct WasmSession
{
    ComPtr<slang::ISession> session;
};

// Builder for a slang::TargetDesc list. Profiles are resolved to a
// SlangProfileID eagerly (spFindProfile needs only the global session, not the
// session being built), so the builder owns no string state past add-time.
struct WasmTargetList
{
    std::vector<slang::TargetDesc> targets;
};

// Builder for a slang::PreprocessorMacroDesc list. Owns the name/value strings
// so the PreprocessorMacroDesc::name/value pointers (built lazily) stay valid.
struct WasmMacroList
{
    std::vector<std::pair<std::string, std::string>> entries;
};

// Builder for a search-path list. Owns the path strings for the same reason.
struct WasmPathList
{
    std::vector<std::string> paths;
};

// One session-wide compiler option entry, stored in raw form so the
// CompilerOptionEntry/CompilerOptionValue actually handed to Slang is built
// only once, at session_create2 time, after the builder is fully populated and
// its backing strings are no longer subject to reallocation.
struct WasmOptionEntry
{
    slang::CompilerOptionName name;
    bool isString;
    int32_t intValue = 0;
    std::string stringValue;
};

// Builder for a slang::CompilerOptionEntry list.
struct WasmOptions
{
    std::vector<WasmOptionEntry> entries;
};

struct WasmResult
{
    bool succeeded = false;
    std::vector<uint8_t> code;
    std::string reflectionJson;
    std::string diagnostics;
};

// A module parsed once via slang_wasm_session_load_module, kept alive across
// multiple independent compiles of its entry points. `session` is held so the
// module's backing ISession cannot be destroyed out from under it even if the
// caller destroys the session handle first.
struct WasmModule
{
    ComPtr<slang::ISession> session;
    slang::IModule* module = nullptr; // owned by `session`'s module cache, not by us
    std::vector<ComPtr<slang::IEntryPoint>> entryPoints;
    std::vector<std::string> entryPointNames;
};

// ── Global state ──────────────────────────────────────────────────────────────

static ComPtr<slang::IGlobalSession> g_globalSession;

static std::unordered_map<uint32_t, WasmSession*> g_sessions;
static std::unordered_map<uint32_t, WasmResult*> g_results;
static std::unordered_map<uint32_t, WasmTargetList*> g_targetLists;
static std::unordered_map<uint32_t, WasmMacroList*> g_macroLists;
static std::unordered_map<uint32_t, WasmPathList*> g_pathLists;
static std::unordered_map<uint32_t, WasmOptions*> g_optionLists;
static std::unordered_map<uint32_t, WasmModule*> g_modules;
static uint32_t g_nextSessionHandle = 1;
static uint32_t g_nextResultHandle = 1;
static uint32_t g_nextTargetListHandle = 1;
static uint32_t g_nextMacroListHandle = 1;
static uint32_t g_nextPathListHandle = 1;
static uint32_t g_nextOptionsHandle = 1;
static uint32_t g_nextModuleHandle = 1;

// Insert `value` into `table` under a freshly allocated handle from `*nextHandle`.
template<typename T>
static uint32_t insertHandle(
    std::unordered_map<uint32_t, T*>& table,
    uint32_t* nextHandle,
    T* value)
{
    uint32_t handle = (*nextHandle)++;
    table[handle] = value;
    return handle;
}

// Pop and return the value for `handle` from `table`, or nullptr if absent.
// Used to consume a builder handle exactly once (e.g. inside session_create2).
template<typename T>
static T* takeHandle(std::unordered_map<uint32_t, T*>& table, uint32_t handle)
{
    if (handle == 0)
        return nullptr;
    auto it = table.find(handle);
    if (it == table.end())
        return nullptr;
    T* value = it->second;
    table.erase(it);
    return value;
}

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

// Copy `blob`'s contents into a freshly malloc'd buffer and write its
// (ptr, len) into the caller-supplied out-params, or (0, 0) if `blob` is empty.
// The buffer is owned by the caller of the function that took these out-params;
// free it with slang_wasm_free. Used by APIs that can fail before producing a
// handle to hang diagnostics off of (e.g. slang_wasm_session_load_module), so
// diagnostics are not lost on a failed load.
static void writeDiagOut(slang::IBlob* blob, uint32_t* diagPtrOut, uint32_t* diagLenOut)
{
    if (!diagPtrOut || !diagLenOut)
        return;
    if (!blob || blob->getBufferSize() == 0)
    {
        *diagPtrOut = 0;
        *diagLenOut = 0;
        return;
    }
    size_t size = blob->getBufferSize();
    void* buf = malloc(size);
    memcpy(buf, blob->getBufferPointer(), size);
    *diagPtrOut = static_cast<uint32_t>(reinterpret_cast<uintptr_t>(buf));
    *diagLenOut = static_cast<uint32_t>(size);
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

// ── Enum metadata ─────────────────────────────────────────────────────────────

// Look up `name` (case-sensitively, against the upper-cased enumerator name
// recorded in the generated metadata blob) under the JSON sub-object `section`,
// matching e.g. "spirv" -> "SPIRV" : 6. Returns -1 if not found. Implemented by
// a small hand-rolled scan rather than a JSON parser: the metadata blob is a
// flat, generator-produced object with no nesting beyond two levels.
static int32_t lookupEnumByLowercaseName(const char* section, const std::string& nameUpper)
{
    const char* json = reinterpret_cast<const char*>(
        static_cast<uintptr_t>(slang_wasm_enum_metadata_ptr()));
    size_t jsonLen = slang_wasm_enum_metadata_len();
    std::string blob(json, jsonLen);

    std::string sectionKey = std::string("\"") + section + "\":{";
    size_t sectionPos = blob.find(sectionKey);
    if (sectionPos == std::string::npos)
        return -1;
    size_t sectionStart = sectionPos + sectionKey.size();
    size_t sectionEnd = blob.find("}", sectionStart);
    if (sectionEnd == std::string::npos)
        return -1;

    std::string entryKey = std::string("\"") + nameUpper + "\":";
    size_t entryPos = blob.find(entryKey, sectionStart);
    if (entryPos == std::string::npos || entryPos >= sectionEnd)
        return -1;

    size_t valueStart = entryPos + entryKey.size();
    return static_cast<int32_t>(std::strtol(blob.c_str() + valueStart, nullptr, 10));
}

// Upper-case a string in place, matching the generator's enumerator naming
// convention (e.g. "spirv" -> "SPIRV").
static std::string toUpper(const char* s, uint32_t len)
{
    std::string out(s, len);
    for (char& c : out)
        c = static_cast<char>(::toupper(static_cast<unsigned char>(c)));
    return out;
}

extern "C" int32_t slang_wasm_target_from_string(const char* name, uint32_t nameLen)
{
    if (!name || nameLen == 0)
        return -1;
    return lookupEnumByLowercaseName("Target", toUpper(name, nameLen));
}

extern "C" int32_t slang_wasm_stage_from_string(const char* name, uint32_t nameLen)
{
    if (!name || nameLen == 0)
        return -1;
    return lookupEnumByLowercaseName("Stage", toUpper(name, nameLen));
}

// ── Session descriptor builders ───────────────────────────────────────────────

extern "C" SlangWasmTargetList slang_wasm_target_list_create(void)
{
    return insertHandle(g_targetLists, &g_nextTargetListHandle, new WasmTargetList());
}

extern "C" void slang_wasm_target_list_add(
    SlangWasmTargetList listHandle,
    uint32_t format,
    const char* profile,
    uint32_t profileLen,
    uint32_t flags)
{
    auto it = g_targetLists.find(listHandle);
    WASM_ASSERT(it != g_targetLists.end());

    ensureGlobalSession();

    slang::TargetDesc target = {};
    target.format = static_cast<SlangCompileTarget>(format);
    target.flags = static_cast<SlangTargetFlags>(flags);
    if (profile && profileLen > 0 && g_globalSession)
    {
        std::string profileStr(profile, profileLen);
        target.profile = spFindProfile(g_globalSession, profileStr.c_str());
    }
    it->second->targets.push_back(target);
}

extern "C" void slang_wasm_target_list_destroy(SlangWasmTargetList handle)
{
    delete takeHandle(g_targetLists, handle);
}

extern "C" SlangWasmMacroList slang_wasm_macro_list_create(void)
{
    return insertHandle(g_macroLists, &g_nextMacroListHandle, new WasmMacroList());
}

extern "C" void slang_wasm_macro_list_add(
    SlangWasmMacroList listHandle,
    const char* name,
    uint32_t nameLen,
    const char* value,
    uint32_t valueLen)
{
    auto it = g_macroLists.find(listHandle);
    WASM_ASSERT(it != g_macroLists.end());
    it->second->entries.emplace_back(
        std::string(name, nameLen),
        std::string(value ? value : "", value ? valueLen : 0));
}

extern "C" void slang_wasm_macro_list_destroy(SlangWasmMacroList handle)
{
    delete takeHandle(g_macroLists, handle);
}

extern "C" SlangWasmPathList slang_wasm_path_list_create(void)
{
    return insertHandle(g_pathLists, &g_nextPathListHandle, new WasmPathList());
}

extern "C" void slang_wasm_path_list_add(
    SlangWasmPathList listHandle,
    const char* path,
    uint32_t pathLen)
{
    auto it = g_pathLists.find(listHandle);
    WASM_ASSERT(it != g_pathLists.end());
    it->second->paths.emplace_back(path, pathLen);
}

extern "C" void slang_wasm_path_list_destroy(SlangWasmPathList handle)
{
    delete takeHandle(g_pathLists, handle);
}

extern "C" SlangWasmOptions slang_wasm_options_create(void)
{
    return insertHandle(g_optionLists, &g_nextOptionsHandle, new WasmOptions());
}

extern "C" void slang_wasm_options_add_string(
    SlangWasmOptions optsHandle,
    uint32_t name,
    const char* val,
    uint32_t valLen)
{
    auto it = g_optionLists.find(optsHandle);
    WASM_ASSERT(it != g_optionLists.end());
    WasmOptionEntry entry;
    entry.name = static_cast<slang::CompilerOptionName>(name);
    entry.isString = true;
    entry.stringValue.assign(val, valLen);
    it->second->entries.push_back(std::move(entry));
}

extern "C" void slang_wasm_options_add_int(SlangWasmOptions optsHandle, uint32_t name, int32_t val)
{
    auto it = g_optionLists.find(optsHandle);
    WASM_ASSERT(it != g_optionLists.end());
    WasmOptionEntry entry;
    entry.name = static_cast<slang::CompilerOptionName>(name);
    entry.isString = false;
    entry.intValue = val;
    it->second->entries.push_back(std::move(entry));
}

extern "C" void slang_wasm_options_destroy(SlangWasmOptions handle)
{
    delete takeHandle(g_optionLists, handle);
}

// ── Session ───────────────────────────────────────────────────────────────────

extern "C" SlangWasmSession slang_wasm_session_create2(
    SlangWasmTargetList targetsHandle,
    SlangWasmMacroList macrosHandle,
    SlangWasmPathList pathsHandle,
    SlangWasmOptions optionsHandle)
{
    // Builders are consumed exactly once: take ownership now so they are freed
    // on every return path (failure or success) without duplicating cleanup.
    std::unique_ptr<WasmTargetList> targets(takeHandle(g_targetLists, targetsHandle));
    std::unique_ptr<WasmMacroList> macros(takeHandle(g_macroLists, macrosHandle));
    std::unique_ptr<WasmPathList> paths(takeHandle(g_pathLists, pathsHandle));
    std::unique_ptr<WasmOptions> options(takeHandle(g_optionLists, optionsHandle));

    try
    {
        if (!ensureGlobalSession())
            return 0;
        if (!targets || targets->targets.empty())
            return 0; // SessionDesc requires at least one target.

        std::vector<slang::PreprocessorMacroDesc> macroDescs;
        if (macros)
        {
            macroDescs.reserve(macros->entries.size());
            for (auto& kv : macros->entries)
                macroDescs.push_back({kv.first.c_str(), kv.second.c_str()});
        }

        std::vector<const char*> pathPtrs;
        if (paths)
        {
            pathPtrs.reserve(paths->paths.size());
            for (auto& p : paths->paths)
                pathPtrs.push_back(p.c_str());
        }

        std::vector<slang::CompilerOptionEntry> optionEntries;
        if (options)
        {
            optionEntries.reserve(options->entries.size());
            for (auto& e : options->entries)
            {
                slang::CompilerOptionEntry entry = {};
                entry.name = e.name;
                if (e.isString)
                {
                    entry.value.kind = slang::CompilerOptionValueKind::String;
                    entry.value.stringValue0 = e.stringValue.c_str();
                }
                else
                {
                    entry.value.kind = slang::CompilerOptionValueKind::Int;
                    entry.value.intValue0 = e.intValue;
                }
                optionEntries.push_back(entry);
            }
        }

        slang::SessionDesc sessionDesc = {};
        sessionDesc.targets = targets->targets.data();
        sessionDesc.targetCount = static_cast<SlangInt>(targets->targets.size());
        if (!macroDescs.empty())
        {
            sessionDesc.preprocessorMacros = macroDescs.data();
            sessionDesc.preprocessorMacroCount = static_cast<SlangInt>(macroDescs.size());
        }
        if (!pathPtrs.empty())
        {
            sessionDesc.searchPaths = pathPtrs.data();
            sessionDesc.searchPathCount = static_cast<SlangInt>(pathPtrs.size());
        }
        if (!optionEntries.empty())
        {
            sessionDesc.compilerOptionEntries = optionEntries.data();
            sessionDesc.compilerOptionEntryCount =
                static_cast<uint32_t>(optionEntries.size());
        }

        ComPtr<slang::ISession> session;
        SlangResult r = g_globalSession->createSession(sessionDesc, session.writeRef());
        if (SLANG_FAILED(r))
            return 0;

        return insertHandle(
            g_sessions,
            &g_nextSessionHandle,
            new WasmSession{std::move(session)});
    }
    catch (...)
    {
        return 0;
    }
}

extern "C" SlangWasmSession slang_wasm_session_create(
    uint32_t targetFormat,
    const char* profile,
    uint32_t profileLen)
{
    SlangWasmTargetList targets = slang_wasm_target_list_create();
    slang_wasm_target_list_add(targets, targetFormat, profile, profileLen, 0);
    return slang_wasm_session_create2(targets, 0, 0, 0);
}

extern "C" void slang_wasm_session_destroy(SlangWasmSession handle)
{
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end())
        return;
    delete it->second;
    g_sessions.erase(it);
}

// ── Modules ───────────────────────────────────────────────────────────────────

extern "C" SlangWasmModule slang_wasm_session_load_module(
    SlangWasmSession sessionHandle,
    const char* name,
    uint32_t nameLen,
    const char* source,
    uint32_t sourceLen,
    uint32_t* diagPtrOut,
    uint32_t* diagLenOut)
{
    try
    {
        auto sessionIt = g_sessions.find(sessionHandle);
        WASM_ASSERT(sessionIt != g_sessions.end());
        ComPtr<slang::ISession> session = sessionIt->second->session;

        std::string nameStr(name, nameLen);
        std::string sourceStr(source, sourceLen);

        ComPtr<slang::IBlob> diagBlob;
        slang::IModule* module = session->loadModuleFromSourceString(
            nameStr.c_str(),
            nameStr.c_str(), // use module name as path
            sourceStr.c_str(),
            diagBlob.writeRef());
        writeDiagOut(diagBlob, diagPtrOut, diagLenOut);
        if (!module)
            return 0;

        auto* wasmModule = new WasmModule();
        wasmModule->session = session;
        wasmModule->module = module;

        SlangInt32 entryPointCount = module->getDefinedEntryPointCount();
        for (SlangInt32 i = 0; i < entryPointCount; ++i)
        {
            ComPtr<slang::IEntryPoint> entryPoint;
            if (SLANG_SUCCEEDED(module->getDefinedEntryPoint(i, entryPoint.writeRef())) &&
                entryPoint)
            {
                wasmModule->entryPointNames.push_back(
                    entryPoint->getFunctionReflection()->getName());
                wasmModule->entryPoints.push_back(std::move(entryPoint));
            }
        }

        return insertHandle(g_modules, &g_nextModuleHandle, wasmModule);
    }
    catch (...)
    {
        writeDiagOut(nullptr, diagPtrOut, diagLenOut);
        return 0;
    }
}

extern "C" void slang_wasm_module_destroy(SlangWasmModule handle)
{
    delete takeHandle(g_modules, handle);
}

extern "C" uint32_t slang_wasm_module_entry_point_count(SlangWasmModule handle)
{
    auto it = g_modules.find(handle);
    WASM_ASSERT(it != g_modules.end());
    return static_cast<uint32_t>(it->second->entryPointNames.size());
}

extern "C" uint32_t slang_wasm_module_entry_point_name_ptr(SlangWasmModule handle, uint32_t index)
{
    auto it = g_modules.find(handle);
    WASM_ASSERT(it != g_modules.end());
    WASM_ASSERT(index < it->second->entryPointNames.size());
    return static_cast<uint32_t>(
        reinterpret_cast<uintptr_t>(it->second->entryPointNames[index].data()));
}

extern "C" uint32_t slang_wasm_module_entry_point_name_len(SlangWasmModule handle, uint32_t index)
{
    auto it = g_modules.find(handle);
    WASM_ASSERT(it != g_modules.end());
    WASM_ASSERT(index < it->second->entryPointNames.size());
    return static_cast<uint32_t>(it->second->entryPointNames[index].size());
}

// ── Compilation ───────────────────────────────────────────────────────────────

// Composite `components`, link, and produce code for the target at
// `targetIndex` plus reflection JSON, writing into `result`. `useTargetCode`
// selects IComponentType::getTargetCode (one combined blob covering every
// entry point linked into the program — e.g. one SPIR-V module containing both
// a vertex and a fragment entry point) over IComponentType::getEntryPointCode
// (one blob for entry point index 0 only, the shape every caller needs when
// compiling a single named entry point). Leaves result->succeeded false (with
// diagnostics populated) on any failure; never throws.
static void linkCompileAndReflect(
    slang::ISession* session,
    slang::IComponentType** components,
    SlangInt componentCount,
    uint32_t targetIndex,
    bool useTargetCode,
    WasmResult* result)
{
    ComPtr<slang::IComponentType> composite;
    ComPtr<slang::IBlob> diagBlob;
    SlangResult r = session->createCompositeComponentType(
        components,
        componentCount,
        composite.writeRef(),
        diagBlob.writeRef());
    appendBlob(result->diagnostics, diagBlob);
    if (SLANG_FAILED(r) || !composite)
        return;

    ComPtr<slang::IComponentType> linked;
    diagBlob = nullptr;
    r = composite->link(linked.writeRef(), diagBlob.writeRef());
    appendBlob(result->diagnostics, diagBlob);
    if (SLANG_FAILED(r) || !linked)
        return;

    // Get the compiled target code, for the target at `targetIndex` (its
    // position in the SlangWasmTargetList the session was created with — 0 for
    // the common single-target case).
    ComPtr<slang::IBlob> codeBlob;
    diagBlob = nullptr;
    r = useTargetCode
            ? linked->getTargetCode(
                  static_cast<SlangInt>(targetIndex),
                  codeBlob.writeRef(),
                  diagBlob.writeRef())
            : linked->getEntryPointCode(
                  0,
                  static_cast<SlangInt>(targetIndex),
                  codeBlob.writeRef(),
                  diagBlob.writeRef());
    appendBlob(result->diagnostics, diagBlob);
    if (SLANG_FAILED(r) || !codeBlob)
        return;

    const uint8_t* codePtr = static_cast<const uint8_t*>(codeBlob->getBufferPointer());
    result->code.assign(codePtr, codePtr + codeBlob->getBufferSize());

    // Serialize reflection to JSON.
    diagBlob = nullptr;
    slang::ProgramLayout* layout =
        linked->getLayout(static_cast<SlangInt>(targetIndex), diagBlob.writeRef());
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
}

extern "C" SlangWasmResult slang_wasm_compile(
    SlangWasmSession sessionHandle,
    const char* moduleName,
    uint32_t moduleNameLen,
    const char* source,
    uint32_t sourceLen,
    const char* entryName,
    uint32_t entryNameLen,
    uint32_t targetIndex)
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

        // Step 3: composite, link, get code, reflect.
        slang::IComponentType* components[] = {module, entryPoint.get()};
        linkCompileAndReflect(session, components, 2, targetIndex, false, result);
        return resultHandle;
    }
    catch (...)
    {
        result->diagnostics += "\n[slang-wasm-lib] internal exception caught; "
                               "compilation aborted.";
        return resultHandle;
    }
}

extern "C" SlangWasmResult slang_wasm_compile_entry_point(
    SlangWasmSession sessionHandle,
    SlangWasmModule moduleHandle,
    const char* entryName,
    uint32_t entryNameLen,
    uint32_t targetIndex)
{
    auto* result = new WasmResult();
    uint32_t resultHandle = g_nextResultHandle++;
    g_results[resultHandle] = result;

    try
    {
        auto sessionIt = g_sessions.find(sessionHandle);
        WASM_ASSERT(sessionIt != g_sessions.end());
        slang::ISession* session = sessionIt->second->session.get();

        auto moduleIt = g_modules.find(moduleHandle);
        WASM_ASSERT(moduleIt != g_modules.end());
        slang::IModule* module = moduleIt->second->module;

        std::string entryNameStr(entryName, entryNameLen);

        ComPtr<slang::IEntryPoint> entryPoint;
        SlangResult r =
            module->findEntryPointByName(entryNameStr.c_str(), entryPoint.writeRef());
        if (SLANG_FAILED(r) || !entryPoint)
            return resultHandle;

        slang::IComponentType* components[] = {module, entryPoint.get()};
        linkCompileAndReflect(session, components, 2, targetIndex, false, result);
        return resultHandle;
    }
    catch (...)
    {
        result->diagnostics += "\n[slang-wasm-lib] internal exception caught; "
                               "compilation aborted.";
        return resultHandle;
    }
}

extern "C" SlangWasmResult slang_wasm_compile_module(
    SlangWasmSession sessionHandle,
    SlangWasmModule moduleHandle,
    uint32_t targetIndex)
{
    auto* result = new WasmResult();
    uint32_t resultHandle = g_nextResultHandle++;
    g_results[resultHandle] = result;

    try
    {
        auto sessionIt = g_sessions.find(sessionHandle);
        WASM_ASSERT(sessionIt != g_sessions.end());
        slang::ISession* session = sessionIt->second->session.get();

        auto moduleIt = g_modules.find(moduleHandle);
        WASM_ASSERT(moduleIt != g_modules.end());
        WasmModule* wasmModule = moduleIt->second;

        std::vector<slang::IComponentType*> components;
        components.push_back(wasmModule->module);
        for (auto& ep : wasmModule->entryPoints)
            components.push_back(ep.get());

        linkCompileAndReflect(
            session,
            components.data(),
            static_cast<SlangInt>(components.size()),
            targetIndex,
            true,
            result);
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
