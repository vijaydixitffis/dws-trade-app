import { useState } from 'react';
import { formatCount } from '../format';
import type { SimulationRequest, SimulationStatus } from '../types';

interface Props {
  status: SimulationStatus | null;
  onStart: (request: SimulationRequest) => void;
  onStop: () => void;
}

const DEFAULTS: SimulationRequest = {
  count: 5000,
  ratePerSecond: 500,
  higherVersionPct: 20,
  sameVersionPct: 10,
  lowerVersionPct: 10,
  pastMaturityPct: 10,
};

const FIELDS: { key: keyof SimulationRequest; label: string }[] = [
  { key: 'count', label: 'Trades to send' },
  { key: 'ratePerSecond', label: 'Trades per second' },
  { key: 'higherVersionPct', label: 'Higher version %' },
  { key: 'sameVersionPct', label: 'Same version %' },
  { key: 'lowerVersionPct', label: 'Lower version %' },
  { key: 'pastMaturityPct', label: 'Past maturity %' },
];

export function SimulatorPanel({ status, onStart, onStop }: Props) {
  const [form, setForm] = useState<SimulationRequest>(DEFAULTS);
  const running = status?.running ?? false;
  const mixTotal = form.higherVersionPct + form.sameVersionPct + form.lowerVersionPct + form.pastMaturityPct;
  const invalid = mixTotal > 100 || form.count < 1 || form.ratePerSecond < 1;

  return (
    <section className="panel" aria-label="Trade simulator">
      <h2>Trade simulator</h2>
      <div className="form-grid">
        {FIELDS.map(({ key, label }) => (
          <label key={key}>
            {label}
            <input
              type="number"
              min={0}
              value={form[key]}
              disabled={running}
              onChange={(e) => setForm({ ...form, [key]: Number(e.target.value) })}
            />
          </label>
        ))}
      </div>
      <div className="toolbar">
        {running ? (
          <button onClick={onStop}>Stop run</button>
        ) : (
          <button className="primary" onClick={() => onStart(form)} disabled={invalid || status === null}>
            Start run
          </button>
        )}
        {mixTotal > 100 && <span className="muted">The four percentages must not exceed 100.</span>}
        {status === null && <span className="muted">Simulator is not reachable.</span>}
        {status?.runId && (
          <span className="muted">
            Run {status.runId}: {status.running ? 'running' : 'finished'}, sent {formatCount(status.sent)} of{' '}
            {formatCount(status.requested)}; capture accepted {formatCount(status.accepted)}, rejected{' '}
            {formatCount(status.rejectedAtCapture)}
            {status.error ? `; stopped on error: ${status.error}` : ''}
          </span>
        )}
      </div>
      <p className="muted small">
        The remainder of the mix is new trades. Lower-version trades are rejected by the trade store; past-maturity
        trades are rejected by the capture API.
      </p>
    </section>
  );
}
