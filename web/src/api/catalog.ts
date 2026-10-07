import type { JobFieldType, JobType, JobTypeField } from '../types';

/**
 * Normalizes the /api/v1/job-types catalog (SPEC §9: "type catalog with
 * payload JSON schemas + example payloads"). The exact wire shape is not
 * pinned by the SPEC, so this parser accepts the common shapes:
 *  - JSON-Schema style: [{ type, description, payloadSchema: { properties, required }, examplePayload }]
 *  - field-array style: [{ type, description, fields: [...], example }]
 *  - string list: ["csv_analysis", ...]
 * When the API is unreachable the built-in SPEC §9 catalog is used instead.
 */

type UnknownRecord = Record<string, unknown>;

function asRecord(v: unknown): UnknownRecord | null {
  return v !== null && typeof v === 'object' && !Array.isArray(v) ? (v as UnknownRecord) : null;
}

function str(v: unknown): string | undefined {
  return typeof v === 'string' && v.length > 0 ? v : undefined;
}

function num(v: unknown): number | undefined {
  return typeof v === 'number' && Number.isFinite(v) ? v : undefined;
}

function fieldFromSchema(name: string, prop: UnknownRecord, required: Set<string>): JobTypeField {
  const rawType = str(prop.type) ?? 'string';
  let type: JobFieldType;
  switch (rawType) {
    case 'integer':
    case 'number':
      type = rawType === 'integer' ? 'integer' : 'number';
      break;
    case 'boolean':
      type = 'boolean';
      break;
    case 'object':
      type = 'object';
      break;
    case 'array':
      type = 'array';
      break;
    default:
      type = 'string';
  }
  const enums = Array.isArray(prop.enum)
    ? prop.enum.filter((e): e is string => typeof e === 'string' && e.length > 0)
    : undefined;
  const maxLength = num(prop.maxLength) ?? (rawType === 'string' ? undefined : undefined);
  const field: JobTypeField = {
    name,
    type,
    required: required.has(name),
    description: str(prop.description),
    enumValues: enums && enums.length > 0 ? enums : undefined,
    example: prop.example ?? prop.default,
    defaultValue: prop.default,
    min: num(prop.minimum) ?? num(prop.min),
    max: num(prop.maximum) ?? num(prop.max),
    maxLength: maxLength ?? (num(prop.maxLength) ?? undefined),
  };
  // large text payloads get a textarea editor
  if (type === 'string' && (field.maxLength == null || field.maxLength > 400) && !enums) field.long = true;
  if (type === 'string' && /base64|csv|text|content|archive|image/i.test(name)) field.long = true;
  return field;
}

