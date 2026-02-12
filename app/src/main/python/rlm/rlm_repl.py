"""
RLM REPL — Recursive Language Model with REPL environment.
Adapted for on-device Gemma 3n inference via GemmaBridge.
"""

from rlm import RLM
from rlm.repl import REPLEnv
from rlm.utils.llm import GemmaBridge
from rlm.utils.prompts import DEFAULT_QUERY, next_action_prompt, build_system_prompt
import rlm.utils.utils as utils


class RLM_REPL(RLM):
    """
    LLM Client that uses a REPL environment to analyze contexts
    and recursively call sub-LLMs for complex reasoning.
    """

    def __init__(self, max_iterations=8):
        self.llm = GemmaBridge()
        self.repl_env = None
        self._max_iterations = max_iterations
        self.messages = []
        self.query = None

    def setup_context(self, context, query=None):
        if query is None:
            query = DEFAULT_QUERY

        self.query = query
        self.messages = build_system_prompt()

        context_data, context_str = utils.convert_context_for_repl(context)

        self.repl_env = REPLEnv(
            context_json=context_data,
            context_str=context_str,
        )

        return self.messages

    def completion(self, context, query=None) -> str:
        """
        Given a query and a (potentially long) context, iteratively
        use the REPL to explore, analyze, and answer.
        """
        self.messages = self.setup_context(context, query)

        for iteration in range(self._max_iterations):
            # Query the model with full conversation + next action prompt
            response = self.llm.completion(
                self.messages + [next_action_prompt(query, iteration)]
            )

            # Check for code blocks
            code_blocks = utils.find_code_blocks(response)

            if code_blocks:
                self.messages = utils.process_code_execution(
                    response, self.messages, self.repl_env
                )
            else:
                self.messages.append({
                    "role": "assistant",
                    "content": "You responded with:\n" + response
                })

            # Check for final answer
            final_answer = utils.check_for_final_answer(response, self.repl_env)

            if final_answer:
                return final_answer

        # Max iterations reached — force a final answer
        self.messages.append(next_action_prompt(query, iteration, final_answer=True))
        final_answer = self.llm.completion(self.messages)
        return final_answer

    def cost_summary(self):
        return {}

    def reset(self):
        self.repl_env = None
        self.messages = []
        self.query = None
