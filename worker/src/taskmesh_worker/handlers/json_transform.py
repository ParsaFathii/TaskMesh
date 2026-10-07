"""json_transform handler: pick / remove / rename / flatten with dot paths.

Dot paths (``a.b.c``) traverse nested dicts; numeric segments index into
lists (``items.0.name``). Semantics:

- ``pick`` — keep only the listed paths; missing paths are tolerated no-ops.
- ``remove`` — delete the listed paths; missing paths are tolerated no-ops.
- ``rename`` — move ``from`` to ``to`` (intermediate containers on ``to`` are
  created); a missing ``from`` path raises ValidationError (non-retryable).
  An existing ``to`` value is overwritten.
- ``flatten`` — collapse nested dicts into single-level keys joined by the
  separator; list values stay intact as leaves.

``applied`` counts effective changes: paths picked/removed, 1 per rename,
and the number of leaf keys produced by flatten.
"""

from __future__ import annotations

import copy
from collections.abc import Mapping
from typing import Any

from ..models import (
    FlattenOperation,
    JsonTransformPayload,
    PickOperation,
    RemoveOperation,
    RenameOperation,
)
from .base import JobContext, JobHandler, ValidationError

_MISSING = object()


def _get(obj: Any, path: str) -> Any:
    current = obj
    for part in path.split("."):
        if isinstance(current, Mapping):
            if part not in current:
                return _MISSING
            current = current[part]
        elif isinstance(current, list) and part.lstrip("-").isdigit():
            index = int(part)
            if -len(current) <= index < len(current):
                current = current[index]
            else:
                return _MISSING
        else:
            return _MISSING
    return current


def _delete(obj: Any, path: str) -> bool:
    parts = path.split(".")
    parent = _get(obj, ".".join(parts[:-1])) if len(parts) > 1 else obj
    key = parts[-1]
    if parent is _MISSING:
        return False
    if isinstance(parent, Mapping) and key in parent:
        del parent[key]
        return True
    if isinstance(parent, list) and key.lstrip("-").isdigit():
        index = int(key)
        if -len(parent) <= index < len(parent):
            del parent[index]
            return True
    return False


def _set(obj: dict[str, Any], path: str, value: Any) -> None:
    parts = path.split(".")
    current: dict[str, Any] = obj
    for part in parts[:-1]:
        child = current.get(part)
        if not isinstance(child, dict):
            child = {}
            current[part] = child
        current = child
    current[parts[-1]] = value


def _flatten(obj: Any, separator: str, prefix: str = "") -> dict[str, Any]:
    flat: dict[str, Any] = {}
    if isinstance(obj, Mapping):
        for key, value in obj.items():
            nested_key = f"{prefix}{separator}{key}" if prefix else str(key)
            if isinstance(value, Mapping):
                flat.update(_flatten(value, separator, nested_key))
            else:
                flat[nested_key] = value
    else:
        flat[prefix] = obj
    return flat


class JsonTransformHandler(JobHandler):
    job_type = "json_transform"
    payload_model = JsonTransformPayload

    def run(self, payload: Mapping[str, Any], ctx: JobContext) -> dict[str, Any]:
        parsed = self.validate(payload)
        document = copy.deepcopy(dict(parsed.input))
        applied = 0
        for operation in parsed.operations:
            if isinstance(operation, PickOperation):
                picked: dict[str, Any] = {}
                for path in operation.paths:
                    value = _get(document, path)
                    if value is not _MISSING:
                        _set(picked, path, copy.deepcopy(value))
                        applied += 1
                document = picked
            elif isinstance(operation, RemoveOperation):
                for path in operation.paths:
                    if _delete(document, path):
                        applied += 1
            elif isinstance(operation, RenameOperation):
                value = _get(document, operation.from_path)
                if value is _MISSING:
                    raise ValidationError(f"rename source path not found: {operation.from_path!r}")
                _delete(document, operation.from_path)
                _set(document, operation.to, copy.deepcopy(value))
                applied += 1
            elif isinstance(operation, FlattenOperation):
                document = _flatten(document, operation.separator)
                applied = len(document)
        return {"output": document, "applied": applied, "operations": len(parsed.operations)}
