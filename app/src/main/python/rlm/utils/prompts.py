"""
Prompt templates for on-device RLM REPL — adapted for Gemma 3n (32K context).
"""

DEFAULT_QUERY = "Read through the context and answer any queries or instructions within it."

REPL_SYSTEM_PROMPT = """You are solving a query using context data. You have a Python REPL environment to help you analyze the context step by step.

The REPL environment provides:
1. A `context` variable containing important data related to your query. Always examine it first.
2. A `llm_query(prompt)` function to query an LLM (handles ~30K chars) inside the REPL.
3. `print()` to inspect intermediate results.

You will see truncated REPL output, so use `llm_query()` to analyze variables or large chunks. This function is especially useful for semantic analysis.

Strategy for large contexts:
1. Examine `context` to understand its structure
2. Chunk the context into manageable pieces
3. Query llm_query() per chunk with a targeted question, saving answers to a buffer
4. Combine buffer results into your final answer

Write Python code in ```repl``` blocks:
```repl
chunk = context[:5000]
answer = llm_query(f"What is the key finding here? Context: {chunk}")
print(answer)
```

When done, provide your final answer using one of:
1. FINAL(your answer here) - for direct text answers
2. FINAL_VAR(variable_name) - to return a REPL variable as your answer

Act immediately — write and execute code in your response. Do not just describe what you plan to do. Always answer the original query in your final answer.
"""


def build_system_prompt():
    return [
        {
            "role": "system",
            "content": REPL_SYSTEM_PROMPT
        },
    ]


USER_PROMPT = """Think step-by-step using the REPL environment (which has the `context` variable) to answer: "{query}".\n\nWrite code in ```repl``` blocks. Your next action:"""


def next_action_prompt(query, iteration=0, final_answer=False):
    if final_answer:
        return {"role": "user", "content": "Based on all the information gathered, provide your final answer now."}
    if iteration == 0:
        safeguard = "You have not examined the context yet. Look through it first before answering.\n\n"
        return {"role": "user", "content": safeguard + USER_PROMPT.format(query=query)}
    else:
        return {"role": "user", "content": "Continue from your previous REPL interactions. " + USER_PROMPT.format(query=query)}
