# Legacy Office fixtures

Binary sample files for `LegacyOfficeReaderTest`. They are data only: tests read them as bytes and never execute them.

Source: Apache POI test data, <https://github.com/apache/poi/tree/trunk/test-data> (`document/` and `spreadsheet/`),
fetched 2026-11-16. License: Apache License 2.0 (<https://www.apache.org/licenses/LICENSE-2.0>), copyright The Apache
Software Foundation.

| File | Upstream path | Use |
| --- | --- | --- |
| `Bug28627.doc` | `document/Bug28627.doc` | plain Word 97 text with a piece table |
| `HeaderFooterUnicode.doc` | `document/HeaderFooterUnicode.doc` | typographic / euro characters, tabs |
| `SampleSS.xls` | `spreadsheet/SampleSS.xls` | three sheets, shared strings, numbers, formula result |
| `SimpleMultiCell.xls` | `spreadsheet/SimpleMultiCell.xls` | numeric cells (RK/NUMBER), empty sheets |
| `xor-encryption-abc.xls` | `spreadsheet/xor-encryption-abc.xls` | XOR-obfuscated workbook (FILEPASS), must be refused |
