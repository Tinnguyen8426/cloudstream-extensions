#!/usr/bin/env python3
"""
Test runner for Kitsunekko Subtitle Enricher Adversarial Challenge test suite.
Compiles host Android stubs and Kotlin challenge sources, then executes on host JVM.
"""
import os
import sys
import subprocess

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
CACHE_DIR = os.path.join(PROJECT_DIR, ".build_cache")
TEST_SRC = os.path.join(PROJECT_DIR, "KitsunekkoEnricher", "src", "test", "kotlin", "com", "kitsunekko", "enricher", "KitsunekkoAdversarialChallengeTest.kt")
CLASSES_DIR = os.path.join(CACHE_DIR, "classes")
TEST_CLASSES_DIR = os.path.join(CACHE_DIR, "test_classes")

is_windows = (os.name == "nt")
kotlinc_bin = os.path.join(CACHE_DIR, "kotlinc-2.1.0", "bin", "kotlinc.bat" if is_windows else "kotlinc")

def main():
    os.makedirs(TEST_CLASSES_DIR, exist_ok=True)

    # 1. Compile Android Log stub for host JVM testing
    log_stub = os.path.join(PROJECT_DIR, "KitsunekkoEnricher", "src", "test", "java", "android", "util", "Log.java")
    if os.path.exists(log_stub):
        print("[*] Compiling Android Log stub...")
        subprocess.run(["javac", "-d", TEST_CLASSES_DIR, log_stub], check=True)

    # 2. Build classpath
    jars = [
        TEST_CLASSES_DIR,
        CLASSES_DIR,
        os.path.join(CACHE_DIR, "cloudstream.jar"),
        os.path.join(CACHE_DIR, "gson.jar"),
        os.path.join(CACHE_DIR, "jackson-annotations.jar"),
        os.path.join(CACHE_DIR, "okhttp.jar"),
        os.path.join(CACHE_DIR, "kotlinx-coroutines.jar"),
        os.path.join(CACHE_DIR, "kotlinx-serialization-core.jar"),
        os.path.join(CACHE_DIR, "kotlinx-serialization-json.jar"),
        os.path.join(CACHE_DIR, "nicehttp.jar"),
        os.path.join(CACHE_DIR, "kotlinc-2.1.0", "lib", "kotlin-stdlib.jar"),
        os.path.join(CACHE_DIR, "android.jar")
    ]
    cp_str = os.pathsep.join(jars)

    print("[*] Compiling adversarial challenge test suite with kotlinc...")
    if is_windows:
        cmd_str = f'"{kotlinc_bin}" -cp "{cp_str}" -d "{TEST_CLASSES_DIR}" -jvm-target 11 -Xskip-metadata-version-check -no-reflect "{TEST_SRC}"'
        ret = subprocess.run(cmd_str, shell=True)
    else:
        cmd = [kotlinc_bin, "-cp", cp_str, "-d", TEST_CLASSES_DIR, "-jvm-target", "11", "-Xskip-metadata-version-check", "-no-reflect", TEST_SRC]
        ret = subprocess.run(cmd)

    if ret.returncode != 0:
        print("[!] Challenge test compilation failed!", file=sys.stderr)
        sys.exit(1)

    print("[+] Challenge test compilation succeeded. Executing challenge harness...")
    run_cp = os.pathsep.join([TEST_CLASSES_DIR, CLASSES_DIR] + jars)
    java_cmd = ["java", "-Dfile.encoding=UTF-8", "-cp", run_cp, "com.kitsunekko.enricher.KitsunekkoAdversarialChallengeTest"]
    ret = subprocess.run(java_cmd)
    if ret.returncode != 0:
        print("[!] Adversarial challenge tests failed!", file=sys.stderr)
        sys.exit(1)
    print("\n[+] All adversarial challenge tests passed successfully!")

if __name__ == "__main__":
    main()
