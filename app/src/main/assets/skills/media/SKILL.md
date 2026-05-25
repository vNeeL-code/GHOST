---
name: media
description: Control media playback (PLAY, PAUSE, NEXT, PREV).
---

# Control media playback (PLAY, PAUSE, NEXT, PREV).

## Instructions

Call the un_intent tool with the following exact parameters:

- intent: media
- parameters: A JSON string with the following fields:
  - action: The action to perform ('PLAY', 'PAUSE', 'NEXT', 'PREV'). String.
