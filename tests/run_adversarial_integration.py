#!/usr/bin/env python3
"""
Compiler and Runner for AdversarialIntegrationTest on Host JVM.
Compiles stubs, Anime47, Stremio, Kitsunekko, and executes AdversarialIntegrationTest.
"""
import os
import sys
import subprocess
import shutil

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_DIR = os.path.join(ROOT_DIR, "tests")
BUILD_CACHE = os.path.join(TEST_DIR, ".build_cache")
TEST_CLASSES_DIR = os.path.join(BUILD_CACHE, "test_classes")
ANIME47_CLASSES_DIR = os.path.join(BUILD_CACHE, "anime47_classes")

STREMIO_DIR = os.path.join(ROOT_DIR, "StremioSubtitleEnricher")
KITSUNEKKO_DIR = os.path.join(ROOT_DIR, "KitsunekkoSubtitleEnricher")
ANIME47_DIR = os.path.join(ROOT_DIR, "Anime47SubtitleEnricher")

STREMIO_CACHE = os.path.join(STREMIO_DIR, ".build_cache")
KITSUNEKKO_CACHE = os.path.join(KITSUNEKKO_DIR, ".build_cache")

is_windows = (os.name == "nt")
kotlinc_bin = os.path.join(STREMIO_CACHE, "kotlinc-2.1.0", "bin", "kotlinc.bat" if is_windows else "kotlinc")

def run_command(cmd, desc="", check=True):
    if desc:
        print(f"[*] {desc}")
    if isinstance(cmd, str):
        ret = subprocess.run(cmd, shell=True)
    else:
        ret = subprocess.run(cmd)
    if check and ret.returncode != 0:
        print(f"[!] Command failed with exit code {ret.returncode}", file=sys.stderr)
        sys.exit(ret.returncode)
    return ret

def main():
    os.makedirs(TEST_CLASSES_DIR, exist_ok=True)
    os.makedirs(ANIME47_CLASSES_DIR, exist_ok=True)

    # 1. Compile Android Log stub if needed
    log_stub = os.path.join(TEST_DIR, "stubs", "android", "util", "Log.java")
    if os.path.exists(log_stub):
        run_command(["javac", "-d", TEST_CLASSES_DIR, log_stub], "Compiling Android Log stub...")

    # 2. Base jars
    okio_jar = os.path.join(BUILD_CACHE, "okio.jar")
    jackson_core_jar = os.path.join(BUILD_CACHE, "jackson-core.jar")
    jackson_databind_jar = os.path.join(BUILD_CACHE, "jackson-databind.jar")
    jackson_kotlin_jar = os.path.join(BUILD_CACHE, "jackson-module-kotlin.jar")

    base_jars = [
        os.path.join(STREMIO_CACHE, "cloudstream.jar"),
        okio_jar,
        jackson_core_jar,
        jackson_databind_jar,
        jackson_kotlin_jar,
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

    stremio_classes = os.path.join(STREMIO_CACHE, "classes")
    kitsunekko_classes = os.path.join(KITSUNEKKO_CACHE, "classes")

    full_cp = [
        TEST_CLASSES_DIR,
        ANIME47_CLASSES_DIR,
        stremio_classes,
        kitsunekko_classes
    ] + base_jars

    cp_str = os.pathsep.join(full_cp)
    test_src = os.path.join(TEST_DIR, "src", "AdversarialIntegrationTest.kt")

    print("[*] Compiling AdversarialIntegrationTest with kotlinc...")
    if is_windows:
        cmd = f'"{kotlinc_bin}" -cp "{cp_str}" -d "{TEST_CLASSES_DIR}" -jvm-target 11 -Xskip-metadata-version-check -no-reflect "{test_src}"'
    else:
        cmd = [kotlinc_bin, "-cp", cp_str, "-d", TEST_CLASSES_DIR, "-jvm-target", "11", "-Xskip-metadata-version-check", "-no-reflect", test_src]
    run_command(cmd)

    # 3. Execute JVM test runner
    print("[*] Executing Kotlin AdversarialIntegrationTest on host JVM...")
    run_cp = os.pathsep.join([TEST_CLASSES_DIR, ANIME47_CLASSES_DIR, stremio_classes, kitsunekko_classes] + base_jars)
    java_cmd = ["java", "-Dfile.encoding=UTF-8", "-cp", run_cp, "com.cloudstream.test.e2e.AdversarialIntegrationTest"]
    ret = subprocess.run(java_cmd)
    if ret.returncode != 0:
        print("[!] AdversarialIntegrationTest reported failures!", file=sys.stderr)
        sys.exit(ret.returncode)

    print("\n[+] Kotlin AdversarialIntegrationTest PASSED successfully!")

if __name__ == "__main__":
    main()
