#!/usr/bin/env python3
"""
Challenger 2 Empirical Adversarial Stress Suite for SubtitleReconstructor and Pipeline Invariants.
Phase 1: Core Subtitle Models, Parser, Tag Shielder & Reconstructor.
"""

import re
import unittest


class ReconstructorSimulator:
    """Python oracle matching SubtitleReconstructor.kt specification."""
    TAG_TOKEN_REGEX = re.compile(r"\[\[\s*TAG_(\d+)\s*\]\]", re.IGNORECASE)
    SIMPLE_TAGS = ["i", "b", "u", "s"]
    OPEN_FONT_REGEX = re.compile(r"<font(?:\s+[^>]*?)?>", re.IGNORECASE)
    CLOSE_FONT_REGEX = re.compile(r"</font>", re.IGNORECASE)

    @classmethod
    def format_srt_timestamp(cls, ms: int) -> str:
        clamped = max(0, ms)
        hours = clamped // 3600000
        minutes = (clamped % 3600000) // 60000
        seconds = (clamped % 60000) // 1000
        millis = clamped % 1000
        return f"{hours:02d}:{minutes:02d}:{seconds:02d},{millis:03d}"

    @classmethod
    def balance_html_tags(cls, input_str: str) -> str:
        balanced = input_str
        for tag in cls.SIMPLE_TAGS:
            open_count = len(re.findall(rf"<{tag}(?:\s+[^>]*?)?>", balanced, re.IGNORECASE))
            close_count = len(re.findall(rf"</{tag}>", balanced, re.IGNORECASE))
            if open_count > close_count:
                balanced += f"</{tag}>" * (open_count - close_count)

        open_font = len(cls.OPEN_FONT_REGEX.findall(balanced))
        close_font = len(cls.CLOSE_FONT_REGEX.findall(balanced))
        if open_font > close_font:
            balanced += "</font>" * (open_font - close_font)
        return balanced

    @classmethod
    def unmask_tags(cls, text: str, tag_map: dict) -> str:
        def repl(match):
            raw_tag_id = match.group(1)
            canonical_tag_id = str(int(raw_tag_id)) if raw_tag_id.isdigit() else raw_tag_id
            canonical_key = f"[[TAG_{canonical_tag_id}]]"
            fallback_key = f"[[TAG_{raw_tag_id}]]"
            return tag_map.get(canonical_key, tag_map.get(fallback_key, match.group(0)))

        unmasked = cls.TAG_TOKEN_REGEX.sub(repl, text)
        return cls.balance_html_tags(unmasked)

    @classmethod
    def build_sub_rip(cls, cues: list, translated_texts: dict) -> str:
        if not cues:
            return ""
        sb = []
        for idx, cue in enumerate(cues):
            translation = translated_texts.get(cue["index"])
            trimmed = translation.strip() if translation else None
            content = cls.unmask_tags(trimmed, cue.get("tagMap", {})) if trimmed else cue["rawText"]
            sb.append(str(idx + 1))
            sb.append(f"{cls.format_srt_timestamp(cue['startMs'])} --> {cls.format_srt_timestamp(cue['endMs'])}")
            sb.append(content)
            sb.append("")
        return "\n".join(sb).rstrip() + "\n"


