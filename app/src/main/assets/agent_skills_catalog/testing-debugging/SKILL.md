---
name: Testing and Debugging
description: Reproduce failures, isolate causes, and verify fixes with focused tests.
version: 1.0.0
license: Apache-2.0
invocation: both
allowed-tools: [search_code, read_file, run_command, check_command, wait_command, command_list]
---

# Testing and debugging

Turn the reported symptom into the smallest reproducible case. Gather evidence before editing, form a falsifiable cause, and run the narrowest useful test. Preserve failing output, fix the root cause, rerun the focused test, then run proportionate regression checks. Do not mask failures with retries or broad exception handling.
