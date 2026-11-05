#!/usr/bin/env python3
"""
Test runner for Stremio Subtitle Enricher unit test suite.
"""
import os
import sys
import subprocess

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
CACHE_DIR = os.path.join(PROJECT_DIR, ".build_cache")
TEST_SRC = os.path.join(PROJECT_DIR, "StremioEnricher", "src", "test", "kotlin", "com", "stremio", "subenricher", "StremioVerificationTest.kt")
CLASSES_DIR = os.path.join(CACHE_DIR, "classes")
TEST_CLASSES_DIR = os.path.join(CACHE_DIR, "test_classes")

is_windows = (os.name == "nt")
kotlinc_bin = os.path.join(CACHE_DIR, "kotlinc-2.1.0", "bin", "kotlinc.bat" if is_windows else "kotlinc")

def main():
    os.makedirs(TEST_CLASSES_DIR, exist_ok=True)

    # 1. Compile Android Log stub for host JVM testing
    log_stub = os.path.join(PROJECT_DIR, "StremioEnricher", "src", "test", "java", "android", "util", "Log.java")
    if os.path.exists(log_stub):
        subprocess.run(["javac", "-d", TEST_CLASSES_DIR, log_stub], check=True)

    # Build classpath
    jars = [
        TEST_CLASSES_DIR,
        os.path.join(CACHE_DIR, "cloudstream.jar"),
        os.path.join(CACHE_DIR, "gson.jar"),
        os.path.join(CACHE_DIR, "jackson-annotations.jar"),
        os.path.join(CACHE_DIR, "okhttp.jar"),
        os.path.join(CACHE_DIR, "kotlinx-coroutines.jar"),
        os.path.join(CACHE_DIR, "kotlinx-serialization-core.jar"),
        os.path.join(CACHE_DIR, "kotlinx-serialization-json.jar"),
        os.path.join(CACHE_DIR, "kotlinc-2.1.0", "lib", "kotlin-stdlib.jar"),
        CLASSES_DIR,
        os.path.join(CACHE_DIR, "android.jar")
    ]
    cp_str = os.pathsep.join(jars)

    print("[*] Compiling test suite...")
    if is_windows:
        cmd_str = f'"{kotlinc_bin}" -cp "{cp_str}" -d "{TEST_CLASSES_DIR}" -jvm-target 11 -Xskip-metadata-version-check -no-reflect "{TEST_SRC}"'
        ret = subprocess.run(cmd_str, shell=True)
    else:
        cmd = [kotlinc_bin, "-cp", cp_str, "-d", TEST_CLASSES_DIR, "-jvm-target", "11", "-Xskip-metadata-version-check", "-no-reflect", TEST_SRC]
        ret = subprocess.run(cmd)

    if ret.returncode != 0:
        print("[!] Test compilation failed!", file=sys.stderr)
        sys.exit(1)

    print("[+] Test compilation succeeded. Executing tests...")
    run_cp = os.pathsep.join([TEST_CLASSES_DIR, CLASSES_DIR] + jars)
    java_cmd = ["java", "-cp", run_cp, "com.stremio.subenricher.StremioVerificationTest"]
    ret = subprocess.run(java_cmd)
    if ret.returncode != 0:
        print("[!] Some tests failed!", file=sys.stderr)
        sys.exit(1)
    print("\n[+] All tests passed successfully!")

if __name__ == "__main__":
    main()
