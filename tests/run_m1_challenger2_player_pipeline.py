#!/usr/bin/env python3
"""
Empirical Test Runner for Challenger 2 (Milestone 1).
Verifies: CloudStream Player Ingestion and Subtitle Offset Pipeline.
"""
import os
import sys
import subprocess
import glob

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_DIR = os.path.join(ROOT_DIR, "tests")
BUILD_CACHE = os.path.join(TEST_DIR, ".build_cache")
TEST_CLASSES_DIR = os.path.join(BUILD_CACHE, "challenger2_classes")

KITSUNEKKO_DIR = os.path.join(ROOT_DIR, "KitsunekkoSubtitleEnricher")
KITSUNEKKO_CACHE = os.path.join(KITSUNEKKO_DIR, ".build_cache")
KITSUNEKKO_CLASSES = os.path.join(KITSUNEKKO_CACHE, "classes")

STREMIO_DIR = os.path.join(ROOT_DIR, "StremioSubtitleEnricher")
STREMIO_CACHE = os.path.join(STREMIO_DIR, ".build_cache")

is_windows = (os.name == "nt")
kotlinc_bin = os.path.join(KITSUNEKKO_CACHE, "kotlinc-2.1.0", "bin", "kotlinc.bat" if is_windows else "kotlinc")
if not os.path.exists(kotlinc_bin):
    kotlinc_bin = os.path.join(STREMIO_CACHE, "kotlinc-2.1.0", "bin", "kotlinc.bat" if is_windows else "kotlinc")

def main():
    os.makedirs(TEST_CLASSES_DIR, exist_ok=True)

    # 1. Compile Java stubs
    stub_files = glob.glob(os.path.join(TEST_DIR, "stubs", "**", "*.java"), recursive=True)
    if stub_files:
        print(f"[*] Compiling {len(stub_files)} Java stubs into {TEST_CLASSES_DIR}...")
        javac_cmd = ["javac", "-d", TEST_CLASSES_DIR, "-cp", os.path.join(KITSUNEKKO_CACHE, "android.jar")] + stub_files
        subprocess.run(javac_cmd, check=True)

    # 2. Assemble Classpath
    jars = [
        TEST_CLASSES_DIR,
        KITSUNEKKO_CLASSES,
        os.path.join(KITSUNEKKO_CACHE, "cloudstream.jar"),
        os.path.join(KITSUNEKKO_CACHE, "okhttp.jar"),
        os.path.join(KITSUNEKKO_CACHE, "okio.jar"),
        os.path.join(BUILD_CACHE, "okio.jar"),
        os.path.join(KITSUNEKKO_CACHE, "kotlinx-coroutines.jar"),
        os.path.join(KITSUNEKKO_CACHE, "kotlinx-serialization-core.jar"),
        os.path.join(KITSUNEKKO_CACHE, "kotlinx-serialization-json.jar"),
        os.path.join(KITSUNEKKO_CACHE, "kotlinc-2.1.0", "lib", "kotlin-stdlib.jar"),
        os.path.join(KITSUNEKKO_CACHE, "android.jar")
    ]
    cp_str = os.pathsep.join(jars)
    test_src = os.path.join(TEST_DIR, "src", "KitsunekkoPlayerIngestionChallengerTest.kt")

    print("[*] Compiling KitsunekkoPlayerIngestionChallengerTest with kotlinc...")
    if is_windows:
        cmd = f'"{kotlinc_bin}" -cp "{cp_str}" -d "{TEST_CLASSES_DIR}" -jvm-target 11 -Xskip-metadata-version-check -no-reflect "{test_src}"'
        ret = subprocess.run(cmd, shell=True)
    else:
        cmd = [kotlinc_bin, "-cp", cp_str, "-d", TEST_CLASSES_DIR, "-jvm-target", "11", "-Xskip-metadata-version-check", "-no-reflect", test_src]
        ret = subprocess.run(cmd)

    if ret.returncode != 0:
        print("[!] Kotlin compilation failed!", file=sys.stderr)
        sys.exit(ret.returncode)

    print("[*] Executing KitsunekkoPlayerIngestionChallengerTest on host JVM...")
    run_cp = os.pathsep.join([TEST_CLASSES_DIR, KITSUNEKKO_CLASSES] + jars)
    java_cmd = ["java", "-Dfile.encoding=UTF-8", "-cp", run_cp, "com.cloudstream.test.m1.KitsunekkoPlayerIngestionChallengerTest"]
    ret = subprocess.run(java_cmd)
    if ret.returncode != 0:
        print("[!] Challenger 2 reported test failures!", file=sys.stderr)
        sys.exit(ret.returncode)

    print("\n[+] Challenger 2 Empirical Verification PASSED 100%!")

if __name__ == "__main__":
    main()
