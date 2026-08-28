---
name: Release Workflow
description: Run a cautious versioned release workflow with artifact verification.
version: 1.0.0
license: Apache-2.0
invocation: both
allowed-tools: [read_file, search_code, run_command, check_command, wait_command]
---

# Release workflow

Read the repository release instructions first. Confirm tests and compilation before changing versions. Use the project’s paired version convention, direct signed build commands, and configured memory limits. Verify packaged version metadata, artifact timestamp and size, then stop build daemons and confirm no build JVM remains.
