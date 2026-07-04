#!/usr/bin/env python3
"""
Tests for generate-slang-bindings.py — the enum-metadata generator that
backs slang-wasm-enum-metadata.cpp.

The generator parses include/slang.h with regexes rather than a real C++
parser, and silently skips (via a stderr WARNING, not a build failure) any
enum it cannot find or any member whose value it cannot statically evaluate.
These tests exist to turn that silent-drop failure mode into a hard test
failure, and to pin down the parsing heuristics (sentinel/deprecated
filtering, alias de-duplication, prefix stripping) against both synthetic
snippets and the real header.

Run with:
    python3 -m unittest source.slang-wasm-wasi.tools.test_generate_slang_bindings -v
or directly:
    python3 source/slang-wasm-wasi/tools/test_generate_slang_bindings.py
"""

import importlib.util
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "generate-slang-bindings.py")
REPO_ROOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
SLANG_H = os.path.join(REPO_ROOT, "include", "slang.h")


def _import_generator():
    spec = importlib.util.spec_from_file_location("generate_slang_bindings", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


gen = _import_generator()


# ---------------------------------------------------------------------------
# parse_enum_body: sentinel/deprecated filtering, aliasing, value forms
# ---------------------------------------------------------------------------


class ParseEnumBodyTests(unittest.TestCase):
    def test_keeps_implicit_and_explicit_decimal_members(self):
        body = """
            FOO_NONE = 0,
            FOO_A,
            FOO_B = 5,
            FOO_C,
        """
        self.assertEqual(
            gen.parse_enum_body(body),
            [("FOO_NONE", 0), ("FOO_A", 1), ("FOO_B", 5), ("FOO_C", 6)],
        )

    def test_keeps_hex_and_bit_shift_members(self):
        body = """
            FLAG_NONE = 0x0,
            FLAG_A = 1 << 4,
            FLAG_B = 0x20,
        """
        self.assertEqual(
            gen.parse_enum_body(body),
            [("FLAG_NONE", 0), ("FLAG_A", 16), ("FLAG_B", 32)],
        )

    def test_drops_sentinel_members_without_advancing_past_them(self):
        body = """
            FOO_A = 0,
            FOO_B = 1,
            FOO_COUNT_OF,
        """
        # The sentinel is dropped from the result, but its slot still
        # advances the implicit counter for anything declared after it.
        self.assertEqual(gen.parse_enum_body(body), [("FOO_A", 0), ("FOO_B", 1)])

    def test_drops_deprecated_and_removed_members(self):
        body = """
            FOO_A = 0,
            FOO_B_DEPRECATED = 1,
            REMOVED_FOO_C = 2,
            FOO_D = 3,
        """
        self.assertEqual(
            gen.parse_enum_body(body), [("FOO_A", 0), ("FOO_D", 3)]
        )

    def test_drops_alias_members_sharing_a_value(self):
        body = """
            FOO_A = 0,
            FOO_A_OLD_NAME = 0,
            FOO_B = 1,
        """
        # FOO_A_OLD_NAME has the same integer value as FOO_A and is dropped
        # as a duplicate, keeping only the first occurrence.
        self.assertEqual(gen.parse_enum_body(body), [("FOO_A", 0), ("FOO_B", 1)])

    def test_skips_members_with_unevaluable_expressions_without_raising(self):
        body = """
            FOO_A = 0,
            FOO_B = (FOO_A | 1),
            FOO_C = 2,
        """
        # A bitwise-OR expression can't be statically evaluated by
        # _eval_value; the member is skipped rather than crashing the
        # generator, but the counter still advances past it (matching
        # parse_enum_body's aliasing rule for unresolved expressions).
        self.assertEqual(gen.parse_enum_body(body), [("FOO_A", 0), ("FOO_C", 2)])

    def test_ignores_block_and_line_comments(self):
        body = """
            /** Leading doc comment spanning
             *  multiple lines. */
            FOO_A = 0, ///< inline doc
            FOO_B = 1, // plain comment
        """
        self.assertEqual(gen.parse_enum_body(body), [("FOO_A", 0), ("FOO_B", 1)])


# ---------------------------------------------------------------------------
# extract_named_enum / _find_raw_enum_body: locating an enum block by name
# ---------------------------------------------------------------------------


class ExtractNamedEnumTests(unittest.TestCase):
    def test_finds_plain_enum_with_base_type(self):
        content = """
            enum SlangWidget : SlangWidgetIntegral
            {
                SLANG_WIDGET_NONE = 0,
                SLANG_WIDGET_ROUND = 1,
            };
        """
        self.assertEqual(
            gen.extract_named_enum(content, "SlangWidget"),
            [("SLANG_WIDGET_NONE", 0), ("SLANG_WIDGET_ROUND", 1)],
        )

    def test_finds_enum_class_without_base_type(self):
        # Mirrors CompilerOptionName in slang.h: `enum class Name { ... }`
        # with no `: BaseType` clause at all.
        content = """
            enum class WidgetOption
            {
                MacroDefine = 0,
                DepFile = 1,
            };
        """
        self.assertEqual(
            gen.extract_named_enum(content, "WidgetOption"),
            [("MacroDefine", 0), ("DepFile", 1)],
        )

    def test_returns_none_for_a_missing_enum(self):
        self.assertIsNone(gen.extract_named_enum("enum Other { A = 0 };", "Missing"))


# ---------------------------------------------------------------------------
# extract_prefixed_members: anonymous/flag enums matched by name prefix
# ---------------------------------------------------------------------------


class ExtractPrefixedMembersTests(unittest.TestCase):
    def test_finds_prefixed_flag_members_anywhere_in_the_file(self):
        # Mirrors the real SlangTargetFlags shape: an anonymous `enum { ... }`
        # following a typedef, where each member starts its own line (rather
        # than a named enum block, which extract_named_enum handles instead).
        content = """
            typedef uint32_t SlangWidgetFlags;
            enum
            {
                /* Leading doc comment unrelated to the match. */
                SLANG_WIDGET_FLAG_ROUND = 1 << 0,
                SLANG_WIDGET_FLAG_SQUARE = 1 << 1,
                SLANG_UNRELATED_THING = 99,
            };
        """
        self.assertEqual(
            gen.extract_prefixed_members(content, "SLANG_WIDGET_FLAG_"),
            [("SLANG_WIDGET_FLAG_ROUND", 1), ("SLANG_WIDGET_FLAG_SQUARE", 2)],
        )


# ---------------------------------------------------------------------------
# strip_prefix: the three stripping modes used by ENUM_CONFIG entries
# ---------------------------------------------------------------------------


class StripPrefixTests(unittest.TestCase):
    def test_auto_detects_longest_common_prefix(self):
        names = ["SLANG_TARGET_UNKNOWN", "SLANG_TARGET_NONE", "SLANG_SPIRV"]
        prefix, stripped = gen.strip_prefix(names, None)
        self.assertEqual(prefix, "SLANG_")
        self.assertEqual(stripped, ["TARGET_UNKNOWN", "TARGET_NONE", "SPIRV"])

    def test_empty_forced_prefix_keeps_names_unchanged(self):
        names = ["MacroDefine", "DepFile"]
        prefix, stripped = gen.strip_prefix(names, "")
        self.assertEqual(prefix, "")
        self.assertEqual(stripped, names)

    def test_explicit_forced_prefix_is_stripped_exactly(self):
        names = ["SLANG_TARGET_FLAG_GENERATE_WHOLE_PROGRAM", "SLANG_TARGET_FLAG_DUMP_IR"]
        prefix, stripped = gen.strip_prefix(names, "SLANG_TARGET_FLAG_")
        self.assertEqual(prefix, "SLANG_TARGET_FLAG_")
        self.assertEqual(stripped, ["GENERATE_WHOLE_PROGRAM", "DUMP_IR"])


# ---------------------------------------------------------------------------
# Load-bearing invariants against the real include/slang.h. These exist so a
# slang.h refactor that changes an enum's declaration shape enough to break
# the regex heuristics fails this test instead of silently shipping a
# metadata blob with a missing or wrong enumerator (generate-slang-bindings.py
# only prints a WARNING to stderr and continues when an enum can't be found
# or has no parseable members).
# ---------------------------------------------------------------------------


class RealSlangHeaderTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(SLANG_H, encoding="utf-8") as f:
            cls.content = f.read()

    def _extract(self, spec):
        if spec.prefix:
            return gen.extract_prefixed_members(self.content, spec.prefix)
        return gen.extract_named_enum(self.content, spec.c_name)

    def test_every_configured_enum_resolves_to_a_non_empty_member_list(self):
        for spec in gen.ENUM_CONFIG:
            with self.subTest(enum=spec.c_name):
                members = self._extract(spec)
                self.assertIsNotNone(
                    members, f"{spec.c_name} was not found in slang.h at all"
                )
                self.assertTrue(
                    members, f"{spec.c_name} was found but has no parseable members"
                )

    def test_target_spirv_and_unknown_have_the_expected_values(self):
        members = dict(gen.extract_named_enum(self.content, "SlangCompileTarget"))
        self.assertEqual(members["SLANG_SPIRV"], 6)
        self.assertEqual(members["SLANG_TARGET_UNKNOWN"], 0)

    def test_target_flags_contains_generate_whole_program(self):
        spec = next(s for s in gen.ENUM_CONFIG if s.c_name == "SlangTargetFlags")
        members = dict(gen.extract_prefixed_members(self.content, spec.prefix))
        self.assertIn("SLANG_TARGET_FLAG_GENERATE_WHOLE_PROGRAM", members)


if __name__ == "__main__":
    unittest.main()