class ParserSimulator:
    """Python oracle matching SubtitleParser.kt specification."""
    BOM_PATTERN = re.compile(r"^(\uFEFF|\uFFFE)")
    TIMING_LINE_REGEX = re.compile(
        r"(?m)^\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,4})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,4})([^\r\n]*)"
    )
    TAG_SHIELD_REGEX = re.compile(
        r"(<\/?[a-zA-Z0-9_.\-]+(?:\s+[^>]*?)?>|\{\\[^}]+\}|<\d{2}:\d{2}(?::\d{2})?[,.]\d{3}>)"
    )
    CUE_ID_REGEX = re.compile(r"^[a-zA-Z0-9_\-]+$")

    @classmethod
    def parse_timestamp_to_ms(cls, h_str, m_str, s_str, ms_str):
        hours = int(h_str) if h_str else 0
        minutes = int(m_str) if m_str else 0
        seconds = int(s_str) if s_str else 0
        ms_padded = ms_str.ljust(3, '0')[:3]
        millis = int(ms_padded) if ms_padded else 0
        return (hours * 3600000) + (minutes * 60000) + (seconds * 1000) + millis

    @classmethod
    def parse(cls, raw_content: str) -> list:
        sanitized = cls.BOM_PATTERN.sub("", raw_content).replace("\r\n", "\n").replace("\r", "\n")
        matches = list(cls.TIMING_LINE_REGEX.finditer(sanitized))
        if not matches:
            return []

        cues = []
        for i, match in enumerate(matches):
            g = match.groups()
            start_ms = cls.parse_timestamp_to_ms(g[0], g[1], g[2], g[3])
            end_ms = cls.parse_timestamp_to_ms(g[4], g[5], g[6], g[7])
            settings = g[8].strip() if g[8] and g[8].strip() else None

            text_start = match.end()
            if i + 1 < len(matches):
                next_start = matches[i + 1].start()
                slice_text = sanitized[text_start:next_start]
                lines = slice_text.split("\n")

                while lines and lines[-1].strip() == "":
                    lines.pop()

                if len(lines) >= 2 and lines[-2].strip() == "":
                    candidate = lines[-1].strip()
                    is_srt_index = candidate.isdigit()
                    is_vtt_cue_id = candidate.startswith("NOTE") or bool(cls.CUE_ID_REGEX.match(candidate))
                    if is_srt_index or is_vtt_cue_id:
                        lines.pop()
                        while lines and lines[-1].strip() == "":
                            lines.pop()

                text_end = text_start + len("\n".join(lines))
            else:
                text_end = len(sanitized)

            dialogue = sanitized[text_start:max(text_start, text_end)].strip()
            if not dialogue:
                continue

            tag_map = {}
            counter = 1
            def repl(m):
                nonlocal counter
                token = f"[[TAG_{counter}]]"
                counter += 1
                tag_map[token] = m.group(0)
                return token

            masked = cls.TAG_SHIELD_REGEX.sub(repl, dialogue)
            cues.append({
                "index": len(cues) + 1,
                "startMs": start_ms,
                "endMs": end_ms,
                "settings": settings,
                "rawText": dialogue,
                "maskedText": masked,
                "tagMap": tag_map
            })
        return cues


class TestTokenCasingAndSpacingVariations(unittest.TestCase):
    def setUp(self):
        self.tag_map = {
            "[[TAG_1]]": "<i>",
            "[[TAG_2]]": "</i>",
            "[[TAG_12]]": "<font color=\"#FFCC00\">",
            "[[TAG_13]]": "</font>"
        }

    def test_lowercase_tokens(self):
        text = "[[tag_1]]Hello[[tag_2]]"
        result = ReconstructorSimulator.unmask_tags(text, self.tag_map)
        self.assertEqual(result, "<i>Hello</i>")

    def test_uppercase_tokens(self):
        text = "[[TAG_1]]World[[TAG_2]]"
        result = ReconstructorSimulator.unmask_tags(text, self.tag_map)
        self.assertEqual(result, "<i>World</i>")

    def test_mixed_case_and_spaced_tokens(self):
        text = "[[ Tag_1 ]]Mixed[[ tAg_2 ]]"
        result = ReconstructorSimulator.unmask_tags(text, self.tag_map)
        self.assertEqual(result, "<i>Mixed</i>")

    def test_multi_digit_multi_spaced_tokens(self):
        text = "Prefix [[  TAG_12  ]]Gold[[   tag_13   ]] Postfix"
        result = ReconstructorSimulator.unmask_tags(text, self.tag_map)
        self.assertEqual(result, "Prefix <font color=\"#FFCC00\">Gold</font> Postfix")

    def test_tab_spaced_tokens(self):
        text = "[[\ttag_1\t]]Tab[[\tTAG_2\t]]"
        result = ReconstructorSimulator.unmask_tags(text, self.tag_map)
        self.assertEqual(result, "<i>Tab</i>")

    def test_non_existent_tokens_preserved(self):
        # Tokens not present in tagMap must be preserved without error
        text = "[[TAG_1]]Hello [[TAG_999]][[TAG_2]] and [[tag_888]]"
        result = ReconstructorSimulator.unmask_tags(text, self.tag_map)
        self.assertEqual(result, "<i>Hello [[TAG_999]]</i> and [[tag_888]]")

    def test_adjacent_and_repeated_tokens(self):
        text = "[[tag_1]][[tag_12]]Dual[[tag_13]][[tag_2]] and [[tag_1]]Again[[TAG_2]]"
        result = ReconstructorSimulator.unmask_tags(text, self.tag_map)
        self.assertEqual(result, "<i><font color=\"#FFCC00\">Dual</font></i> and <i>Again</i>")

    def test_leading_zero_tokens(self):
        text = "[[tag_01]]Leading zero[[TAG_002]] and [[tag_00012]]padded font[[TAG_013]]"
        result = ReconstructorSimulator.unmask_tags(text, self.tag_map)
        self.assertEqual(result, "<i>Leading zero</i> and <font color=\"#FFCC00\">padded font</font>")


