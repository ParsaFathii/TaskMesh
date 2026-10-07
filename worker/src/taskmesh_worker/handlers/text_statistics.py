"""text_statistics handler: word/character/line statistics for plain text.

Definitions (documented, deterministic):

- ``characters`` — Unicode code points; ``charactersNoSpaces`` excludes all
  whitespace.
- ``words`` — whitespace tokens (``str.split``); punctuation attached to a
  token ("jobs.", "(the)") is trimmed for frequency counting only, so
  "Jobs" and "jobs." count as the same word.
- ``lines`` — ``splitlines`` count; ``paragraphs`` — non-empty blocks split
  by blank lines.
- ``avgWordLength`` — mean word length (``statistics.fmean``), 0.0 when
  empty; ``readingTimeSeconds`` — words at 200 words/minute.
- ``topWords`` — 20 most frequent words, ties broken alphabetically;
  case-insensitive unless ``caseSensitive`` is true.
"""

from __future__ import annotations

import re
import statistics
import string
from collections import Counter
from collections.abc import Mapping
from typing import Any

from ..models import TextStatisticsPayload
from .base import JobContext, JobHandler

WORDS_PER_MINUTE = 200
TOP_WORDS_LIMIT = 20
_PUNCT = string.punctuation
_PARAGRAPH_SPLIT = re.compile(r"\n\s*\n")


class TextStatisticsHandler(JobHandler):
    job_type = "text_statistics"
    payload_model = TextStatisticsPayload

    def run(self, payload: Mapping[str, Any], ctx: JobContext) -> dict[str, Any]:
        parsed = self.validate(payload)
        text = parsed.text
        words = text.split()
        tokens = [w.strip(_PUNCT) for w in words]
        tokens = [t for t in tokens if t]
        counted = tokens if parsed.caseSensitive else [t.lower() for t in tokens]
        counts = Counter(counted)
        top_words = [
            {"word": word, "count": count}
            for word, count in sorted(counts.items(), key=lambda item: (-item[1], item[0]))[
                :TOP_WORDS_LIMIT
            ]
        ]
        return {
            "characters": len(text),
            "charactersNoSpaces": sum(1 for c in text if not c.isspace()),
            "words": len(words),
            "uniqueWords": len(counts),
            "lines": len(text.splitlines()),
            "paragraphs": len([block for block in _PARAGRAPH_SPLIT.split(text) if block.strip()]),
            "avgWordLength": round(statistics.fmean(map(len, words)), 6) if words else 0.0,
            "readingTimeSeconds": (len(words) * 60) / WORDS_PER_MINUTE,
            "topWords": top_words,
        }
