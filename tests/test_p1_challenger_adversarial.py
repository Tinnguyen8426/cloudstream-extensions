#!/usr/bin/env python3
"""
Adversarial Stress Test Suite for AI Subtitle Translator Phase 1.
Challenger: challenger_p1_1

Empirically tests parser edge cases:
1. WebVTT without cue identifiers containing single-token dialogue ("Yes", "No", "Hello", "Wait", "Stop", "Run").
2. WebVTT containing dialogue starting with "NOTE" (e.g. "NOTE: Be careful").
3. Unnumbered SRT containing single numeric dialogue ("47", "101").
4. Extreme timecodes (00:00:00,000, 99:59:59,999, missing hours 00:05.100, variable milliseconds).
5. Tag shielding and case-insensitive/whitespace-tolerant unmasking.
"""

import unittest
from tests.test_m5_adversarial_empirical import (
    RemediatedSubtitleParser,
    RemediatedSubtitleReconstructor
)

class TestAdversarialParserFlaws(unittest.TestCase):
    def test_vtt_without_cue_ids_single_word_dialogue(self):
        """
        Adversarial Test 1: WebVTT without cue IDs containing single-word dialogue lines.
        Real-world WebVTT subtitles standardly omit cue identifiers.
        Common dialogues: 'Yes', 'No', 'Wait', 'Done'.
        """
        vtt = (
            "WEBVTT\n\n"
            "00:00:01.000 --> 00:00:02.000\n"
            "Yes\n\n"
            "00:00:03.000 --> 00:00:04.000\n"
            "No\n\n"
            "00:00:05.000 --> 00:00:06.000\n"
            "Wait\n\n"
            "00:00:07.000 --> 00:00:08.000\n"
            "Done\n"
        )
        cues = RemediatedSubtitleParser.parse(vtt)
        self.assertEqual(len(cues), 4, f"Expected 4 cues, but parser returned {len(cues)} cues!")
        self.assertEqual(cues[0]["rawText"], "Yes")
        self.assertEqual(cues[1]["rawText"], "No")
        self.assertEqual(cues[2]["rawText"], "Wait")
        self.assertEqual(cues[3]["rawText"], "Done")

    def test_vtt_dialogue_starting_with_note(self):
        """
        Adversarial Test 2: Dialogue line starting with 'NOTE'.
        In WebVTT, comments start with 'NOTE', but dialogue can also start with 'NOTE: ...'.
        """
        vtt = (
            "WEBVTT\n\n"
            "00:00:01.000 --> 00:00:02.000\n"
            "NOTE: Please be quiet\n\n"
            "00:00:03.000 --> 00:00:04.000\n"
            "Thank you\n"
        )
        cues = RemediatedSubtitleParser.parse(vtt)
        self.assertEqual(len(cues), 2, f"Expected 2 cues, but parser returned {len(cues)} cues!")
        self.assertEqual(cues[0]["rawText"], "NOTE: Please be quiet")
        self.assertEqual(cues[1]["rawText"], "Thank you")

    def test_unnumbered_srt_single_number_dialogue(self):
        """
        Adversarial Test 3: Unnumbered SRT where dialogue is a number like '47'.
        """
        srt = (
            "00:00:01,000 --> 00:00:02,000\n"
            "47\n\n"
            "00:00:03,000 --> 00:00:04,000\n"
            "Next cue\n"
        )
        cues = RemediatedSubtitleParser.parse(srt)
        self.assertEqual(len(cues), 2, f"Expected 2 cues, but parser returned {len(cues)} cues!")
        self.assertEqual(cues[0]["rawText"], "47")
        self.assertEqual(cues[1]["rawText"], "Next cue")

    def test_timecode_robustness(self):
        """
        Adversarial Test 4: Extreme timecodes and millisecond representations.
        """
        doc = (
            "1\n00:00:00,000 --> 00:00:01,000\nStart\n\n"
            "2\n99:59:59,999 --> 99:59:59,999\nEnd of century\n\n"
            "3\n00:05.100 --> 00:06.20\nMissing hours and 2-digit ms\n\n"
            "4\n00:00:01.1234 --> 00:00:02.5\n4-digit ms and 1-digit ms\n"
        )
        cues = RemediatedSubtitleParser.parse(doc)
        self.assertEqual(len(cues), 4)
        self.assertEqual(cues[0]["startMs"], 0)
        self.assertEqual(cues[1]["startMs"], (99 * 3600 + 59 * 60 + 59) * 1000 + 999)
        self.assertEqual(cues[2]["startMs"], 5100)
        self.assertEqual(cues[2]["endMs"], 6200)
        self.assertEqual(cues[3]["startMs"], 1123)
        self.assertEqual(cues[3]["endMs"], 2500)

    def test_tag_shielding_and_unmasking_variations(self):
        """
        Adversarial Test 5: Complex tags, token distortion, unclosed font tags.
        """
        dialogue = "{\\an8\\pos(100,200)}<v Roger>Hello <font color=\"#ff0000\">World</font> <00:19.500>♪"
        srt = f"1\n00:00:01,000 --> 00:00:04,000\n{dialogue}\n"
        cues = RemediatedSubtitleParser.parse(srt)
        self.assertEqual(len(cues), 1)
        cue = cues[0]
        # Distort tokens as an LLM might
        translated = "[[tag_1]][[tag_2]]Xin chào [[  TAG_3   ]]Thế giới[[tag_4]] [[TAG_5]]♪"
        unmasked = RemediatedSubtitleReconstructor.unmask_tags(translated, cue["tagMap"])
        self.assertIn("{\\an8\\pos(100,200)}", unmasked)
        self.assertIn("<v Roger>", unmasked)
        self.assertIn("<font color=\"#ff0000\">", unmasked)
        self.assertIn("</font>", unmasked)
        self.assertIn("<00:19.500>", unmasked)

if __name__ == "__main__":
    unittest.main()
