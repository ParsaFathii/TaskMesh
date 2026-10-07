import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';

const inputClass =
  'w-full rounded-sm border border-line bg-bg-deep px-2.5 py-1.5 font-mono text-[12.5px] text-ink placeholder:text-dim focus:border-amber/60 focus:outline-none disabled:opacity-50';

export function FieldWrap({
  label,
  hint,
  error,
  required,
  children,
  htmlFor,
}: {
  label: ReactNode;
  hint?: ReactNode;
  error?: string;
  required?: boolean;
  children: ReactNode;
  htmlFor?: string;
}) {
  return (
    <div className="block">
      <label htmlFor={htmlFor} className="micro mb-1 block text-mute">
        {label}
        {required && <span className="ml-1 text-amber" aria-hidden="true">*</span>}
      </label>
      {children}
      {hint != null && !error && <p className="mt-1 font-mono text-[10.5px] leading-4 text-dim">{hint}</p>}
      {error && (
        <p className="mt-1 font-mono text-[10.5px] leading-4 text-rose" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

export function TextInput({
  label,
  hint,
  error,
  required,
  mono = true,
  className = '',
  ...rest
}: InputHTMLAttributes<HTMLInputElement> & { label?: ReactNode; hint?: ReactNode; error?: string; mono?: boolean }) {
  const id = useId();
  return (
    <FieldWrap label={label} hint={hint} error={error} required={required} htmlFor={id}>
      <input id={id} className={`${mono ? inputClass : inputClass.replace('font-mono', '')} ${className}`} {...rest} />
    </FieldWrap>
  );
}

export function TextArea({
  label,
  hint,
  error,
  required,
  rows = 3,
  className = '',
  ...rest
}: TextareaHTMLAttributes<HTMLTextAreaElement> & { label?: ReactNode; hint?: ReactNode; error?: string }) {
  const id = useId();
  return (
    <FieldWrap label={label} hint={hint} error={error} required={required} htmlFor={id}>
      <textarea id={id} rows={rows} className={`${inputClass} resize-y leading-5 ${className}`} {...rest} />
    </FieldWrap>
  );
}

export function SelectInput({
  label,
  hint,
  error,
  required,
  options,
  className = '',
  ...rest
}: SelectHTMLAttributes<HTMLSelectElement> & {
  label?: ReactNode;
  hint?: ReactNode;
  error?: string;
  options: { value: string; label: string }[];
}) {
  const id = useId();
  return (
    <FieldWrap label={label} hint={hint} error={error} required={required} htmlFor={id}>
      <select id={id} className={`${inputClass} cursor-pointer appearance-none pr-6 ${className}`} {...rest}>
        {options.map((o) => (
          <option key={o.value} value={o.value} className="bg-panel text-ink">
            {o.label}
          </option>
        ))}
      </select>
    </FieldWrap>
  );
}

export function CheckboxInput({
  label,
  hint,
  error,
  className = '',
  ...rest
}: InputHTMLAttributes<HTMLInputElement> & { label?: ReactNode; hint?: ReactNode; error?: string }) {
  const id = useId();
  return (
    <div className={`block ${className}`}>
      <div className="flex items-center gap-2">
        <input
          id={id}
          type="checkbox"
          className="h-3.5 w-3.5 shrink-0 cursor-pointer appearance-none rounded-[2px] border border-line bg-bg-deep checked:border-amber checked:bg-amber focus-visible:outline-2 focus-visible:outline-amber"
          {...rest}
        />
        {label != null && (
          <label htmlFor={id} className="cursor-pointer text-xs text-ink select-none">
            {label}
          </label>
        )}
      </div>
      {hint != null && !error && <p className="mt-1 font-mono text-[10.5px] leading-4 text-dim">{hint}</p>}
      {error && (
        <p className="mt-1 font-mono text-[10.5px] leading-4 text-rose" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
