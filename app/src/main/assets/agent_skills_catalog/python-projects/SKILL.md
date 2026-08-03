---
name: Python Projects
description: Implement and validate maintainable Python projects in the local sandbox.
version: 1.0.0
license: Apache-2.0
invocation: both
allowed-tools: [list_directory, read_file, write_file, edit_lines, run_project, check_project_run]
---

# Python projects

Prefer a small explicit module structure, standard-library solutions, clear errors, and deterministic tests. For local sandbox projects maintain `.adt/run.json` with the correct Python entry point. Run the project through the provided runner, inspect its bounded logs, and keep dependencies compatible with the sandbox.
