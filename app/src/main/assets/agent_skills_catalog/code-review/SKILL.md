---
name: Code Review
description: Review a change for correctness, regressions, maintainability, and missing tests.
version: 1.0.0
license: Apache-2.0
invocation: both
allowed-tools: [search_code, read_file, read_file_lines, file_line_count, run_command]
---

# Code review

Review the actual diff and surrounding code. Prioritize concrete correctness defects, crashes, data loss, unsafe lifecycle behavior, missing migrations, and untested edge cases. Cite file locations in findings. Distinguish verified defects from questions and avoid style-only noise unless it creates real maintenance risk.
