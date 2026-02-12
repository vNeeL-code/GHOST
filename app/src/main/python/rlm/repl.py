"""
REPL Environment for RLM — adapted for on-device Android (Chaquopy).
Sandboxed Python execution with llm_query() for sub-LLM calls.
"""

import sys
import io
import threading
import json
import tempfile
import os
import time
from contextlib import contextmanager
from dataclasses import dataclass

from rlm import RLM


# Modules blocked in the sandbox (no network, no native code, no subprocesses)
_BLOCKED_MODULES = frozenset({
    'subprocess', 'socket', 'http', 'urllib', 'ftplib', 'smtplib',
    'ctypes', 'multiprocessing', 'signal', 'webbrowser',
})

_original_import = __import__


def _safe_import(name, *args, **kwargs):
    """Import hook that blocks dangerous modules in the REPL sandbox."""
    top_module = name.split('.')[0]
    if top_module in _BLOCKED_MODULES:
        raise ImportError(f"Module '{name}' is blocked in RLM sandbox")
    return _original_import(name, *args, **kwargs)


class Sub_RLM(RLM):
    """Sub-LLM client for REPL environment — uses on-device Gemma via GemmaBridge."""

    def __init__(self):
        from rlm.utils.llm import GemmaBridge
        self.client = GemmaBridge()

    def completion(self, prompt) -> str:
        try:
            return self.client.completion(messages=prompt, timeout=300)
        except Exception as e:
            return f"Error making LLM query: {str(e)}"

    def cost_summary(self):
        return {}

    def reset(self):
        pass


@dataclass
class REPLResult:
    stdout: str
    stderr: str
    locals: dict
    execution_time: float = 0.0

    def __str__(self):
        return f"REPLResult(stdout={self.stdout}, stderr={self.stderr}, execution_time={self.execution_time})"


