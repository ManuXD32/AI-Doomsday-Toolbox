---
name: Repository Exploration
description: Map an unfamiliar repository efficiently before making changes.
version: 1.0.0
license: Apache-2.0
invocation: both
allowed-tools: [list_directory, search_code, read_file, read_file_lines, file_line_count]
---

# Repository exploration

Start from the project root and identify build files, entry points, tests, documentation, and local instruction files. Search for concepts before opening large files. Read only the relevant ranges, then summarize concrete architecture, constraints, and likely change points. Do not edit during exploration.
