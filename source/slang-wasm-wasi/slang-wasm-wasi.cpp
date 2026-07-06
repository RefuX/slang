// slang-wasm-wasi.cpp — WASI reactor shim exposing a flat C ABI over Slang's COM API.
//
// All public symbols are declared in slang-wasm-wasi.h. See that file for the
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

#include "slang-wasm-wasi.h"

#include "../compiler-core/slang-pretty-writer.h"
#include "../core/slang-blob.h"
#include "../core/slang-string-escape-util.h"
#include "../core/slang-type-text-util.h"
#include "../slang/slang-profile.h"

#include <memory>
#include <slang-com-ptr.h>
#include <slang-deprecated.h>
#include <slang.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

// Traps on an out-of-contract handle in a builder mutator (target/macro/path/
// options list _add, spec_args _add_*): the handle should come from the
// matching _create call, so a stale/wrong one is a pure caller bug, not a
// recoverable condition. Other entry points use SLANG_RELEASE_ASSERT +
// try/catch, or return a 0/empty sentinel, instead of trapping.
#define SLANG_WASM_ASSERT(cond) \
    do                          \
    {                           \
        if (!(cond))            \
        {                       \
            __builtin_trap();   \
        }                       \
    } while (0)

using Slang::ComPtr;

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

// One specialization argument, stored in raw form (resolved to an actual
// slang::SpecializationArg only at use time, since a type-name argument needs
// a ProgramLayout — only available once the entry point being specialized is
// known — to resolve into a TypeReflection*).
struct WasmSpecArgEntry
{
    bool isType; // true: `value` is a type name; false: `value` is an expression.
    std::string value;
};

