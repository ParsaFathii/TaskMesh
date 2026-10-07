"""Strict payload and result models for the job type catalog (SPEC §9).

Every payload model forbids extra fields and enforces the documented bounds;
camelCase keys in payloads are accepted via aliases. Handlers build result
dicts whose shapes are validated here as well (used by tests to guarantee
the catalog contract).
"""

from __future__ import annotations

import binascii
from typing import Annotated, Any, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    field_validator,
    model_validator,
)

MAX_INLINE_BYTES = 32 * 1024
MAX_TEXT_BYTES = 2 * 1024 * 1024  # csv_analysis.csv, text_statistics.text
MAX_IMAGE_B64_BYTES = 20 * 1024 * 1024  # safety bound, mirrors archive cap
MAX_ARCHIVE_B64_BYTES = 20 * 1024 * 1024
MAX_HASH_B64_BYTES = 20 * 1024 * 1024
MAX_HASH_TEXT_BYTES = 20 * 1024 * 1024
MAX_OPERATIONS = 1000
MAX_PATHS_PER_OP = 100


def _as_bytes(value: str) -> int:
    return len(value.encode("utf-8"))


class StrictModel(BaseModel):
    """Base config: unknown fields are a validation error."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)


def _check_b64(value: str) -> str:
    """Require RFC 4648 base64: ascii, valid alphabet, correct padding."""
    try:
        raw = value.encode("ascii")
    except UnicodeEncodeError as exc:
        raise ValueError("invalid base64: non-ascii characters") from exc
    try:
        binascii.a2b_base64(raw, strict_mode=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError(f"invalid base64: {exc}") from exc
    return value


# --------------------------------------------------------------------------
# csv_analysis


class CsvAnalysisPayload(StrictModel):
    csv: str
    delimiter: Literal[",", ";", "\t"] | None = None
    hasHeader: bool = True

    @field_validator("csv")
    @classmethod
    def _csv_size(cls, v: str) -> str:
        if _as_bytes(v) > MAX_TEXT_BYTES:
            raise ValueError(f"csv exceeds {MAX_TEXT_BYTES} bytes")
        return v


class CsvColumn(StrictModel):
    name: str
    type: Literal["int", "float", "text"]
    nonNull: int = Field(ge=0)
    missing: int = Field(ge=0)
    unique: int = Field(ge=0)
    min: float | int | None = None
    max: float | int | None = None
    mean: float | None = None


class CsvParseError(StrictModel):
    row: int = Field(ge=1)
    message: str


class CsvAnalysisResult(StrictModel):
    rows: int = Field(ge=0)
    columns: list[CsvColumn]
    delimiterUsed: str
    parseErrors: list[CsvParseError]


# --------------------------------------------------------------------------
# json_transform


class PickOperation(StrictModel):
    op: Literal["pick"]
    paths: list[str] = Field(min_length=1, max_length=MAX_PATHS_PER_OP)

    @field_validator("paths")
    @classmethod
    def _paths_valid(cls, v: list[str]) -> list[str]:
        for path in v:
            if not path or len(path) > 256:
                raise ValueError(f"invalid path: {path!r}")
        return v


class RemoveOperation(StrictModel):
    op: Literal["remove"]
    paths: list[str] = Field(min_length=1, max_length=MAX_PATHS_PER_OP)

    @field_validator("paths")
    @classmethod
    def _paths_valid(cls, v: list[str]) -> list[str]:
        for path in v:
            if not path or len(path) > 256:
                raise ValueError(f"invalid path: {path!r}")
        return v


class RenameOperation(StrictModel):
    op: Literal["rename"]
    from_path: Annotated[str, Field(alias="from", min_length=1, max_length=256)]
    to: str = Field(min_length=1, max_length=256)


class FlattenOperation(StrictModel):
    op: Literal["flatten"]
    separator: str = Field(default=".", min_length=1, max_length=8)


Operation = Annotated[
    PickOperation | RemoveOperation | RenameOperation | FlattenOperation,
    Field(discriminator="op"),
]


class JsonTransformPayload(StrictModel):
    input: dict[str, Any]
    operations: list[Operation] = Field(min_length=1, max_length=MAX_OPERATIONS)


class JsonTransformResult(StrictModel):
    output: dict[str, Any]
    applied: int = Field(ge=0)
    operations: int = Field(ge=1)


# --------------------------------------------------------------------------
# image_resize


class ImageResizePayload(StrictModel):
    imageBase64: str
    width: int = Field(ge=1, le=10000)
    height: int = Field(ge=1, le=10000)
    maintainAspect: bool = True
    format: Literal["png", "jpeg"] | None = None

    @field_validator("imageBase64")
    @classmethod
    def _b64(cls, v: str) -> str:
        if len(v.encode("ascii", errors="ignore")) > MAX_IMAGE_B64_BYTES:
            raise ValueError(f"imageBase64 exceeds {MAX_IMAGE_B64_BYTES} bytes")
        return _check_b64(v)


class ImageResizeResult(StrictModel):
    width: int = Field(ge=1, le=10000)
    height: int = Field(ge=1, le=10000)
    format: Literal["png", "jpeg"]
    bytes: int = Field(ge=0)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


# --------------------------------------------------------------------------
# hash_sha256


class HashSha256Payload(StrictModel):
    contentBase64: str | None = None
    text: str | None = None

    @field_validator("contentBase64")
    @classmethod
    def _b64(cls, v: str | None) -> str | None:
        if v is None:
            return v
        if len(v.encode("ascii", errors="ignore")) > MAX_HASH_B64_BYTES:
            raise ValueError(f"contentBase64 exceeds {MAX_HASH_B64_BYTES} bytes")
        return _check_b64(v)

    @field_validator("text")
    @classmethod
    def _text_size(cls, v: str | None) -> str | None:
        if v is not None and _as_bytes(v) > MAX_HASH_TEXT_BYTES:
            raise ValueError(f"text exceeds {MAX_HASH_TEXT_BYTES} bytes")
        return v

    @model_validator(mode="after")
    def _exactly_one_source(self) -> HashSha256Payload:
        if (self.contentBase64 is None) == (self.text is None):
            raise ValueError("provide exactly one of contentBase64 or text")
        return self


class HashSha256Result(StrictModel):
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    bytes: int = Field(ge=0)
    source: Literal["base64", "text"]


# --------------------------------------------------------------------------
# text_statistics


class TextStatisticsPayload(StrictModel):
    text: str
    caseSensitive: bool = False

    @field_validator("text")
    @classmethod
    def _text_size(cls, v: str) -> str:
        if _as_bytes(v) > MAX_TEXT_BYTES:
            raise ValueError(f"text exceeds {MAX_TEXT_BYTES} bytes")
        return v


class TopWord(StrictModel):
    word: str
    count: int = Field(ge=1)


class TextStatisticsResult(StrictModel):
    characters: int = Field(ge=0)
    charactersNoSpaces: int = Field(ge=0)
    words: int = Field(ge=0)
    uniqueWords: int = Field(ge=0)
    lines: int = Field(ge=0)
    paragraphs: int = Field(ge=0)
    avgWordLength: float = Field(ge=0)
    readingTimeSeconds: float = Field(ge=0)
    topWords: list[TopWord] = Field(max_length=20)


# --------------------------------------------------------------------------
# archive_inspection


class ArchiveInspectionPayload(StrictModel):
    archiveBase64: str
    maxEntries: int = Field(default=500, ge=1, le=10000)

    @field_validator("archiveBase64")
    @classmethod
    def _b64(cls, v: str) -> str:
        if len(v.encode("ascii", errors="ignore")) > MAX_ARCHIVE_B64_BYTES:
            raise ValueError(f"archiveBase64 exceeds {MAX_ARCHIVE_B64_BYTES} bytes")
        return _check_b64(v)


class ArchiveEntry(StrictModel):
    name: str
    size: int = Field(ge=0)
    compressedSize: int = Field(ge=0)
    isDir: bool
    modified: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$")


class ArchiveInspectionResult(StrictModel):
    format: Literal["zip"]
    totalEntries: int = Field(ge=0)
    totalUncompressedBytes: int = Field(ge=0)
    compressionRatio: float = Field(ge=0)
    entries: list[ArchiveEntry]
    truncated: bool


# --------------------------------------------------------------------------
# cpu_benchmark


class CpuBenchmarkPayload(StrictModel):
    workload: Literal["primes", "matrix"]
    durationSeconds: int = Field(default=5, ge=1, le=30)


class CpuBenchmarkResult(StrictModel):
    workload: Literal["primes", "matrix"]
    operations: int = Field(ge=0)
    durationMs: int = Field(ge=0)
    opsPerSecond: float = Field(ge=0)
    threads: int = Field(ge=1)
