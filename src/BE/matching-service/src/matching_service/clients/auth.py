from matching_service.config import Settings


def driver_metadata(settings: Settings, correlation_id) -> tuple:
    """Current User Service authenticates users, not service clients.

    No Identity endpoint or passenger login is invented here. A bearer can be
    supplied explicitly when Driver supports validating User Service tokens.
    """
    metadata = [("x-correlation-id", str(correlation_id))]
    token = settings.driver_grpc_bearer_token.get_secret_value()
    if token:
        if not token.isascii() or any(c.isspace() for c in token):
            raise ValueError("DRIVER_GRPC_BEARER_TOKEN must be an ASCII token without whitespace")
        metadata.append(("authorization", f"Bearer {token}"))
    return tuple(metadata)
