// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Compiler option key. Mirrors {@code CompilerOptionName} in slang.h. */
public enum CompilerOptionName {
    MacroDefine(0), DepFile(1), EntryPointName(2), Specialize(3), Help(4), HelpStyle(5), Include(6), Language(7), MatrixLayoutColumn(8), MatrixLayoutRow(9), ZeroInitialize(10), IgnoreCapabilities(11), RestrictiveCapabilityCheck(12), ModuleName(13), Output(14), Profile(15), Stage(16), Target(17), Version(18), WarningsAsErrors(19), DisableWarnings(20), EnableWarning(21), DisableWarning(22), DumpWarningDiagnostics(23), InputFilesRemain(24), EmitIr(25), ReportDownstreamTime(26), ReportPerfBenchmark(27), ReportCheckpointIntermediates(28), SkipSPIRVValidation(29), SourceEmbedStyle(30), SourceEmbedName(31), SourceEmbedLanguage(32), DisableShortCircuit(33), MinimumSlangOptimization(34), DisableNonEssentialValidations(35), DisableSourceMap(36), UnscopedEnum(37), PreserveParameters(38), Capability(39), DefaultImageFormatUnknown(40), DisableDynamicDispatch(41), DisableSpecialization(42), FloatingPointMode(43), DebugInformation(44), LineDirectiveMode(45), Optimization(46), Obfuscate(47), VulkanBindShift(48), VulkanBindGlobals(49), VulkanInvertY(50), VulkanUseDxPositionW(51), VulkanUseEntryPointName(52), VulkanUseGLLayout(53), VulkanEmitReflection(54), GLSLForceScalarLayout(55), EnableEffectAnnotations(56), EmitSpirvViaGLSL(57), EmitSpirvDirectly(58), SPIRVCoreGrammarJSON(59), IncompleteLibrary(60), CompilerPath(61), DefaultDownstreamCompiler(62), DownstreamArgs(63), PassThrough(64), DumpRepro(65), DumpReproOnError(66), ExtractRepro(67), LoadRepro(68), LoadReproDirectory(69), ReproFallbackDirectory(70), DumpAst(71), DumpIntermediatePrefix(72), DumpIntermediates(73), DumpIr(74), DumpIrIds(75), PreprocessorOutput(76), OutputIncludes(77), ReproFileSystem(78), SkipCodeGen(80), ValidateIr(81), VerbosePaths(82), VerifyDebugSerialIr(83), NoCodeGen(84), FileSystem(85), Heterogeneous(86), NoMangle(87), NoHLSLBinding(88), NoHLSLPackConstantBufferElements(89), ValidateUniformity(90), AllowGLSL(91), EnableExperimentalPasses(92), BindlessSpaceIndex(93), SPIRVResourceHeapStride(94), SPIRVSamplerHeapStride(95), ArchiveType(96), CompileCoreModule(97), Doc(98), IrCompression(99), LoadCoreModule(100), ReferenceModule(101), SaveCoreModule(102), SaveCoreModuleBinSource(103), TrackLiveness(104), LoopInversion(105), ParameterBlocksUseRegisterSpaces(106), LanguageVersion(107), TypeConformance(108), EnableExperimentalDynamicDispatch(109), EmitReflectionJSON(110), DebugInformationFormat(112), VulkanBindShiftAll(113), GenerateWholeProgram(114), UseUpToDateBinaryModule(115), EmbedDownstreamIR(116), ForceDXLayout(117), EmitSpirvMethod(118), SaveGLSLModuleBinSource(119), SkipDownstreamLinking(120), DumpModule(121), GetModuleInfo(122), GetSupportedModuleVersions(123), EmitSeparateDebug(124), DenormalModeFp16(125), DenormalModeFp32(126), DenormalModeFp64(127), UseMSVCStyleBitfieldPacking(128), ForceCLayout(129), ExperimentalFeature(130), ReportDetailedPerfBenchmark(131), ValidateIRDetailed(132), DumpIRBefore(133), DumpIRAfter(134), EmitCPUMethod(135), EmitCPUViaCPP(136), EmitCPUViaLLVM(137), LLVMTargetTriple(138), LLVMCPU(139), LLVMFeatures(140), EnableRichDiagnostics(141), ReportDynamicDispatchSites(142), EnableMachineReadableDiagnostics(143), DiagnosticColor(144), TraceCoverage(145), TraceFunctionCoverage(148), TraceBranchCoverage(149);

    public final int value;

    CompilerOptionName(int value) {
        this.value = value;
    }

    /** Return the CompilerOptionName constant for the given integer value. */
    public static CompilerOptionName fromValue(int v) {
        for (CompilerOptionName t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown CompilerOptionName value: " + v);
    }
}
