"""Static regression guard for password-bearing Android view state.

This checks resource attributes only; actual Bundle/OEM behavior needs a device.
"""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ANDROID = "{http://schemas.android.com/apk/res/android}"
LAYOUT = (Path(__file__).resolve().parent.parent /
          "android/app/src/main/res/layout/activity_main.xml")


class SensitiveViewStateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = ET.parse(LAYOUT).getroot()
        cls.views = {
            node.attrib[ANDROID + "id"].split("/")[-1]: node
            for node in root.iter()
            if ANDROID + "id" in node.attrib
        }

    def test_selectable_password_displays_do_not_enter_saved_view_state(self):
        # These TextViews show a sender SOCKS/Wi-Fi secret or imported QR
        # Wi-Fi password. They are repopulated from in-memory state on rotation.
        for view_id in ("credentialsText", "scannedWifiText"):
            with self.subTest(view=view_id):
                view = self.views[view_id]
                self.assertEqual("TextView", view.tag)
                self.assertEqual("true", view.get(ANDROID + "textIsSelectable"))
                self.assertEqual("false", view.get(ANDROID + "saveEnabled"))
                self.assertNotEqual("true", view.get(ANDROID + "freezesText"))

    def test_secret_input_fields_still_disable_automatic_state_save(self):
        for view_id in ("receiverPassword", "customProxyPassword",
                        "p2pCustomPassword"):
            with self.subTest(view=view_id):
                view = self.views[view_id]
                self.assertEqual("EditText", view.tag)
                self.assertEqual("false", view.get(ANDROID + "saveEnabled"))
                self.assertEqual("no", view.get(ANDROID + "importantForAutofill"))


if __name__ == "__main__":
    unittest.main()
