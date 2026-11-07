#!/usr/bin/env python3
import os
import sys
import subprocess

def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.environ["JAVA_TOOL_OPTIONS"] = "-Dfile.encoding=UTF-8"
    kbin = os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'kotlinc-2.1.0', 'bin', 'kotlinc.bat')

    cp_jars = [
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'android.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'cloudstream.jar'),
        os.path.join(root, 'tests', '.build_cache', 'okio.jar'),
        os.path.join(root, 'tests', '.build_cache', 'jackson-core.jar'),
        os.path.join(root, 'tests', '.build_cache', 'jackson-databind.jar'),
        os.path.join(root, 'tests', '.build_cache', 'jackson-module-kotlin.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'jackson-annotations.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'okhttp.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'nicehttp.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'kotlinx-coroutines.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'kotlinx-serialization-core.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'kotlinx-serialization-json.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'kotlinc-2.1.0', 'lib', 'kotlin-stdlib.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'kotlinc-2.1.0', 'lib', 'kotlin-reflect.jar'),
    ]

    out_dir = os.path.join(root, 'tests', 'build', 'p4_adversarial_verify')
    os.makedirs(out_dir, exist_ok=True)

    ai_src_dir = os.path.join(root, 'AiSubtitleTranslator', 'AiTranslator', 'src', 'main', 'kotlin')
    src_files = []
    for r, _, fs in os.walk(ai_src_dir):
        for f in fs:
            if f.endswith('.kt'):
                src_files.append(os.path.join(r, f))

    adv_test = os.path.join(root, 'tests', 'src', 'Phase4AdversarialStressTest.kt')
    if os.path.exists(adv_test):
        src_files.append(adv_test)

    print(f"[*] Compiling {len(src_files)} Kotlin source files for Phase 4 Adversarial Stress...")
    cp_str = ';'.join(cp_jars)
    src_str = ' '.join(f'"{f}"' for f in src_files)

    cmd = f'"{kbin}" -cp "{cp_str}" -d "{out_dir}" -jvm-target 11 -Xskip-metadata-version-check {src_str}'
    res = subprocess.run(cmd, shell=True)
    if res.returncode != 0:
        print(f"[!] Compilation failed with code {res.returncode}")
        sys.exit(res.returncode)

    print(f"[+] kotlinc compilation SUCCEEDED with code 0! Classes emitted to {out_dir}")

    # Execute empirical test suite on JVM
    print("[*] Executing tests.adversarial.Phase4AdversarialStressTestKt on JVM...")
    run_cp = ';'.join([out_dir] + cp_jars)
    java_cmd = f'java -Dfile.encoding=UTF-8 -cp "{run_cp}" tests.adversarial.Phase4AdversarialStressTestKt'
    res_run = subprocess.run(java_cmd, shell=True)
    if res_run.returncode != 0:
        print(f"[!] Phase 4 adversarial stress tests failed with code {res_run.returncode}")
        sys.exit(res_run.returncode)
    print("[+] Phase 4 adversarial stress tests PASSED cleanly.")

if __name__ == "__main__":
    main()
