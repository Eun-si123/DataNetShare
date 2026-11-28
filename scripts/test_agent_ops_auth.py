import importlib.util
import io
import os
from pathlib import Path
import subprocess
import unittest
import urllib.error
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("agent_ops", Path(__file__).with_name("agent-ops.py"))
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


class AuthTests(unittest.TestCase):
    def test_env_precedes_cli(self):
        with patch.dict(os.environ, {"GITHUB_TOKEN": "explicit", "GH_TOKEN": "second"}, clear=True), patch.object(ops.subprocess, "run") as cli:
            self.assertEqual(ops.read_token(), "explicit")
            cli.assert_not_called()

    def test_cli_token(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(ops.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "synthetic\n", "")):
            self.assertEqual(ops.read_token(), "synthetic")

    def test_missing_cli(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(ops.subprocess, "run", side_effect=FileNotFoundError()):
            self.assertIsNone(ops.read_token())

    def test_anonymous_read(self):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = "[]"
        with patch.object(ops, "read_token", return_value=None), patch.object(ops.urllib.request, "urlopen", return_value=response) as fetch:
            self.assertEqual(ops.api("repos/example/repo/pulls"), [])
            self.assertIsNone(fetch.call_args.args[0].get_header("Authorization"))

    def test_failed_reads_fail_closed(self):
        for token in (None, "synthetic"):
            for status in (401, 403, 404, 429, 500):
                with patch.object(ops, "read_token", return_value=token), patch.object(ops.urllib.request, "urlopen", side_effect=urllib.error.HTTPError("https://api.github.com", status, "failed", {}, io.BytesIO())) as fetch:
                    with self.assertRaisesRegex(RuntimeError, "no task may be claimed"):
                        ops.api("repos/example/repo/pulls")
                    self.assertEqual(fetch.call_count, 1)


if __name__ == "__main__":
    unittest.main()
