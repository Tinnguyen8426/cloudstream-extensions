#!/usr/bin/env python3
"""
Kotlin E2E Test Runner for Multi-Enricher Coexistence.
Compiles stubs, Anime47 sources, and MultiEnricherCoexistenceTest, then executes the test suite.
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

    # 1. Compile Android Log stub
    log_stub = os.path.join(TEST_DIR, "stubs", "android", "util", "Log.java")
    if os.path.exists(log_stub):
        run_command(["javac", "-d", TEST_CLASSES_DIR, log_stub], "Compiling Android Log stub...")

    # 1b. Ensure okio and jackson jars are present
    import urllib.request
    okio_jar = os.path.join(BUILD_CACHE, "okio.jar")
    if not os.path.exists(okio_jar) or os.path.getsize(okio_jar) < 100:
        okio_url = "https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar"
        print(f"[*] Downloading okio.jar from {okio_url}...")
        urllib.request.urlretrieve(okio_url, okio_jar)

    jackson_core_jar = os.path.join(BUILD_CACHE, "jackson-core.jar")
    if not os.path.exists(jackson_core_jar) or os.path.getsize(jackson_core_jar) < 100:
        jcore_url = "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.13.1/jackson-core-2.13.1.jar"
        print(f"[*] Downloading jackson-core.jar from {jcore_url}...")
        urllib.request.urlretrieve(jcore_url, jackson_core_jar)

    jackson_databind_jar = os.path.join(BUILD_CACHE, "jackson-databind.jar")
    if not os.path.exists(jackson_databind_jar) or os.path.getsize(jackson_databind_jar) < 100:
        jdb_url = "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.13.1/jackson-databind-2.13.1.jar"
        print(f"[*] Downloading jackson-databind.jar from {jdb_url}...")
        urllib.request.urlretrieve(jdb_url, jackson_databind_jar)

    jackson_kotlin_jar = os.path.join(BUILD_CACHE, "jackson-module-kotlin.jar")
    if not os.path.exists(jackson_kotlin_jar) or os.path.getsize(jackson_kotlin_jar) < 100:
        jk_url = "https://repo1.maven.org/maven2/com/fasterxml/jackson/module/jackson-module-kotlin/2.13.1/jackson-module-kotlin-2.13.1.jar"
        print(f"[*] Downloading jackson-module-kotlin.jar from {jk_url}...")
        urllib.request.urlretrieve(jk_url, jackson_kotlin_jar)

    # 2. Base jars
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

    # 3. Compile Anime47 sources if not compiled
    anime47_src_dir = os.path.join(ANIME47_DIR, "Anime47Enricher", "src", "main", "kotlin")
    anime47_files = []
    for root, _, files in os.walk(anime47_src_dir):
        for f in files:
            if f.endswith(".kt"):
                anime47_files.append(os.path.join(root, f))
    
    cp_anime47 = os.pathsep.join([TEST_CLASSES_DIR] + base_jars)
    if is_windows:
        cmd = f'"{kotlinc_bin}" -cp "{cp_anime47}" -d "{ANIME47_CLASSES_DIR}" -jvm-target 11 -Xskip-metadata-version-check -no-reflect ' + " ".join(f'"{f}"' for f in anime47_files)
    else:
        cmd = [kotlinc_bin, "-cp", cp_anime47, "-d", ANIME47_CLASSES_DIR, "-jvm-target", "11", "-Xskip-metadata-version-check", "-no-reflect"] + anime47_files
    run_command(cmd, "Compiling Anime47 sources with kotlinc...")

    # 4. Build classpath for MultiEnricherCoexistenceTest
    stremio_classes = os.path.join(STREMIO_CACHE, "classes")
    kitsunekko_classes = os.path.join(KITSUNEKKO_CACHE, "classes")

    full_cp = [
        TEST_CLASSES_DIR,
        ANIME47_CLASSES_DIR,
        stremio_classes,
        kitsunekko_classes
    ] + base_jars

    cp_str = os.pathsep.join(full_cp)
    test_src = os.path.join(TEST_DIR, "src", "MultiEnricherCoexistenceTest.kt")

    print("[*] Compiling MultiEnricherCoexistenceTest with kotlinc...")
    if is_windows:
        cmd = f'"{kotlinc_bin}" -cp "{cp_str}" -d "{TEST_CLASSES_DIR}" -jvm-target 11 -Xskip-metadata-version-check -no-reflect "{test_src}"'
    else:
        cmd = [kotlinc_bin, "-cp", cp_str, "-d", TEST_CLASSES_DIR, "-jvm-target", "11", "-Xskip-metadata-version-check", "-no-reflect", test_src]
    run_command(cmd)

    # 5. Execute JVM test runner
    print("[*] Executing Kotlin MultiEnricherCoexistenceTest on host JVM...")
    run_cp = os.pathsep.join([TEST_CLASSES_DIR, ANIME47_CLASSES_DIR, stremio_classes, kitsunekko_classes] + base_jars)
    java_cmd = ["java", "-Dfile.encoding=UTF-8", "-cp", run_cp, "com.cloudstream.test.e2e.MultiEnricherCoexistenceTest"]
    ret = subprocess.run(java_cmd)
    if ret.returncode != 0:
        print("[!] MultiEnricherCoexistenceTest reported failures!", file=sys.stderr)
        sys.exit(ret.returncode)

    print("\n[+] Kotlin MultiEnricherCoexistenceTest PASSED successfully!")

if __name__ == "__main__":
    main()
