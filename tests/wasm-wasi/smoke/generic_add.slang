// A compute entry point whose numthreads size is itself a generic value
// parameter, used by smoke-test.py to exercise slang_wasm_compile_specialized_entry_point.
// Mirrors the fixture in tools/slang-unit-test/unit-test-generic-entrypoint.cpp:
// specializing `x` to different constants must produce different compiled code,
// which is how the smoke test proves the specialization argument actually took
// effect without needing to decode the target binary.

RWStructuredBuffer<int> outputBuffer;

[shader("compute")]
[numthreads(x, 1, 1)]
void computeMain<int x>(uint3 tid : SV_DispatchThreadID)
{
    outputBuffer[tid.x] = x * 100 + 7;
}
