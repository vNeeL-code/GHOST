---
name: sensor-lab
description: Phyphox-style pocket physics and hardware sensor lab (live 3-axis accelerometer oscilloscope, spirit level, tilt/pitch-roll gauge, compass heading, and device telemetry).
---

# Phyphox Pocket Sensor Lab

## Instructions

When the user asks for a spirit level, bubble level, accelerometer/seismograph, G-force meter, compass, or pocket physics lab:

1. Call `run_js` with:
   - `skillName`: `"sensor-lab"`
   - `scriptName`: `"index.html"`
   - `data`: JSON string with optional `mode`: `"level"` | `"accel"` | `"compass"` | `"all"` (default `"all"`).
2. If the user also asks for battery, thermals, barometer/pressure, or light lux telemetry, call `execute_command("status", "")` and report the exact sensor numbers alongside the live instrument card.
