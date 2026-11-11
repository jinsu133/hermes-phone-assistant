"""Static contract for Nous assistant palette; no device or screenshot claims."""
import re
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/mist/connect/ui/theme/Theme.kt'


def luminance(rgb):
    v = [int(rgb[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    v = [x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in v]
    return .2126 * v[0] + .7152 * v[1] + .0722 * v[2]


def ratio(a, b):
    values = sorted((luminance(a), luminance(b)))
    return (values[1] + .05) / (values[0] + .05)


def roles(section):
    return dict(re.findall(r'\b(\w+)\s*=\s*Color\(0xFF([A-Fa-f0-9]{6})\)', section))


class NousThemeContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        text = SOURCE.read_text(encoding='utf8')
        cls.light = roles(text.split('private val LightColorScheme', 1)[1].split('private val DarkColorScheme', 1)[0])
        cls.dark = roles(text.split('private val DarkColorScheme', 1)[1].split('@Composable', 1)[0])

    def test_brand_palette(self):
        self.assertEqual(self.light.get('background'), 'F8EFE3')
        self.assertEqual(self.dark.get('background'), '211F1E')
        self.assertEqual(self.light.get('primary'), '262321')

    def test_text_role_contrast(self):
        for name, palette in [('light', self.light), ('dark', self.dark)]:
            for fg, bg in [('onPrimary','primary'), ('onPrimaryContainer','primaryContainer'),
                           ('onSecondary','secondary'), ('onSecondaryContainer','secondaryContainer'),
                           ('onTertiary','tertiary'), ('onTertiaryContainer','tertiaryContainer'),
                           ('onBackground','background'), ('onSurface','surface'),
                           ('onSurfaceVariant','surfaceVariant'), ('onError','error'),
                           ('onErrorContainer','errorContainer')]:
                with self.subTest(theme=name, fg=fg, bg=bg):
                    self.assertIn(fg, palette); self.assertIn(bg, palette)
                    self.assertGreaterEqual(ratio(palette[fg], palette[bg]), 4.5)

    def test_contrast_negative_control(self):
        self.assertLess(ratio('AAAAAA', 'FFFFFF'), 4.5)


if __name__ == '__main__':
    unittest.main()
