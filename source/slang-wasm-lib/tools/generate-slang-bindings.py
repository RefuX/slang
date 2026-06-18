#!/usr/bin/env python3
"""
generate-slang-bindings.py
Reads include/slang.h and emits two generated artefacts:
  1. source/slang-wasm-lib/slang-wasm-enum-metadata.cpp
     A C++ translation unit containing a static JSON blob of all Slang enum values,
     exported from the WASM module via slang_wasm_enum_metadata_ptr/len. Dynamic
     runtimes (Go, Python, Rust) call these two exports at startup to resolve enum
     integer values without hardcoding them.
  2. source/slang-wasm-lib/java/src/generated/java/org/shaderslang/wasm/enums/*.java
     One Java source file per Slang enum, with integer values baked in from slang.h.
     These provide compile-time type safety and IDE autocomplete for the Java binding.

Both outputs are committed to the repository so they are always available without
running the generator. CMake runs the generator at configure time when slang.h is
newer than the C++ output (see source/slang-wasm-lib/CMakeLists.txt).

Usage:
  python source/slang-wasm-lib/tools/generate-slang-bindings.py [--slang-h PATH] [--cpp-out PATH] [--java-out DIR]

Defaults assume the script is run from the repository root.
"""

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from typing import Optional

# ---------------------------------------------------------------------------
# Enum specification table
# ---------------------------------------------------------------------------


@dataclass
class EnumSpec:
    c_name: str         # C++ enum name, e.g. "SlangCompileTarget" or "CompilerOptionName"
    java_name: str      # Java enum class name, e.g. "Target"
    javadoc: str        # One-sentence Javadoc summary
    # Prefix to strip from member names when forming Java constant names:
    #   None  → auto-detect the longest common prefix ending with '_'
    #   ""    → keep member names as-is (CompilerOptionName: already PascalCase)
    #   "FOO_" → strip exactly "FOO_" from each member name
    prefix: Optional[str] = None


ENUM_CONFIG = [
    EnumSpec("SlangCompileTarget",     "Target",            "Compile output format."),
    EnumSpec("SlangStage",             "Stage",             "Pipeline stage."),
    EnumSpec("CompilerOptionName",     "CompilerOptionName","Compiler option key.",           prefix=""),
    EnumSpec("SlangTypeKind",          "TypeKind",          "Reflection type kind."),
    EnumSpec("SlangScalarType",        "ScalarType",        "Scalar element type."),
    EnumSpec("SlangResourceAccess",    "ResourceAccess",    "Resource access mode."),
    EnumSpec("SlangResourceShape",     "ResourceShape",     "Resource shape."),
    EnumSpec("SlangParameterCategory", "ParameterCategory", "Parameter binding category."),
    EnumSpec("SlangLayoutRules",       "LayoutRules",       "Layout rule set."),
    EnumSpec("SlangMatrixLayoutMode",  "MatrixLayoutMode",  "Matrix storage order."),
    EnumSpec("SlangFloatingPointMode", "FloatingPointMode", "Floating-point mode."),
    EnumSpec("SlangOptimizationLevel", "OptimizationLevel", "Optimisation level."),
    EnumSpec("SlangDebugInfoLevel",    "DebugInfoLevel",    "Debug info verbosity."),
    EnumSpec("SlangPassThrough",       "PassThrough",       "Downstream compiler."),
    # SlangTargetFlags is an anonymous enum; identify members by prefix.
    EnumSpec("SlangTargetFlags",       "TargetFlags",       "Target flag bits.",             prefix="SLANG_TARGET_FLAG_"),
]

# Member names matching these patterns are sentinels that should be excluded.
_SENTINEL_RE = re.compile(
    r"(?:"
    r"_COUNT_OF$|_COUNT$|COUNT_OF$|CountOf$|CountOfParsableOptions$"
    r"|_FORCE_UINT32$|_FORCE_INT32$|_COUNT_V1$"
    r")"
)

# Member names containing these substrings are deprecated/removed and excluded.
_DEPRECATED_RE = re.compile(r"_DEPRECATED|^REMOVED_")

# ---------------------------------------------------------------------------
# Value parsing
# ---------------------------------------------------------------------------


def _eval_value(expr: str, member_map: dict) -> Optional[int]:
    """Evaluate a simple enum value expression.

    Returns the integer value, or None when the expression references another
    member (alias) or is too complex to evaluate statically (bitwise OR, etc.).
    Callers treat None as a signal to skip the member.
    """
    expr = expr.strip()
    # Hex literal
    if re.match(r"^-?0x[0-9A-Fa-f]+$", expr):
        return int(expr, 16)
    # Decimal literal
    if re.match(r"^-?\d+$", expr):
        return int(expr)
    # Bit-shift literal: 1 << N
    m = re.match(r"^1\s*<<\s*(\d+)$", expr)
    if m:
        return 1 << int(m.group(1))
    # Reference to a previously-seen member (alias) — skip
    if expr in member_map:
        return None
    # Anything else (bitwise OR, complex expression) — skip
    return None


