---
name: spotify
description: Search and play songs, artists, albums, podcasts, or playlists on Spotify, and control playback.
---

# Spotify Control

## Instructions

### 1. Play a Specific Song, Artist, Podcast, or Playlist on Spotify
Call `execute_command` with:
- `command`: `"play_media"`
- `args`: JSON string:
```json
{"query": "<song, artist, podcast, or playlist name>", "service": "spotify"}
```
*(If Spotify is not installed on the device, report that honestly — do NOT open the Play Store unless the user asks to install it.)*

### 2. Transport Controls (Play / Pause / Next / Previous)
Use `execute_command("music", "action=play|pause|next|previous")`.
