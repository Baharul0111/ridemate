"""Hot state store. Production: the Modal Dict `ridemate-hot`. Tests: MemoryStore.

Modal Dict has no atomic increment, so callers that count money shard their keys per writer
(see spend.py) and only ever read-modify-write keys they own.
"""

from __future__ import annotations

import threading
from typing import Any, Protocol


class StoreUnavailable(Exception):
    """Raised when the hot store can't be read or written. Money paths fail closed on it."""


class HotStore(Protocol):
    def get(self, key: str, default: Any = None) -> Any: ...

    def put(self, key: str, value: Any) -> None: ...

    def put_if_absent(self, key: str, value: Any) -> bool: ...

    def delete(self, key: str) -> None: ...

    def keys_with_prefix(self, prefix: str) -> list[str]: ...


class MemoryStore:
    def __init__(self) -> None:
        self._d: dict[str, Any] = {}
        self._lock = threading.Lock()
        self.fail = False  # tests flip this to simulate an unreadable store

    def _check(self) -> None:
        if self.fail:
            raise StoreUnavailable("memory store forced failure")

    def get(self, key: str, default: Any = None) -> Any:
        self._check()
        with self._lock:
            return self._d.get(key, default)

    def put(self, key: str, value: Any) -> None:
        self._check()
        with self._lock:
            self._d[key] = value

    def put_if_absent(self, key: str, value: Any) -> bool:
        self._check()
        with self._lock:
            if key in self._d:
                return False
            self._d[key] = value
            return True

    def delete(self, key: str) -> None:
        self._check()
        with self._lock:
            self._d.pop(key, None)

    def keys_with_prefix(self, prefix: str) -> list[str]:
        self._check()
        with self._lock:
            return [k for k in self._d if k.startswith(prefix)]


class ModalDictStore:
    """Thin wrapper over modal.Dict that converts every failure into StoreUnavailable: any Modal error (network,
    auth, server) means "unreadable", so each method catches Exception and re-raises it as StoreUnavailable."""

    def __init__(self, name: str, environment_name: str | None = None) -> None:
        import modal  # imported lazily so unit tests never need Modal credentials

        self._d = modal.Dict.from_name(name, create_if_missing=True, environment_name=environment_name)

    def get(self, key: str, default: Any = None) -> Any:
        try:
            return self._d.get(key, default)
        except Exception as e:
            raise StoreUnavailable(str(type(e).__name__)) from e

    def put(self, key: str, value: Any) -> None:
        try:
            self._d.put(key, value)
        except Exception as e:
            raise StoreUnavailable(str(type(e).__name__)) from e

    def put_if_absent(self, key: str, value: Any) -> bool:
        try:
            return bool(self._d.put(key, value, skip_if_exists=True))
        except Exception as e:
            raise StoreUnavailable(str(type(e).__name__)) from e

    def delete(self, key: str) -> None:
        try:
            self._d.pop(key)
        except KeyError:
            return
        except Exception as e:
            raise StoreUnavailable(str(type(e).__name__)) from e

    def keys_with_prefix(self, prefix: str) -> list[str]:
        try:
            # SIM118 is wrong here: modal.Dict is not a dict. It has no __iter__; keys() streams from the server.
            return [k for k in self._d.keys() if isinstance(k, str) and k.startswith(prefix)]  # noqa: SIM118
        except Exception as e:
            raise StoreUnavailable(str(type(e).__name__)) from e
