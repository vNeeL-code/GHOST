---
name: youtube
description: Search and open videos, channels, or tutorials on YouTube, and control media playback.
---

# YouTube Control

## Instructions

### 1. Search Videos on YouTube
Call `run_intent` with:
- `intent`: `"android.intent.action.VIEW"`
- `parameters`: JSON string:
```json
{
  "data": "https://www.youtube.com/results?search_query=<URL-encoded search query>",
  "component_package": "com.google.android.youtube"
}
```

### 2. Play Directly From Search
Call `run_intent` with:
- `intent`: `"android.media.action.MEDIA_PLAY_FROM_SEARCH"`
- `parameters`: JSON string:
```json
{
  "component_package": "com.google.android.youtube",
  "extras": {
    "query": "<video title or creator>"
  }
}
```

### 3. Transport Controls (Play / Pause / Next / Previous)
Use `execute_command("music", "action=play|pause|next|previous")` or `run_intent("media", "{\"action\":\"PAUSE\"}")`.
