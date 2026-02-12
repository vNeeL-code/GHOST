from abc import ABC, abstractmethod

class RLM(ABC):
    @abstractmethod
    def completion(self, context, query: str) -> str:
        pass

    @abstractmethod
    def cost_summary(self) -> dict:
        pass

    @abstractmethod
    def reset(self):
        pass
