"""Job type handler implementations (SPEC §9 catalog).

Each handler module is independently testable and declares its payload
model, validation semantics, and result shape.
"""

from .archive_inspection import ArchiveInspectionHandler
from .base import (
    CancelState,
    JobCancelled,
    JobContext,
    JobHandler,
    TransientError,
    ValidationError,
    classify_error,
)
from .cpu_benchmark import CpuBenchmarkHandler
from .csv_analysis import CsvAnalysisHandler
from .hash_sha256 import HashSha256Handler
from .image_resize import ImageResizeHandler
from .json_transform import JsonTransformHandler
from .text_statistics import TextStatisticsHandler

__all__ = [
    "BUILTIN_HANDLERS",
    "ArchiveInspectionHandler",
    "CancelState",
    "CpuBenchmarkHandler",
    "CsvAnalysisHandler",
    "HashSha256Handler",
    "ImageResizeHandler",
    "JobCancelled",
    "JobContext",
    "JobHandler",
    "JsonTransformHandler",
    "TextStatisticsHandler",
    "TransientError",
    "ValidationError",
    "classify_error",
]
