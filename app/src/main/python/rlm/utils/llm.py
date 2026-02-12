"""
GemmaBridge - On-device LLM client replacing OpenAIClient.
Calls back to Kotlin via Chaquopy's Java interop for Gemma 3n inference.
"""

_inference_callback = None


def set_inference_callback(callback):
    """Called from Kotlin to register the GemmaEngine inference callback."""
    global _inference_callback
    _inference_callback = callback


class GemmaBridge:
    """LLM client that bridges to on-device Gemma 3n via Kotlin callback."""

    def __init__(self):
        pass

    def completion(self, messages, **kwargs):
        """
        Generate a completion from the on-device Gemma 3n model.

        Args:
            messages: A string prompt, a single dict, or a list of message dicts
                      with 'role' and 'content' keys.
        Returns:
            str: The model's response text
        """
        if _inference_callback is None:
            return "Error: Gemma engine callback not set"

        prompt = self._format_messages(messages)

        try:
            result = str(_inference_callback.infer(prompt))
            return result
        except Exception as e:
            return f"Error during inference: {str(e)}"

    def _format_messages(self, messages):
        """Format a message list into a single text prompt for Gemma 3n."""
        if isinstance(messages, str):
            return messages

        if isinstance(messages, dict):
            messages = [messages]

        if isinstance(messages, list):
            parts = []
            for msg in messages:
                if isinstance(msg, dict):
                    role = msg.get('role', 'user')
                    content = msg.get('content', '')
                    if role == 'system':
                        parts.append(f"[SYSTEM]: {content}")
                    elif role == 'assistant':
                        parts.append(f"[ASSISTANT]: {content}")
                    else:
                        parts.append(f"[USER]: {content}")
                else:
                    parts.append(str(msg))
            return "\n\n".join(parts)

        return str(messages)
