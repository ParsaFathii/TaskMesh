"""TaskMesh worker runtime.

Claims typed jobs from a PostgreSQL queue (SKIP LOCKED), executes them in
isolated handler threads with hard timeouts, and persists results inline or
on disk. See docs/SPEC.md for the binding contract.
"""

__version__ = "0.1.0"
