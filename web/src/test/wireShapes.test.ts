import { describe, expect, it } from 'vitest';
import { parseMetrics, sumQueueDepth, workersOnline } from '../lib/metrics';
import { parseJobTypes } from '../api/catalog';

/**
 * Wire-shape regression tests: these payloads are the verbatim JSON returned
 * by the live Java backend (probed 2026-10-06, v0.1.0).
 */

const LIVE_METRICS = {
  jobsByStatus: {
    QUEUED: 3, RUNNING: 1, SUCCEEDED: 7, FAILED: 2, CANCELLED: 0, RETRYING: 1, TIMED_OUT: 1,
  },
  queueDepthByPriority: { CRITICAL: 1, HIGH: 2, NORMAL: 1, LOW: 0 },
  workersByStatus: { STARTING: 0, IDLE: 2, BUSY: 1, DRAINING: 0, OFFLINE: 1, ERROR: 0 },
  succeededLast24h: { count: 7, avgDurationSeconds: 1.5, maxDurationSeconds: 4.25 },
  completedPerHourLast24h: 0.29,
};

describe('parseMetrics — live backend shape', () => {
  it('maps jobsByStatus / queueDepthByPriority / workersByStatus', () => {
    const m = parseMetrics(LIVE_METRICS);
    expect(m.jobsByStatus.SUCCEEDED).toBe(7);
    expect(m.jobsByStatus.QUEUED).toBe(3);
    expect(m.queueDepth).toEqual({ CRITICAL: 1, HIGH: 2, NORMAL: 1, LOW: 0 });
    expect(m.workersByStatus.IDLE).toBe(2);
    expect(m.workersByStatus.OFFLINE).toBe(1);
  });

  it('reads durations and the 24h count from the nested succeededLast24h record', () => {
    const m = parseMetrics(LIVE_METRICS);
    expect(m.succeededLast24h).toBe(7);
    expect(m.avgDurationMs).toBe(1500);
    expect(m.maxDurationMs).toBe(4250);
  });

  it('reads the scalar throughput (completedPerHourLast24h)', () => {
    const m = parseMetrics(LIVE_METRICS);
    expect(m.throughputPerHourScalar).toBeCloseTo(0.29);
    expect(m.throughputPerHour).toBeUndefined();
  });

  it('derives queue totals and online workers', () => {
    const m = parseMetrics(LIVE_METRICS);
    expect(sumQueueDepth(m)).toBe(4);
    expect(workersOnline(m)).toBe(3); // STARTING+IDLE+BUSY+DRAINING, excl. OFFLINE/ERROR
  });

  it('keeps everything undefined for an empty payload (no fabricated data)', () => {
    const m = parseMetrics({});
    expect(m.avgDurationMs).toBeUndefined();
    expect(m.succeededLast24h).toBeUndefined();
    expect(m.throughputPerHourScalar).toBeUndefined();
    expect(sumQueueDepth(m)).toBe(0);
  });

  it('accepts an hourly series when one is published', () => {
    const m = parseMetrics({ throughputPerHour: [0, 2, 5, 3] });
    expect(m.throughputPerHour).toEqual([0, 2, 5, 3]);
  });
});

const LIVE_JOB_TYPES = [
  {
    type: 'hash_sha256',
    description: 'Computes the SHA-256 digest of base64-encoded bytes or plain text.',
    payloadSchema: {
      type: 'object',
      properties: {
        contentBase64: { type: 'string', description: 'Base64-encoded bytes to hash' },
        text: { type: 'string', description: 'UTF-8 text to hash' },
      },
      required: [],
      'x-exactly-one-of': ['contentBase64', 'text'],
    },
    example: { text: 'The quick brown fox jumps over the lazy dog' },
  },
  {
    type: 'csv_analysis',
    description: 'Parses CSV content.',
    payloadSchema: {
      type: 'object',
      properties: {
        csv: { type: 'string', maxLength: 2000000, description: 'CSV content' },
        delimiter: { type: 'string', enum: [',', ';', '\\t'] },
        hasHeader: { type: 'boolean', description: 'Header row' },
      },
      required: ['csv'],
    },
    example: { csv: 'name,age\nAda,36\n', delimiter: ',', hasHeader: true },
  },
];

describe('parseJobTypes — live backend shape', () => {
  it('builds fields from payloadSchema properties + required', () => {
    const types = parseJobTypes(LIVE_JOB_TYPES);
    expect(types).toHaveLength(2);
    const csv = types.find((t) => t.type === 'csv_analysis')!;
    const csvField = csv.fields.find((f) => f.name === 'csv')!;
    expect(csvField.required).toBe(true);
    expect(csvField.maxLength).toBe(2_000_000);
    const delim = csv.fields.find((f) => f.name === 'delimiter')!;
    expect(delim.enumValues).toEqual([',', ';', '\\t']);
  });

  it('applies schema-level x-exactly-one-of groups to the fields', () => {
    const hash = parseJobTypes(LIVE_JOB_TYPES).find((t) => t.type === 'hash_sha256')!;
    const b64 = hash.fields.find((f) => f.name === 'contentBase64')!;
    const text = hash.fields.find((f) => f.name === 'text')!;
    expect(b64.exactlyOneOf).toEqual(['contentBase64', 'text']);
    expect(text.exactlyOneOf).toEqual(['contentBase64', 'text']);
    expect(b64.required).toBe(false);
  });

  it('exposes the example payload for form prefill', () => {
    const hash = parseJobTypes(LIVE_JOB_TYPES).find((t) => t.type === 'hash_sha256')!;
    expect(hash.examplePayload).toEqual({ text: 'The quick brown fox jumps over the lazy dog' });
  });
});
