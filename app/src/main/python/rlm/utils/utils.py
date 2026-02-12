"""
Utility functions for the RLM REPL — unchanged from MIT reference except minor cleanups.
"""

import re


def find_code_blocks(text):
    """Find ```repl``` code blocks in text. Returns list of code strings, or empty list."""
    pattern = r'```repl\s*\n(.*?)\n```'
    results = []
    for match in re.finditer(pattern, text, re.DOTALL):
        code_content = match.group(1).strip()
        results.append(code_content)
    return results


def find_final_answer(text):
    """Find FINAL(...) or FINAL_VAR(...) in response. Returns (type, content) or None."""
    # FINAL_VAR first (more specific)
    match = re.search(r'^\s*FINAL_VAR\((.*?)\)', text, re.MULTILINE | re.DOTALL)
    if match:
        return ('FINAL_VAR', match.group(1).strip())

    match = re.search(r'^\s*FINAL\((.*?)\)', text, re.MULTILINE | re.DOTALL)
    if match:
        return ('FINAL', match.group(1).strip())

    return None


def add_execution_result_to_messages(messages, code, result, max_character_length=50000):
    """Append code execution result to conversation messages."""
    if len(result) > max_character_length:
        result = result[:max_character_length] + "..."

    execution_message = {
        "role": "user",
        "content": f"Code executed:\n```python\n{code}\n```\n\nREPL output:\n{result}"
    }
    messages.append(execution_message)
    return messages


def format_execution_result(stdout, stderr, locals_dict, truncate_length=100):
    """Format execution result as a string for the LLM to read."""
    result_parts = []

    if stdout:
        result_parts.append(f"\n{stdout}")

    if stderr:
        result_parts.append(f"\n{stderr}")

    important_vars = {}
    for key, value in locals_dict.items():
        if not key.startswith('_') and key not in ('__builtins__', '__name__', '__doc__'):
            try:
                if isinstance(value, (str, int, float, bool, list, dict, tuple)):
                    if isinstance(value, str) and len(value) > truncate_length:
                        important_vars[key] = f"'{value[:truncate_length]}...'"
                    else:
                        important_vars[key] = repr(value)
            except:
                important_vars[key] = f"<{type(value).__name__}>"

    if important_vars:
        result_parts.append(f"REPL variables: {list(important_vars.keys())}\n")

    return "\n\n".join(result_parts) if result_parts else "No output"


def execute_code(repl_env, code):
    """Execute code in REPL and return formatted result."""
    try:
        result = repl_env.code_execution(code)
        formatted = format_execution_result(result.stdout, result.stderr, result.locals)
        return formatted
    except Exception as e:
        return f"Error executing code: {str(e)}"


def process_code_execution(response, messages, repl_env):
    """Process all ```repl``` code blocks from model response."""
    code_blocks = find_code_blocks(response)

    if code_blocks:
        for code in code_blocks:
            execution_result = execute_code(repl_env, code)
            messages = add_execution_result_to_messages(messages, code, execution_result)

    return messages


def check_for_final_answer(response, repl_env):
    """Check if response contains FINAL() or FINAL_VAR() answer."""
    result = find_final_answer(response)
    if result is None:
        return None

    answer_type, content = result

    if answer_type == 'FINAL':
        return content
    elif answer_type == 'FINAL_VAR':
        try:
            variable_name = content.strip().strip('"').strip("'").strip()
            if variable_name in repl_env.locals:
                return str(repl_env.locals[variable_name])
            else:
                return None
        except Exception:
            return None

    return None


def convert_context_for_repl(context):
    """Convert context into (json_data, string_data) tuple for REPL loading."""
    if isinstance(context, dict):
        return context, None
    elif isinstance(context, str):
        return None, context
    elif isinstance(context, list):
        if len(context) > 0 and isinstance(context[0], dict):
            if "content" in context[0]:
                return [msg.get("content", "") for msg in context], None
            else:
                return context, None
        else:
            return context, None
    else:
        return context, None
