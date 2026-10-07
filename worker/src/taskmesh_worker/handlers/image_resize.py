"""image_resize handler: real Pillow resizing with LANCZOS resampling.

The resized image bytes always become the job's FILE result (stored via the
execution context); the returned dict carries the metadata promised by
SPEC §9: ``{width, height, format, bytes, sha256}``.

Aspect handling: with ``maintainAspect`` the image is scaled to fit inside
the ``(width, height)`` box (never upscaled beyond the box); otherwise the
exact requested dimensions are used. Output format defaults to the source
format when it is already png/jpeg, otherwise png.
"""

from __future__ import annotations

import base64
import binascii
import io
from collections.abc import Mapping
from typing import Any

from PIL import Image

from ..models import ImageResizePayload
from .base import JobContext, JobHandler, ValidationError

MAX_DECODED_IMAGE_BYTES = 64 * 1024 * 1024


class ImageResizeHandler(JobHandler):
    job_type = "image_resize"
    payload_model = ImageResizePayload

    def run(self, payload: Mapping[str, Any], ctx: JobContext) -> dict[str, Any]:
        parsed = self.validate(payload)
        raw = self._decode(parsed.imageBase64)
        image = self._open(raw)
        source_format = (image.format or "PNG").lower()

        target_format = parsed.format or (
            source_format if source_format in ("png", "jpeg") else "png"
        )

        if parsed.maintainAspect:
            scale = min(
                parsed.width / image.width,
                parsed.height / image.height,
            )
            new_size = (
                max(1, round(image.width * scale)),
                max(1, round(image.height * scale)),
            )
        else:
            new_size = (parsed.width, parsed.height)

        image.load()
        resized = image.resize(new_size, Image.Resampling.LANCZOS)
        if target_format == "jpeg" and resized.mode not in ("RGB", "L"):
            resized = resized.convert("RGB")

        buffer = io.BytesIO()
        save_format = "JPEG" if target_format == "jpeg" else "PNG"
        resized.save(buffer, format=save_format)
        data = buffer.getvalue()

        record = ctx.storage.store_artifact(ctx.job_id, data)
        ctx.attach_artifact(record)
        return {
            "width": resized.width,
            "height": resized.height,
            "format": target_format,
            "bytes": len(data),
            "sha256": record.sha256,
        }

    def _decode(self, image_b64: str) -> bytes:
        try:
            raw = base64.b64decode(image_b64, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise ValidationError(f"imageBase64 is not valid base64: {exc}") from exc
        if not raw:
            raise ValidationError("imageBase64 decodes to empty content")
        if len(raw) > MAX_DECODED_IMAGE_BYTES:
            raise ValidationError(f"decoded image exceeds {MAX_DECODED_IMAGE_BYTES} bytes")
        return raw

    def _open(self, raw: bytes) -> Image.Image:
        try:
            with Image.open(io.BytesIO(raw)) as probe:
                probe.verify()
            image = Image.open(io.BytesIO(raw))
        except Exception as exc:  # Pillow raises a zoo of exception types
            raise ValidationError(f"imageBase64 is not a decodable image: {exc}") from exc
        if image.width < 1 or image.height < 1:
            raise ValidationError("image has invalid dimensions")
        return image
