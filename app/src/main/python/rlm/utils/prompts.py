"""
Prompt templates for on-device RLM REPL — adapted for Gemma 3n (32K context).
"""

DEFAULT_QUERY = "Read through the context and answer any queries or instructions within it."

# NOTE: No system prompt here — generateOneShot is a pure sub-call.
# We do NOT tell the model who it is — it already knows.
# Injecting a competing identity ("you are a REPL solver") was poisoning
# the main conversation history via role bleed.

REPL_TASK_PROMPT = """Use the Python REPL to reason through this step by step.

The REPL provides:
- `context` variable: the data to analyze
- `llm_query(prompt)` function: sub-query for semantic analysis of large chunks
- `print()` for intermediate inspection

For large contexts, chunk and query per chunk, save results to a buffer, combine.

Write code in ```repl``` blocks:
```repl
chunk = context[:5000]
answer = llm_query(f"Key finding: {chunk}")
print(answer)
```

When done: FINAL(your answer) or FINAL_VAR(variable_name)
Act immediately. Do not describe what you plan to do — just do it."""


def build_system_prompt():
    # No system role — avoids identity conflict with main conversation.
    # First message is the task prompt as "assistant" priming, not "user".
    return [
        {
            "role": "assistant",
            "content": "I'll use the REPL to work through this carefully."
        }
    ]


USER_PROMPT = """Task: "{query}"\n\nExamine the context variable first, then write ```repl``` code. Your next action:"""


def next_action_prompt(query, iteration=0, final_answer=False):
    if final_answer:
        # Keep as user to prompt a response, but make it neutral
        return {"role": "user", "content": "Provide your final answer now using FINAL(...)."}
    if iteration == 0:
        return {"role": "user", "content": USER_PROMPT.format(query=query)}
    else:
        # Alternate user/assistant framing to keep turn structure valid
        return {"role": "user", "content": "Continue. " + USER_PROMPT.format(query=query)}
