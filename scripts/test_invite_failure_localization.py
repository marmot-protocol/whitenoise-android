"""Regression coverage for the Portuguese invitation repair guidance."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


class InviteFailureLocalizationTest(unittest.TestCase):
    def test_portuguese_invalid_key_guidance_uses_contracted_demonstrative(self):
        resources = Path(__file__).resolve().parents[1] / "app/src/main/res"
        # Parse only this repository's checked-in Android resource, never external XML.
        root = ET.parse(resources / "values-pt/strings.xml").getroot()
        node = root.find("string[@name='error_invalid_key_package']")
        self.assertIsNotNone(node)
        assert node is not None
        message = node.text
        self.assertEqual(
            "Não foi possível validar a chave de conversa publicada desta pessoa. "
            "Pede-lhe para atualizar o White Noise e publicar uma nova chave de conversa e tenta novamente.",
            message,
        )


if __name__ == "__main__":
    unittest.main()