function parseTypeEntry(entry: UnknownRecord): JobType | null {
  const typeName = str(entry.type) ?? str(entry.name);
  if (!typeName) return null;
  const description = str(entry.description) ?? '';
  const fields: JobTypeField[] = [];

  const schema = asRecord(entry.payloadSchema) ?? asRecord(entry.payload_schema) ?? asRecord(entry.schema);
  if (schema) {
    const required = new Set<string>(
      (Array.isArray(schema.required) ? schema.required : []).filter((r): r is string => typeof r === 'string'),
    );
    const props = asRecord(schema.properties) ?? asRecord(schema.fields) ?? asRecord(schema.props);
    if (props) {
      for (const [name, raw] of Object.entries(props)) {
        const prop = asRecord(raw);
        if (prop) fields.push(fieldFromSchema(name, prop, required));
        else if (typeof raw === 'string') fields.push(fieldFromSchema(name, { type: raw }, required));
      }
    } else if (Array.isArray(schema.fields)) {
      for (const f of schema.fields) {
        const fr = asRecord(f);
        if (fr) fields.push(fieldFromSchema(str(fr.name) ?? '', fr, new Set()));
      }
    }

    // schema-level "exactly one of" groups (live API: x-exactly-one-of for hash_sha256)
    const exactlyOneRaw =
      schema['x-exactly-one-of'] ?? schema['x_exactly_one_of'] ?? schema['exactlyOneOf'] ?? schema['exactly_one_of'];
    if (Array.isArray(exactlyOneRaw)) {
      const group = exactlyOneRaw.filter((n): n is string => typeof n === 'string');
      for (const field of fields) {
        if (group.includes(field.name)) field.exactlyOneOf = group;
      }
    }
  }

  if (fields.length === 0 && Array.isArray(entry.fields)) {
    for (const f of entry.fields) {
      const fr = asRecord(f);
      if (!fr) continue;
      const name = str(fr.name) ?? '';
      const rawType = str(fr.type) ?? 'string';
      const type = (['string', 'number', 'integer', 'boolean', 'object', 'array'] as const).includes(
        rawType as JobFieldType,
      )
        ? (rawType as JobFieldType)
        : 'string';
      const enums = Array.isArray(fr.enumValues)
        ? fr.enumValues.filter((e): e is string => typeof e === 'string')
        : undefined;
      fields.push({
        name,
        type,
        required: fr.required === true,
        description: str(fr.description),
        enumValues: enums && enums.length > 0 ? enums : undefined,
        example: fr.example,
        defaultValue: fr.default ?? fr.defaultValue,
        min: num(fr.min),
        max: num(fr.max),
        maxLength: num(fr.maxLength),
        long: fr.long === true || (type === 'string' && (num(fr.maxLength) ?? 0) > 400),
      });
    }
  }

  const exampleRaw = entry.examplePayload ?? entry.example_payload ?? entry.example ?? entry.payloadExample;
  const examplePayload = asRecord(exampleRaw) ?? undefined;

  if (fields.length === 0) {
    // no schema available: render a single JSON payload editor
    fields.push({
      name: typeName,
      type: 'object',
      required: false,
      description: 'No schema published — edit the raw JSON payload.',
    });
  }

  return { type: typeName, description, fields, examplePayload, source: 'api' };
}

export function parseJobTypes(raw: unknown): JobType[] {
  if (!Array.isArray(raw)) return [];
  const out: JobType[] = [];
  for (const entry of raw) {
    if (typeof entry === 'string') {
      out.push(SPEC_JOB_TYPES.find((t) => t.type === entry) ?? {
        type: entry,
        description: '',
        fields: [{ name: 'payload', type: 'object', required: false, description: 'No schema published — edit the raw JSON payload.' }],
        source: 'api' as const,
      });
      continue;
    }
    const parsed = parseTypeEntry(asRecord(entry) ?? {});
    if (parsed) out.push(parsed);
  }
  return out;
}

/* ---------------------------------------------------------------------------
 * Built-in catalog transcribed from SPEC §9. Used ONLY as a fallback when
 * /api/v1/job-types is unreachable, so the console stays usable offline.
 * ------------------------------------------------------------------------- */

