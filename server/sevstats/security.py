"""Tokens, pairing codes, password hashing and a small in-memory attempt limiter. Standard library only."""
import hashlib
import hmac
import secrets
import threading
import time
from collections import deque

# No 0/O, 1/I/L: the code is read off one screen and typed on another.
CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
CODE_LENGTH = 8
CODE_TTL_MS = 10 * 60_000


def new_token() -> str:
    return secrets.token_urlsafe(32)


def token_hash(token: str) -> str:
    """Tokens are 256 random bits, so a plain hash is enough to keep the database from holding them."""
    return hashlib.sha256(token.encode()).hexdigest()


def new_code() -> str:
    return "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))


def normalise_code(code: str) -> str:
    return "".join(ch for ch in code.upper() if ch.isalnum())


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    h = hashlib.scrypt(password.encode(), salt=salt, n=2**15, r=8, p=1, maxmem=2**27, dklen=32)
    return f"scrypt${salt.hex()}${h.hex()}"


def check_password(password: str, stored: str) -> bool:
    try:
        kind, salt, want = stored.split("$")
        if kind != "scrypt":
            return False
        h = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=2**15, r=8, p=1, maxmem=2**27, dklen=32)
        return hmac.compare_digest(h.hex(), want)
    except (ValueError, TypeError):
        return False


class Limiter:
    """At most [limit] failures per [window_s] for one key; a key is an address, or "*" for everyone."""

    def __init__(self, limit: int, window_s: float):
        self.limit, self.window_s = limit, window_s
        self._hits: dict[str, deque] = {}
        self._lock = threading.Lock()

    def blocked(self, key: str) -> bool:
        with self._lock:
            q = self._hits.get(key)
            if not q:
                return False
            cut = time.monotonic() - self.window_s
            while q and q[0] < cut:
                q.popleft()
            return len(q) >= self.limit

    def fail(self, key: str) -> None:
        with self._lock:
            if len(self._hits) > 10_000:
                self._hits.clear()
            self._hits.setdefault(key, deque()).append(time.monotonic())
