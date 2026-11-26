---
name: local-wiki
description: Search long PDFs, manuals, notes, and local offline wiki / static persona cartridges in Documents/GHOST/wiki/ using on-device Gecko-110M RAG.
---

# Local Wiki & PDF Document Reader

## Instructions

Never guess or hallucinate details from long files or PDFs. Use the on-device Gecko-110M RAG tools so only the most relevant passages enter context:

### 1. Search the Local Wiki / Static Cartridges (`Documents/GHOST/wiki/`)
When the user asks about their local wiki, personal docs, lore sheets, or dropped cartridges:
- Call `execute_command("search_wiki", "<question or keywords>")`
- Cite the returned `[wiki/<file> ...]` passages directly in your answer.

### 2. Search Inside a Specific PDF or Long Text File
1. If you don't know the exact path yet, call `execute_command("search_files", "<filename or pdf>")`.
2. Call `execute_command("search_document", "{\"filePath\":\"<absolute path>\",\"query\":\"<question or keywords>\"}")`.
3. Cite page numbers (`p.X`) or line ranges (`L10-L30`) from the returned hits.

### 3. Read Sequentially / Paginate Through a PDF or File
When the user asks to read or summarize specific pages or lines:
- Call `execute_command("read_file_text", "{\"filePath\":\"<absolute path>\",\"startOffset\":1}")`
- For PDFs, `startOffset` is the 1-indexed page number (returns 3 pages per call). For text files, `startOffset` is the 1-indexed line number (returns 100 lines per call).
