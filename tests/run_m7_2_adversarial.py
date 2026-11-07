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
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'kotlinc-2.1.0', 'lib', 'kotlin-stdlib.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'kotlinc-2.1.0', 'lib', 'kotlin-reflect.jar'),
        os.path.join(root, 'tests', 'build', 'p3_verify'),
    ]

    out_dir = os.path.join(root, 'tests', 'build', 'm7_2_adversarial')
    os.makedirs(out_dir, exist_ok=True)

    test_src = os.path.join(root, 'tests', 'src', 'Phase3ReviewerM72AdversarialTest.kt')
    cp_str = ';'.join(cp_jars)

    cmd = f'"{kbin}" -cp "{cp_str}" -d "{out_dir}" -jvm-target 11 -Xskip-metadata-version-check "{test_src}"'
    print("[*] Running kotlinc compilation of Reviewer M7-2 Adversarial test...")
    res = subprocess.run(cmd, shell=True)
    if res.returncode != 0:
        print(f"[!] Compilation failed with code {res.returncode}")
        sys.exit(res.returncode)

    print(f"[+] kotlinc compilation SUCCEEDED with code 0! Classes emitted to {out_dir}")

    # Execute test suite on JVM
    print("[*] Executing tests.adversarial.Phase3ReviewerM72AdversarialTestKt on JVM...")
    run_cp = ';'.join([out_dir] + cp_jars)
    java_cmd = f'java -Dfile.encoding=UTF-8 -cp "{run_cp}" tests.adversarial.Phase3ReviewerM72AdversarialTestKt'
    res_run = subprocess.run(java_cmd, shell=True)
    if res_run.returncode != 0:
        print(f"[!] M7-2 adversarial tests failed with code {res_run.returncode}")
        sys.exit(res_run.returncode)
    print("[+] M7-2 adversarial tests PASSED cleanly.")

if __name__ == "__main__":
    main()
