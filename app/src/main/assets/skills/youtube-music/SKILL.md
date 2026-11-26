---
name: youtube-music
description: Search and play songs, artists, albums, or playlists on YouTube Music, and control playback.
---

# YouTube Music Control

## Instructions

### 1. Play a Specific Song, Artist, Album, or Playlist on YouTube Music
Call `execute_command` with:
- `command`: `"play_media"`
- `args`: JSON string:
```json
{"query": "<song, artist, or playlist name>", "service": "youtube_music"}
```

### 2. Transport Controls (Play / Pause / Next / Previous)
Use `execute_command("music", "action=play|pause|next|previous")`.
