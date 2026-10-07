"""archive_inspection handler: zip catalog via stdlib ``zipfile``.

Reports entry metadata (name/size/compressedSize/isDir/modified ISO-8601),
totals, and the overall compression ratio (uncompressed / compressed).
The entry list is capped at ``maxEntries`` (default 500) with a
``truncated`` flag; totals always cover the entire archive.
"""

from __future__ import annotations

import base64
import binascii
import io
import zipfile
from collections.abc import Mapping
from typing import Any

from ..models import ArchiveInspectionPayload
from .base import JobContext, JobHandler, ValidationError

DEFAULT_MAX_ENTRIES = 500


class ArchiveInspectionHandler(JobHandler):
    job_type = "archive_inspection"
    payload_model = ArchiveInspectionPayload

    def run(self, payload: Mapping[str, Any], ctx: JobContext) -> dict[str, Any]:
        parsed = self.validate(payload)
        try:
            raw = base64.b64decode(parsed.archiveBase64, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise ValidationError(f"archiveBase64 is not valid base64: {exc}") from exc

        try:
            with zipfile.ZipFile(io.BytesIO(raw)) as archive:
                infos = archive.infolist()
        except (zipfile.BadZipFile, zipfile.LargeZipFile, OSError) as exc:
            raise ValidationError(f"archiveBase64 is not a valid zip archive: {exc}") from exc

        entries = [
            {
                "name": info.filename,
                "size": info.file_size,
                "compressedSize": info.compress_size,
                "isDir": info.is_dir(),
                "modified": _iso_modified(info.date_time),
            }
            for info in infos[: parsed.maxEntries]
        ]
        total_uncompressed = sum(info.file_size for info in infos)
        total_compressed = sum(info.compress_size for info in infos)
        ratio = round(total_uncompressed / total_compressed, 4) if total_compressed > 0 else 0.0
        return {
            "format": "zip",
            "totalEntries": len(infos),
            "totalUncompressedBytes": total_uncompressed,
            "compressionRatio": ratio,
            "entries": entries,
            "truncated": len(infos) > parsed.maxEntries,
        }


def _iso_modified(date_time: tuple[int, int, int, int, int, int]) -> str:
    year, month, day, hour, minute, second = date_time
    return f"{year:04d}-{month:02d}-{day:02d}T{hour:02d}:{minute:02d}:{second:02d}"