class TestSevereUnclosedTagNesting(unittest.TestCase):
    def test_deeply_nested_all_five_tag_types(self):
        text = "<i><b><u><s><font color=\"red\">Deeply nested unclosed"
        balanced = ReconstructorSimulator.balance_html_tags(text)
        self.assertTrue(balanced.endswith("</i></b></u></s></font>"))
        self.assertEqual(len(re.findall(r"<i>", balanced)), len(re.findall(r"</i>", balanced)))
        self.assertEqual(len(re.findall(r"<b>", balanced)), len(re.findall(r"</b>", balanced)))
        self.assertEqual(len(re.findall(r"<u>", balanced)), len(re.findall(r"</u>", balanced)))
        self.assertEqual(len(re.findall(r"<s>", balanced)), len(re.findall(r"</s>", balanced)))
        self.assertEqual(len(re.findall(r"<font[^>]*>", balanced)), len(re.findall(r"</font>", balanced)))

    def test_asymmetric_tag_counts(self):
        text = "<i>First <i>Second <i>Third</i>"
        balanced = ReconstructorSimulator.balance_html_tags(text)
        self.assertEqual(balanced, "<i>First <i>Second <i>Third</i></i></i>")

        font_text = "<font color=\"blue\"><font color=\"green\">Double font"
        balanced_font = ReconstructorSimulator.balance_html_tags(font_text)
        self.assertEqual(balanced_font, "<font color=\"blue\"><font color=\"green\">Double font</font></font>")

    def test_mixed_case_unclosed_tags(self):
        text = "<I><B><U><S><FONT COLOR=\"#AABBCC\">Uppercase unclosed"
        balanced = ReconstructorSimulator.balance_html_tags(text)
        self.assertTrue(balanced.endswith("</i></b></u></s></font>"))

    def test_orphan_close_tags_unmodified(self):
        orphan = "Dialogue</i></b></font>"
        balanced = ReconstructorSimulator.balance_html_tags(orphan)
        self.assertEqual(balanced, orphan)

    def test_tags_with_attributes_balancing(self):
        text = '<i class="highlight"><b style="color:red">Styled unclosed'
        balanced = ReconstructorSimulator.balance_html_tags(text)
        self.assertTrue(balanced.endswith("</i></b>") or ("</b>" in balanced and "</i>" in balanced))
        self.assertEqual(len(re.findall(r"<i(?:\s+[^>]*?)?>", balanced, re.IGNORECASE)), len(re.findall(r"</i>", balanced, re.IGNORECASE)))
        self.assertEqual(len(re.findall(r"<b(?:\s+[^>]*?)?>", balanced, re.IGNORECASE)), len(re.findall(r"</b>", balanced, re.IGNORECASE)))

    def test_non_target_tags_invariance(self):
        cases = [
            '<input type="text" value="abc">',
            '<button class="btn">Click</button>',
            '<ul><li>Item</li></ul>',
            '<span>Span text</span>'
        ]
        for c in cases:
            self.assertEqual(ReconstructorSimulator.balance_html_tags(c), c)


class TestPreBalancedTagsInvariance(unittest.TestCase):
    def test_pre_balanced_tags_remain_identical(self):
        test_cases = [
            "<i>Italic</i> <b>Bold</b> <font color=\"blue\">Color</font>",
            "<i><b><u><s><font color=\"cyan\">All five pre-balanced</font></s></u></b></i>",
            "Plain text with no tags at all 12345.",
            "",
            "Already balanced: <font size=\"3\" color=\"red\">Red text</font> and <b>bold text</b>."
        ]
        for tc in test_cases:
            result = ReconstructorSimulator.balance_html_tags(tc)
            self.assertEqual(result, tc, f"Failed for input: {tc}")


