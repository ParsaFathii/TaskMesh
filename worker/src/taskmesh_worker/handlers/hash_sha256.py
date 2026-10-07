"""hash_sha256 handler: real SHA-256 over exactly one input source.

Payload must contain ``contentBase64`` (binary content, base64-encoded) or
``text`` — never both, never neither. Result: ``{sha256, bytes, source}``.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
from collections.abc import Mapping
from typing import Any

from ..models import HashSha256Payload
from .base import JobContext, JobHandler, ValidationError


class HashSha256Handler(JobHandler):
    job_type = "hash_sha256"
    payload_model = HashSha256Payload

    def run(self, payload: Mapping[str, Any], ctx: JobContext) -> dict[str, Any]:
        parsed = self.validate(payload)
        if parsed.contentBase64 is not None:
            try:
                data = base64.b64decode(parsed.contentBase64, validate=True)
            except (binascii.Error, ValueError) as exc:
                raise ValidationError(f"contentBase64 is not valid base64: {exc}") from exc
            source = "base64"
        else:
            assert parsed.text is not None  # model guarantees exactly one source
            data = parsed.text.encode("utf-8")
            source = "text"
        return {
            "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
            "source": source,
        }
