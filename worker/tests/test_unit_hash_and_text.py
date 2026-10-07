"""Unit tests: hash_sha256, text_statistics handlers (no database)."""

from __future__ import annotations

import hashlib

import pytest

from taskmesh_worker.handlers import ValidationError
from taskmesh_worker.handlers.hash_sha256 import HashSha256Handler
from taskmesh_worker.handlers.text_statistics import TextStatisticsHandler

HELLO_SHA256 = hashlib.sha256(b"hello").hexdigest()


@pytest.fixture()
def hash_handler() -> HashSha256Handler:
    return HashSha256Handler()


@pytest.fixture()
def text_handler() -> TextStatisticsHandler:
    return TextStatisticsHandler()


class TestHashSha256:
    def test_text_source(self, hash_handler, job_ctx):
        result = hash_handler.run({"text": "hello"}, job_ctx)
        assert result == {"sha256": HELLO_SHA256, "bytes": 5, "source": "text"}

    def test_base64_source(self, hash_handler, job_ctx):
        result = hash_handler.run({"contentBase64": "aGVsbG8="}, job_ctx)
        assert result == {"sha256": HELLO_SHA256, "bytes": 5, "source": "base64"}

    def test_unicode_text_counts_utf8_bytes(self, hash_handler, job_ctx):
        text = "héllo"  # 5 code points, 6 UTF-8 bytes
        result = hash_handler.run({"text": text}, job_ctx)
        assert result["bytes"] == 6
        assert result["sha256"] == hashlib.sha256(text.encode()).hexdigest()

    def test_both_sources_rejected(self, hash_handler, job_ctx):
        with pytest.raises(ValidationError, match="exactly one"):
            hash_handler.run({"text": "a", "contentBase64": "aGk="}, job_ctx)

    def test_neither_source_rejected(self, hash_handler, job_ctx):
        with pytest.raises(ValidationError, match="exactly one"):
            hash_handler.run({}, job_ctx)

    def test_invalid_base64_rejected(self, hash_handler, job_ctx):
        with pytest.raises(ValidationError, match="base64"):
            hash_handler.run({"contentBase64": "not valid base64!"}, job_ctx)

    def test_extra_field_rejected(self, hash_handler, job_ctx):
        with pytest.raises(ValidationError):
            hash_handler.run({"text": "a", "algorithm": "md5"}, job_ctx)


class TestTextStatistics:
    def test_exact_result(self, text_handler, job_ctx):
        result = text_handler.run({"text": "Hello world\n\nHello again"}, job_ctx)
        assert result == {
            "characters": 24,
            "charactersNoSpaces": 20,
            "words": 4,
            "uniqueWords": 3,
            "lines": 3,
            "paragraphs": 2,
            "avgWordLength": 5.0,
            "readingTimeSeconds": 1.2,
            "topWords": [
                {"word": "hello", "count": 2},
                {"word": "again", "count": 1},
                {"word": "world", "count": 1},
            ],
        }

    def test_case_sensitive_top_words(self, text_handler, job_ctx):
        result = text_handler.run({"text": "The the THE", "caseSensitive": True}, job_ctx)
        assert result["uniqueWords"] == 3
        assert result["topWords"] == [
            {"word": "THE", "count": 1},
            {"word": "The", "count": 1},
            {"word": "the", "count": 1},
        ]

    def test_case_insensitive_default(self, text_handler, job_ctx):
        result = text_handler.run({"text": "The the THE"}, job_ctx)
        assert result["uniqueWords"] == 1
        assert result["topWords"] == [{"word": "the", "count": 3}]

    def test_empty_text(self, text_handler, job_ctx):
        result = text_handler.run({"text": ""}, job_ctx)
        assert result["words"] == 0
        assert result["avgWordLength"] == 0.0
        assert result["readingTimeSeconds"] == 0
        assert result["topWords"] == []
        assert result["paragraphs"] == 0

    def test_top_words_capped_at_20(self, text_handler, job_ctx):
        words = " ".join(f"w{i}" for i in range(30))
        result = text_handler.run({"text": words}, job_ctx)
        assert len(result["topWords"]) == 20

    def test_punctuation_trimmed_for_frequency(self, text_handler, job_ctx):
        result = text_handler.run({"text": "Jobs run. Jobs, jobs!"}, job_ctx)
        assert result["words"] == 4
        assert result["uniqueWords"] == 2
        assert result["topWords"][0] == {"word": "jobs", "count": 3}

    def test_reading_time_200wpm(self, text_handler, job_ctx):
        result = text_handler.run({"text": " ".join(["x"] * 400)}, job_ctx)
        assert result["readingTimeSeconds"] == 120

    def test_missing_text_rejected(self, text_handler, job_ctx):
        with pytest.raises(ValidationError):
            text_handler.run({}, job_ctx)

    def test_oversized_text_rejected(self, text_handler, job_ctx):
        with pytest.raises(ValidationError, match="exceeds"):
            text_handler.run({"text": "x" * (2 * 1024 * 1024 + 1)}, job_ctx)
