"""csv_analysis handler: real CSV parsing and per-column statistics.

Uses stdlib ``csv`` and ``statistics``. Column types are inferred per column:
a column is ``int`` when every present value matches an integer literal,
``float`` when every present value matches a numeric literal (at least one
float), otherwise ``text``. Empty/whitespace-only cells count as missing.

Rows whose field count differs from the header row (or the first row when
``hasHeader`` is false) are recorded as parse errors and excluded from
statistics. Fully blank rows are ignored.
"""

from __future__ import annotations

import csv
import io
import re
import statistics
from collections.abc import Mapping
from typing import Any

from ..models import CsvAnalysisPayload
from .base import JobContext, JobHandler

_INT_RE = re.compile(r"^[+-]?\d+$")
_FLOAT_RE = re.compile(r"^[+-]?(\d+\.\d*|\.\d+|\d+)([eE][+-]?\d+)?$")
MAX_PARSE_ERRORS = 100


class CsvAnalysisHandler(JobHandler):
    job_type = "csv_analysis"
    payload_model = CsvAnalysisPayload

    def run(self, payload: Mapping[str, Any], ctx: JobContext) -> dict[str, Any]:
        parsed = self.validate(payload)
        delimiter = parsed.delimiter or ","
        rows, parse_errors = self._parse(parsed.csv, delimiter)

        if parsed.hasHeader and rows:
            header = rows[0]
            data = rows[1:]
        else:
            width = len(rows[0]) if rows else 0
            header = [f"column_{i + 1}" for i in range(width)]
            data = rows

        columns = [self._column_stats(header[i], i, data) for i in range(len(header))]
        return {
            "rows": len(data),
            "columns": columns,
            "delimiterUsed": delimiter,
            "parseErrors": parse_errors,
        }

    def _parse(self, text: str, delimiter: str) -> tuple[list[list[str]], list[dict[str, Any]]]:
        reader = csv.reader(io.StringIO(text), delimiter=delimiter, strict=True)
        rows: list[list[str]] = []
        errors: list[dict[str, Any]] = []
        expected_width: int | None = None
        row_number = 0
        try:
            for fields in reader:
                row_number += 1
                if not any(field.strip() for field in fields):
                    continue  # blank line: ignored, not an error
                if expected_width is None:
                    expected_width = len(fields)
                elif len(fields) != expected_width:
                    if len(errors) < MAX_PARSE_ERRORS:
                        errors.append(
                            {
                                "row": row_number,
                                "message": (f"expected {expected_width} fields, got {len(fields)}"),
                            }
                        )
                    continue
                rows.append(fields)
        except csv.Error as exc:
            errors.append({"row": row_number + 1, "message": f"csv parse error: {exc}"})
        return rows, errors

    def _column_stats(self, name: str, index: int, data: list[list[str]]) -> dict[str, Any]:
        present: list[str] = []
        missing = 0
        for row in data:
            raw = row[index]
            if raw.strip() == "":
                missing += 1
            else:
                present.append(raw)

        all_int = bool(present) and all(_INT_RE.match(v.strip()) for v in present)
        all_float = bool(present) and all(_FLOAT_RE.match(v.strip()) for v in present)

        stats: dict[str, Any] = {
            "name": name,
            "type": "int" if all_int else ("float" if all_float else "text"),
            "nonNull": len(present),
            "missing": missing,
        }

        if all_int:
            typed: list[Any] = [int(v.strip()) for v in present]
        elif all_float:
            typed = [float(v.strip()) for v in present]
        else:
            typed = list(present)

        stats["unique"] = len(set(typed))
        if all_int or all_float:
            stats["min"] = min(typed)
            stats["max"] = max(typed)
            stats["mean"] = round(statistics.fmean(typed), 6)
        return stats