def parse_enum_body(body: str) -> list:
    """Parse the interior of an enum block and return (name, int_value) pairs.

    Skips sentinels, deprecated members, duplicate integer values (aliases),
    and members whose value expression cannot be evaluated statically.
    """
    results = []
    seen_values: set = set()
    member_map: dict = {}
    counter = 0

    # Remove all block comments (/* ... */) including multi-line ones before
    # processing line by line. This handles /** Docs spanning multiple lines */
    # patterns that appear after member values in this file.
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.DOTALL)

    for line in body.splitlines():
        line = re.sub(r"//.*", "", line).strip().rstrip(",")
        if not line:
            continue

        m = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\s*(?:=\s*(.+))?$", line)
        if not m:
            continue

        name = m.group(1)
        value_str = (m.group(2) or "").strip()

        if value_str:
            val = _eval_value(value_str, member_map)
            if val is None:
                continue  # alias or complex — skip without advancing counter
            counter = val
        else:
            val = counter

        counter = val + 1
        member_map[name] = val

        if _SENTINEL_RE.search(name):
            continue
        if _DEPRECATED_RE.search(name):
            continue
        if val in seen_values:
            continue  # duplicate value (alias) — keep first occurrence

        seen_values.add(val)
        results.append((name, val))

    return results


def extract_named_enum(content: str, c_name: str) -> Optional[list]:
    """Find a named enum block (enum [class] c_name [: BaseType] { ... }) and parse it."""
    pattern = (
        rf"enum\s+(?:class\s+)?{re.escape(c_name)}"
        rf"\s*(?::[^{{]+?)?\s*\{{(.*?)\}}"
    )
    m = re.search(pattern, content, re.DOTALL)
    if not m:
        return None
    return parse_enum_body(m.group(1))


def extract_prefixed_members(content: str, prefix: str) -> list:
    """Find all 'PREFIX_name = simple_value' lines anywhere in the file.

    Used for SlangTargetFlags, which is declared as an anonymous enum after
    a typedef rather than as a named enum.
    """
    results = []
    seen_values: set = set()

    for line in content.splitlines():
        line_clean = re.sub(r"//.*", "", line).strip().rstrip(",")
        m = re.match(
            rf"^({re.escape(prefix)}[A-Za-z0-9_]+)\s*=\s*(.+)$", line_clean
        )
        if not m:
            continue
        name = m.group(1)
        val_str = m.group(2).strip()
        val = _eval_value(val_str, {})
        if val is None:
            continue
        if _SENTINEL_RE.search(name):
            continue
        if _DEPRECATED_RE.search(name):
            continue
        if val in seen_values:
            continue
        seen_values.add(val)
        results.append((name, val))

    return results


# ---------------------------------------------------------------------------
# Prefix stripping
# ---------------------------------------------------------------------------


def strip_prefix(names: list, forced_prefix: Optional[str]) -> tuple:
    """Determine and strip the common name prefix from a list of C member names.

    Returns (detected_prefix, list_of_stripped_names).

    forced_prefix="" → keep names unchanged (CompilerOptionName: already PascalCase).
    forced_prefix="FOO_" → strip that exact prefix from every name.
    forced_prefix=None → auto-detect the longest common prefix ending with '_'.
    """
    if forced_prefix == "":
        return ("", list(names))
    if forced_prefix:
        stripped = [
            n[len(forced_prefix):] if n.startswith(forced_prefix) else n
            for n in names
        ]
        return (forced_prefix, stripped)
    # Auto-detect
    if not names:
        return ("", list(names))
    common = os.path.commonprefix(names)
    idx = common.rfind("_")
    prefix = common[: idx + 1] if idx >= 0 else ""
    stripped = [n[len(prefix):] if n.startswith(prefix) else n for n in names]
    return (prefix, stripped)


# ---------------------------------------------------------------------------
# Code generation
# ---------------------------------------------------------------------------

_GENERATED_HEADER = (
    "// THIS FILE IS GENERATED — DO NOT EDIT.\n"
    "// Source: include/slang.h\n"
    "// Generator: source/slang-wasm-lib/tools/generate-slang-bindings.py\n"
    "// Re-run: python source/slang-wasm-lib/tools/generate-slang-bindings.py  (or cmake --preset default)\n"
)


