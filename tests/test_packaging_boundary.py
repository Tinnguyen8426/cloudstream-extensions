#!/usr/bin/env python3
"""
Adversarial Packaging & DEX Bytecode Boundary Test Suite.

Adversarially stress tests:
1. .cs3 zip archives for boundary defects:
   - Extra unauthorized files detection
   - Corrupt header / truncated zip / zero-byte zip tests
   - File permission and attribute verification
2. classes.dex disassembly and inspection:
   - Valid DEX 035 magic header, Adler32 checksum, SHA1 signature, file size parity
   - Endianness (0x12345678) and header size (0x70)
   - Full symbol table resolution: entrypoint classes exist and extend Plugin
   - Zero unresolved external dependencies (only standard Android/CloudStream/bundled libs)
3. Checksum tampering detection:
   - Byte-level modifications invalidate repo.json / plugins.json SHA256 hashes
"""
import os
import sys
import json
import struct
import zlib
import hashlib
import zipfile
import tempfile
import unittest

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STREMIO_CS3 = os.path.join(ROOT_DIR, "StremioSubtitleEnricher", "dist", "StremioSubEnricher.cs3")
KITSUNEKKO_CS3 = os.path.join(ROOT_DIR, "KitsunekkoSubtitleEnricher", "dist", "KitsunekkoEnricher.cs3")
AI_TRANSLATOR_CS3 = os.path.join(ROOT_DIR, "AiSubtitleTranslator", "dist", "AiSubtitleTranslator.cs3")
STREMIO_PLUGINS_JSON = os.path.join(ROOT_DIR, "StremioSubtitleEnricher", "dist", "plugins.json")
KITSUNEKKO_PLUGINS_JSON = os.path.join(ROOT_DIR, "KitsunekkoSubtitleEnricher", "dist", "plugins.json")
AI_TRANSLATOR_PLUGINS_JSON = os.path.join(ROOT_DIR, "AiSubtitleTranslator", "dist", "plugins.json")

def compute_sha256(filepath_or_bytes):
    h = hashlib.sha256()
    if isinstance(filepath_or_bytes, bytes):
        h.update(filepath_or_bytes)
    else:
        with open(filepath_or_bytes, "rb") as f:
            while chunk := f.read(65536):
                h.update(chunk)
    return "sha256-" + h.hexdigest()

def parse_dex(dex_bytes):
    """Parses a DEX binary and returns header dict, strings, types, and classes."""
    assert len(dex_bytes) >= 112, "DEX file too small for header"
    magic = dex_bytes[0:8]
    checksum = struct.unpack('<I', dex_bytes[8:12])[0]
    signature = dex_bytes[12:32]
    file_size = struct.unpack('<I', dex_bytes[32:36])[0]
    header_size = struct.unpack('<I', dex_bytes[36:40])[0]
    endian_tag = struct.unpack('<I', dex_bytes[40:44])[0]
    
    string_ids_size, string_ids_off = struct.unpack('<II', dex_bytes[56:64])
    type_ids_size, type_ids_off = struct.unpack('<II', dex_bytes[64:72])
    proto_ids_size, proto_ids_off = struct.unpack('<II', dex_bytes[72:80])
    field_ids_size, field_ids_off = struct.unpack('<II', dex_bytes[80:88])
    method_ids_size, method_ids_off = struct.unpack('<II', dex_bytes[88:96])
    class_defs_size, class_defs_off = struct.unpack('<II', dex_bytes[96:104])

    string_offsets = [
        struct.unpack('<I', dex_bytes[string_ids_off + i*4 : string_ids_off + (i+1)*4])[0]
        for i in range(string_ids_size)
    ]
    strings = []
    for off in string_offsets:
        p = off
        length = 0
        shift = 0
        while True:
            b = dex_bytes[p]
            p += 1
            length |= (b & 0x7f) << shift
            shift += 7
            if (b & 0x80) == 0:
                break
        end = dex_bytes.find(b'\0', p)
        strings.append(dex_bytes[p:end].decode('utf-8', errors='replace'))

    types = [
        strings[struct.unpack('<I', dex_bytes[type_ids_off + i*4 : type_ids_off + (i+1)*4])[0]]
        for i in range(type_ids_size)
    ]

    classes = []
    for i in range(class_defs_size):
        class_idx, access_flags, superclass_idx, interfaces_off = struct.unpack(
            '<IIII',
            dex_bytes[class_defs_off + i*32 : class_defs_off + i*32 + 16]
        )
        class_name = types[class_idx]
        super_name = types[superclass_idx] if superclass_idx != 0xffffffff else None
        classes.append((class_name, super_name))

    header = {
        "magic": magic,
        "checksum": checksum,
        "signature": signature,
        "file_size": file_size,
        "header_size": header_size,
        "endian_tag": endian_tag,
        "class_defs_size": class_defs_size,
        "string_ids_size": string_ids_size,
        "type_ids_size": type_ids_size,
        "method_ids_size": method_ids_size
    }
    return header, strings, types, classes


