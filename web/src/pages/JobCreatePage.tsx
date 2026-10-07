import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useProjects, useJobTypes, useCreateJob, errMessage, ApiError } from '../api/hooks';
import { validatePayload, initialPayloadValues, type PayloadValues } from '../api/catalog';
import { JOB_PRIORITIES, type JobPriority, type JobTypeField } from '../types';
import { Panel, PageHead } from '../components/ui/Panel';
import { Button } from '../components/ui/Button';
import { SelectInput, TextInput, TextArea, CheckboxInput } from '../components/ui/Inputs';
import { RadioChips } from '../components/ui/RadioChips';
import { JsonInspector } from '../components/ui/JsonInspector';
import { ErrorPanel } from '../components/ui/States';
import { priorityVisual } from '../lib/status';
import { IconAlert, IconPlus } from '../components/icons';

const TIMEOUT_MIN = 5;
const TIMEOUT_MAX = 3600;
const RETRIES_MIN = 0;
const RETRIES_MAX = 10;
const EMPTY_JOB_TYPES: never[] = [];

function chipClassFor(priority: JobPriority): string {
  return priorityVisual[priority].chip;
}

/** Render one payload-schema field with the right control for its type. */
function PayloadField({
  field,
  value,
  error,
  onChange,
}: {
  field: JobTypeField;
  value: unknown;
  error?: string;
  onChange: (value: unknown) => void;
}) {
  const label = field.name;
  const hint = field.description
    ? `${field.description}${
        field.maxLength != null ? ` · ≤ ${field.maxLength.toLocaleString('en-US')} chars` : ''
      }${field.min != null || field.max != null ? ` · ${field.min ?? '−∞'}..${field.max ?? '∞'}` : ''}`
    : undefined;

  if (field.type === 'boolean') {
    return (
      <CheckboxInput
        label={label}
        hint={hint}
        error={error}
        checked={value === true}
        onChange={(e) => onChange(e.target.checked)}
      />
    );
  }

  if (field.enumValues != null && field.enumValues.length > 0 && field.type === 'string') {
    return (
      <SelectInput
        label={label}
        hint={hint}
        error={error}
        required={field.required}
        value={String(value ?? '')}
        onChange={(e) => onChange(e.target.value)}
        options={[{ value: '', label: '— unset —' }, ...field.enumValues.map((v) => ({ value: v, label: v }))]}
      />
    );
  }

  if (field.type === 'number' || field.type === 'integer') {
    return (
      <TextInput
        label={label}
        hint={hint}
        error={error}
        required={field.required}
        inputMode="decimal"
        value={String(value ?? '')}
        onChange={(e) => onChange(e.target.value)}
        placeholder={field.example != null ? String(field.example) : `number${field.min != null ? ` ≥ ${field.min}` : ''}`}
      />
    );
  }

  if (field.type === 'object' || field.type === 'array') {
    return (
      <TextArea
        label={`${label} · json`}
        hint={hint}
        error={error}
        required={field.required}
        rows={field.name === 'operations' ? 5 : 4}
        value={String(value ?? '')}
        spellCheck={false}
        onChange={(e) => onChange(e.target.value)}
        placeholder={field.type === 'array' ? '[ … ]' : '{ … }'}
      />
    );
  }

  return (
    <TextArea
      label={label}
      hint={hint}
      error={error}
      required={field.required}
      rows={field.long ? 5 : 2}
      value={String(value ?? '')}
      spellCheck={false}
      onChange={(e) => onChange(e.target.value)}
      placeholder={field.example != null ? String(field.example).slice(0, 80) : 'string'}
    />
  );
}

