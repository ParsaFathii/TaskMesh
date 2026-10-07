"""Unit tests: csv_analysis handler (no database)."""

from __future__ import annotations

import pytest

from taskmesh_worker.handlers import ValidationError
from taskmesh_worker.handlers.csv_analysis import CsvAnalysisHandler


@pytest.fixture()
def handler() -> CsvAnalysisHandler:
    return CsvAnalysisHandler()


class TestHappyPath:
    def test_basic_inference(self, handler, job_ctx):
        result = handler.run(
            {
                "csv": "name,age,score\nalice,30,1.5\nbob,,2.5\nalice,30,3.5",
                "hasHeader": True,
            },
            job_ctx,
        )
        assert result["rows"] == 3
        assert result["delimiterUsed"] == ","
        assert result["parseErrors"] == []
        name, age, score = result["columns"]
        assert name == {
            "name": "name",
            "type": "text",
            "nonNull": 3,
            "missing": 0,
            "unique": 2,
        }
        assert age == {
            "name": "age",
            "type": "int",
            "nonNull": 2,
            "missing": 1,
            "unique": 1,
            "min": 30,
            "max": 30,
            "mean": 30.0,
        }
        assert score == {
            "name": "score",
            "type": "float",
            "nonNull": 3,
            "missing": 0,
            "unique": 3,
            "min": 1.5,
            "max": 3.5,
            "mean": 2.5,
        }

    def test_explicit_delimiters(self, handler, job_ctx):
        for delimiter in (";", "\t"):
            result = handler.run(
                {"csv": f"a{delimiter}b\n1{delimiter}2", "delimiter": delimiter},
                job_ctx,
            )
            assert result["delimiterUsed"] == delimiter
            assert result["rows"] == 1
            assert result["columns"][0]["name"] == "a"

    def test_no_header_generates_column_names(self, handler, job_ctx):
        result = handler.run({"csv": "1,2\n3,4", "hasHeader": False}, job_ctx)
        assert [c["name"] for c in result["columns"]] == ["column_1", "column_2"]
        assert result["rows"] == 2

    def test_field_count_mismatch_is_parse_error(self, handler, job_ctx):
        result = handler.run({"csv": "a,b\n1,2\n3\n"}, job_ctx)
        assert result["rows"] == 1
        assert result["parseErrors"] == [{"row": 3, "message": "expected 2 fields, got 1"}]

    def test_blank_lines_ignored(self, handler, job_ctx):
        result = handler.run({"csv": "a\n1\n\n2"}, job_ctx)
        assert result["rows"] == 2
        assert result["parseErrors"] == []

    def test_empty_csv(self, handler, job_ctx):
        result = handler.run({"csv": ""}, job_ctx)
        assert result == {
            "rows": 0,
            "columns": [],
            "delimiterUsed": ",",
            "parseErrors": [],
        }

    def test_header_only(self, handler, job_ctx):
        result = handler.run({"csv": "a,b"}, job_ctx)
        assert result["rows"] == 0
        assert len(result["columns"]) == 2
        assert all(c["type"] == "text" and c["missing"] == 0 for c in result["columns"])

    def test_negative_and_scientific_numbers(self, handler, job_ctx):
        result = handler.run({"csv": "v\n-3\n2.5e1\n"}, job_ctx)
        (column,) = result["columns"]
        assert column["type"] == "float"
        assert column["min"] == -3.0
        assert column["max"] == 25.0


class TestValidation:
    def test_missing_csv(self, handler, job_ctx):
        with pytest.raises(ValidationError, match="csv"):
            handler.run({}, job_ctx)

    def test_oversized_csv(self, handler, job_ctx):
        with pytest.raises(ValidationError, match="exceeds"):
            handler.run({"csv": "x" * (2 * 1024 * 1024 + 1)}, job_ctx)

    def test_bad_delimiter(self, handler, job_ctx):
        with pytest.raises(ValidationError):
            handler.run({"csv": "a,b", "delimiter": "|"}, job_ctx)

    def test_extra_field_forbidden(self, handler, job_ctx):
        with pytest.raises(ValidationError):
            handler.run({"csv": "a", "unexpected": 1}, job_ctx)

    def test_has_header_wrong_type(self, handler, job_ctx):
        with pytest.raises(ValidationError):
            handler.run({"csv": "a", "hasHeader": "maybe"}, job_ctx)