// Builder for a slang::SpecializationArg list.
struct WasmSpecArgs
{
    std::vector<WasmSpecArgEntry> entries;
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

// A list of type conformances, resolved eagerly against the owning module's
// layout at add-time
struct WasmTypeConformances
{
    ComPtr<slang::ISession> session;
    slang::IModule* module = nullptr; // owned by `session`'s module cache, not by us
    std::vector<ComPtr<slang::ITypeConformance>> conformances;
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
static std::unordered_map<uint32_t, WasmSpecArgs*> g_specArgsLists;
static std::unordered_map<uint32_t, WasmTypeConformances*> g_typeConformancesLists;
static uint32_t g_nextSessionHandle = 1;
static uint32_t g_nextResultHandle = 1;
static uint32_t g_nextTargetListHandle = 1;
static uint32_t g_nextMacroListHandle = 1;
static uint32_t g_nextPathListHandle = 1;
static uint32_t g_nextOptionsHandle = 1;
static uint32_t g_nextModuleHandle = 1;
static uint32_t g_nextSpecArgsHandle = 1;
static uint32_t g_nextTypeConformancesHandle = 1;

// Insert `value` into `table` under a freshly allocated handle from `*nextHandle`.
template<typename T>
static uint32_t insertHandle(
    std::unordered_map<uint32_t, T*>& table,
    uint32_t* nextHandle,
    T* value)
{
    const uint32_t handle = (*nextHandle)++;
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
    const auto it = table.find(handle);
    if (it == table.end())
        return nullptr;
    T* value = it->second;
    table.erase(it);
    return value;
}

// Allocate a WasmResult and register it under a fresh handle, catching any
// exception this can throw (e.g. std::bad_alloc) so the exported functions
// that call this before their own try block never let one escape. Returns
// {nullptr, 0} on failure.
static std::pair<WasmResult*, uint32_t> makeWasmResult()
{
    try
    {
        auto* result = new WasmResult();
        const uint32_t resultHandle = insertHandle(g_results, &g_nextResultHandle, result);
        return {result, resultHandle};
    }
    catch (...)
    {
        return {nullptr, 0};
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

// Convert a (ptr, len) argument pair from the host into a std::string,
// treating a null ptr as an empty string. Used at every (ptr, len) argument
// site instead of calling std::string's (ptr, len) constructor directly:
// that constructor's contract requires a non-null ptr whenever len > 0, and
// violating it is undefined behavior rather than a catchable exception, so
// it would not be saved by any of this file's try/catch blocks. A null ptr
// with a nonzero len is a realistic caller mistake here, not just a
// theoretical one — slang_wasm_alloc can return null on allocation failure,
// and a caller could plausibly forward that null straight into one of these
// arguments without checking it first.
static std::string toStr(const char* ptr, uint32_t len)
{
    return ptr ? std::string(ptr, len) : std::string();
}

// Ensure the single shared IGlobalSession exists. Returns false on failure.
static bool ensureGlobalSession()
{
    if (g_globalSession)
        return true;
    const SlangGlobalSessionDesc desc = {};
    const SlangResult r = slang_createGlobalSession2(&desc, g_globalSession.writeRef());
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

// Recursively serialize `decl` and its children to JSON: { "name", "kind",
// "children": [...] }. `children` is omitted when there are none. Built with
// Slang::PrettyWriter to reuse the same StringBuilder/escaping machinery
// spReflection_ToJson (called from linkAndGetCode above) already uses for
// reflection JSON, instead of hand-rolling string escaping and comma-joining.
// String values are escaped via StringEscapeUtil directly with Style::JSON,
// not via PrettyWriter::writeEscapedString (which hardcodes Style::Cpp): Cpp
// escaping falls back to octal escapes like "\001" for control/high bytes,
// which are not valid JSON syntax, whereas Style::JSON emits \uXXXX.
static void emitDeclReflectionJson(slang::DeclReflection* decl, Slang::PrettyWriter& writer)
{
    auto* jsonHandler = Slang::StringEscapeUtil::getHandler(Slang::StringEscapeUtil::Style::JSON);

    writer << "{\"name\":";
    Slang::StringEscapeUtil::appendQuoted(
        jsonHandler,
        Slang::UnownedStringSlice(decl->getName()),
        writer.getBuilder());
    writer << R"(,"kind":")";
    switch (decl->getKind())
    {
    case slang::DeclReflection::Kind::Struct:
        writer << "struct";
        break;
    case slang::DeclReflection::Kind::Func:
        writer << "function";
        break;
    case slang::DeclReflection::Kind::Module:
        writer << "module";
        break;
    case slang::DeclReflection::Kind::Generic:
        writer << "generic";
        break;
    case slang::DeclReflection::Kind::Variable:
        writer << "variable";
        break;
    case slang::DeclReflection::Kind::Namespace:
        writer << "namespace";
        break;
    case slang::DeclReflection::Kind::Enum:
        writer << "enum";
        break;
    default:
        writer << "unsupported";
        break;
    }
    writer << "\"";

    const unsigned int childCount = decl->getChildrenCount();
    if (childCount > 0)
    {
        // A plain indexed comma join, matching the sibling "fields" array
        // loop in slang-reflection-json.cpp's emitReflectionTypeJSON, rather
        // than PrettyWriter's maybeComma()/CommaTrackerRAII: that machinery
        // is for objects whose fields are each conditionally emitted (so the
        // "was there a previous field" state can't be known from a loop
        // index alone); here every child is unconditionally comma-joined by
        // position, so a simple index check already gives the right answer.
        writer << ",\"children\":[";
        for (unsigned int i = 0; i < childCount; ++i)
        {
            if (i != 0)
                writer << ",";
            emitDeclReflectionJson(decl->getChild(i), writer);
        }
        writer << "]";
    }
    writer << "}";
}

// Copy `size` bytes from `data` into a freshly malloc'd buffer and write its
// (ptr, len) into the caller-supplied out-params, or (0, 0) if `size` is 0.
// The buffer is owned by the caller of the function that took these out-params;
// free it with slang_wasm_free. Shared by writeDiagOut (a single slang::IBlob)
// and writeDiagOutString (diagnostics accumulated from multiple sources into
// one std::string).
static void writeDiagOutBytes(const void* data, size_t size, uint32_t* diagPtrOut, uint32_t* diagLenOut)
{
    if (!diagPtrOut || !diagLenOut)
        return;
    if (!data || size == 0)
    {
        *diagPtrOut = 0;
        *diagLenOut = 0;
        return;
    }
    void* buf = malloc(size);
    if (!buf)
    {
        // Drop the diagnostics rather than memcpy into a null buffer: losing
        // the diagnostic text under allocation failure is preferable to
        // crashing the instance over it.
        *diagPtrOut = 0;
        *diagLenOut = 0;
        return;
    }
    memcpy(buf, data, size);
    *diagPtrOut = static_cast<uint32_t>(reinterpret_cast<uintptr_t>(buf));
    *diagLenOut = static_cast<uint32_t>(size);
}

// Used by APIs that can fail before producing a handle to hang diagnostics off
// of (e.g. slang_wasm_session_load_module), so diagnostics are not lost on a
// failed load.
static void writeDiagOut(slang::IBlob* blob, uint32_t* diagPtrOut, uint32_t* diagLenOut)
{
    writeDiagOutBytes(
        blob ? blob->getBufferPointer() : nullptr,
        blob ? blob->getBufferSize() : 0,
        diagPtrOut,
        diagLenOut);
}

// As writeDiagOut, but for diagnostics already accumulated (e.g. via
// appendBlob) from more than one Slang call into a single std::string.
static void writeDiagOutString(const std::string& diagnostics, uint32_t* diagPtrOut, uint32_t* diagLenOut)
{
    writeDiagOutBytes(diagnostics.data(), diagnostics.size(), diagPtrOut, diagLenOut);
}

// Link `unlinked`, then produce code for the target at `targetIndex` plus
// reflection JSON, writing into `result`. Factored out of
// linkCompileAndReflect (below) so a component type that doesn't need
// compositing first — e.g. the result of IComponentType::specialize, which is
// already a complete component type — can skip straight to this step.
static void linkAndGetCode(
    slang::IComponentType* unlinked,
    uint32_t targetIndex,
    bool useTargetCode,
    WasmResult* result)
{
    ComPtr<slang::IComponentType> linked;
    ComPtr<slang::IBlob> diagBlob;
    SlangResult r = unlinked->link(linked.writeRef(), diagBlob.writeRef());
    appendBlob(result->diagnostics, diagBlob);
    if (SLANG_FAILED(r) || !linked)
        return;

    // Get the compiled target code, for the target at `targetIndex` (its
    // position in the SlangWasmTargetList the session was created with — 0 for
    // the common single-target case).
    ComPtr<slang::IBlob> codeBlob;
    diagBlob = nullptr;
    r = useTargetCode ? linked->getTargetCode(
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

    const auto* codePtr = static_cast<const uint8_t*>(codeBlob->getBufferPointer());
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

// Composite `components`, then link and produce code via linkAndGetCode.
// `useTargetCode` selects IComponentType::getTargetCode (one combined blob
// covering every entry point linked into the program — e.g. one SPIR-V module
// containing both a vertex and a fragment entry point) over
// IComponentType::getEntryPointCode (one blob for entry point index 0 only,
// the shape every caller needs when compiling a single named entry point).
// Leaves result->succeeded false (with diagnostics populated) on any failure;
// never throws.
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
    const SlangResult r = session->createCompositeComponentType(
        components,
        componentCount,
        composite.writeRef(),
        diagBlob.writeRef());
    appendBlob(result->diagnostics, diagBlob);
    if (SLANG_FAILED(r) || !composite)
        return;

    linkAndGetCode(composite, targetIndex, useTargetCode, result);
}

// Append `typeConformancesHandle`'s already-resolved ITypeConformance component
// types (if any) onto `components`, so they participate in the same
// createCompositeComponentType call as the module/entry-point(s) being compiled.
// A 0/unknown handle appends nothing.
static void appendTypeConformances(
    SlangWasmTypeConformances typeConformancesHandle,
    std::vector<slang::IComponentType*>& components)
{
    const auto it = g_typeConformancesLists.find(typeConformancesHandle);
    if (it == g_typeConformancesLists.end())
        return;
    for (auto& conformance : it->second->conformances)
        components.push_back(conformance.get());
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

extern "C" int32_t slang_wasm_target_from_string(const char* name, uint32_t nameLen)
{
    if (!name || nameLen == 0)
        return -1;
    // Delegate to the same name table that backs the compiler's own `-target`
    // command-line flag, rather than re-deriving names from the generated
    // enum metadata blob: this recognizes every alias the real compiler does
    // (e.g. both "spv" and "spirv" name SLANG_SPIRV), not just the single
    // name mechanically derived from the C++ enumerator's spelling.
    const SlangCompileTarget target =
        Slang::TypeTextUtil::findCompileTargetFromName(Slang::UnownedStringSlice(name, nameLen));
    return target == SLANG_TARGET_UNKNOWN ? -1 : static_cast<int32_t>(target);
}

extern "C" int32_t slang_wasm_stage_from_string(const char* name, uint32_t nameLen)
{
    if (!name || nameLen == 0)
        return -1;
    // Delegate to the same name table that backs the compiler's own `-stage`
    // command-line flag, for the same reason as slang_wasm_target_from_string
    // above.
    const Slang::Stage stage =
        Slang::findStageByName(Slang::String(Slang::UnownedStringSlice(name, nameLen)));
    return stage == Slang::Stage::Unknown ? -1 : static_cast<int32_t>(stage);
}

// ── Session descriptor builders ───────────────────────────────────────────────

extern "C" SlangWasmTargetList slang_wasm_target_list_create(void)
{
    try
    {
        return insertHandle(g_targetLists, &g_nextTargetListHandle, new WasmTargetList());
    }
    catch (...)
    {
        return 0;
    }
}

extern "C" void slang_wasm_target_list_add(
    SlangWasmTargetList listHandle,
    uint32_t format,
    const char* profile,
    uint32_t profileLen,
    uint32_t flags)
{
    const auto it = g_targetLists.find(listHandle);
    SLANG_WASM_ASSERT(it != g_targetLists.end());

    // ensureGlobalSession()/spFindProfile() can throw; catch it here (no result
    // object exists to report through) and just drop the target.
    try
    {
        ensureGlobalSession();

        slang::TargetDesc target = {};
        target.format = static_cast<SlangCompileTarget>(format);
        target.flags = static_cast<SlangTargetFlags>(flags);
        if (profile && profileLen > 0 && g_globalSession)
        {
            const std::string profileStr(profile, profileLen);
            target.profile = spFindProfile(g_globalSession, profileStr.c_str());
        }
        it->second->targets.push_back(target);
    }
    catch (...)
    {
    }
}

extern "C" void slang_wasm_target_list_destroy(SlangWasmTargetList handle)
{
    delete takeHandle(g_targetLists, handle);
}

extern "C" SlangWasmMacroList slang_wasm_macro_list_create(void)
{
    try
    {
        return insertHandle(g_macroLists, &g_nextMacroListHandle, new WasmMacroList());
    }
    catch (...)
    {
        return 0;
    }
}

extern "C" void slang_wasm_macro_list_add(
    SlangWasmMacroList listHandle,
    const char* name,
    uint32_t nameLen,
    const char* value,
    uint32_t valueLen)
{
    const auto it = g_macroLists.find(listHandle);
    SLANG_WASM_ASSERT(it != g_macroLists.end());
    try
    {
        it->second->entries.emplace_back(toStr(name, nameLen), toStr(value, valueLen));
    }
    catch (...)
    {
    }
}

extern "C" void slang_wasm_macro_list_destroy(SlangWasmMacroList handle)
{
    delete takeHandle(g_macroLists, handle);
}

extern "C" SlangWasmPathList slang_wasm_path_list_create(void)
{
    try
    {
        return insertHandle(g_pathLists, &g_nextPathListHandle, new WasmPathList());
    }
    catch (...)
    {
        return 0;
    }
}

extern "C" void slang_wasm_path_list_add(
    SlangWasmPathList listHandle,
    const char* path,
    uint32_t pathLen)
{
    const auto it = g_pathLists.find(listHandle);
    SLANG_WASM_ASSERT(it != g_pathLists.end());
    try
    {
        it->second->paths.push_back(toStr(path, pathLen));
    }
    catch (...)
    {
    }
}

extern "C" void slang_wasm_path_list_destroy(SlangWasmPathList handle)
{
    delete takeHandle(g_pathLists, handle);
}

extern "C" SlangWasmOptions slang_wasm_options_create(void)
{
    try
    {
        return insertHandle(g_optionLists, &g_nextOptionsHandle, new WasmOptions());
    }
    catch (...)
    {
        return 0;
    }
}

extern "C" void slang_wasm_options_add_string(
    SlangWasmOptions optsHandle,
    uint32_t name,
    const char* val,
    uint32_t valLen)
{
    const auto it = g_optionLists.find(optsHandle);
    SLANG_WASM_ASSERT(it != g_optionLists.end());
    try
    {
        WasmOptionEntry entry;
        entry.name = static_cast<slang::CompilerOptionName>(name);
        entry.isString = true;
        entry.stringValue = toStr(val, valLen);
        it->second->entries.push_back(std::move(entry));
    }
    catch (...)
    {
    }
}

extern "C" void slang_wasm_options_add_int(SlangWasmOptions optsHandle, uint32_t name, int32_t val)
{
    const auto it = g_optionLists.find(optsHandle);
    SLANG_WASM_ASSERT(it != g_optionLists.end());
    try
    {
        WasmOptionEntry entry;
        entry.name = static_cast<slang::CompilerOptionName>(name);
        entry.isString = false;
        entry.intValue = val;
        it->second->entries.push_back(std::move(entry));
    }
    catch (...)
    {
    }
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
                macroDescs.push_back({.name = kv.first.c_str(), .value = kv.second.c_str()});
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
            sessionDesc.compilerOptionEntryCount = static_cast<uint32_t>(optionEntries.size());
        }

        ComPtr<slang::ISession> session;
        const SlangResult r = g_globalSession->createSession(sessionDesc, session.writeRef());
        if (SLANG_FAILED(r))
            return 0;

        return insertHandle(g_sessions, &g_nextSessionHandle, new WasmSession{std::move(session)});
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
    const SlangWasmTargetList targets = slang_wasm_target_list_create();
    slang_wasm_target_list_add(targets, targetFormat, profile, profileLen, 0);
    return slang_wasm_session_create2(targets, 0, 0, 0);
}

extern "C" void slang_wasm_session_destroy(SlangWasmSession handle)
{
    delete takeHandle(g_sessions, handle);
}

// ── Modules ───────────────────────────────────────────────────────────────────

// Wrap a freshly loaded `module` (from any ISession load entry point: source
// string, IR blob, ...) in a handle, caching its defined entry points up
// front. Shared by slang_wasm_session_load_module and
// slang_wasm_session_load_module_ir so the entry-point enumeration logic has
// one definition.
static SlangWasmModule makeWasmModule(ComPtr<slang::ISession> session, slang::IModule* module)
{
    auto* wasmModule = new WasmModule();
    wasmModule->session = std::move(session);
    wasmModule->module = module;

    const SlangInt32 entryPointCount = module->getDefinedEntryPointCount();
    for (SlangInt32 i = 0; i < entryPointCount; ++i)
    {
        ComPtr<slang::IEntryPoint> entryPoint;
        if (SLANG_SUCCEEDED(module->getDefinedEntryPoint(i, entryPoint.writeRef())) && entryPoint)
        {
            slang::FunctionReflection* funcReflection = entryPoint->getFunctionReflection();
            const char* name = funcReflection ? funcReflection->getName() : nullptr;
            wasmModule->entryPointNames.emplace_back(name ? name : "");
            wasmModule->entryPoints.push_back(std::move(entryPoint));
        }
    }

    return insertHandle(g_modules, &g_nextModuleHandle, wasmModule);
}

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
        const auto sessionIt = g_sessions.find(sessionHandle);
        SLANG_RELEASE_ASSERT(sessionIt != g_sessions.end());
        const ComPtr<slang::ISession> session = sessionIt->second->session;

        const std::string nameStr = toStr(name, nameLen);
        const std::string sourceStr = toStr(source, sourceLen);

        ComPtr<slang::IBlob> diagBlob;
        slang::IModule* module = session->loadModuleFromSourceString(
            nameStr.c_str(),
            nameStr.c_str(), // use module name as path
            sourceStr.c_str(),
            diagBlob.writeRef());
        writeDiagOut(diagBlob, diagPtrOut, diagLenOut);
        if (!module)
            return 0;

        return makeWasmModule(session, module);
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

// Returns 0 for an unknown handle (rather than trapping the instance): the
// caller has no result object to hang a diagnostic off of here, and an empty
// module already legitimately reports 0 entry points, so 0 is a safe,
// ambiguous-but-harmless sentinel for "nothing to enumerate" either way.
extern "C" uint32_t slang_wasm_module_entry_point_count(SlangWasmModule handle)
{
    const auto it = g_modules.find(handle);
    if (it == g_modules.end())
        return 0;
    return static_cast<uint32_t>(it->second->entryPointNames.size());
}

// Returns 0 for an unknown handle or an out-of-range index, for the same
// reason as slang_wasm_module_entry_point_count above.
extern "C" uint32_t slang_wasm_module_entry_point_name_ptr(SlangWasmModule handle, uint32_t index)
{
    const auto it = g_modules.find(handle);
    if (it == g_modules.end() || index >= it->second->entryPointNames.size())
        return 0;
    return static_cast<uint32_t>(
        reinterpret_cast<uintptr_t>(it->second->entryPointNames[index].data()));
}

// Returns 0 for an unknown handle or an out-of-range index, for the same
// reason as slang_wasm_module_entry_point_count above.
extern "C" uint32_t slang_wasm_module_entry_point_name_len(SlangWasmModule handle, uint32_t index)
{
    const auto it = g_modules.find(handle);
    if (it == g_modules.end() || index >= it->second->entryPointNames.size())
        return 0;
    return static_cast<uint32_t>(it->second->entryPointNames[index].size());
}

extern "C" SlangWasmResult slang_wasm_module_serialize(SlangWasmModule moduleHandle)
{
    auto [result, resultHandle] = makeWasmResult();
    if (!result)
        return 0;

    try
    {
        const auto moduleIt = g_modules.find(moduleHandle);
        SLANG_RELEASE_ASSERT(moduleIt != g_modules.end());
        slang::IModule* module = moduleIt->second->module;

        ComPtr<slang::IBlob> irBlob;
        const SlangResult r = module->serialize(irBlob.writeRef());
        if (SLANG_FAILED(r) || !irBlob)
        {
            result->diagnostics = "[slang-wasm-wasi] IModule::serialize failed";
            return resultHandle;
        }

        const auto* irPtr = static_cast<const uint8_t*>(irBlob->getBufferPointer());
        result->code.assign(irPtr, irPtr + irBlob->getBufferSize());
        result->succeeded = true;
        return resultHandle;
    }
    catch (...)
    {
        result->diagnostics += "\n[slang-wasm-wasi] internal exception caught; "
                               "module serialization aborted.";
        return resultHandle;
    }
}

extern "C" SlangWasmModule slang_wasm_session_load_module_ir(
    SlangWasmSession sessionHandle,
    const char* name,
    uint32_t nameLen,
    const void* irBlob,
    uint32_t irLen,
    uint32_t* diagPtrOut,
    uint32_t* diagLenOut)
{
    try
    {
        const auto sessionIt = g_sessions.find(sessionHandle);
        SLANG_RELEASE_ASSERT(sessionIt != g_sessions.end());
        const ComPtr<slang::ISession> session = sessionIt->second->session;

        const std::string nameStr = toStr(name, nameLen);
        const ComPtr<slang::IBlob> sourceBlob = Slang::RawBlob::create(irBlob, irLen);
        if (!sourceBlob)
        {
            // RawBlob::create returns null both for a (null, len>0) argument
            // pair and for allocation failure; either way there is no blob
            // to hand to loadModuleFromIRBlob.
            writeDiagOut(nullptr, diagPtrOut, diagLenOut);
            return 0;
        }

        ComPtr<slang::IBlob> diagBlob;
        slang::IModule* module = session->loadModuleFromIRBlob(
            nameStr.c_str(),
            nameStr.c_str(), // use module name as path
            sourceBlob,
            diagBlob.writeRef());
        writeDiagOut(diagBlob, diagPtrOut, diagLenOut);
        if (!module)
            return 0;

        return makeWasmModule(session, module);
    }
    catch (...)
    {
        writeDiagOut(nullptr, diagPtrOut, diagLenOut);
        return 0;
    }
}

// ── Type conformance ──────────────────────────────────────────────────────────

extern "C" SlangWasmTypeConformances slang_wasm_type_conformances_create(
    SlangWasmModule moduleHandle)
{
    const auto moduleIt = g_modules.find(moduleHandle);
    if (moduleIt == g_modules.end())
        return 0;

    try
    {
        auto* conformances = new WasmTypeConformances();
        conformances->session = moduleIt->second->session;
        conformances->module = moduleIt->second->module;
        return insertHandle(g_typeConformancesLists, &g_nextTypeConformancesHandle, conformances);
    }
    catch (...)
    {
        return 0;
    }
}

extern "C" int32_t slang_wasm_type_conformances_add(
    SlangWasmTypeConformances conformancesHandle,
    const char* concreteTypeName,
    uint32_t concreteTypeNameLen,
    const char* interfaceTypeName,
    uint32_t interfaceTypeNameLen,
    int32_t conformanceIdOverride,
    uint32_t* diagPtrOut,
    uint32_t* diagLenOut)
{
    const auto it = g_typeConformancesLists.find(conformancesHandle);
    SLANG_WASM_ASSERT(it != g_typeConformancesLists.end());
    try
    {
        WasmTypeConformances* wasmConformances = it->second;
        std::string diagnostics;

        ComPtr<slang::IBlob> diagBlob;
        slang::ProgramLayout* layout = wasmConformances->module->getLayout(0, diagBlob.writeRef());
        appendBlob(diagnostics, diagBlob);
        if (!layout)
        {
            writeDiagOutString(diagnostics, diagPtrOut, diagLenOut);
            return -1;
        }

        const std::string concreteTypeNameStr = toStr(concreteTypeName, concreteTypeNameLen);
        const std::string interfaceTypeNameStr = toStr(interfaceTypeName, interfaceTypeNameLen);
        slang::TypeReflection* concreteType = layout->findTypeByName(concreteTypeNameStr.c_str());
        slang::TypeReflection* interfaceType = layout->findTypeByName(interfaceTypeNameStr.c_str());
        if (!concreteType || !interfaceType)
        {
            // findTypeByName gives no diagnostic blob of its own, so synthesize
            // a message naming whichever lookup(s) failed.
            if (!concreteType)
                diagnostics += "error: type '" + concreteTypeNameStr + "' not found in module layout\n";
            if (!interfaceType)
                diagnostics += "error: type '" + interfaceTypeNameStr + "' not found in module layout\n";
            writeDiagOutString(diagnostics, diagPtrOut, diagLenOut);
            return -1;
        }

        ComPtr<slang::ITypeConformance> conformance;
        diagBlob = nullptr;
        const SlangResult r = wasmConformances->session->createTypeConformanceComponentType(
            concreteType,
            interfaceType,
            conformance.writeRef(),
            static_cast<SlangInt>(conformanceIdOverride),
            diagBlob.writeRef());
        appendBlob(diagnostics, diagBlob);
        if (SLANG_FAILED(r) || !conformance)
        {
            writeDiagOutString(diagnostics, diagPtrOut, diagLenOut);
            return -1;
        }

        uint32_t assignedId = 0;
        if (SLANG_FAILED(wasmConformances->session->getTypeConformanceWitnessSequentialID(
                concreteType,
                interfaceType,
                &assignedId)))
        {
            diagnostics += "error: failed to assign a dispatch ID to conformance of '" +
                           concreteTypeNameStr + "' to '" + interfaceTypeNameStr + "'\n";
            writeDiagOutString(diagnostics, diagPtrOut, diagLenOut);
            return -1;
        }

        wasmConformances->conformances.push_back(std::move(conformance));
        writeDiagOutString(diagnostics, diagPtrOut, diagLenOut);
        return static_cast<int32_t>(assignedId);
    }
    catch (...)
    {
        writeDiagOut(nullptr, diagPtrOut, diagLenOut);
        return -1;
    }
}

extern "C" void slang_wasm_type_conformances_destroy(SlangWasmTypeConformances handle)
{
    delete takeHandle(g_typeConformancesLists, handle);
}

// ── Specialization ────────────────────────────────────────────────────────────

extern "C" SlangWasmSpecArgs slang_wasm_spec_args_create(void)
{
    try
    {
        return insertHandle(g_specArgsLists, &g_nextSpecArgsHandle, new WasmSpecArgs());
    }
    catch (...)
    {
        return 0;
    }
}

extern "C" void slang_wasm_spec_args_add_type(
    SlangWasmSpecArgs argsHandle,
    const char* typeName,
    uint32_t typeNameLen)
{
    const auto it = g_specArgsLists.find(argsHandle);
    SLANG_WASM_ASSERT(it != g_specArgsLists.end());
    try
    {
        it->second->entries.push_back({.isType = true, .value = toStr(typeName, typeNameLen)});
    }
    catch (...)
    {
    }
}

extern "C" void slang_wasm_spec_args_add_expr(
    SlangWasmSpecArgs argsHandle,
    const char* expr,
    uint32_t exprLen)
{
    const auto it = g_specArgsLists.find(argsHandle);
    SLANG_WASM_ASSERT(it != g_specArgsLists.end());
    try
    {
        it->second->entries.push_back({.isType = false, .value = toStr(expr, exprLen)});
    }
    catch (...)
    {
    }
}

extern "C" void slang_wasm_spec_args_destroy(SlangWasmSpecArgs handle)
{
    delete takeHandle(g_specArgsLists, handle);
}

extern "C" SlangWasmResult slang_wasm_compile_specialized_entry_point(
    SlangWasmModule moduleHandle,
    const char* entryName,
    uint32_t entryNameLen,
    SlangWasmSpecArgs specArgsHandle,
    uint32_t targetIndex,
    SlangWasmTypeConformances typeConformances)
{
    auto [result, resultHandle] = makeWasmResult();
    if (!result)
        return 0;

    // Consumed exactly once, on every return path.
    std::unique_ptr<WasmSpecArgs> specArgs(takeHandle(g_specArgsLists, specArgsHandle));

    try
    {
        const auto moduleIt = g_modules.find(moduleHandle);
        SLANG_RELEASE_ASSERT(moduleIt != g_modules.end());
        slang::ISession* session = moduleIt->second->session.get();
        slang::IModule* module = moduleIt->second->module;

        const std::string entryNameStr = toStr(entryName, entryNameLen);
        ComPtr<slang::IEntryPoint> entryPoint;
        SlangResult r = module->findEntryPointByName(entryNameStr.c_str(), entryPoint.writeRef());
        if (SLANG_FAILED(r) || !entryPoint)
            return resultHandle;

        std::vector<slang::IComponentType*> components = {module, entryPoint.get()};
        appendTypeConformances(typeConformances, components);
        ComPtr<slang::IComponentType> composite;
        ComPtr<slang::IBlob> diagBlob;
        r = session->createCompositeComponentType(
            components.data(),
            static_cast<SlangInt>(components.size()),
            composite.writeRef(),
            diagBlob.writeRef());
        appendBlob(result->diagnostics, diagBlob);
        if (SLANG_FAILED(r) || !composite)
            return resultHandle;

        // Resolve type-name specialization arguments against the composite's
        // own program layout. This works even though the program may still
        // have unresolved generic parameters of its own (that's the whole
        // point of specializing) because findTypeByName is a global lookup
        // by declared name, not dependent on those parameters being resolved.
        slang::ProgramLayout* layout = nullptr;
        bool needsLayout = false;
        if (specArgs)
        {
            for (auto& entry : specArgs->entries)
                needsLayout |= entry.isType;
        }
        if (needsLayout)
        {
            diagBlob = nullptr;
            layout = composite->getLayout(static_cast<SlangInt>(targetIndex), diagBlob.writeRef());
            appendBlob(result->diagnostics, diagBlob);
        }

        std::vector<slang::SpecializationArg> args;
        if (specArgs)
        {
            args.reserve(specArgs->entries.size());
            for (auto& entry : specArgs->entries)
            {
                if (entry.isType)
                {
                    slang::TypeReflection* type =
                        layout ? layout->findTypeByName(entry.value.c_str()) : nullptr;
                    if (!type)
                    {
                        result->diagnostics +=
                            "\n[slang-wasm-wasi] specialization type not found: " + entry.value;
                        return resultHandle;
                    }
                    args.push_back(slang::SpecializationArg::fromType(type));
                }
                else
                {
                    args.push_back(slang::SpecializationArg::fromExpr(entry.value.c_str()));
                }
            }
        }

        ComPtr<slang::IComponentType> specialized;
        diagBlob = nullptr;
        r = composite->specialize(
            args.data(),
            static_cast<SlangInt>(args.size()),
            specialized.writeRef(),
            diagBlob.writeRef());
        appendBlob(result->diagnostics, diagBlob);
        if (SLANG_FAILED(r) || !specialized)
            return resultHandle;

        linkAndGetCode(specialized, targetIndex, false, result);
        return resultHandle;
    }
    catch (...)
    {
        result->diagnostics += "\n[slang-wasm-wasi] internal exception caught; "
                               "specialization aborted.";
        return resultHandle;
    }
}

// ── Declaration reflection ────────────────────────────────────────────────────

extern "C" SlangWasmResult slang_wasm_module_decl_reflection_json(SlangWasmModule moduleHandle)
{
    auto [result, resultHandle] = makeWasmResult();
    if (!result)
        return 0;

    try
    {
        const auto moduleIt = g_modules.find(moduleHandle);
        SLANG_RELEASE_ASSERT(moduleIt != g_modules.end());
        slang::IModule* module = moduleIt->second->module;

        slang::DeclReflection* decl = module->getModuleReflection();
        if (!decl)
        {
            result->diagnostics = "[slang-wasm-wasi] IModule::getModuleReflection returned null";
            return resultHandle;
        }

        Slang::PrettyWriter writer;
        emitDeclReflectionJson(decl, writer);
        result->reflectionJson.assign(
            writer.getBuilder().getBuffer(),
            static_cast<size_t>(writer.getBuilder().getLength()));
        result->succeeded = true;
        return resultHandle;
    }
    catch (...)
    {
        result->diagnostics += "\n[slang-wasm-wasi] internal exception caught; "
                               "decl reflection aborted.";
        return resultHandle;
    }
}

extern "C" SlangWasmResult slang_wasm_module_disassemble(SlangWasmModule moduleHandle)
{
    auto [result, resultHandle] = makeWasmResult();
    if (!result)
        return 0;

    try
    {
        const auto moduleIt = g_modules.find(moduleHandle);
        SLANG_RELEASE_ASSERT(moduleIt != g_modules.end());
        slang::IModule* module = moduleIt->second->module;

        ComPtr<slang::IBlob> disasmBlob;
        const SlangResult r = module->disassemble(disasmBlob.writeRef());
        if (SLANG_FAILED(r) || !disasmBlob)
        {
            result->diagnostics = "[slang-wasm-wasi] IModule::disassemble failed";
            return resultHandle;
        }

        appendBlob(result->diagnostics, disasmBlob);
        result->succeeded = true;
        return resultHandle;
    }
    catch (...)
    {
        result->diagnostics += "\n[slang-wasm-wasi] internal exception caught; "
                               "disassembly aborted.";
        return resultHandle;
    }
}

// ── Compilation ───────────────────────────────────────────────────────────────

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
    auto [result, resultHandle] = makeWasmResult();
    if (!result)
        return 0;

    try
    {
        const auto sessionIt = g_sessions.find(sessionHandle);
        SLANG_RELEASE_ASSERT(sessionIt != g_sessions.end());
        slang::ISession* session = sessionIt->second->session.get();

        const std::string moduleNameStr = toStr(moduleName, moduleNameLen);
        const std::string sourceStr = toStr(source, sourceLen);
        const std::string entryNameStr = toStr(entryName, entryNameLen);

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
        const SlangResult r =
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
        result->diagnostics += "\n[slang-wasm-wasi] internal exception caught; "
                               "compilation aborted.";
        return resultHandle;
    }
}

extern "C" SlangWasmResult slang_wasm_compile_entry_point(
    SlangWasmModule moduleHandle,
    const char* entryName,
    uint32_t entryNameLen,
    uint32_t targetIndex,
    SlangWasmTypeConformances typeConformances)
{
    auto [result, resultHandle] = makeWasmResult();
    if (!result)
        return 0;

    try
    {
        const auto moduleIt = g_modules.find(moduleHandle);
        SLANG_RELEASE_ASSERT(moduleIt != g_modules.end());
        slang::ISession* session = moduleIt->second->session.get();
        slang::IModule* module = moduleIt->second->module;

        const std::string entryNameStr = toStr(entryName, entryNameLen);

        ComPtr<slang::IEntryPoint> entryPoint;
        const SlangResult r =
            module->findEntryPointByName(entryNameStr.c_str(), entryPoint.writeRef());
        if (SLANG_FAILED(r) || !entryPoint)
            return resultHandle;

        std::vector<slang::IComponentType*> components = {module, entryPoint.get()};
        appendTypeConformances(typeConformances, components);
        linkCompileAndReflect(
            session,
            components.data(),
            static_cast<SlangInt>(components.size()),
            targetIndex,
            false,
            result);
        return resultHandle;
    }
    catch (...)
    {
        result->diagnostics += "\n[slang-wasm-wasi] internal exception caught; "
                               "compilation aborted.";
        return resultHandle;
    }
}

extern "C" SlangWasmResult slang_wasm_compile_module(
    SlangWasmModule moduleHandle,
    uint32_t targetIndex,
    SlangWasmTypeConformances typeConformances)
{
    auto [result, resultHandle] = makeWasmResult();
    if (!result)
        return 0;

    try
    {
        const auto moduleIt = g_modules.find(moduleHandle);
        SLANG_RELEASE_ASSERT(moduleIt != g_modules.end());
        WasmModule* wasmModule = moduleIt->second;
        slang::ISession* session = wasmModule->session.get();

        std::vector<slang::IComponentType*> components;
        components.push_back(wasmModule->module);
        for (auto& ep : wasmModule->entryPoints)
            components.push_back(ep.get());
        appendTypeConformances(typeConformances, components);

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
        result->diagnostics += "\n[slang-wasm-wasi] internal exception caught; "
                               "compilation aborted.";
        return resultHandle;
    }
}

// ── Result accessors ──────────────────────────────────────────────────────────

// Every accessor below returns a 0/empty sentinel for an unknown handle
// rather than trapping the instance: like the module entry-point accessors
// above, there is no result object here to hang a diagnostic off of, so a
// hard trap would only turn one bad caller call into total loss of the
// module for every other in-flight caller.
extern "C" int32_t slang_wasm_result_succeeded(SlangWasmResult handle)
{
    const auto it = g_results.find(handle);
    if (it == g_results.end())
        return 0;
    return it->second->succeeded ? 1 : 0;
}

extern "C" uint32_t slang_wasm_result_code_ptr(SlangWasmResult handle)
{
    const auto it = g_results.find(handle);
    if (it == g_results.end())
        return 0;
    return static_cast<uint32_t>(reinterpret_cast<uintptr_t>(it->second->code.data()));
}

extern "C" uint32_t slang_wasm_result_code_len(SlangWasmResult handle)
{
    const auto it = g_results.find(handle);
    if (it == g_results.end())
        return 0;
    return static_cast<uint32_t>(it->second->code.size());
}

extern "C" uint32_t slang_wasm_result_reflection_json_ptr(SlangWasmResult handle)
{
    const auto it = g_results.find(handle);
    if (it == g_results.end())
        return 0;
    return static_cast<uint32_t>(reinterpret_cast<uintptr_t>(it->second->reflectionJson.data()));
}

extern "C" uint32_t slang_wasm_result_reflection_json_len(SlangWasmResult handle)
{
    const auto it = g_results.find(handle);
    if (it == g_results.end())
        return 0;
    return static_cast<uint32_t>(it->second->reflectionJson.size());
}

extern "C" uint32_t slang_wasm_result_diagnostics_ptr(SlangWasmResult handle)
{
    const auto it = g_results.find(handle);
    if (it == g_results.end())
        return 0;
    return static_cast<uint32_t>(reinterpret_cast<uintptr_t>(it->second->diagnostics.data()));
}

extern "C" uint32_t slang_wasm_result_diagnostics_len(SlangWasmResult handle)
{
    const auto it = g_results.find(handle);
    if (it == g_results.end())
        return 0;
    return static_cast<uint32_t>(it->second->diagnostics.size());
}

extern "C" void slang_wasm_result_destroy(SlangWasmResult handle)
{
    delete takeHandle(g_results, handle);
}

// ── Version ───────────────────────────────────────────────────────────────────

extern "C" const char* slang_wasm_version(void)
{
    if (!ensureGlobalSession())
        return "unknown";
    return g_globalSession->getBuildTagString();
}
