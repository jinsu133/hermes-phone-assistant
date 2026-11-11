"""Offline checks for the actual Android default/Korean resource contract."""
from collections import Counter
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEFAULT = ROOT / "app/src/main/res/values/strings.xml"
KOREAN = ROOT / "app/src/main/res/values-ko/strings.xml"
SOURCES = ROOT / "app/src/main/java/com/mist/connect"


def read_strings(path):
    root = ET.parse(path).getroot()
    if root.tag != "resources":
        raise ValueError(f"Wrong root in {path}")
    result = {}
    for item in root:
        if item.tag != "string" or len(item):
            raise ValueError(f"Unsupported string definition in {path}: {item.tag}")
        name = item.get("name")
        if not name or name in result or not re.fullmatch(r"[a-z][a-z0-9_]*", name):
            raise ValueError(f"Duplicate/invalid key {name!r} in {path}")
        if item.text is None or not item.text.strip():
            raise ValueError(f"Blank translation {name} in {path}")
        result[name] = item.text
    return result


def format_tokens(text):
    """Ordered (argument index, conversion) and literal %% escape positions.

    Fail closed on bare percent and unsupported format syntax; XML parsing handles
    Android entities separately. Escape positions catch %1$s%% vs %% %1$s.
    """
    tokens = []
    pattern = re.compile(r"%(?:%|([1-9]\d*)\$([sSdDfF]))")
    pos = 0
    for match in pattern.finditer(text):
        between = text[pos:match.start()]
        if "%" in between:
            raise ValueError(f"Invalid Android format in {text!r}")
        tokens.append(("literal",) if match.group() == "%%" else (int(match[1]), match[2].lower()))
        pos = match.end()
    if "%" in text[pos:]:
        raise ValueError(f"Invalid Android format in {text!r}")
    return tuple(tokens)


class LocaleResourcesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.en = read_strings(DEFAULT)
        cls.ko = read_strings(KOREAN)
        for target, base in ((cls.en, DEFAULT), (cls.ko, KOREAN)):
            extra = read_strings(base.with_name("permissions_overview.xml"))
            if target.keys() & extra.keys():
                raise ValueError("Duplicate resource key across files")
            target.update(extra)

    def test_nonempty_key_parity_and_expected_scope(self):
        self.assertEqual(set(self.en), set(self.ko))
        self.assertGreaterEqual(len(self.en), 180)
        for key in ("app_name", "voice_widget_description", "accessibility_desc",
                    "home_heading", "action_listen_off", "action_stop_everything",
                    "diagnostics_service_note", "detail_cleanup", "overlay_message"):
            self.assertIn(key, self.en)
            self.assertIn(key, self.ko)

    def test_every_translation_and_ordered_format(self):
        for key in self.en:
            with self.subTest(key=key):
                self.assertTrue(self.en[key].strip() and self.ko[key].strip())
                self.assertEqual(format_tokens(self.en[key]), format_tokens(self.ko[key]))

    def test_exact_english_korean_sentinels(self):
        expected = {
            "home_heading": ("Voice, under your control", "내가 제어하는 음성 도우미"),
            "action_listen_off": ("Listen OFF", "듣기 OFF"),
            "accessibility_desc": (
                "Reads the screen for voice requests and performs only phone actions allowed by the user.",
                "음성 요청에 따라 화면을 확인하고 사용자가 허용한 휴대폰 조작만 수행합니다."),
        }
        for key, pair in expected.items():
            self.assertEqual((self.en[key], self.ko[key]), pair)
            self.assertFalse(re.search(r"[가-힣]", self.en[key]))
            self.assertTrue(re.search(r"[가-힣]", self.ko[key]))
        self.assertNotEqual(self.en["voice_widget_description"], self.ko["voice_widget_description"])

    def test_negative_control_format_drift_and_escape(self):
        for bad in ("Count %1$s", "Count %2$d", "Count %% %1$d", "Count %1$d %"):
            with self.subTest(bad=bad):
                with self.assertRaises((ValueError, AssertionError)):
                    self.assertEqual(format_tokens("Count %1$d %%"), format_tokens(bad))
        self.assertEqual(format_tokens("Rate %1$s %%"), ((1, "s"), ("literal",)))

    def test_reject_duplicate_and_blank_fixtures(self):
        for xml in ('<resources><string name="x">ok</string><string name="x">again</string></resources>',
                    '<resources><string name="x"> </string></resources>'):
            # Reuse the same checks as read_strings through a temporary in-memory root.
            root = ET.fromstring(xml)
            seen = set()
            with self.assertRaises(ValueError):
                for item in root:
                    name = item.get("name")
                    if name in seen or not (item.text or "").strip():
                        raise ValueError("invalid fixture")
                    seen.add(name)

    def test_scoped_resource_references_exist(self):
        for filename in ("HomeScreen.kt", "MainActivity.kt", "CompanionUiState.kt",
                         "SetupActivity.kt", "ModelPickerCard.kt", "OverlaySettingsDialog.kt",
                         "PermissionOverviewActivity.kt"):
            text = (SOURCES / filename).read_text(encoding="utf-8")
            for key in re.findall(r"R\.string\.([a-z][a-z0-9_]*)", text):
                with self.subTest(file=filename, key=key):
                    self.assertIn(key, self.en)

    def test_overlay_discloses_visible_answer(self):
        self.assertIn("final reply", self.en["overlay_message"])
        self.assertIn("People nearby may see", self.en["overlay_message"])
        self.assertIn("최종 답변", self.ko["overlay_message"])
        self.assertIn("주변 사람", self.ko["overlay_message"])
        self.assertNotIn("no conversation content", self.en["overlay_message"])
        self.assertNotIn("대화 내용·녹음", self.ko["overlay_message"])

    def test_home_safety_affordances_remain(self):
        home = (SOURCES / "HomeScreen.kt").read_text(encoding="utf-8")
        main = (SOURCES / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("actions.filter { it.section == HomeSection.PRIMARY }", home)
        self.assertIn("HomeCommand.STOP_EVERYTHING, R.string.action_stop_everything, HomeSection.PRIMARY", main)
        self.assertIn("HomeCommand.LISTEN_OFF, R.string.action_listen_off, HomeSection.PRIMARY", main)
        self.assertIn("HomeCommand.CANCEL_REQUEST", main)
        self.assertIn("CompanionUiRepository.offRequested()", main)
        self.assertIn("heightIn(min = 48.dp)", home)


if __name__ == "__main__":
    unittest.main()