class TestPackageBoundaryDefects(unittest.TestCase):
    """Adversarially tests .cs3 zip archives for boundary defects and integrity."""

    def test_stremio_cs3_unauthorized_files(self):
        """Asserts .cs3 contains ONLY authorized runtime entries: classes.dex and manifest.json."""
        with zipfile.ZipFile(STREMIO_CS3, "r") as zf:
            namelist = zf.namelist()
            self.assertEqual(
                sorted(namelist),
                ["classes.dex", "manifest.json"],
                f"Unauthorized files found in Stremio package: {namelist}"
            )
            # Ensure no hidden dot-files, .kt source files, or temp artifacts
            for name in namelist:
                self.assertFalse(name.startswith("."), f"Dot-file found: {name}")
                self.assertFalse(name.endswith(".kt"), f"Source file leaked: {name}")
                self.assertFalse(name.endswith(".java"), f"Source file leaked: {name}")
                self.assertFalse("cache" in name.lower(), f"Cache artifact found: {name}")

    def test_kitsunekko_cs3_unauthorized_files(self):
        """Asserts .cs3 contains ONLY authorized runtime entries: classes.dex and manifest.json."""
        with zipfile.ZipFile(KITSUNEKKO_CS3, "r") as zf:
            namelist = zf.namelist()
            self.assertEqual(
                sorted(namelist),
                ["classes.dex", "manifest.json"],
                f"Unauthorized files found in Kitsunekko package: {namelist}"
            )
            for name in namelist:
                self.assertFalse(name.startswith("."), f"Dot-file found: {name}")
                self.assertFalse(name.endswith(".kt"), f"Source file leaked: {name}")
                self.assertFalse(name.endswith(".java"), f"Source file leaked: {name}")
                self.assertFalse("cache" in name.lower(), f"Cache artifact found: {name}")

    def test_ai_translator_cs3_unauthorized_files(self):
        """Asserts AiSubtitleTranslator.cs3 contains ONLY authorized runtime entries: classes.dex and manifest.json."""
        with zipfile.ZipFile(AI_TRANSLATOR_CS3, "r") as zf:
            namelist = zf.namelist()
            self.assertEqual(
                sorted(namelist),
                ["classes.dex", "manifest.json"],
                f"Unauthorized files found in AI Translator package: {namelist}"
            )
            for name in namelist:
                self.assertFalse(name.startswith("."), f"Dot-file found: {name}")
                self.assertFalse(name.endswith(".kt"), f"Source file leaked: {name}")
                self.assertFalse(name.endswith(".java"), f"Source file leaked: {name}")
                self.assertFalse("cache" in name.lower(), f"Cache artifact found: {name}")

    def test_zip_file_attributes_and_permissions(self):
        """Verifies zip entries have safe read/write attributes and no dangerous flags."""
        for cs3_path in [STREMIO_CS3, KITSUNEKKO_CS3, AI_TRANSLATOR_CS3]:
            with zipfile.ZipFile(cs3_path, "r") as zf:
                for zinfo in zf.infolist():
                    mode = zinfo.external_attr >> 16
                    self.assertEqual(mode, 0o100666, f"Unexpected permissions on {zinfo.filename} in {cs3_path}")
                    self.assertEqual(zinfo.extract_version, 20, "Extract version should be standard 20 (deflate)")
                    self.assertEqual(zinfo.flag_bits, 0, "No unexpected zip encryption or streaming flags")

    def test_corrupt_header_handling(self):
        """Adversarially tests loading behaviors against malformed zip headers."""
        # 1. Zero-byte archive raises BadZipFile on open
        with tempfile.NamedTemporaryFile(suffix=".cs3", delete=False) as tf:
            tf_path = tf.name
        try:
            with self.assertRaises(zipfile.BadZipFile):
                with zipfile.ZipFile(tf_path, "r") as zf:
                    zf.testzip()
        finally:
            if os.path.exists(tf_path):
                os.remove(tf_path)

        # 2. Corrupted EOCD (End of Central Directory) signature raises BadZipFile on open
        with open(STREMIO_CS3, "rb") as f:
            valid_bytes = f.read()

        corrupt_eocd = bytearray(valid_bytes)
        # Find EOCD signature PK\x05\x06
        eocd_pos = corrupt_eocd.rfind(b"PK\x05\x06")
        self.assertGreater(eocd_pos, 0, "EOCD signature not found")
        corrupt_eocd[eocd_pos : eocd_pos + 4] = b"\x00\x00\x00\x00"

        with tempfile.NamedTemporaryFile(suffix=".cs3", delete=False) as tf:
            tf.write(corrupt_eocd)
            tf_path = tf.name
        try:
            with self.assertRaises(zipfile.BadZipFile):
                with zipfile.ZipFile(tf_path, "r") as zf:
                    zf.infolist()
        finally:
            if os.path.exists(tf_path):
                os.remove(tf_path)

        # 3. Corrupted local file header magic signature raises BadZipFile when reading entry
        corrupt_local = bytearray(valid_bytes)
        corrupt_local[0:4] = b"\x00\x00\x00\x00"

        with tempfile.NamedTemporaryFile(suffix=".cs3", delete=False) as tf:
            tf.write(corrupt_local)
            tf_path = tf.name
        try:
            with zipfile.ZipFile(tf_path, "r") as zf:
                with self.assertRaises(zipfile.BadZipFile):
                    zf.read("classes.dex")
        finally:
            if os.path.exists(tf_path):
                os.remove(tf_path)

        # 4. Truncated archive (cut in half) raises BadZipFile on open
        truncated_bytes = valid_bytes[: len(valid_bytes) // 2]
        with tempfile.NamedTemporaryFile(suffix=".cs3", delete=False) as tf:
            tf.write(truncated_bytes)
            tf_path = tf.name
        try:
            with self.assertRaises((zipfile.BadZipFile, EOFError)):
                with zipfile.ZipFile(tf_path, "r") as zf:
                    zf.testzip()
        finally:
            if os.path.exists(tf_path):
                os.remove(tf_path)

    def test_tamper_detection_checksums(self):
        """Verifies that tampering with even a single byte in .cs3 breaks SHA256 checksum match."""
        for cs3_path, plugins_json_path in [
            (STREMIO_CS3, STREMIO_PLUGINS_JSON),
            (KITSUNEKKO_CS3, KITSUNEKKO_PLUGINS_JSON),
            (AI_TRANSLATOR_CS3, AI_TRANSLATOR_PLUGINS_JSON)
        ]:
            with open(plugins_json_path, "r", encoding="utf-8") as f:
                plugins_data = json.load(f)
            expected_hash = plugins_data[0]["fileHash"] if isinstance(plugins_data, list) else plugins_data["fileHash"]

            # Genuine file matches
            genuine_hash = compute_sha256(cs3_path)
            self.assertEqual(genuine_hash, expected_hash, f"Genuine file hash mismatch for {cs3_path}")

            # Tampered byte
            with open(cs3_path, "rb") as f:
                content = bytearray(f.read())
            # Invert byte at offset 100
            content[100] ^= 0xFF
            tampered_hash = compute_sha256(bytes(content))
            self.assertNotEqual(tampered_hash, expected_hash, f"Tampering failed to invalidate hash for {cs3_path}")


class TestDex035BytecodeInspection(unittest.TestCase):
    """Adversarially disassembles and inspects classes.dex in both packages."""

    def _verify_dex_contract(self, cs3_path, expected_plugin_class):
        with zipfile.ZipFile(cs3_path, "r") as zf:
            dex_bytes = zf.read("classes.dex")
            manifest_bytes = zf.read("manifest.json")

        manifest = json.loads(manifest_bytes.decode("utf-8"))
        self.assertEqual(manifest["pluginClassName"], expected_plugin_class)
        self.assertFalse(manifest.get("requiresResources", True), "requiresResources must be false")

        header, strings, types, classes = parse_dex(dex_bytes)

        # 1. DEX 035 format compliance
        self.assertEqual(header["magic"], b"dex\n035\x00", "DEX version must be 035 (API 21+ Dalvik/ART)")
        self.assertEqual(header["header_size"], 112, "DEX header size must be 112 (0x70)")
        self.assertEqual(header["endian_tag"], 0x12345678, "Endian tag must be LITTLE_ENDIAN (0x12345678)")
        self.assertEqual(header["file_size"], len(dex_bytes), "Header file_size must match actual file size")

        # 2. Checksum validation (Adler32 over all bytes past checksum field)
        computed_checksum = zlib.adler32(dex_bytes[12:]) & 0xffffffff
        self.assertEqual(header["checksum"], computed_checksum, "DEX Adler32 checksum mismatch")

        # 3. Signature validation (SHA-1 over all bytes past signature field)
        computed_sig = hashlib.sha1(dex_bytes[32:]).digest()
        self.assertEqual(header["signature"], computed_sig, "DEX SHA-1 signature mismatch")

        # 4. Symbol resolution: Entrypoint plugin class must exist
        dex_entrypoint_descriptor = "L" + expected_plugin_class.replace(".", "/") + ";"
        class_map = {c[0]: c[1] for c in classes}
        self.assertIn(
            dex_entrypoint_descriptor,
            class_map,
            f"Plugin entrypoint {dex_entrypoint_descriptor} not defined in classes.dex"
        )
        self.assertEqual(
            class_map[dex_entrypoint_descriptor],
            "Lcom/lagradost/cloudstream3/plugins/Plugin;",
            f"Plugin {expected_plugin_class} must inherit from com.lagradost.cloudstream3.plugins.Plugin"
        )

        # 5. External type resolution: Verify all external types belong to allowed standard libraries
        defined_classes = set(class_map.keys())
        allowed_roots = (
            "Ljava/", "Lkotlin/", "Lkotlinx/", "Landroid/", "Landroidx/",
            "Lcom/lagradost/cloudstream3/", "Lokhttp3/", "Lokio/", "Lcom/google/gson/",
            "Lcom/fasterxml/jackson/", "Lcom/lagradost/nicehttp/", "Ldalvik/annotation/"
        )
        external_types = [t for t in types if t.startswith("L") and t not in defined_classes]
        unresolved = [t for t in external_types if not any(t.startswith(r) for r in allowed_roots)]
        self.assertEqual(
            unresolved,
            [],
            f"Unresolved / dangling external symbols found in {cs3_path}: {unresolved}"
        )

        # 6. Verify EnrichedMainAPI is defined and extends MainAPI
        enriched_api_classes = [c for c in classes if "EnrichedMainAPI" in c[0]]
        self.assertGreater(len(enriched_api_classes), 0, "EnrichedMainAPI class must exist in DEX")
        for enriched_c, super_c in enriched_api_classes:
            if not "$" in enriched_c.split("EnrichedMainAPI")[-1]: # Not inner lambda/continuation
                self.assertEqual(
                    super_c,
                    "Lcom/lagradost/cloudstream3/MainAPI;",
                    f"{enriched_c} must extend MainAPI"
                )

    def test_stremio_dex_bytecode_and_symbols(self):
        self._verify_dex_contract(STREMIO_CS3, "com.stremio.subenricher.StremioSubPlugin")

    def test_kitsunekko_dex_bytecode_and_symbols(self):
        self._verify_dex_contract(KITSUNEKKO_CS3, "com.kitsunekko.enricher.KitsunekkoPlugin")

    def test_ai_translator_dex_bytecode_and_symbols(self):
        self._verify_dex_contract(AI_TRANSLATOR_CS3, "com.lagradost.cloudstream3.subtitles.ai.AITranslatorPlugin")


if __name__ == "__main__":
    unittest.main(verbosity=2)