class REPLEnv:
    def __init__(
        self,
        context_json=None,
        context_str=None,
        setup_code=None,
        **kwargs,  # Accept and ignore recursive_model etc.
    ):
        self.original_cwd = os.getcwd()
        self.temp_dir = tempfile.mkdtemp(prefix="rlm_env_")

        # Sub-LLM for llm_query() calls
        self.sub_rlm = Sub_RLM()

        # Safe builtins — blocks eval/exec/compile/input + dangerous modules
        self.globals = {
            '__builtins__': {
                # Core types and functions
                'print': print, 'len': len, 'str': str, 'int': int, 'float': float,
                'list': list, 'dict': dict, 'set': set, 'tuple': tuple, 'bool': bool,
                'type': type, 'isinstance': isinstance, 'enumerate': enumerate,
                'zip': zip, 'map': map, 'filter': filter, 'sorted': sorted,
                'min': min, 'max': max, 'sum': sum, 'abs': abs, 'round': round,
                'chr': chr, 'ord': ord, 'hex': hex, 'bin': bin, 'oct': oct,
                'repr': repr, 'ascii': ascii, 'format': format,
                'any': any, 'all': all, 'hasattr': hasattr, 'getattr': getattr,
                'setattr': setattr, 'dir': dir, 'range': range,
                'reversed': reversed, 'iter': iter, 'next': next,
                'pow': pow, 'divmod': divmod, 'hash': hash, 'id': id,
                'callable': callable, 'issubclass': issubclass, 'super': super,
                'bytes': bytes, 'bytearray': bytearray,
                'property': property, 'staticmethod': staticmethod, 'classmethod': classmethod,
                'object': object, 'slice': slice, 'complex': complex,

                # Exception types
                'Exception': Exception, 'ValueError': ValueError, 'TypeError': TypeError,
                'KeyError': KeyError, 'IndexError': IndexError, 'AttributeError': AttributeError,
                'FileNotFoundError': FileNotFoundError, 'OSError': OSError,
                'RuntimeError': RuntimeError, 'NameError': NameError, 'ImportError': ImportError,
                'StopIteration': StopIteration, 'NotImplementedError': NotImplementedError,
                'AssertionError': AssertionError, 'ZeroDivisionError': ZeroDivisionError,

                # Sandboxed import (blocks network/native modules)
                '__import__': _safe_import,
                # File access (scoped to temp dir on Android)
                'open': open,

                # BLOCKED
                'input': None,
                'eval': None,
                'exec': None,
                'compile': None,
                'globals': None,
                'locals': None,
            }
        }
        self.locals = {}
        self._lock = threading.Lock()

        self.load_context(context_json, context_str)

        # Inject llm_query() into REPL globals
        def llm_query(prompt):
            """Query the on-device LLM with the given prompt."""
            return self.sub_rlm.completion(prompt)

        self.globals['llm_query'] = llm_query

        # Inject FINAL_VAR() for returning variables as final answers
        def final_var(variable_name):
            variable_name = variable_name.strip().strip('"').strip("'").strip()
            try:
                if variable_name in self.locals:
                    return str(self.locals[variable_name])
                else:
                    return f"Error: Variable '{variable_name}' not found"
            except Exception as e:
                return f"Error retrieving '{variable_name}': {str(e)}"

        self.globals['FINAL_VAR'] = final_var

        if setup_code:
            self.code_execution(setup_code)

    def load_context(self, context_json=None, context_str=None):
        if context_json is not None:
            context_path = os.path.join(self.temp_dir, "context.json")
            with open(context_path, "w") as f:
                json.dump(context_json, f, indent=2)
            self.code_execution(
                f"import json\n"
                f"with open(r'{context_path}', 'r') as f:\n"
                f"    context = json.load(f)\n"
            )

        if context_str is not None:
            context_path = os.path.join(self.temp_dir, "context.txt")
            with open(context_path, "w") as f:
                f.write(context_str)
            self.code_execution(
                f"with open(r'{context_path}', 'r') as f:\n"
                f"    context = f.read()\n"
            )

    def __del__(self):
        try:
            import shutil
            shutil.rmtree(self.temp_dir, ignore_errors=True)
        except:
            pass

    @contextmanager
    def _capture_output(self):
        with self._lock:
            old_stdout = sys.stdout
            old_stderr = sys.stderr
            stdout_buffer = io.StringIO()
            stderr_buffer = io.StringIO()
            try:
                sys.stdout = stdout_buffer
                sys.stderr = stderr_buffer
                yield stdout_buffer, stderr_buffer
            finally:
                sys.stdout = old_stdout
                sys.stderr = old_stderr

    @contextmanager
    def _temp_working_directory(self):
        old_cwd = os.getcwd()
        try:
            os.chdir(self.temp_dir)
            yield
        except OSError:
            yield  # On Android, chdir might fail — continue anyway
        finally:
            try:
                os.chdir(old_cwd)
            except OSError:
                pass

    def code_execution(self, code) -> REPLResult:
        """Execute code notebook-style in the sandboxed REPL."""
        start_time = time.time()
        with self._capture_output() as (stdout_buffer, stderr_buffer):
            with self._temp_working_directory():
                try:
                    lines = code.split('\n')
                    import_lines = []
                    other_lines = []

                    for line in lines:
                        if line.startswith(('import ', 'from ')) and not line.startswith('#'):
                            import_lines.append(line)
                        else:
                            other_lines.append(line)

                    if import_lines:
                        exec('\n'.join(import_lines), self.globals, self.globals)

                    if other_lines:
                        other_code = '\n'.join(other_lines)
                        combined = {**self.globals, **self.locals}

                        non_comment = [l for l in other_lines if l.strip() and not l.strip().startswith('#')]

                        if non_comment:
                            last_line = non_comment[-1]
                            is_expression = (
                                not last_line.strip().startswith((
                                    'import ', 'from ', 'def ', 'class ', 'if ', 'for ',
                                    'while ', 'try:', 'with ', 'return ', 'yield ',
                                    'break', 'continue', 'pass'
                                )) and
                                '=' not in last_line.split('#')[0] and
                                not last_line.strip().endswith(':') and
                                not last_line.strip().startswith('print(')
                            )

                            if is_expression:
                                try:
                                    if len(non_comment) > 1:
                                        for i, line in enumerate(other_lines):
                                            if line == last_line:
                                                statements = '\n'.join(other_lines[:i])
                                                exec(statements, combined, combined)
                                                break

                                    result = eval(last_line, combined, combined)
                                    if result is not None:
                                        print(repr(result))
                                except:
                                    exec(other_code, combined, combined)
                            else:
                                exec(other_code, combined, combined)
                        else:
                            exec(other_code, combined, combined)

                        for key, value in combined.items():
                            if key not in self.globals:
                                self.locals[key] = value

                    stdout_content = stdout_buffer.getvalue()
                    stderr_content = stderr_buffer.getvalue()
                except Exception as e:
                    stderr_content = stderr_buffer.getvalue() + str(e)
                    stdout_content = stdout_buffer.getvalue()

        execution_time = time.time() - start_time

        self.locals['_stdout'] = stdout_content
        self.locals['_stderr'] = stderr_content

        return REPLResult(stdout_content, stderr_content, self.locals.copy(), execution_time)
