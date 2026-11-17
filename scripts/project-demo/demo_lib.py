#!/usr/bin/env python3
"""Shared helpers for the isolated demo automation."""

from __future__ import annotations

import json
import os
import pathlib
import ssl
import urllib.error
import urllib.request
from typing import Any

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_ENV_FILE = REPO_ROOT / ".secrets" / "project-demo.env"


def load_env(path: pathlib.Path = DEFAULT_ENV_FILE) -> dict[str, str]:
    if not path.exists():
        raise RuntimeError(f"缺少演示配置: {path}")
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip("\"'")
    values.update({key: value for key, value in os.environ.items() if key in values})
    return values


class DemoApi:
    def __init__(self, base_url: str, token: str = "") -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token

    def request(
        self,
        path: str,
        method: str = "GET",
        payload: dict[str, Any] | None = None,
        timeout: int = 120,
    ) -> Any:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=data,
            method=method,
            headers={"Content-Type": "application/json"},
        )
        if self.token:
            request.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(request, timeout=timeout, context=ssl.create_default_context()) as response:
                body = response.read().decode("utf-8")
                return json.loads(body) if body else None
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{method} {path} -> HTTP {error.code}: {body[:800]}") from error

    def login(self, username: str, password: str) -> str:
        response = self.request(
            "/api/v1/auth/login",
            "POST",
            {"username": username, "password": password},
        )
        self.token = response["data"]["token"]
        return self.token


def require(env: dict[str, str], *keys: str) -> None:
    missing = [key for key in keys if not env.get(key)]
    if missing:
        raise RuntimeError("缺少配置项: " + ", ".join(missing))


def data(response: Any) -> Any:
    if not isinstance(response, dict) or "data" not in response:
        raise RuntimeError(f"响应缺少 data: {response!r}")
    return response["data"]
