"""Hot state store. Production: the Modal Dict `ridemate-hot`. Tests: MemoryStore.

Modal Dict has no atomic increment, so callers that count money shard their keys per writer
(see spend.py) and only ever read-modify-write keys they own.
"""

from __future__ import annotations

import threading
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FutureTimeout
from typing import Any, Protocol, TypeVar

T = TypeVar("T")

# Every hot-store call has a time limit (goal 21): a Modal Dict call that does not answer counts as "unreadable",
# so money paths fail closed instead of holding the request until the platform's own timeout.
STORE_TIMEOUT_S = 5.0
_STORE_POOL = ThreadPoolExecutor(max_workers=16, thread_name_prefix="hot-store")


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
    auth, server) means "unreadable", so each method catches Exception and re-raises it as StoreUnavailable. Every
    call is bounded by `timeout_s`; one that does not answer in time is "unreadable" too ("Timeout")."""

    def __init__(
        self,
        name: str,
        environment_name: str | None = None,
        *,
        timeout_s: float = STORE_TIMEOUT_S,
        backing: Any = None,
    ) -> None:
        self._timeout_s = timeout_s
        if backing is not None:  # tests: any object with modal.Dict's get/put/pop/keys
            self._d = backing
            return
        import modal  # imported lazily so unit tests never need Modal credentials

        self._d = modal.Dict.from_name(name, create_if_missing=True, environment_name=environment_name)

    def _call(self, fn: Callable[[], T]) -> T:
        future = _STORE_POOL.submit(fn)
        try:
            return future.result(timeout=self._timeout_s)
        except FutureTimeout as e:
            future.cancel()
            raise StoreUnavailable("Timeout") from e

    def get(self, key: str, default: Any = None) -> Any:
        try:
            return self._call(lambda: self._d.get(key, default))
        except StoreUnavailable:
            raise
        except Exception as e:
            raise StoreUnavailable(str(type(e).__name__)) from e

    def put(self, key: str, value: Any) -> None:
        try:
            self._call(lambda: self._d.put(key, value))
        except StoreUnavailable:
            raise
        except Exception as e:
            raise StoreUnavailable(str(type(e).__name__)) from e

    def put_if_absent(self, key: str, value: Any) -> bool:
        try:
            return bool(self._call(lambda: self._d.put(key, value, skip_if_exists=True)))
        except StoreUnavailable:
            raise
        except Exception as e:
            raise StoreUnavailable(str(type(e).__name__)) from e

    def delete(self, key: str) -> None:
        try:
            self._call(lambda: self._d.pop(key))
        except KeyError:
            return
        except StoreUnavailable:
            raise
        except Exception as e:
            raise StoreUnavailable(str(type(e).__name__)) from e

    def keys_with_prefix(self, prefix: str) -> list[str]:
        try:
            # SIM118 is wrong here: modal.Dict is not a dict. It has no __iter__; keys() streams from the server.
            return self._call(lambda: [k for k in self._d.keys() if isinstance(k, str) and k.startswith(prefix)])  # noqa: SIM118
        except StoreUnavailable:
            raise
        except Exception as e:
            raise StoreUnavailable(str(type(e).__name__)) from e