export const SPEC_JOB_TYPES: JobType[] = [
  {
    type: 'csv_analysis',
    description: 'Parse a CSV document, infer column types, compute per-column statistics.',
    source: 'spec',
    examplePayload: { csv: 'name,score\nada,91\nlinus,88\ndennis,97', delimiter: ',', hasHeader: true },
    fields: [
      { name: 'csv', type: 'string', required: true, description: 'CSV document (≤ 2 MB).', maxLength: 2_097_152, long: true },
      { name: 'delimiter', type: 'string', required: false, enumValues: [',', ';', '\t'], description: 'Field delimiter.' },
      { name: 'hasHeader', type: 'boolean', required: false, description: 'First row is a header (default true).' },
    ],
  },
  {
    type: 'json_transform',
    description: 'Apply a pipeline of pick/remove/rename/flatten operations to a JSON document.',
    source: 'spec',
    examplePayload: { input: { user: { name: 'ada', tags: ['math'] } }, operations: [{ op: 'pick', paths: ['user.name'] }] },
    fields: [
      { name: 'input', type: 'object', required: true, description: 'JSON document to transform.' },
      { name: 'operations', type: 'array', required: true, description: 'Operations: pick{paths}, remove{paths}, rename{from,to}, flatten{separator}.' },
    ],
  },
  {
    type: 'image_resize',
    description: 'Resize a base64-encoded image; the result is stored as a file artifact.',
    source: 'spec',
    examplePayload: { imageBase64: 'iVBORw0KGgo…', width: 512, height: 512, maintainAspect: true, format: 'png' },
    fields: [
      { name: 'imageBase64', type: 'string', required: true, description: 'Base64 image payload.', long: true },
      { name: 'width', type: 'integer', required: true, min: 1, max: 10000, description: 'Target width (1..10000).' },
      { name: 'height', type: 'integer', required: true, min: 1, max: 10000, description: 'Target height (1..10000).' },
      { name: 'maintainAspect', type: 'boolean', required: false, description: 'Preserve aspect ratio (default true).' },
      { name: 'format', type: 'string', required: false, enumValues: ['png', 'jpeg'], description: 'Output format.' },
    ],
  },
  {
    type: 'hash_sha256',
    description: 'SHA-256 digest of a base64 payload or a UTF-8 text (exactly one input).',
    source: 'spec',
    examplePayload: { text: 'taskmesh' },
    fields: [
      { name: 'contentBase64', type: 'string', required: false, long: true, description: 'Base64 content to hash.', exactlyOneOf: ['contentBase64', 'text'] },
      { name: 'text', type: 'string', required: false, long: true, description: 'UTF-8 text to hash.', exactlyOneOf: ['contentBase64', 'text'] },
    ],
  },
  {
    type: 'text_statistics',
    description: 'Word/character statistics and top-word frequencies for a text document.',
    source: 'spec',
    examplePayload: { text: 'The quick brown fox jumps over the lazy dog.', caseSensitive: false },
    fields: [
      { name: 'text', type: 'string', required: true, description: 'Text document (≤ 2 MB).', maxLength: 2_097_152, long: true },
      { name: 'caseSensitive', type: 'boolean', required: false, description: 'Case-sensitive word counting.' },
    ],
  },
  {
    type: 'archive_inspection',
    description: 'Inspect a zip archive: entries, sizes, compression ratio.',
    source: 'spec',
    examplePayload: { archiveBase64: 'UEsDBBQAAAAI…', maxEntries: 500 },
    fields: [
      { name: 'archiveBase64', type: 'string', required: true, description: 'Base64 zip archive (≤ 20 MB).', long: true },
      { name: 'maxEntries', type: 'integer', required: false, min: 1, max: 5000, description: 'Maximum entries to report (default 500).' },
    ],
  },
  {
    type: 'cpu_benchmark',
    description: 'Run a CPU workload (primes or matrix) for a bounded duration.',
    source: 'spec',
    examplePayload: { workload: 'primes', durationSeconds: 5 },
    fields: [
      { name: 'workload', type: 'string', required: true, enumValues: ['primes', 'matrix'], description: 'Benchmark workload.' },
      { name: 'durationSeconds', type: 'integer', required: false, min: 1, max: 30, description: 'Run duration 1..30s (default 5).' },
    ],
  },
];

/* --------------------------- payload validation ---------------------------- */

export interface PayloadValues {
  [field: string]: unknown;
}

export interface ValidationResult {
  payload: Record<string, unknown>;
  errors: Record<string, string>;
}

/**
 * Client-side validation mirroring SPEC §9 bounds. Returns the coerced payload
 * plus per-field errors (empty record when valid).
 */