def generate_cpp(enum_data: dict) -> str:
    """Emit slang-wasm-enum-metadata.cpp.

    The file exports two C functions from the WASM module:
      slang_wasm_enum_metadata_ptr() → pointer to the JSON blob in linear memory
      slang_wasm_enum_metadata_len() → byte length of the blob (excluding NUL)

    The JSON format is: {"EnumName":{"MemberName":intValue,...},...}
    """
    # Build JSON: {JavaEnumName: {JavaMemberName: intValue}}
    obj = {name: dict(members) for name, members in enum_data.items()}
    blob = json.dumps(obj, separators=(",", ":"))

    lines = [
        _GENERATED_HEADER,
        "",
        "#include <cstdint>",
        "",
        "static const char kEnumMetadataJson[] =",
        f"    {json.dumps(blob)};",
        "",
        'extern "C" uint32_t slang_wasm_enum_metadata_ptr(void) {',
        "    return static_cast<uint32_t>(",
        "        reinterpret_cast<uintptr_t>(kEnumMetadataJson));",
        "}",
        'extern "C" uint32_t slang_wasm_enum_metadata_len(void) {',
        "    return static_cast<uint32_t>(sizeof(kEnumMetadataJson) - 1);",
        "}",
        "",
    ]
    return "\n".join(lines)


def generate_java_enum(spec: EnumSpec, java_members: list) -> str:
    """Emit a Java enum source file for one Slang enum."""
    members_str = ", ".join(f"{name}({value})" for name, value in java_members)
    lines = [
        _GENERATED_HEADER,
        "",
        "package org.shaderslang.wasm.enums;",
        "",
        f"/** {spec.javadoc} Mirrors {{@code {spec.c_name}}} in slang.h. */",
        f"public enum {spec.java_name} {{",
        f"    {members_str};",
        "",
        "    public final int value;",
        "",
        f"    {spec.java_name}(int value) {{",
        "        this.value = value;",
        "    }",
        "",
        f"    /** Return the {spec.java_name} constant for the given integer value. */",
        f"    public static {spec.java_name} fromValue(int v) {{",
        f"        for ({spec.java_name} t : values()) if (t.value == v) return t;",
        f'        throw new IllegalArgumentException("Unknown {spec.java_name} value: " + v);',
        "    }",
        "}",
        "",
    ]
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    repo_root = os.path.dirname(
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    )

    parser = argparse.ArgumentParser(description="Generate Slang enum bindings.")
    parser.add_argument(
        "--slang-h",
        default=os.path.join(repo_root, "include", "slang.h"),
        help="Path to include/slang.h",
    )
    parser.add_argument(
        "--cpp-out",
        default=os.path.join(
            repo_root,
            "source", "slang-wasm-lib", "slang-wasm-enum-metadata.cpp",
        ),
        help="Output path for the C++ metadata translation unit",
    )
    parser.add_argument(
        "--java-out",
        default=os.path.join(
            repo_root,
            "source", "slang-wasm-lib", "java", "src", "generated",
            "java", "org", "shaderslang", "wasm", "enums",
        ),
        help="Output directory for generated Java enum source files",
    )
    args = parser.parse_args()

    with open(args.slang_h, encoding="utf-8") as f:
        content = f.read()

    # Collect per-enum (java_name → [(java_member_name, int_value)]) data.
    enum_data: dict = {}

    for spec in ENUM_CONFIG:
        # Extract raw (C_member_name, int_value) pairs.
        if spec.prefix:
            # Anonymous or flag enum: locate members by their name prefix.
            raw = extract_prefixed_members(content, spec.prefix)
        else:
            # Named enum (covers both "enum class" and "enum Name : Base").
            raw = extract_named_enum(content, spec.c_name)

        if raw is None:
            print(
                f"WARNING: enum {spec.c_name!r} not found in {args.slang_h}",
                file=sys.stderr,
            )
            continue
        if not raw:
            print(
                f"WARNING: enum {spec.c_name!r} has no parseable members",
                file=sys.stderr,
            )
            continue

        c_names = [n for n, _ in raw]
        values = [v for _, v in raw]
        _, stripped = strip_prefix(c_names, spec.prefix)
        enum_data[spec.java_name] = list(zip(stripped, values))

    # Write C++ output.
    cpp_dir = os.path.dirname(args.cpp_out)
    if cpp_dir:
        os.makedirs(cpp_dir, exist_ok=True)
    with open(args.cpp_out, "w", encoding="utf-8") as f:
        f.write(generate_cpp(enum_data))
    print(f"Wrote {args.cpp_out}")

    # Write Java outputs.
    os.makedirs(args.java_out, exist_ok=True)
    for spec in ENUM_CONFIG:
        if spec.java_name not in enum_data:
            continue
        java_source = generate_java_enum(spec, enum_data[spec.java_name])
        out_path = os.path.join(args.java_out, f"{spec.java_name}.java")
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(java_source)
        print(f"Wrote {out_path}")


if __name__ == "__main__":
    main()
