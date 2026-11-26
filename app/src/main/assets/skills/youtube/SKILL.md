---
name: youtube
description: Search and play videos, channels, or tutorials on YouTube, and control media playback.
---

# YouTube Control

## Instructions

### 1. Search or Play a Video on YouTube
Call `execute_command` with:
- `command`: `"play_media"`
- `args`: JSON string:
```json
{"query": "<video title or creator>", "service": "youtube"}
```

### 2. Transport Controls (Play / Pause / Next / Previous)
Use `execute_command("music", "action=play|pause|next|previous")`.