export function validatePayload(jobType: JobType, values: PayloadValues): ValidationResult {
  const payload: Record<string, unknown> = {};
  const errors: Record<string, string> = {};

  for (const field of jobType.fields) {
    const value = values[field.name];
    const isBlank =
      value === undefined || value === null || (typeof value === 'string' && value.trim() === '');

    if (isBlank) {
      if (field.required) errors[field.name] = 'required';
      continue;
    }

    switch (field.type) {
      case 'string': {
        const s = String(value);
        if (field.enumValues && !field.enumValues.includes(s)) {
          errors[field.name] = `must be one of: ${field.enumValues.join(', ')}`;
          break;
        }
        if (field.maxLength != null && s.length > field.maxLength) {
          errors[field.name] = `must be ≤ ${field.maxLength.toLocaleString('en-US')} characters (got ${s.length.toLocaleString('en-US')})`;
          break;
        }
        payload[field.name] = s;
        break;
      }
      case 'number':
      case 'integer': {
        const n = typeof value === 'number' ? value : Number(String(value).trim());
        if (!Number.isFinite(n)) {
          errors[field.name] = 'must be a number';
          break;
        }
        if (field.type === 'integer' && !Number.isInteger(n)) {
          errors[field.name] = 'must be an integer';
          break;
        }
        if (field.min != null && n < field.min) {
          errors[field.name] = `must be ≥ ${field.min}`;
          break;
        }
        if (field.max != null && n > field.max) {
          errors[field.name] = `must be ≤ ${field.max}`;
          break;
        }
        payload[field.name] = n;
        break;
      }
      case 'boolean': {
        payload[field.name] = value === true || value === 'true';
        break;
      }
      case 'object':
      case 'array': {
        const text = typeof value === 'string' ? value.trim() : '';
        if (text === '') {
          if (field.required) errors[field.name] = 'required';
          break;
        }
        try {
          const parsed: unknown = JSON.parse(text);
          if (field.type === 'array' && !Array.isArray(parsed)) {
            errors[field.name] = 'must be a JSON array';
            break;
          }
          if (field.type === 'object' && (typeof parsed !== 'object' || Array.isArray(parsed) || parsed === null)) {
            errors[field.name] = 'must be a JSON object';
            break;
          }
          payload[field.name] = parsed;
        } catch {
          errors[field.name] = 'invalid JSON';
        }
        break;
      }
    }
  }

  // exactly-one-of groups (hash_sha256)
  const groups = new Map<string, string[]>();
  for (const field of jobType.fields) {
    if (field.exactlyOneOf) {
      const key = field.exactlyOneOf.join('|');
      groups.set(key, field.exactlyOneOf);
    }
  }
  for (const [key, group] of groups) {
    const provided = group.filter((name) => {
      const v = payload[name] ?? values[name];
      return v !== undefined && v !== null && String(v).trim() !== '';
    });
    if (provided.length > 1) {
      for (const name of provided) errors[name] = `provide only one of: ${key.replaceAll('|', ', ')}`;
    } else if (provided.length === 0) {
      // only complain when none of the group is required individually
      const anyRequired = jobType.fields.some((f) => group.includes(f.name) && f.required);
      if (!anyRequired) {
        for (const name of group) errors[name] = `provide exactly one of: ${key.replaceAll('|', ', ')}`;
      } else {
        for (const name of group) if (!errors[name] && (values[name] ?? '') === '') errors[name] = `provide one of: ${key.replaceAll('|', ', ')}`;
      }
    }
    void key;
  }

  return { payload, errors };
}

/** Default raw form values for a job type, prefilled from its example payload. */
export function initialPayloadValues(jobType: JobType): PayloadValues {
  const values: PayloadValues = {};
  for (const field of jobType.fields) {
    const example = jobType.examplePayload?.[field.name];
    const raw = example ?? field.defaultValue;
    if (raw === undefined) {
      values[field.name] = field.type === 'boolean' ? false : '';
      continue;
    }
    if (field.type === 'object' || field.type === 'array') {
      values[field.name] = JSON.stringify(raw, null, 2);
    } else if (field.type === 'boolean') {
      values[field.name] = raw === true;
    } else {
      values[field.name] = String(raw);
    }
  }
  return values;
}
