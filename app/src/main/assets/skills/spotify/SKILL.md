---
name: spotify
description: Search and play songs, artists, albums, podcasts, or playlists on Spotify, and control playback.
---

# Spotify Control

## Instructions

### 1. Play or Search on Spotify
Call `run_intent` with:
- `intent`: `"android.media.action.MEDIA_PLAY_FROM_SEARCH"`
- `parameters`: JSON string:
```json
{
  "component_package": "com.spotify.music",
  "extras": {
    "query": "<song, artist, podcast, or playlist name>",
    "android.intent.extra.focus": "vnd.android.cursor.item/*"
  }
}
```

### 2. Open Spotify Search Deep Link
Call `run_intent` with:
- `intent`: `"android.intent.action.VIEW"`
- `parameters`: JSON string:
```json
{
  "data": "spotify:search:<URL-encoded query>",
  "component_package": "com.spotify.music"
}
```

### 3. Transport Controls (Play / Pause / Next / Previous)
Use `execute_command("music", "action=play|pause|next|previous")` or `run_intent("media", "{\"action\":\"NEXT\"}")`.
