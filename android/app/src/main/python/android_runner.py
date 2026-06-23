"""
Thin Android bridge around the unmodified `proxy` package.

Kotlin (ProxyService) calls into this module via Chaquopy:
    start(port, secret, dc_ips, fallback_cfproxy)  -> begins serving in a thread
    stop()                                         -> stops the server cleanly
    make_link(host, port, secret)                  -> tg://proxy?... link
    gen_secret()                                   -> fresh 32-hex secret
    is_running()                                   -> bool

The proxy itself is pure-asyncio; we run its event loop on a background
thread so the calling (service) thread is never blocked.
"""
from __future__ import annotations

import os
import asyncio
import logging
import threading

from collections import deque

from proxy.config import proxy_config, parse_dc_ip_list, coerce_domain_list
from proxy.tg_ws_proxy import _run

log = logging.getLogger("tg-mtproto-proxy")

_log_configured = False
_log_buffer: "deque[str]" = deque(maxlen=300)


class _BufferHandler(logging.Handler):
    """Keep the most recent log lines so the UI can display them."""

    def emit(self, record: logging.LogRecord) -> None:
        try:
            _log_buffer.append(self.format(record))
        except Exception:
            pass


def get_logs() -> str:
    """Return recent proxy log lines (newest last) for the in-app viewer."""
    return "\n".join(_log_buffer)


def _setup_logging() -> None:
    global _log_configured
    if _log_configured:
        return
    fmt = logging.Formatter("%(asctime)s %(levelname)-5s %(message)s",
                            datefmt="%H:%M:%S")
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    # Chaquopy redirects Python stdout to logcat (tag "python.stdout").
    stream = logging.StreamHandler()
    stream.setFormatter(fmt)
    root.addHandler(stream)
    buf = _BufferHandler()
    buf.setFormatter(fmt)
    root.addHandler(buf)
    logging.getLogger("asyncio").setLevel(logging.WARNING)
    _log_configured = True


_thread: threading.Thread | None = None
_loop: asyncio.AbstractEventLoop | None = None
_stop_event: asyncio.Event | None = None
_ready = threading.Event()


def gen_secret() -> str:
    """Generate a fresh 16-byte MTProto secret as 32 hex chars."""
    return os.urandom(16).hex()


def make_link(host: str, port: int, secret: str, web: bool = False) -> str:
    """Build an MTProto proxy link.

    Uses the plain 16-byte secret exactly as shown in the app (no dd/ee
    prefix), so the link matches the secret the proxy is configured with.

    web=False -> tg://proxy?...      (direct app scheme, for ACTION_VIEW)
    web=True  -> https://t.me/proxy?... (canonical, tappable when pasted)
    """
    query = f"server={host}&port={int(port)}&secret={secret}"
    if web:
        return f"https://t.me/proxy?{query}"
    return f"tg://proxy?{query}"


def is_running() -> bool:
    return _thread is not None and _thread.is_alive()


def _configure(port: int, secret: str, dc_ips, fallback_cfproxy: bool) -> None:
    proxy_config.host = "127.0.0.1"
    proxy_config.port = int(port)
    proxy_config.secret = secret

    dc_list = coerce_domain_list(dc_ips)
    if not dc_list:
        dc_list = ["2:149.154.167.220", "4:149.154.167.220"]
    proxy_config.dc_redirects = parse_dc_ip_list(dc_list)

    proxy_config.fallback_cfproxy = bool(fallback_cfproxy)
    # Fake TLS / proxy-protocol are desktop/server features; keep defaults off.
    proxy_config.fake_tls_domain = ""
    proxy_config.proxy_protocol = False


def _thread_main() -> None:
    global _loop, _stop_event
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    _loop = loop
    _stop_event = asyncio.Event()
    _ready.set()
    try:
        loop.run_until_complete(_run(_stop_event))
    except Exception:
        log.exception("proxy loop crashed")
    finally:
        try:
            loop.run_until_complete(loop.shutdown_asyncgens())
        except Exception:
            pass
        loop.close()
        _loop = None
        _stop_event = None


def start(port: int = 1443, secret: str = "", dc_ips=None,
          fallback_cfproxy: bool = True) -> str:
    """Configure and start the proxy on a background thread.

    Returns the secret actually in use (generated if none was supplied).
    Idempotent: a no-op if already running.
    """
    global _thread
    if is_running():
        return proxy_config.secret

    if not secret:
        secret = gen_secret()

    _setup_logging()
    _configure(port, secret, dc_ips, fallback_cfproxy)

    _ready.clear()
    _thread = threading.Thread(target=_thread_main, name="tg-ws-proxy",
                               daemon=True)
    _thread.start()
    _ready.wait(timeout=10)
    log.info("tg-ws-proxy started on 127.0.0.1:%d", proxy_config.port)
    return secret


def stop() -> None:
    """Signal the proxy to stop and wait for the thread to finish."""
    global _thread
    loop = _loop
    stop_event = _stop_event
    if loop is not None and stop_event is not None:
        loop.call_soon_threadsafe(stop_event.set)
    t = _thread
    if t is not None:
        t.join(timeout=10)
    _thread = None
    log.info("tg-ws-proxy stopped")
