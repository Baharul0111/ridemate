"""The FastAPI app factory (contracts §1).

`create_app(deps)` wires the middleware (request ids, body limits, per-IP limit), renders every error as the §1.2
envelope, and mounts the endpoints: api/misc.py always; api/ai.py and api/ondc.py when they import, so the
platform keeps serving while one of them is missing or broken (a broken module is logged and shown in /readyz).
"""

from __future__ import annotations

import importlib
import logging
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.middleware import Middleware

from .api import misc
from .deps import Deps
from .interfaces import ApiError, FirestoreUnavailable
from .middleware import (
    BodyLimitMiddleware,
    IpLimitMiddleware,
    RequestContextMiddleware,
    api_error_response,
    error_response,
    request_id_of,
)
from .store import StoreUnavailable

log = logging.getLogger(__name__)

OPTIONAL_ROUTERS: tuple[str, ...] = ("ridemate_api.api.ai", "ridemate_api.api.ondc")
_HTTP_CODES = {
    400: "invalid",
    401: "auth_required",
    403: "forbidden",
    404: "not_found",
    405: "method_not_allowed",
    413: "too_large",
    415: "bad_media",
    422: "invalid",
    429: "rate_limited",
}


def create_app(deps: Deps) -> FastAPI:
    app = FastAPI(
        title="ridemate-api",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        middleware=[
            Middleware(RequestContextMiddleware),
            Middleware(BodyLimitMiddleware),
            Middleware(IpLimitMiddleware),
        ],
    )
    app.state.deps = deps
    _install_error_handlers(app)
    app.include_router(misc.router)
    app.state.optional_routes = {
        name.rsplit(".", 1)[-1]: _mount_optional(app, name, deps) for name in OPTIONAL_ROUTERS
    }
    return app


def _mount_optional(app: FastAPI, module_name: str, deps: Deps) -> bool:
    try:
        module = importlib.import_module(module_name)
    except ModuleNotFoundError as e:
        if e.name == module_name:
            log.info("optional routes %s not present", module_name)
        else:
            log.error("optional routes %s failed to import: missing %s", module_name, e.name)
        return False
    except Exception as e:  # noqa: BLE001 - a broken module must not take the platform endpoints down
        log.error("optional routes %s failed to import: %s", module_name, type(e).__name__)
        return False
    mount = getattr(module, "mount", None)
    router = getattr(module, "router", None)
    if not callable(mount) and router is None:
        log.error("optional routes %s define neither mount() nor router", module_name)
        return False
    try:
        if callable(mount):  # api/ondc.py decides itself whether the mock seller is mounted (ONDC_MODE)
            mount(app, deps.settings)
        else:
            app.include_router(router)
    except Exception as e:  # noqa: BLE001 - same rule as a failed import
        log.error("optional routes %s failed to mount: %s", module_name, type(e).__name__)
        return False
    return True


def _now(request: Request) -> Any:
    return request.app.state.deps.clock.now_utc()


def _install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _api_error(request: Request, exc: ApiError) -> JSONResponse:
        return api_error_response(exc, request_id=request_id_of(request), now=_now(request))

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, exc: RequestValidationError) -> JSONResponse:
        # The offending values may be user text, so only the code goes back.
        return error_response(422, "invalid", request_id=request_id_of(request))

    @app.exception_handler(StarletteHTTPException)
    async def _http(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        response = error_response(
            exc.status_code, _HTTP_CODES.get(exc.status_code, "error"), request_id=request_id_of(request)
        )
        for name, value in (exc.headers or {}).items():
            response.headers.setdefault(name, value)
        return response

    @app.exception_handler(StoreUnavailable)
    async def _store_down(request: Request, exc: StoreUnavailable) -> JSONResponse:
        # Money and quota paths fail closed: an unreadable hot store means no AI.
        return error_response(503, "meter_unavailable", reason="meter", request_id=request_id_of(request))

    @app.exception_handler(FirestoreUnavailable)
    async def _firestore_down(request: Request, exc: FirestoreUnavailable) -> JSONResponse:
        return error_response(503, "storage_unavailable", request_id=request_id_of(request))
