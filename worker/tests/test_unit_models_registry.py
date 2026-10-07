"""Unit tests: strict payload models, registry, retryability, backoff, config."""

from __future__ import annotations

import pytest
from pydantic import ValidationError as PydanticValidationError

from taskmesh_worker.config import WorkerConfig
from taskmesh_worker.handlers import (
    TransientError,
    ValidationError,
    classify_error,
)
from taskmesh_worker.models import (
    ArchiveInspectionPayload,
    CpuBenchmarkPayload,
    HashSha256Payload,
    ImageResizePayload,
    JsonTransformPayload,
    TextStatisticsPayload,
)
from taskmesh_worker.registry import ALL_JOB_TYPES, HandlerRegistry
from taskmesh_worker.runtime import backoff_seconds


class TestModelStrictness:
    def test_extra_fields_forbidden(self):
        with pytest.raises(PydanticValidationError):
            TextStatisticsPayload.model_validate({"text": "hi", "extra": 1})

    def test_unknown_operation_rejected(self):
        with pytest.raises(PydanticValidationError):
            JsonTransformPayload.model_validate(
                {"input": {}, "operations": [{"op": "explode", "paths": ["a"]}]}
            )

    def test_rename_accepts_from_alias(self):
        payload = JsonTransformPayload.model_validate(
            {"input": {"a": 1}, "operations": [{"op": "rename", "from": "a", "to": "b"}]}
        )
        assert payload.operations[0].to == "b"  # type: ignore[attr-defined]

    def test_hash_exactly_one_source(self):
        with pytest.raises(PydanticValidationError):
            HashSha256Payload.model_validate({"contentBase64": "aGk=", "text": "hi"})
        with pytest.raises(PydanticValidationError):
            HashSha256Payload.model_validate({})
        ok = HashSha256Payload.model_validate({"text": "hi"})
        assert ok.text == "hi"

    def test_image_dimension_bounds(self):
        base = {"imageBase64": "aGk="}
        with pytest.raises(PydanticValidationError):
            ImageResizePayload.model_validate({**base, "width": 0, "height": 10})
        with pytest.raises(PydanticValidationError):
            ImageResizePayload.model_validate({**base, "width": 10, "height": 10001})

    def test_cpu_duration_bounds(self):
        with pytest.raises(PydanticValidationError):
            CpuBenchmarkPayload.model_validate({"workload": "primes", "durationSeconds": 0})
        with pytest.raises(PydanticValidationError):
            CpuBenchmarkPayload.model_validate({"workload": "primes", "durationSeconds": 31})
        assert CpuBenchmarkPayload.model_validate({"workload": "primes"}).durationSeconds == 5

    def test_archive_bounds(self):
        with pytest.raises(PydanticValidationError):
            ArchiveInspectionPayload.model_validate({"archiveBase64": "aGk=", "maxEntries": 0})
        assert ArchiveInspectionPayload.model_validate({"archiveBase64": "aGk="}).maxEntries == 500

    def test_invalid_base64_alphabet_rejected(self):
        with pytest.raises(PydanticValidationError, match="base64"):
            ImageResizePayload.model_validate(
                {"imageBase64": "not valid!", "width": 5, "height": 5}
            )


class TestRegistry:
    def test_default_registry_has_all_seven_types(self):
        registry = HandlerRegistry.default()
        assert registry.job_types() == sorted(ALL_JOB_TYPES)
        assert len(ALL_JOB_TYPES) == 7

    def test_capability_filter(self):
        registry = HandlerRegistry.default(["csv_analysis", "hash_sha256"])
        assert registry.job_types() == ["csv_analysis", "hash_sha256"]

    def test_unknown_type_has_no_handler(self):
        assert HandlerRegistry.default().handler("nope") is None

    def test_duplicate_registration_rejected(self):
        from stub_handlers import BoomHandler

        registry = HandlerRegistry()
        registry.register(BoomHandler)
        with pytest.raises(ValueError, match="duplicate"):
            registry.register(BoomHandler)


class TestRetryabilityAndBackoff:
    def test_validation_error_is_non_retryable(self):
        assert classify_error(ValidationError("bad payload")) == "non_retryable"

    def test_transient_error_is_retryable(self):
        assert classify_error(TransientError("db hiccup")) == "retryable"

    def test_unexpected_errors_are_retryable(self):
        assert classify_error(RuntimeError("handler bug")) == "retryable"
        assert classify_error(KeyError("oops")) == "retryable"

    @pytest.mark.parametrize(
        ("retry_count", "expected"),
        [
            (0, 5),
            (1, 10),
            (2, 20),
            (3, 40),
            (4, 80),
            (5, 160),
            (6, 300),
            (7, 300),
            (20, 300),
        ],
    )
    def test_backoff_math(self, retry_count: int, expected: int):
        assert backoff_seconds(retry_count) == expected


class TestConfig:
    def test_defaults(self):
        config = WorkerConfig.from_env(env={})
        assert config.database_url == "postgresql://taskmesh@localhost:5433/taskmesh"
        assert config.capabilities == list(ALL_JOB_TYPES)
        assert config.heartbeat_interval_s == 10
        assert config.lease_margin_s == 15
        assert config.control_port == 9100
        assert str(config.storage_dir) == "data"
        assert config.worker_name  # derived from hostname+pid

    def test_env_overrides(self):
        config = WorkerConfig.from_env(
            env={
                "TASKMESH_DATABASE_URL": "postgresql://taskmesh@localhost:5433/other",
                "TASKMESH_CAPABILITIES": "hash_sha256,csv_analysis",
                "TASKMESH_HEARTBEAT_INTERVAL_S": "2",
                "TASKMESH_LEASE_MARGIN_S": "7",
                "TASKMESH_CONTROL_PORT": "9111",
                "TASKMESH_STORAGE_DIR": "/tmp/taskmesh-test-data",
                "TASKMESH_WORKER_NAME": "w1",
            }
        )
        assert config.database_url.endswith("/other")
        assert config.capabilities == ["hash_sha256", "csv_analysis"]
        assert config.heartbeat_interval_s == 2
        assert config.lease_margin_s == 7
        assert config.control_port == 9111
        assert config.worker_name == "w1"

    def test_unknown_capability_rejected(self):
        with pytest.raises(ValueError, match="unknown capabilities"):
            WorkerConfig.from_env(env={"TASKMESH_CAPABILITIES": "time_travel"})

    def test_bad_port_rejected(self):
        with pytest.raises(ValueError, match="CONTROL_PORT"):
            WorkerConfig(control_port=99999)

    def test_safe_view_strips_credentials(self):
        config = WorkerConfig(database_url="postgresql://user:secret@db.example.com:5433/taskmesh")
        view = config.safe_view()
        assert "secret" not in str(view)
        assert view["databaseUrl"] == "postgresql://db.example.com:5433/taskmesh"