class TestSubRipGenerationAndFallback(unittest.TestCase):
    def setUp(self):
        self.cues = [
            {"index": 1, "startMs": 1000, "endMs": 2500, "rawText": "Original cue 1", "tagMap": {}},
            {"index": 2, "startMs": 3000, "endMs": 4500, "rawText": "Original cue 2", "tagMap": {"[[TAG_1]]": "<b>", "[[TAG_2]]": "</b>"}},
            {"index": 3, "startMs": 5000, "endMs": 6000, "rawText": "Agent 47 Fallback", "tagMap": {}},
            {"index": 4, "startMs": 7000, "endMs": 8000, "rawText": "Room 101 Empty Fallback", "tagMap": {}},
            {"index": 5, "startMs": 9000, "endMs": 10000, "rawText": "Whitespace Fallback", "tagMap": {}},
            {"index": 6, "startMs": 11000, "endMs": 12000, "rawText": "Unclosed Tag", "tagMap": {"[[TAG_1]]": "<font color=\"red\">"}}
        ]

    def test_dialogue_fallback_and_formatting(self):
        translations = {
            1: "Translated cue 1",
            2: "Dịch cue 2 với [[tag_1]]in đậm[[tag_2]]",
            # 3 missing -> fallback to "Agent 47 Fallback"
            4: "", # Empty string -> fallback to "Room 101 Empty Fallback"
            5: "   \n\t  ", # Whitespace -> fallback to "Whitespace Fallback"
            6: "[[tag_1]]Cảnh báo" # Unclosed -> balanced
        }

        srt = ReconstructorSimulator.build_sub_rip(self.cues, translations)
        self.assertIn("1\n00:00:01,000 --> 00:00:02,500\nTranslated cue 1\n", srt)
        self.assertIn("2\n00:00:03,000 --> 00:00:04,500\nDịch cue 2 với <b>in đậm</b>\n", srt)
        self.assertIn("3\n00:00:05,000 --> 00:00:06,000\nAgent 47 Fallback\n", srt)
        self.assertIn("4\n00:00:07,000 --> 00:00:08,000\nRoom 101 Empty Fallback\n", srt)
        self.assertIn("5\n00:00:09,000 --> 00:00:10,000\nWhitespace Fallback\n", srt)
        self.assertIn("6\n00:00:11,000 --> 00:00:12,000\n<font color=\"red\">Cảnh báo</font>\n", srt)

    def test_empty_cues_list_returns_empty_string(self):
        self.assertEqual(ReconstructorSimulator.build_sub_rip([], {}), "")


class TestTimestampFormattingBoundaries(unittest.TestCase):
    def test_timestamp_boundaries(self):
        self.assertEqual(ReconstructorSimulator.format_srt_timestamp(-1000), "00:00:00,000")
        self.assertEqual(ReconstructorSimulator.format_srt_timestamp(0), "00:00:00,000")
        self.assertEqual(ReconstructorSimulator.format_srt_timestamp(5), "00:00:00,005")
        self.assertEqual(ReconstructorSimulator.format_srt_timestamp(45), "00:00:00,045")
        self.assertEqual(ReconstructorSimulator.format_srt_timestamp(999), "00:00:00,999")
        self.assertEqual(ReconstructorSimulator.format_srt_timestamp(1000), "00:00:01,000")
        self.assertEqual(ReconstructorSimulator.format_srt_timestamp(60000), "00:01:00,000")
        self.assertEqual(ReconstructorSimulator.format_srt_timestamp(3600000), "01:00:00,000")
        self.assertEqual(ReconstructorSimulator.format_srt_timestamp(86400000), "24:00:00,000")
        self.assertEqual(ReconstructorSimulator.format_srt_timestamp(360000000), "100:00:00,000")


class TestEndToEndRoundTripWithReconstructedSubRip(unittest.TestCase):
    def test_round_trip_parse_and_reconstruct(self):
        raw_srt = (
            "1\n00:00:01,000 --> 00:00:03,000\n{\\an8}<i>Agent</i>\n47\n\n"
            "2\n00:00:04,500 --> 00:00:06,000\nMeet at <font color=\"yellow\">Room 101\n\n"
            "3\n00:00:07,000 --> 00:00:09,000\n47\n"
        )
        cues = ParserSimulator.parse(raw_srt)
        self.assertEqual(len(cues), 3)
        self.assertEqual(cues[0]["rawText"], "{\\an8}<i>Agent</i>\n47")
        self.assertEqual(cues[1]["rawText"], "Meet at <font color=\"yellow\">Room 101")
        self.assertEqual(cues[2]["rawText"], "47")

        translations = {
            1: "[[tag_1]][[tag_2]]Điệp viên[[tag_3]]\n47",
            2: "Gặp tại [[  TAG_1  ]]Phòng 101",
            3: ""  # Empty -> fallback
        }

        srt_output = ReconstructorSimulator.build_sub_rip(cues, translations)
        round_trip_cues = ParserSimulator.parse(srt_output)
        self.assertEqual(len(round_trip_cues), 3)
        self.assertEqual(round_trip_cues[0]["startMs"], 1000)
        self.assertEqual(round_trip_cues[0]["endMs"], 3000)
        self.assertEqual(round_trip_cues[0]["rawText"], "{\\an8}<i>Điệp viên</i>\n47")
        self.assertEqual(round_trip_cues[1]["rawText"], "Gặp tại <font color=\"yellow\">Phòng 101</font>")
        self.assertEqual(round_trip_cues[2]["rawText"], "47")


if __name__ == "__main__":
    unittest.main(verbosity=2)
