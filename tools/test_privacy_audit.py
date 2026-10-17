"""Regression tests for disclosure-safe source audit output."""
import contextlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("privacy_audit", Path(__file__).with_name("privacy_audit.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class PrivacyAuditTest(unittest.TestCase):
    def test_comment_and_example_do_not_hide_private_path(self):
        with tempfile.TemporaryDirectory() as directory:
            private_path = "C:" + "/Users/" + "private_fixture/work"
            Path(directory, "Example.kt").write_text(
                "// example.com " + private_path, encoding="utf-8",
            )
            findings = audit.scan(directory)
            self.assertTrue(any(item[2] == "Windows 用户目录" for item in findings))

    def test_output_redacts_path_and_token(self):
        with tempfile.TemporaryDirectory() as directory:
            token = "sk-" + "aB1x" * 8
            private_path = "C:" + "/Users/" + "private_fixture/work"
            Path(directory, "Example.kt").write_text(
                "// " + private_path + "\nval apiKey = \"" + token + "\"", encoding="utf-8",
            )
            output = io.StringIO()
            with patch.object(sys, "argv", ["privacy_audit", "--root", directory]):
                with contextlib.redirect_stdout(output):
                    self.assertEqual(audit.main(), 1)
            text = output.getvalue()
            self.assertNotIn(token, text)
            self.assertNotIn("private_fixture", text)
            self.assertNotIn(directory, text)
            self.assertIn("Example.kt", text)

    def test_anchored_ignore_does_not_hide_nested_config(self):
        patterns = ["/local.properties", "keystore.properties", "browser/browser-api/build/"]
        self.assertTrue(audit.is_gitignored("local.properties", patterns))
        self.assertFalse(audit.is_gitignored("nested/local.properties", patterns))
        self.assertTrue(audit.is_gitignored("nested/keystore.properties", patterns))
        self.assertTrue(audit.is_gitignored("browser/browser-api/build/generated/x", patterns))

    def test_fixture_exception_does_not_hide_another_value_on_the_line(self):
        with tempfile.TemporaryDirectory() as directory:
            tests = Path(directory, "src/test")
            tests.mkdir(parents=True)
            token = "sk-" + "zQ9b" * 8
            Path(tests, "Fixture.kt").write_text(
                'val url = "https://user:pass@' + 'example.test/" // ' + token, encoding="utf-8",
            )
            findings = audit.scan(directory)
            self.assertTrue(any(item[2] == "API 令牌样式" for item in findings))
            self.assertFalse(any(item[2] == "邮箱地址" for item in findings))

    def test_license_word_exception_does_not_hide_api_token(self):
        with tempfile.TemporaryDirectory() as directory:
            licenses = Path(directory, "licenses")
            licenses.mkdir()
            token = "sk-" + "rT5v" * 8
            Path(licenses, "LICENSE.txt").write_text("MERCHAN" "TABILITY " + token, encoding="utf-8")
            findings = audit.scan(directory)
            self.assertFalse(any(item[2] == "设备序列号样式" for item in findings))
            self.assertTrue(any(item[2] == "API 令牌样式" for item in findings))

    def test_cjs_and_underscore_prefixed_token_are_scanned(self):
        with tempfile.TemporaryDirectory() as directory:
            token = "sk-" + "aB9r" * 8
            Path(directory, "entry.cjs").write_text("// TOKEN_" + token, encoding="utf-8")
            self.assertTrue(any(item[2] == "API 令牌样式" for item in audit.scan(directory)))

    def test_private_key_header_and_additional_secret_file_are_detected(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "Example.kt").write_text(
                "// -----BEGIN PRIVATE " + "KEY-----", encoding="utf-8",
            )
            Path(directory, "credentials.json").write_text("{}", encoding="utf-8")
            self.assertTrue(any(item[2] == "私钥头" for item in audit.scan(directory)))
            self.assertIn("credentials.json", audit.find_secret_files(directory))

    def test_strict_mode_flags_preserved_local_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, ".gitignore").write_text("/local.properties\n", encoding="utf-8")
            Path(directory, "local.properties").write_text("sdk.dir=example\n", encoding="utf-8")
            for strict, expected in [(False, 0), (True, 1)]:
                args = ["privacy_audit", "--root", directory] + (["--strict"] if strict else [])
                with patch.object(sys, "argv", args), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(audit.main(), expected)


if __name__ == "__main__":
    unittest.main()