export default function JobCreatePage() {
  const navigate = useNavigate();
  const projectsQ = useProjects(0, 100);
  const typesQ = useJobTypes();
  const createMut = useCreateJob();

  const [projectId, setProjectId] = useState('');
  const [jobType, setJobType] = useState('');
  const [priority, setPriority] = useState<JobPriority>('NORMAL');
  const [timeoutStr, setTimeoutStr] = useState('120');
  const [retriesStr, setRetriesStr] = useState('3');
  const [idemKey, setIdemKey] = useState('');
  const [values, setValues] = useState<PayloadValues>({});
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);

  const jobTypes = useMemo(() => typesQ.data ?? EMPTY_JOB_TYPES, [typesQ.data]);
  const activeType = useMemo(() => jobTypes.find((t) => t.type === jobType), [jobTypes, jobType]);

  // default to the first project and first job type once loaded
  useEffect(() => {
    if (projectId === '' && projectsQ.data?.items.length) setProjectId(projectsQ.data.items[0]!.id);
  }, [projectId, projectsQ.data]);

  useEffect(() => {
    if (jobType === '' && jobTypes.length > 0) setJobType(jobTypes[0]!.type);
  }, [jobType, jobTypes]);

  // (re)build payload form state when the type changes
  useEffect(() => {
    if (activeType != null && jobType !== '' && values.__type !== jobType) {
      setValues({ ...initialPayloadValues(activeType), __type: jobType });
      setFieldErrors({});
    }
  }, [activeType, jobType, values.__type]);

  const payloadPreview = useMemo(() => {
    if (activeType == null) return null;
    const { payload } = validatePayload(activeType, values);
    return payload;
  }, [activeType, values]);

  const valid = useMemo(() => {
    if (activeType == null) return false;
    const { errors } = validatePayload(activeType, values);
    const timeoutOk = Number(timeoutStr) >= TIMEOUT_MIN && Number(timeoutStr) <= TIMEOUT_MAX;
    const retriesOk = Number(retriesStr) >= RETRIES_MIN && Number(retriesStr) <= RETRIES_MAX;
    return Object.keys(errors).length === 0 && timeoutOk && retriesOk && projectId !== '';
  }, [activeType, values, timeoutStr, retriesStr, projectId]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (activeType == null) return;
    setFormError(null);

    const { payload, errors } = validatePayload(activeType, values);
    const timeout = Number(timeoutStr);
    const retries = Number(retriesStr);

    const nextErrors: Record<string, string> = { ...errors };
    if (!Number.isFinite(timeout) || timeout < TIMEOUT_MIN || timeout > TIMEOUT_MAX) {
      nextErrors.__timeout = `timeout must be ${TIMEOUT_MIN}..${TIMEOUT_MAX} seconds`;
    }
    if (!Number.isFinite(retries) || retries < RETRIES_MIN || retries > RETRIES_MAX) {
      nextErrors.__retries = `max retries must be ${RETRIES_MIN}..${RETRIES_MAX}`;
    }
    if (projectId === '') nextErrors.__project = 'select a project';
    setFieldErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return;

    try {
      const job = await createMut.mutateAsync({
        projectId,
        type: jobType,
        priority,
        payload,
        timeoutSeconds: timeout,
        maxRetries: retries,
        idempotencyKey: idemKey.trim() || undefined,
      });
      if (job?.id) navigate(`/jobs/${job.id}`);
      else navigate('/jobs');
    } catch (err) {
      if (err instanceof ApiError) {
        setFormError(err.message);
        // try to attribute VALIDATION messages to specific fields
        if (err.code === 'VALIDATION' || err.status === 400) {
          const lower = err.message.toLowerCase();
          setFieldErrors((prev) => {
            const next = { ...prev };
            for (const field of activeType.fields) {
              if (lower.includes(field.name.toLowerCase())) next[field.name] = err.message;
            }
            if (lower.includes('project')) next.__project = err.message;
            if (lower.includes('timeout')) next.__timeout = err.message;
            if (lower.includes('retr')) next.__retries = err.message;
            if (lower.includes('idempotency')) next.__idem = err.message;
            return next;
          });
        }
      } else {
        setFormError(errMessage(err, 'job submission failed'));
      }
    }
  };

  const timeoutNum = Number(timeoutStr);
  const retriesNum = Number(retriesStr);

  return (
    <div className="space-y-3">
      <PageHead
        title="New job"
        sub="payload form is generated from the live type catalog (GET /api/v1/job-types)"
      />

      {typesQ.isError && (
        <div className="flex items-center gap-2 rounded-sm border border-yellow/40 bg-yellow/5 px-3 py-2 font-mono text-[11px] text-yellow">
          <IconAlert size={14} className="shrink-0" />
          type catalog API unreachable — using the built-in SPEC §9 catalog
        </div>
      )}

      <form onSubmit={submit} className="grid gap-3 xl:grid-cols-[1fr_360px]">
        <div className="space-y-3">
          <Panel title="target">
            <div className="grid gap-3 sm:grid-cols-2">
              <SelectInput
                label="project"
                required
                error={fieldErrors.__project}
                value={projectId}
                onChange={(e) => setProjectId(e.target.value)}
                options={
                  projectsQ.isLoading
                    ? [{ value: '', label: 'loading…' }]
                    : [
                        ...(projectsQ.data?.items.length ? [] : [{ value: '', label: 'no projects — create one first' }]),
                        ...(projectsQ.data?.items ?? []).map((p) => ({ value: p.id, label: p.name })),
                      ]
                }
              />
              <SelectInput
                label="job type"
                required
                value={jobType}
                onChange={(e) => setJobType(e.target.value)}
                options={jobTypes.map((t) => ({ value: t.type, label: t.type }))}
              />
            </div>
            {activeType?.description != null && (
              <p className="mt-2.5 font-mono text-[11px] leading-4 text-mute">{activeType.description}</p>
            )}
            {activeType?.source === 'spec' && (
              <p className="mt-1.5 font-mono text-[10px] text-dim">schema source: SPEC §9 built-in catalog (API unreachable)</p>
            )}
            {projectsQ.data != null && projectsQ.data.items.length === 0 && (
              <p className="mt-2.5 font-mono text-[11px] text-amber">
                you have no projects yet —{' '}
                <Link to="/projects" className="underline">
                  create one first
                </Link>
              </p>
            )}
          </Panel>

          <Panel title={`payload · ${jobType || '…'}`} actions={
            activeType?.examplePayload != null ? (
              <Button
                type="button"
                size="sm"
                variant="ghost"
                onClick={() => activeType != null && setValues({ ...initialPayloadValues(activeType), __type: jobType })}
              >
                reset to example
              </Button>
            ) : null
          }>
            {activeType == null ? (
              <p className="py-4 text-center font-mono text-[11.5px] text-dim">
                {typesQ.isLoading ? 'loading type catalog…' : 'select a job type'}
              </p>
            ) : (
              <div className="grid gap-3.5">
                {activeType.fields.map((field) => (
                  <PayloadField
                    key={field.name}
                    field={field}
                    value={values[field.name]}
                    error={fieldErrors[field.name]}
                    onChange={(v) => setValues((prev) => ({ ...prev, [field.name]: v }))}
                  />
                ))}
              </div>
            )}
          </Panel>

          <Panel title="execution">
            <div className="grid gap-3.5 sm:grid-cols-2">
              <div>
                <p className="micro mb-1.5 text-mute">
                  priority<span className="ml-1 text-amber" aria-hidden="true">*</span>
                </p>
                <RadioChips
                  id="priority"
                  label="priority"
                  value={priority}
                  options={JOB_PRIORITIES}
                  onChange={setPriority}
                  chipClass={chipClassFor}
                />
              </div>
              <TextInput
                label={`timeout · seconds (${TIMEOUT_MIN}–${TIMEOUT_MAX})`}
                inputMode="numeric"
                required
                error={fieldErrors.__timeout}
                value={timeoutStr}
                onChange={(e) => setTimeoutStr(e.target.value)}
                hint="default 120 · enforced by the worker runtime"
              />
              <TextInput
                label={`max retries (${RETRIES_MIN}–${RETRIES_MAX})`}
                inputMode="numeric"
                required
                error={fieldErrors.__retries}
                value={retriesStr}
                onChange={(e) => setRetriesStr(e.target.value)}
                hint="backoff = min(300, 2^retry_count × 5) seconds"
              />
              <TextInput
                label="idempotency key · optional"
                error={fieldErrors.__idem}
                value={idemKey}
                spellCheck={false}
                maxLength={255}
                onChange={(e) => setIdemKey(e.target.value)}
                hint="unique per owner — duplicate submissions return the existing job (200)"
              />
            </div>
          </Panel>
        </div>

        <div className="space-y-3 xl:sticky xl:top-14 xl:self-start">
          <Panel title="payload preview">
            <JsonInspector data={payloadPreview} name={`${jobType || 'payload'} · request body`} defaultOpen={3} emptyLabel="no fields yet" />
            <dl className="mt-3 space-y-1.5 border-t border-line/60 pt-2.5 font-mono text-[11px]">
              <div className="flex justify-between gap-2">
                <dt className="text-dim">timeout</dt>
                <dd className={Number.isFinite(timeoutNum) && timeoutNum >= TIMEOUT_MIN && timeoutNum <= TIMEOUT_MAX ? 'text-mute' : 'text-rose'}>
                  {Number.isFinite(timeoutNum) ? `${timeoutNum}s` : '—'}
                </dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-dim">max retries</dt>
                <dd className={Number.isFinite(retriesNum) && retriesNum >= RETRIES_MIN && retriesNum <= RETRIES_MAX ? 'text-mute' : 'text-rose'}>
                  {Number.isFinite(retriesNum) ? retriesNum : '—'}
                </dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-dim">idempotency</dt>
                <dd className="truncate text-mute">{idemKey.trim() || '—'}</dd>
              </div>
            </dl>
          </Panel>

          {formError != null && <ErrorPanel err={new ApiError(0, 'SUBMIT', formError)} compact />}

          <Panel title="submit">
            <Button
              type="submit"
              variant="primary"
              className="w-full"
              loading={createMut.isPending}
              disabled={!valid}
              icon={<IconPlus size={13} />}
            >
              create job
            </Button>
            <p className="mt-2 font-mono text-[10px] leading-4 text-dim">
              POST /api/v1/jobs → 201 created (200 on idempotent replay) → redirect to the job console
            </p>
          </Panel>
        </div>
      </form>
    </div>
  );
}
