"""Blocking calls on the request path run off the event loop (architecture §4.12).

A container serves up to 20 requests at once on one event loop. Modal Dict, Firestore and the spend meter are
synchronous clients, so one slow call made on the loop would stall every other request in the container,
/healthz included. Endpoint code therefore awaits them through these helpers:

- `off_loop(fn, ...)` runs `fn` in Starlette's worker-thread pool and returns its result.
- `off_loop_shielded(fn, ..., undo=...)` is for calls whose effect matters even when the awaiting request is
  cancelled (client gone, Modal input timeout): the call keeps running in its thread instead of being abandoned
  half-way, and when the request gave up before it returned, `undo(result)` runs in a thread once it does, so a
  spend reservation or quota unit that nobody will settle is given back.
"""

from __future__ import annotations

import asyncio
import functools
import logging
from collections.abc import Callable
from typing import Any

from starlette.concurrency import run_in_threadpool

log = logging.getLogger(__name__)

# Strong references to calls that outlived their request, so they aren't garbage-collected mid-flight.
_ABANDONED: set[asyncio.Future[Any]] = set()


async def off_loop[**P, T](fn: Callable[P, T], /, *args: P.args, **kwargs: P.kwargs) -> T:
    return await run_in_threadpool(fn, *args, **kwargs)


async def off_loop_shielded[T](
    fn: Callable[..., T], /, *args: Any, undo: Callable[[T], object] | None = None, **kwargs: Any
) -> T:
    future = asyncio.ensure_future(run_in_threadpool(fn, *args, **kwargs))
    try:
        return await asyncio.shield(future)
    except asyncio.CancelledError:
        _ABANDONED.add(future)
        future.add_done_callback(functools.partial(_after_abandoned, undo))
        raise


def _after_abandoned(undo: Callable[[Any], object] | None, future: asyncio.Future[Any]) -> None:
    _ABANDONED.discard(future)
    if future.cancelled():
        return
    error = future.exception()  # retrieved here, so asyncio never reports it as unhandled
    if error is not None:
        log.warning("abandoned call failed: %s", type(error).__name__)
        return
    if undo is None:
        return
    undone = asyncio.get_running_loop().run_in_executor(None, _run_undo, undo, future.result())
    _ABANDONED.add(undone)
    undone.add_done_callback(_ABANDONED.discard)


def _run_undo(undo: Callable[[Any], object], value: Any) -> None:
    try:
        undo(value)
    except Exception as e:  # noqa: BLE001 - an undo is best effort; the original request is already gone
        log.warning("undo of an abandoned call failed: %s", type(e).__name__)


def abandoned_count() -> int:
    """Calls (and undos) still running after their request gave up (tests, graceful shutdown)."""
    return len(_ABANDONED)
