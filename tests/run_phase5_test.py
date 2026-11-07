#!/usr/bin/env python3
"""
Phase 5 Test Runner: CloudStream Settings UI & SharedPreferences Persistence.

1. Compiles all Kotlin source files for AiSubtitleTranslator and tests/src/Phase5SettingsTest.kt with kotlinc.
2. Disassembles and scans compiled bytecode for AITranslatorSettingsDialog to empirically verify
   ABSOLUTELY ZERO XML layout inflation calls (no LayoutInflater.inflate, no R.layout, no setContentView).
3. Executes tests.adversarial.Phase5SettingsTestKt on the JVM to verify 100% save/restore fidelity of all 12 keys.
"""

import os
import sys
import subprocess
import re

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

    out_dir = os.path.join(root, 'tests', 'build', 'p5_verify')
    os.makedirs(out_dir, exist_ok=True)

    ai_src_dir = os.path.join(root, 'AiSubtitleTranslator', 'AiTranslator', 'src', 'main', 'kotlin')
    src_files = []
    for r, _, fs in os.walk(ai_src_dir):
        for f in fs:
            if f.endswith('.kt'):
                src_files.append(os.path.join(r, f))

    p5_test = os.path.join(root, 'tests', 'src', 'Phase5SettingsTest.kt')
    if os.path.exists(p5_test):
        src_files.append(p5_test)

    print(f"[*] Found {len(src_files)} Kotlin source files to compile for Phase 5.")
    for f in src_files:
        print(f"    - {os.path.relpath(f, root)}")

    cp_str = ';'.join(cp_jars)
    src_str = ' '.join(f'"{f}"' for f in src_files)

    cmd = f'"{kbin}" -cp "{cp_str}" -d "{out_dir}" -jvm-target 11 -Xskip-metadata-version-check {src_str}'
    print("[*] Running kotlinc compilation for Phase 5...")
    res = subprocess.run(cmd, shell=True)
    if res.returncode != 0:
        print(f"[!] Compilation failed with code {res.returncode}")
        sys.exit(res.returncode)

    print(f"[+] kotlinc compilation SUCCEEDED with code 0! Classes emitted to {out_dir}")

    # =========================================================================
    # Step 2: Bytecode Inspection for Zero XML Layout Inflation Calls
    # =========================================================================
    print("\n[*] Disassembling bytecode to verify 0 XML layout inflation calls...")
    ui_classes = []
    for r, _, fs in os.walk(out_dir):
        for f in fs:
            if f.endswith('.class') and 'AITranslatorSettingsDialog' in f:
                ui_classes.append(os.path.join(r, f))

    if not ui_classes:
        print("[!] No AITranslatorSettingsDialog class files found for bytecode audit!")
        sys.exit(1)

    prohibited_patterns = [
        re.compile(r'LayoutInflater', re.IGNORECASE),
        re.compile(r'\binflate\s*\(', re.IGNORECASE),
        re.compile(r'R\$layout', re.IGNORECASE),
        re.compile(r'R\.layout', re.IGNORECASE),
        re.compile(r'setContentView', re.IGNORECASE),
    ]

    violations = []
    for cls_path in ui_classes:
        cls_name = os.path.relpath(cls_path, out_dir).replace(os.sep, '.')[:-6]
        # Run javap disassembly
        javap_cmd = f'javap -c -p -cp "{out_dir}" {cls_name}'
        javap_res = subprocess.run(javap_cmd, shell=True, capture_output=True, text=True)
        disassembly = javap_res.stdout

        for pattern in prohibited_patterns:
            matches = pattern.findall(disassembly)
            if matches:
                violations.append((cls_name, pattern.pattern, matches))

    if violations:
        print(f"[!] INTEGRITY VIOLATION: Found XML inflation calls in bytecode!")
        for cls_name, pat, matches in violations:
            print(f"    - In {cls_name}: pattern '{pat}' matched {len(matches)} times: {matches}")
        sys.exit(1)

    print(f"[+] BYTECODE AUDIT PASSED: 0 XML layout inflation calls detected across {len(ui_classes)} UI class files!")
    print("    - No LayoutInflater.inflate calls")
    print("    - No R.layout references")
    print("    - No setContentView calls")
    print("    - 100% pure Kotlin programmatic View construction")

    # =========================================================================
    # Step 3: Execute Phase 5 Unit Tests on JVM
    # =========================================================================
    print("\n[*] Executing tests.adversarial.Phase5SettingsTestKt on JVM...")
    run_cp = ';'.join([out_dir] + cp_jars)
    java_cmd = f'java -Dfile.encoding=UTF-8 -cp "{run_cp}" tests.adversarial.Phase5SettingsTestKt'
    res_run = subprocess.run(java_cmd, shell=True)
    if res_run.returncode != 0:
        print(f"[!] Phase 5 settings tests failed with code {res_run.returncode}")
        sys.exit(res_run.returncode)

    print("[+] Phase 5 settings tests PASSED cleanly.")

if __name__ == "__main__":
    main()
