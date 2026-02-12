"""
RLM Bridge — Entry point for Kotlin → Python calls via Chaquopy.
Kotlin calls init(callback) once, then completion(context, query) per request.
"""

from rlm.utils.llm import set_inference_callback

_initialized = False


def init(callback):
    """
    Initialize with Kotlin inference callback.
    Called once from RLMBridge.kt after Python.start().

    Args:
        callback: Java/Kotlin object with an infer(String) -> String method
    """
    global _initialized
    set_inference_callback(callback)
    _initialized = True


def completion(context, query, max_iterations=8):
    """
    Run RLM completion on the given context and query.

    Args:
        context: The context to analyze (string or structured data)
        query: The user's question
        max_iterations: Max REPL iterations (default 8, capped for on-device speed)

    Returns:
        str: The final answer
    """
    if not _initialized:
        return "Error: RLM not initialized. Call init(callback) first."

    # Cap iterations for on-device performance
    max_iterations = min(int(max_iterations), 10)

    from rlm.rlm_repl import RLM_REPL

    rlm = RLM_REPL(max_iterations=max_iterations)
    result = rlm.completion(context=context, query=query)
    return result
