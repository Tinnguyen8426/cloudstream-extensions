#!/usr/bin/env python3
import os
import sys
import subprocess

def run():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    kbin = os.path.join(root, "StremioSubtitleEnricher", ".build_cache", "kotlinc-2.1.0", "bin", "kotlinc.bat")
    
    cp_jars = [
        os.path.join(root, "StremioSubtitleEnricher", ".build_cache", "android.jar"),
        os.path.join(root, "tests", ".build_cache", "jackson-core.jar"),
        os.path.join(root, "tests", ".build_cache", "jackson-databind.jar"),
        os.path.join(root, "tests", ".build_cache", "jackson-module-kotlin.jar"),
        os.path.join(root, "StremioSubtitleEnricher", ".build_cache", "jackson-annotations.jar"),
        os.path.join(root, "StremioSubtitleEnricher", ".build_cache", "kotlinc-2.1.0", "lib", "kotlin-stdlib.jar"),
        os.path.join(root, "StremioSubtitleEnricher", ".build_cache", "kotlinc-2.1.0", "lib", "kotlin-reflect.jar"),
    ]
    cp_str = ";".join(cp_jars)
    out_dir = os.path.join(root, "tests", "build", "m6_1_cache_stress")
    os.makedirs(out_dir, exist_ok=True)
    
    src_files = [
        os.path.join(root, "AiSubtitleTranslator", "AiTranslator", "src", "main", "kotlin", "com", "lagradost", "cloudstream3", "subtitles", "ai", "cache", "SubtitleCacheManager.kt"),
        os.path.join(root, "tests", "src", "Phase2CacheAdversarialStressTest.kt"),
    ]
    src_str = " ".join(f'"{f}"' for f in src_files)
    
    print("[*] Compiling SubtitleCacheManager & Phase2CacheAdversarialStressTest with kotlinc...")
    cmd = f'"{kbin}" -cp "{cp_str}" -d "{out_dir}" -jvm-target 11 -Xskip-metadata-version-check {src_str}'
    res = subprocess.run(cmd, shell=True)
    if res.returncode != 0:
        print(f"[!] Compilation failed with code {res.returncode}")
        sys.exit(res.returncode)
    print("[+] Compilation succeeded.")
    
    print("[*] Executing tests.adversarial.Phase2CacheAdversarialStressTest on JVM...")
    run_cp = ";".join([out_dir] + cp_jars)
    java_cmd = f'java -Dfile.encoding=UTF-8 -cp "{run_cp}" tests.adversarial.Phase2CacheAdversarialStressTest'
    res_run = subprocess.run(java_cmd, shell=True)
    if res_run.returncode != 0:
        print(f"[!] Phase 2 cache adversarial stress tests failed with code {res_run.returncode}")
        sys.exit(res_run.returncode)
    print("\n[+] Challenger M6-1 Stress Tests PASSED 100% cleanly.")

if __name__ == "__main__":
    run()
