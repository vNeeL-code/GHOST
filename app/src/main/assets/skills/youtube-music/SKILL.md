---
name: youtube-music
description: Search and play songs, artists, albums, or playlists on YouTube Music, and control playback.
---

# YouTube Music Control

## Instructions

### 1. Play or Search a Song / Artist / Playlist on YouTube Music
Call `run_intent` with:
- `intent`: `"android.media.action.MEDIA_PLAY_FROM_SEARCH"`
- `parameters`: JSON string:
```json
{
  "component_package": "com.google.android.apps.youtube.music",
  "extras": {
    "query": "<song, artist, or playlist name>",
    "android.intent.extra.focus": "vnd.android.cursor.item/*"
  }
}
```

### 2. Fallback Web / Deep-Link Search (if play-from-search is unavailable)
Call `run_intent` with:
- `intent`: `"android.intent.action.VIEW"`
- `parameters`: JSON string:
```json
{
  "data": "https://music.youtube.com/search?q=<URL-encoded query>",
  "component_package": "com.google.android.apps.youtube.music"
}
```

### 3. Transport Controls (Play / Pause / Next / Previous)
Use `execute_command("music", "action=play|pause|next|previous")` or `run_intent("media", "{\"action\":\"NEXT\"}")`.
