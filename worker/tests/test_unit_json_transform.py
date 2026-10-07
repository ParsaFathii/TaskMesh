"""Unit tests: json_transform handler (no database)."""

from __future__ import annotations

import pytest

from taskmesh_worker.handlers import ValidationError
from taskmesh_worker.handlers.json_transform import JsonTransformHandler


@pytest.fixture()
def handler() -> JsonTransformHandler:
    return JsonTransformHandler()


class TestOperations:
    def test_pick_existing_and_missing_paths(self, handler, job_ctx):
        result = handler.run(
            {
                "input": {"a": {"b": 1}, "c": 2},
                "operations": [{"op": "pick", "paths": ["a.b", "zz"]}],
            },
            job_ctx,
        )
        assert result == {"output": {"a": {"b": 1}}, "applied": 1, "operations": 1}

    def test_pick_missing_path_is_noop(self, handler, job_ctx):
        result = handler.run(
            {"input": {"a": 1}, "operations": [{"op": "pick", "paths": ["zz"]}]},
            job_ctx,
        )
        assert result == {"output": {}, "applied": 0, "operations": 1}

    def test_remove(self, handler, job_ctx):
        result = handler.run(
            {
                "input": {"a": {"b": 1}, "c": 2},
                "operations": [{"op": "remove", "paths": ["a.b", "zz"]}],
            },
            job_ctx,
        )
        assert result == {
            "output": {"a": {}, "c": 2},
            "applied": 1,
            "operations": 1,
        }

    def test_remove_list_index(self, handler, job_ctx):
        result = handler.run(
            {"input": {"items": [1, 2, 3]}, "operations": [{"op": "remove", "paths": ["items.0"]}]},
            job_ctx,
        )
        assert result["output"] == {"items": [2, 3]}
        assert result["applied"] == 1

    def test_rename_moves_nested_value(self, handler, job_ctx):
        result = handler.run(
            {
                "input": {"a": {"b": 1}, "c": 2},
                "operations": [{"op": "rename", "from": "a.b", "to": "x"}],
            },
            job_ctx,
        )
        assert result["output"] == {"a": {}, "x": 1, "c": 2}
        assert result["applied"] == 1

    def test_rename_missing_source_raises_validation_error(self, handler, job_ctx):
        with pytest.raises(ValidationError, match="not found"):
            handler.run(
                {"input": {"a": 1}, "operations": [{"op": "rename", "from": "zz", "to": "y"}]},
                job_ctx,
            )

    def test_rename_to_nested_destination_creates_parents(self, handler, job_ctx):
        result = handler.run(
            {
                "input": {"a": 1},
                "operations": [{"op": "rename", "from": "a", "to": "deep.nest.v"}],
            },
            job_ctx,
        )
        assert result["output"] == {"deep": {"nest": {"v": 1}}}

    def test_flatten_default_separator(self, handler, job_ctx):
        result = handler.run(
            {
                "input": {"a": {"b": {"c": 1}}, "d": [1, 2]},
                "operations": [{"op": "flatten", "separator": "."}],
            },
            job_ctx,
        )
        assert result["output"] == {"a.b.c": 1, "d": [1, 2]}
        assert result["applied"] == 2  # two leaf keys produced

    def test_flatten_custom_separator(self, handler, job_ctx):
        result = handler.run(
            {
                "input": {"a": {"b": 1}},
                "operations": [{"op": "flatten", "separator": "__"}],
            },
            job_ctx,
        )
        assert result["output"] == {"a__b": 1}

    def test_flatten_defaults_separator_when_omitted(self, handler, job_ctx):
        result = handler.run(
            {"input": {"a": {"b": 1}}, "operations": [{"op": "flatten"}]},
            job_ctx,
        )
        assert result["output"] == {"a.b": 1}

    def test_operations_run_sequentially(self, handler, job_ctx):
        result = handler.run(
            {
                "input": {"keep": 1, "drop": 2, "ren": 3},
                "operations": [
                    {"op": "remove", "paths": ["drop"]},
                    {"op": "rename", "from": "ren", "to": "renamed"},
                ],
            },
            job_ctx,
        )
        assert result == {
            "output": {"keep": 1, "renamed": 3},
            "applied": 2,
            "operations": 2,
        }


class TestValidation:
    def test_empty_operations_rejected(self, handler, job_ctx):
        with pytest.raises(ValidationError):
            handler.run({"input": {}, "operations": []}, job_ctx)

    def test_unknown_op_rejected(self, handler, job_ctx):
        with pytest.raises(ValidationError):
            handler.run(
                {"input": {}, "operations": [{"op": "explode", "paths": ["a"]}]},
                job_ctx,
            )

    def test_input_must_be_object(self, handler, job_ctx):
        with pytest.raises(ValidationError):
            handler.run({"input": [1, 2], "operations": [{"op": "flatten"}]}, job_ctx)

    def test_empty_path_rejected(self, handler, job_ctx):
        with pytest.raises(ValidationError):
            handler.run(
                {"input": {}, "operations": [{"op": "pick", "paths": [""]}]},
                job_ctx,
            )

    def test_extra_field_on_operation_rejected(self, handler, job_ctx):
        with pytest.raises(ValidationError):
            handler.run(
                {
                    "input": {"a": 1},
                    "operations": [{"op": "rename", "from": "a", "to": "b", "extra": 1}],
                },
                job_ctx,
            )
