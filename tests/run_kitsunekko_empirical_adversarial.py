#!/usr/bin/env python3
"""
Test runner for KitsunekkoEmpiricalAdversarialHarness on host JVM.
Agent: m4_challenger_2
"""
import os
import sys
import subprocess

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_DIR = os.path.join(ROOT_DIR, "tests")
BUILD_CACHE = os.path.join(TEST_DIR, ".build_cache")
TEST_CLASSES_DIR = os.path.join(BUILD_CACHE, "test_classes")

STREMIO_DIR = os.path.join(ROOT_DIR, "StremioSubtitleEnricher")
KITSUNEKKO_DIR = os.path.join(ROOT_DIR, "KitsunekkoSubtitleEnricher")
STREMIO_CACHE = os.path.join(STREMIO_DIR, ".build_cache")
KITSUNEKKO_CACHE = os.path.join(KITSUNEKKO_DIR, ".build_cache")

is_windows = (os.name == "nt")
kotlinc_bin = os.path.join(STREMIO_CACHE, "kotlinc-2.1.0", "bin", "kotlinc.bat" if is_windows else "kotlinc")

def main():
    os.makedirs(TEST_CLASSES_DIR, exist_ok=True)

    base_jars = [
        os.path.join(STREMIO_CACHE, "cloudstream.jar"),
        os.path.join(BUILD_CACHE, "okio.jar"),
        os.path.join(BUILD_CACHE, "jackson-core.jar"),
        os.path.join(BUILD_CACHE, "jackson-databind.jar"),
        os.path.join(BUILD_CACHE, "jackson-module-kotlin.jar"),
        os.path.join(STREMIO_CACHE, "nicehttp.jar"),
        os.path.join(STREMIO_CACHE, "gson.jar"),
        os.path.join(STREMIO_CACHE, "jackson-annotations.jar"),
        os.path.join(STREMIO_CACHE, "okhttp.jar"),
        os.path.join(STREMIO_CACHE, "kotlinx-coroutines.jar"),
        os.path.join(STREMIO_CACHE, "kotlinx-serialization-core.jar"),
        os.path.join(STREMIO_CACHE, "kotlinx-serialization-json.jar"),
        os.path.join(STREMIO_CACHE, "kotlinc-2.1.0", "lib", "kotlin-stdlib.jar"),
        os.path.join(STREMIO_CACHE, "kotlinc-2.1.0", "lib", "kotlin-reflect.jar"),
        os.path.join(STREMIO_CACHE, "android.jar")
    ]

    kitsunekko_classes = os.path.join(KITSUNEKKO_CACHE, "classes")
    full_cp = [TEST_CLASSES_DIR, kitsunekko_classes] + base_jars
    cp_str = os.pathsep.join(full_cp)
    test_src = os.path.join(TEST_DIR, "src", "KitsunekkoEmpiricalAdversarialHarness.kt")

    print("[*] Compiling KitsunekkoEmpiricalAdversarialHarness with kotlinc...")
    if is_windows:
        cmd = f'"{kotlinc_bin}" -cp "{cp_str}" -d "{TEST_CLASSES_DIR}" -jvm-target 11 -Xskip-metadata-version-check -no-reflect "{test_src}"'
        ret = subprocess.run(cmd, shell=True)
    else:
        cmd = [kotlinc_bin, "-cp", cp_str, "-d", TEST_CLASSES_DIR, "-jvm-target", "11", "-Xskip-metadata-version-check", "-no-reflect", test_src]
        ret = subprocess.run(cmd)

    if ret.returncode != 0:
        print("[!] Compilation failed!", file=sys.stderr)
        sys.exit(ret.returncode)

    print("[*] Executing KitsunekkoEmpiricalAdversarialHarness on host JVM...")
    run_cp = os.pathsep.join([TEST_CLASSES_DIR, kitsunekko_classes] + base_jars)
    java_cmd = ["java", "-Dfile.encoding=UTF-8", "-cp", run_cp, "com.cloudstream.test.e2e.KitsunekkoEmpiricalAdversarialHarness"]
    ret = subprocess.run(java_cmd)
    if ret.returncode != 0:
        print("[!] KitsunekkoEmpiricalAdversarialHarness reported failures!", file=sys.stderr)
        sys.exit(ret.returncode)

    print("\n[+] KitsunekkoEmpiricalAdversarialHarness PASSED successfully!")

if __name__ == "__main__":
    main()
