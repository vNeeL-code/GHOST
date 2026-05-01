---
name: ui-automation
description: Control the physical UI of any app on this device (clicks, scrolls, typing).
---
# UI Automation Skill
You can control the physical screen of the device using accessibility hooks.

## Guidelines
1. Always start by calling `dumpScreenState()` to understand the current layout.
2. Use `clickUIElement(text="...")` to interact with buttons or links.
3. If an element is not visible, use `scrollScreen("DOWN")` to find it.
4. If the app is not open, use `performNavigation("HOME")` first.
5. Provide a brief one-sentence summary of the action you took.

## Tools Available
- clickUIElement
- scrollScreen
- performNavigation
- dumpScreenState
- typeText
