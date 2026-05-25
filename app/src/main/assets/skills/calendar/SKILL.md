---
name: calendar
description: Create a calendar event.
---

# Create a calendar event.

## Instructions

Call the un_intent tool with the following exact parameters:

- intent: calendar
- parameters: A JSON string with the following fields:
  - title: Event title. String.
  - description: Event description. String.
  - minutes: Duration in minutes. Int.
