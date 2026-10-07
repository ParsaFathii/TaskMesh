"""Unit tests: image_resize handler (no database, real Pillow round-trip)."""

from __future__ import annotations

import base64
import hashlib
import io
import uuid as uuidlib

import pytest
from PIL import Image

from taskmesh_worker.handlers import ValidationError
from taskmesh_worker.handlers.image_resize import ImageResizeHandler


@pytest.fixture()
def handler() -> ImageResizeHandler:
    return ImageResizeHandler()


def png_b64(size: tuple[int, int], mode: str = "RGB", color: tuple = (10, 20, 30)) -> str:
    image = Image.new(mode, size, color)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode("ascii")


class TestHappyPath:
    def test_resize_maintain_aspect(self, handler, job_ctx):
        result = handler.run(
            {
                "imageBase64": png_b64((100, 50)),
                "width": 50,
                "height": 50,
                "maintainAspect": True,
            },
            job_ctx,
        )
        assert result["width"] == 50
        assert result["height"] == 25
        assert result["format"] == "png"
        assert result["bytes"] > 0
        assert len(result["sha256"]) == 64

    def test_resize_exact_dimensions(self, handler, job_ctx):
        result = handler.run(
            {
                "imageBase64": png_b64((100, 50)),
                "width": 30,
                "height": 20,
                "maintainAspect": False,
            },
            job_ctx,
        )
        assert (result["width"], result["height"]) == (30, 20)

    def test_bytes_go_to_file_storage(self, handler, job_ctx):
        result = handler.run(
            {"imageBase64": png_b64((64, 64)), "width": 32, "height": 32},
            job_ctx,
        )
        artifact = job_ctx.artifact
        assert artifact is not None
        assert artifact.kind == "file"
        assert artifact.size_bytes == result["bytes"]
        assert artifact.sha256 == result["sha256"]
        stored = job_ctx.storage.read_file(artifact.file_path or "")
        assert hashlib.sha256(stored).hexdigest() == result["sha256"]
        with Image.open(io.BytesIO(stored)) as reloaded:
            assert reloaded.size == (32, 32)
            assert reloaded.format == "PNG"

    def test_jpeg_output_converts_alpha(self, handler, job_ctx):
        result = handler.run(
            {
                "imageBase64": png_b64((40, 40), mode="RGBA"),
                "width": 20,
                "height": 20,
                "format": "jpeg",
            },
            job_ctx,
        )
        assert result["format"] == "jpeg"
        stored = job_ctx.storage.read_file(job_ctx.artifact.file_path or "")
        with Image.open(io.BytesIO(stored)) as reloaded:
            assert reloaded.format == "JPEG"
            assert reloaded.mode == "RGB"

    def test_upscale_within_requested_box(self, handler, job_ctx):
        result = handler.run(
            {"imageBase64": png_b64((10, 10)), "width": 100, "height": 100},
            job_ctx,
        )
        assert (result["width"], result["height"]) == (100, 100)


class TestValidation:
    def test_width_out_of_bounds(self, handler, job_ctx):
        for width in (0, -1, 10001):
            with pytest.raises(ValidationError):
                handler.run(
                    {"imageBase64": png_b64((10, 10)), "width": width, "height": 10},
                    job_ctx,
                )

    def test_height_out_of_bounds(self, handler, job_ctx):
        with pytest.raises(ValidationError):
            handler.run(
                {"imageBase64": png_b64((10, 10)), "width": 10, "height": 20000},
                job_ctx,
            )

    def test_missing_dimensions(self, handler, job_ctx):
        with pytest.raises(ValidationError):
            handler.run({"imageBase64": png_b64((10, 10))}, job_ctx)

    def test_invalid_base64(self, handler, job_ctx):
        with pytest.raises(ValidationError, match="base64"):
            handler.run(
                {"imageBase64": "!!!not-base64!!!", "width": 10, "height": 10},
                job_ctx,
            )

    def test_not_an_image(self, handler, job_ctx):
        with pytest.raises(ValidationError, match="image"):
            handler.run(
                {
                    "imageBase64": base64.b64encode(b"hello world, not an image").decode(),
                    "width": 10,
                    "height": 10,
                },
                job_ctx,
            )

    def test_bad_format_value(self, handler, job_ctx):
        with pytest.raises(ValidationError):
            handler.run(
                {
                    "imageBase64": png_b64((10, 10)),
                    "width": 5,
                    "height": 5,
                    "format": "gif",
                },
                job_ctx,
            )

    def test_extra_field_forbidden(self, handler, job_ctx):
        with pytest.raises(ValidationError):
            handler.run(
                {
                    "imageBase64": png_b64((10, 10)),
                    "width": 5,
                    "height": 5,
                    "rotation": 90,
                },
                job_ctx,
            )

    def test_artifact_attached_once(self, handler, job_ctx):
        handler.run({"imageBase64": png_b64((10, 10)), "width": 5, "height": 5}, job_ctx)
        with pytest.raises(ValidationError):
            job_ctx.attach_artifact(job_ctx.artifact)  # type: ignore[arg-type]


def test_artifact_file_named_by_job_id(handler, storage):
    from taskmesh_worker.handlers import CancelState, JobContext

    job_id = str(uuidlib.uuid4())
    ctx = JobContext(job_id=job_id, storage=storage, cancel=CancelState())
    handler.run({"imageBase64": png_b64((10, 10)), "width": 5, "height": 5}, ctx)
    assert ctx.artifact is not None
    assert ctx.artifact.file_path == f"results/{job_id}.bin"
    assert (storage.root / "results" / f"{job_id}.bin").exists()
