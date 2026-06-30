// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)


package org.shaderslang.wasm.enums;

/** Compiler option key. Mirrors {@code CompilerOptionName} in slang.h. */
public enum CompilerOptionName {
    /** stringValue0: macro name;  stringValue1: macro value */
    MacroDefine(0),
    DepFile(1),
    EntryPointName(2),
    Specialize(3),
    Help(4),
    HelpStyle(5),
    /** stringValue: additional include path. */
    Include(6),
    Language(7),
    /** bool */
    MatrixLayoutColumn(8),
    /** bool */
    MatrixLayoutRow(9),
    /** bool */
    ZeroInitialize(10),
    /** bool */
    IgnoreCapabilities(11),
    /** bool */
    RestrictiveCapabilityCheck(12),
    /** stringValue0: module name. */
    ModuleName(13),
    Output(14),
    /** intValue0: profile */
    Profile(15),
    /** intValue0: stage */
    Stage(16),
    /** intValue0: CodeGenTarget */
    Target(17),
    Version(18),
    /** stringValue0: "all" or comma-separated list of */
    WarningsAsErrors(19),
    /** stringValue0: comma separated list of warning codes or names. */
    DisableWarnings(20),
    /** stringValue0: warning code or name. */
    EnableWarning(21),
    /** stringValue0: warning code or name. */
    DisableWarning(22),
    DumpWarningDiagnostics(23),
    InputFilesRemain(24),
    /** bool */
    EmitIr(25),
    /** bool */
    ReportDownstreamTime(26),
    /** bool */
    ReportPerfBenchmark(27),
    /** bool */
    ReportCheckpointIntermediates(28),
    /** bool */
    SkipSPIRVValidation(29),
    SourceEmbedStyle(30),
    SourceEmbedName(31),
    SourceEmbedLanguage(32),
    /** bool */
    DisableShortCircuit(33),
    /** bool */
    MinimumSlangOptimization(34),
    /** bool */
    DisableNonEssentialValidations(35),
    /** bool */
    DisableSourceMap(36),
    /** bool */
    UnscopedEnum(37),
    /** bool: preserve all resource parameters in the output code. */
    PreserveParameters(38),
    /** intValue0: CapabilityName */
    Capability(39),
    /** bool */
    DefaultImageFormatUnknown(40),
    /** bool */
    DisableDynamicDispatch(41),
    /** bool */
    DisableSpecialization(42),
    /** intValue0: FloatingPointMode */
    FloatingPointMode(43),
    /** intValue0: DebugInfoLevel */
    DebugInformation(44),
    LineDirectiveMode(45),
    /** intValue0: OptimizationLevel */
    Optimization(46),
    /** bool */
    Obfuscate(47),
    /** intValue0 (higher 8 bits): kind; intValue0(lower bits): set; */
    VulkanBindShift(48),
    /** intValue0: index; intValue1: set */
    VulkanBindGlobals(49),
    /** bool */
    VulkanInvertY(50),
    /** bool */
    VulkanUseDxPositionW(51),
    /** bool */
    VulkanUseEntryPointName(52),
    /** bool */
    VulkanUseGLLayout(53),
    /** bool */
    VulkanEmitReflection(54),
    /** bool */
    GLSLForceScalarLayout(55),
    /** bool */
    EnableEffectAnnotations(56),
    /** bool (will be deprecated) */
    EmitSpirvViaGLSL(57),
    /** bool (will be deprecated) */
    EmitSpirvDirectly(58),
    /** stringValue0: json path */
    SPIRVCoreGrammarJSON(59),
    /** bool, when set, will not issue an error when the linked program */
    IncompleteLibrary(60),
    CompilerPath(61),
    DefaultDownstreamCompiler(62),
    /** stringValue0: downstream compiler name. stringValue1: argument list, */
    DownstreamArgs(63),
    PassThrough(64),
    DumpRepro(65),
    DumpReproOnError(66),
    ExtractRepro(67),
    LoadRepro(68),
    LoadReproDirectory(69),
    ReproFallbackDirectory(70),
    DumpAst(71),
    DumpIntermediatePrefix(72),
    /** bool */
    DumpIntermediates(73),
    /** bool */
    DumpIr(74),
    DumpIrIds(75),
    PreprocessorOutput(76),
    OutputIncludes(77),
    ReproFileSystem(78),
    /** bool */
    SkipCodeGen(80),
    /** bool */
    ValidateIr(81),
    VerbosePaths(82),
    VerifyDebugSerialIr(83),
    /** Not used. */
    NoCodeGen(84),
    FileSystem(85),
    Heterogeneous(86),
    NoMangle(87),
    NoHLSLBinding(88),
    NoHLSLPackConstantBufferElements(89),
    ValidateUniformity(90),
    AllowGLSL(91),
    EnableExperimentalPasses(92),
    /** int */
    BindlessSpaceIndex(93),
    /** int: byte stride for SPIRV resource descriptor heap */
    SPIRVResourceHeapStride(94),
    /** int: byte stride for SPIRV sampler descriptor heap */
    SPIRVSamplerHeapStride(95),
    ArchiveType(96),
    CompileCoreModule(97),
    Doc(98),
    /** deprecated; value must never be reused */
    IrCompression(99),
    LoadCoreModule(100),
    ReferenceModule(101),
    SaveCoreModule(102),
    SaveCoreModuleBinSource(103),
    TrackLiveness(104),
    /** bool, enable loop inversion optimization */
    LoopInversion(105),
    /** Deprecated; value must never be reused */
    ParameterBlocksUseRegisterSpaces(106),
    /** intValue0: SlangLanguageVersion */
    LanguageVersion(107),
    /** stringValue0: type conformance to link; format: */
    TypeConformance(108),
    /** bool, experimental */
    EnableExperimentalDynamicDispatch(109),
    /** bool */
    EmitReflectionJSON(110),
    /** intValue0: DebugInfoFormat (derived from -g; no direct CLI */
    DebugInformationFormat(112),
    /** intValue0: kind; intValue1: shift (derived from */
    VulkanBindShiftAll(113),
    /** bool */
    GenerateWholeProgram(114),
    /** bool, when set, will only load precompiled modules */
    UseUpToDateBinaryModule(115),
    /** bool */
    EmbedDownstreamIR(116),
    /** bool */
    ForceDXLayout(117),
    /** enum SlangEmitSpirvMethod (derived; no direct CLI flag) */
    EmitSpirvMethod(118),
    SaveGLSLModuleBinSource(119),
    /** bool, experimental (API-only; no direct CLI flag) */
    SkipDownstreamLinking(120),
    DumpModule(121),
    /** Print serialized module version and name */
    GetModuleInfo(122),
    /** Print the min and max module versions this compiler */
    GetSupportedModuleVersions(123),
    /** bool */
    EmitSeparateDebug(124),
    DenormalModeFp16(125),
    DenormalModeFp32(126),
    DenormalModeFp64(127),
    /** bool */
    UseMSVCStyleBitfieldPacking(128),
    /** bool */
    ForceCLayout(129),
    /** bool, enable experimental features */
    ExperimentalFeature(130),
    /** bool, reports detailed compiler performance benchmark */
    ReportDetailedPerfBenchmark(131),
    /** bool, enable detailed IR validation */
    ValidateIRDetailed(132),
    /** string, pass name to dump IR before */
    DumpIRBefore(133),
    /** string, pass name to dump IR after */
    DumpIRAfter(134),
    /** enum SlangEmitCPUMethod (derived; no direct CLI flag) */
    EmitCPUMethod(135),
    /** bool */
    EmitCPUViaCPP(136),
    /** bool */
    EmitCPUViaLLVM(137),
    /** string */
    LLVMTargetTriple(138),
    /** string */
    LLVMCPU(139),
    /** string */
    LLVMFeatures(140),
    /** bool, enable the experimental rich diagnostics */
    EnableRichDiagnostics(141),
    /** bool */
    ReportDynamicDispatchSites(142),
    /** bool, enable machine-readable diagnostic output */
    EnableMachineReadableDiagnostics(143),
    /** intValue0: SlangDiagnosticColor (always, never, auto) */
    DiagnosticColor(144),
    /** bool: insert per-statement line coverage counters */
    TraceCoverage(145),
    /** bool: insert per-function-entry coverage counters */
    TraceFunctionCoverage(148),
    /** bool: insert per-branch-arm coverage counters */
    TraceBranchCoverage(149),
    CompilerVersion(153);

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
