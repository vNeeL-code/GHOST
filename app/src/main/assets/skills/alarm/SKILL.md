---
name: alarm
description: Set an alarm.
---

# Set an alarm.

## Instructions

Call the un_intent tool with the following exact parameters:

- intent: alarm
- parameters: A JSON string with the following fields:
  - hour: The hour (0-23). Int.
  - minutes: The minutes (0-59). Int.
  - label: The alarm label. String.
