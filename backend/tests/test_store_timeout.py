"""The hot store never hangs a request (goal 21): a Modal Dict call that does not answer is "unreadable"."""

from __future__ import annotations

import threading
import time
from typing import Any

import pytest

from ridemate_api.store import ModalDictStore, StoreUnavailable


class _SlowDict:
    """modal.Dict's surface; every call blocks until released (a stuck network call)."""

    def __init__(self) -> None:
        self.release = threading.Event()
        self.data: dict[str, Any] = {}

    def _wait(self) -> None:
        self.release.wait(5)

    def get(self, key: str, default: Any = None) -> Any:
        self._wait()
        return self.data.get(key, default)

    def put(self, key: str, value: Any, skip_if_exists: bool = False) -> bool:
        self._wait()
        if skip_if_exists and key in self.data:
            return False
        self.data[key] = value
        return True

    def pop(self, key: str) -> Any:
        self._wait()
        return self.data.pop(key)

    def keys(self) -> list[str]:
        self._wait()
        return list(self.data)


def test_a_stuck_modal_dict_call_is_unreadable_within_the_time_limit() -> None:
    slow = _SlowDict()
    store = ModalDictStore("unused", timeout_s=0.05, backing=slow)
    for call in (
        lambda: store.get("k"),
        lambda: store.put("k", 1),
        lambda: store.put_if_absent("k", 1),
        lambda: store.delete("k"),
        lambda: store.keys_with_prefix("k"),
    ):
        started = time.monotonic()
        with pytest.raises(StoreUnavailable, match="Timeout"):
            call()
        assert time.monotonic() - started < 2
    slow.release.set()


def test_an_answering_modal_dict_works_as_before() -> None:
    fast = _SlowDict()
    fast.release.set()
    store = ModalDictStore("unused", timeout_s=2, backing=fast)
    store.put("spend:a", 1)
    assert store.put_if_absent("spend:a", 2) is False
    assert store.get("spend:a") == 1
    assert store.keys_with_prefix("spend:") == ["spend:a"]
    store.delete("spend:a")
    store.delete("missing")  # KeyError is "already gone"
    assert store.get("spend:a", "none") == "none"
