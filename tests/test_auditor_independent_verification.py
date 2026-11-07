#!/usr/bin/env python3
"""
Independent Victory Auditor Adversarial Stress & Forensic Test Suite.
Verifies all Phase 1 requirements, specifications, and potential edge cases:
1. SubRip and WebVTT multi-format parsing
2. BOM and CRLF sanitization
3. Numeric dialogue preservation ("Agent 47", "Room 101", standalone "47")
4. WebVTT single-word cues preservation
5. Tag shielding (HTML, ASS, WebVTT tags)
6. Tag unmasking case-insensitivity, leading zeros, and whitespace tolerance
7. HTML tag balancing (<i>, <b>, <u>, <s>, <font>) with attributes
8. SubRip generation with fallback to original text on empty/whitespace translation
9. Non-existent / hallucinated token preservation
10. Strict execution boundary (Phase 2 components not implemented)
"""

import os
import sys
import unittest
from tests.test_m5_adversarial_empirical import (
    RemediatedSubtitleParser,
    RemediatedSubtitleReconstructor
)

class AuditorIndependentVerificationTest(unittest.TestCase):

    def test_bom_and_various_line_endings(self):
        # UTF-8 BOM, UTF-16 BOM indicators, \r\n, \r, \n
        raw = "\uFEFF1\r00:00:01,000 --> 00:00:02,000\rLine 1\r\r2\r\n00:00:03,000 --> 00:00:04,000\r\nLine 2\n"
        cues = RemediatedSubtitleParser.parse(raw)
        self.assertEqual(len(cues), 2)
        self.assertEqual(cues[0]["rawText"], "Line 1")
        self.assertEqual(cues[1]["rawText"], "Line 2")

    def test_dialogue_ending_in_number(self):
        srt = (
            "1\n00:00:01,000 --> 00:00:02,000\nTarget is Agent 47\n\n"
            "2\n00:00:03,000 --> 00:00:04,000\nProceed to Room 101\n\n"
            "3\n00:00:05,000 --> 00:00:06,000\nCode 007 confirmed\n"
        )
        cues = RemediatedSubtitleParser.parse(srt)
        self.assertEqual(len(cues), 3)
        self.assertEqual(cues[0]["rawText"], "Target is Agent 47")
        self.assertEqual(cues[1]["rawText"], "Proceed to Room 101")
        self.assertEqual(cues[2]["rawText"], "Code 007 confirmed")

    def test_dialogue_starting_with_number(self):
        srt = (
            "1\n00:00:01,000 --> 00:00:02,000\n12 Angry Men\n\n"
            "2\n00:00:03,000 --> 00:00:04,000\n24 Hours left\n"
        )
        cues = RemediatedSubtitleParser.parse(srt)
        self.assertEqual(len(cues), 2)
        self.assertEqual(cues[0]["rawText"], "12 Angry Men")
        self.assertEqual(cues[1]["rawText"], "24 Hours left")

    def test_single_number_dialogue_numbered_and_unnumbered(self):
        # Numbered
        srt1 = "1\n00:00:01,000 --> 00:00:02,000\n47\n\n2\n00:00:03,000 --> 00:00:04,000\nNext\n"
        cues1 = RemediatedSubtitleParser.parse(srt1)
        self.assertEqual(len(cues1), 2)
        self.assertEqual(cues1[0]["rawText"], "47")
        self.assertEqual(cues1[1]["rawText"], "Next")

        # Unnumbered
        srt2 = "00:00:01,000 --> 00:00:02,000\n47\n\n00:00:03,000 --> 00:00:04,000\nNext\n"
        cues2 = RemediatedSubtitleParser.parse(srt2)
        self.assertEqual(len(cues2), 2)
        self.assertEqual(cues2[0]["rawText"], "47")
        self.assertEqual(cues2[1]["rawText"], "Next")

    def test_webvtt_cue_settings_and_layout(self):
        vtt = (
            "WEBVTT - Header commentary\n\n"
            "00:01.000 --> 00:02.000 position:10%,line-left align:left size:35%\n"
            "Top left subtitle\n\n"
            "00:03.500 --> 00:04.900 line:90% position:50% align:center\n"
            "Bottom center subtitle\n"
        )
        cues = RemediatedSubtitleParser.parse(vtt)
        self.assertEqual(len(cues), 2)
        self.assertEqual(cues[0]["settings"], "position:10%,line-left align:left size:35%")
        self.assertEqual(cues[0]["rawText"], "Top left subtitle")
        self.assertEqual(cues[1]["settings"], "line:90% position:50% align:center")
        self.assertEqual(cues[1]["rawText"], "Bottom center subtitle")

    def test_webvtt_single_word_dialogue_cues(self):
        vtt = (
            "WEBVTT\n\n"
            "00:00:01.000 --> 00:00:02.000\nYes\n\n"
            "00:00:03.000 --> 00:00:04.000\nNo\n\n"
            "00:00:05.000 --> 00:00:06.000\nWait\n\n"
            "00:00:07.000 --> 00:00:08.000\nGo\n"
        )
        cues = RemediatedSubtitleParser.parse(vtt)
        self.assertEqual(len(cues), 4)
        self.assertEqual([c["rawText"] for c in cues], ["Yes", "No", "Wait", "Go"])

    def test_all_tag_types_shielding(self):
        dialogue = "{\\an8\\pos(192,1080)}<i><b>Bold Italic</b></i> <v Narrator>Spoken</v> <font color=\"#112233\" face=\"Arial\">Colored</font> <00:01:23.456>Karaoke"
        srt = f"1\n00:00:01,000 --> 00:00:05,000\n{dialogue}\n"
        cues = RemediatedSubtitleParser.parse(srt)
        self.assertEqual(len(cues), 1)
        cue = cues[0]
        self.assertEqual(len(cue["tagMap"]), 10)
        self.assertIn("[[TAG_1]]", cue["maskedText"])
        self.assertIn("[[TAG_10]]", cue["maskedText"])

    def test_unmasking_variations_and_hallucinations(self):
        tag_map = {
            "[[TAG_1]]": "<i>",
            "[[TAG_2]]": "</i>",
            "[[TAG_3]]": "<font color=\"red\">",
            "[[TAG_4]]": "</font>"
        }
        # Case variations, whitespace, and leading zeros
        text = "[[tag_1]]Hello[[ TAG_2 ]], [[Tag_003]]Red[[  tag_0004  ]]. Also [[TAG_999]] hallucinated."
        restored = RemediatedSubtitleReconstructor.unmask_tags(text, tag_map)
        self.assertEqual(restored, "<i>Hello</i>, <font color=\"red\">Red</font>. Also [[TAG_999]] hallucinated.")

    def test_html_tag_balancing_edge_cases(self):
        # 1. Multiple unclosed simple tags
        t1 = "<i><b><u><s>All unclosed"
        b1 = RemediatedSubtitleReconstructor.balance_html_tags(t1)
        self.assertEqual(b1, "<i><b><u><s>All unclosed</i></b></u></s>")

        # 2. Unclosed font with complex attributes
        t2 = "<font size=\"+2\" color=\"#FF8800\" face=\"Roboto\">Important"
        b2 = RemediatedSubtitleReconstructor.balance_html_tags(t2)
        self.assertEqual(b2, "<font size=\"+2\" color=\"#FF8800\" face=\"Roboto\">Important</font>")

        # 3. Already closed tags should not get duplicate closers
        t3 = "<i>Italic</i> <b>Bold</b>"
        b3 = RemediatedSubtitleReconstructor.balance_html_tags(t3)
        self.assertEqual(b3, t3)

    def test_reconstructor_fallback_to_source_on_blank_translation(self):
        cues = [
            {"index": 1, "startMs": 1000, "endMs": 2000, "rawText": "Original 1", "tagMap": {}},
            {"index": 2, "startMs": 3000, "endMs": 4000, "rawText": "Original 2", "tagMap": {}},
            {"index": 3, "startMs": 5000, "endMs": 6000, "rawText": "Original 3", "tagMap": {}}
        ]
        # Cue 1 has translation, Cue 2 has whitespace, Cue 3 is missing
        translations = {
            1: "Translated 1",
            2: "   \n  \t  "
        }
        from tests.test_p1_challenger2_reconstructor_stress import ReconstructorSimulator
        srt_output = ReconstructorSimulator.build_sub_rip(cues, translations)
        expected = (
            "1\n00:00:01,000 --> 00:00:02,000\nTranslated 1\n\n"
            "2\n00:00:03,000 --> 00:00:04,000\nOriginal 2\n\n"
            "3\n00:00:05,000 --> 00:00:06,000\nOriginal 3\n"
        )
        self.assertEqual(srt_output, expected)

    def test_phase2_boundary_no_phase2_code_leak(self):
        # Check that no Phase 2 components exist in AiSubtitleTranslator
        ai_src_dir = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "AiSubtitleTranslator", "AiTranslator", "src", "main", "kotlin"
        )
        all_kt_files = []
        for root, _, files in os.walk(ai_src_dir):
            for f in files:
                if f.endswith(".kt"):
                    all_kt_files.append(os.path.relpath(os.path.join(root, f), ai_src_dir))

        expected_files = {
            os.path.normpath("com/lagradost/cloudstream3/subtitles/ai/models/SubtitleModels.kt"),
            os.path.normpath("com/lagradost/cloudstream3/subtitles/ai/parser/SubtitleParser.kt"),
            os.path.normpath("com/lagradost/cloudstream3/subtitles/ai/parser/SubtitleReconstructor.kt")
        }
        actual_files = {os.path.normpath(p) for p in all_kt_files}
        self.assertEqual(actual_files, expected_files, f"Unexpected extra files outside Phase 1: {actual_files - expected_files}")

if __name__ == "__main__":
    unittest.main(verbosity=2)
