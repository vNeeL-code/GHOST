---
name: qr-code
description: Generates a scannable QR code image for any URL or text string.
---

# Instructions

You MUST use the `run_js` tool with the following exact parameters:

- skillName: "qr-code"
- scriptName: "index.html"
- data: A JSON string with the following fields:
  - url: String - the URL or text to create a QR code for (e.g. `{"url":"https://github.com/vNeeL-code/GHOST"}`)
