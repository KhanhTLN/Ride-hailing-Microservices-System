class InvalidEventError(ValueError):
    pass


class EventConflictError(InvalidEventError):
    pass


class WorkflowBusyError(RuntimeError):
    pass


class LostClaimError(RuntimeError):
    pass


class DriverError(RuntimeError):
    def __init__(self, code: str, *, retryable: bool = False, ambiguous: bool = False):
        super().__init__(code)
        self.code = code
        self.retryable = retryable
        self.ambiguous = ambiguous
