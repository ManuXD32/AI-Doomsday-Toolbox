import sys
import tempfile
import unittest
from pathlib import Path


MAIN_PYTHON = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(MAIN_PYTHON))

from adt_local_runner import (  # noqa: E402
    MAX_RETURNED_OUTPUT_CHARS,
    _BoundedCapture,
    run_script,
)


class LocalRunnerOutputTest(unittest.TestCase):
    def test_tail_capture_is_bounded_and_preserves_recent_output(self):
        capture = _BoundedCapture(limit=32)
        capture.write("prefix")
        capture.write("x" * 100)

        self.assertTrue(capture.truncated)
        self.assertLessEqual(len(capture.getvalue()), 32)
        self.assertTrue(capture.getvalue().endswith("x" * 32))

    def test_run_script_bounds_combined_stdout_and_stderr(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entrypoint = root / "main.py"
            entrypoint.write_text(
                "import sys\n"
                "print('o' * 50000)\n"
                "print('e' * 50000, file=sys.stderr)\n",
                encoding="utf-8",
            )

            output = run_script(str(root), str(entrypoint))

        self.assertLessEqual(len(output), MAX_RETURNED_OUTPUT_CHARS)
        self.assertIn("e" * 128, output)


if __name__ == "__main__":
    unittest.main()
