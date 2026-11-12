import { useState } from 'react';
import { formatCount } from '../format';
import type { SimulationRequest, SimulationStatus } from '../types';
import { Modal } from './Modal';

interface Props {
  status: SimulationStatus | null;
  /** Resolves to null when the run started, or to the reason the simulator refused it. */
  onStart: (request: SimulationRequest) => Promise<string | null>;
  onStop: () => void;
}

type Bulk = Pick<
  SimulationRequest,
  'count' | 'ratePerSecond' | 'higherVersionPct' | 'sameVersionPct' | 'lowerVersionPct' | 'pastMaturityPct'
>;

interface Variety {
  counterParties: string;
  books: string;
  minMaturityDays: number;
  maxMaturityDays: number;
  tradeIdPrefix: string;
}

const DEFAULT_BULK: Bulk = {
  count: 5000,
  ratePerSecond: 500,
  higherVersionPct: 20,
  sameVersionPct: 10,
  lowerVersionPct: 10,
  pastMaturityPct: 10,
};

const DEFAULT_VARIETY: Variety = {
  counterParties: '',
  books: '',
  minMaturityDays: 1,
  maxMaturityDays: 3650,
  tradeIdPrefix: '',
};

const VOLUME_FIELDS: { key: 'count' | 'ratePerSecond'; label: string }[] = [
  { key: 'count', label: 'Trades to send' },
  { key: 'ratePerSecond', label: 'Trades per second' },
];

const MIX_FIELDS: { key: keyof Bulk; label: string; hint: string }[] = [
  { key: 'higherVersionPct', label: 'Higher version %', hint: 'accepted as a new row' },
  { key: 'sameVersionPct', label: 'Same version %', hint: 'replaces the stored record' },
  { key: 'lowerVersionPct', label: 'Lower version %', hint: 'rejected by the trade store' },
  { key: 'pastMaturityPct', label: 'Past maturity %', hint: 'rejected by the capture API' },
];

/** Comma or line separated names, trimmed, without blanks or repeats. */
export function parseList(text: string): string[] {
  return [...new Set(text.split(/[,\n]/).map((item) => item.trim()).filter(Boolean))];
}

function mixTotal(bulk: Bulk): number {
  return bulk.higherVersionPct + bulk.sameVersionPct + bulk.lowerVersionPct + bulk.pastMaturityPct;
}

const inRange = (value: number, min: number, max: number) => Number.isInteger(value) && value >= min && value <= max;

/** The same limits the simulator enforces, so a value it would refuse is caught before it is sent. */
function bulkErrors(bulk: Bulk): string[] {
  const errors: string[] = [];
  if (!inRange(bulk.count, 1, 10_000_000)) {
    errors.push('Trades to send must be between 1 and 10,000,000.');
  }
  if (!inRange(bulk.ratePerSecond, 1, 50_000)) {
    errors.push('Trades per second must be between 1 and 50,000.');
  }
  const percentages = [bulk.higherVersionPct, bulk.sameVersionPct, bulk.lowerVersionPct, bulk.pastMaturityPct];
  if (percentages.some((value) => !inRange(value, 0, 100))) {
    errors.push('Each percentage must be between 0 and 100.');
  } else if (mixTotal(bulk) > 100) {
    errors.push('The four percentages must not exceed 100.');
  }
  return errors;
}

function varietyErrors(variety: Variety): string[] {
  const errors: string[] = [];
  if (!inRange(variety.minMaturityDays, 0, 3650) || !inRange(variety.maxMaturityDays, 0, 3650)) {
    errors.push('Maturity days must be between 0 and 3,650.');
  } else if (variety.maxMaturityDays < variety.minMaturityDays) {
    errors.push('The latest maturity must not be before the earliest.');
  }
  const prefix = variety.tradeIdPrefix.trim();
  if (prefix && !/^[A-Za-z0-9-]{1,16}$/.test(prefix)) {
    errors.push('The trade id prefix may use letters, digits and hyphens, up to 16 characters.');
  }
  const lists = [parseList(variety.counterParties), parseList(variety.books)];
  if (lists.some((names) => names.length > 100 || names.some((name) => name.length > 32))) {
    errors.push('Counter-party and book names may be up to 32 characters, at most 100 of each.');
  }
  return errors;
}

export function SimulatorPanel({ status, onStart, onStop }: Props) {
  const [dialog, setDialog] = useState<'quick' | 'detailed' | null>(null);
  const [bulk, setBulk] = useState<Bulk>(DEFAULT_BULK);
  const [variety, setVariety] = useState<Variety>(DEFAULT_VARIETY);
  const [refusal, setRefusal] = useState<string | null>(null);
  const running = status?.running ?? false;
  const available = status !== null && !running;

  const start = async (request: SimulationRequest) => {
    setRefusal(null);
    const message = await onStart(request);
    if (message) {
      // Keep the dialog open so the values can be corrected.
      setRefusal(message);
      return;
    }
    setDialog(null);
  };

  const openDialog = (kind: 'quick' | 'detailed') => {
    setRefusal(null);
    setDialog(kind);
  };

  const startQuick = () => start({ ...bulk });

  const startDetailed = () => {
    const request: SimulationRequest = {
      ...bulk,
      minMaturityDays: variety.minMaturityDays,
      maxMaturityDays: variety.maxMaturityDays,
    };
    const counterParties = parseList(variety.counterParties);
    const books = parseList(variety.books);
    if (counterParties.length > 0) {
      request.counterParties = counterParties;
    }
    if (books.length > 0) {
      request.books = books;
    }
    if (variety.tradeIdPrefix.trim()) {
      request.tradeIdPrefix = variety.tradeIdPrefix.trim();
    }
    return start(request);
  };

  const total = mixTotal(bulk);
  const quickErrors = bulkErrors(bulk);
  const detailedErrors = [...bulkErrors(bulk), ...varietyErrors(variety)];

  return (
    <section className="panel" aria-label="Trade simulator">
      <h2>Trade simulator</h2>
      <div className="toolbar">
        <button className="primary" onClick={() => openDialog('quick')} disabled={!available}>
          Quick run
        </button>
        <button className="primary" onClick={() => openDialog('detailed')} disabled={!available}>
          Detailed run
        </button>
        {running && <button onClick={onStop}>Stop run</button>}
        {status === null && <span className="muted">Simulator is not reachable.</span>}
      </div>
      {status?.runId && (
        <p className="muted small">
          Run {status.runId}: {status.running ? 'running' : 'finished'}, sent {formatCount(status.sent)} of{' '}
          {formatCount(status.requested)}; capture accepted {formatCount(status.accepted)}, rejected{' '}
          {formatCount(status.rejectedAtCapture)}
          {status.error ? `; stopped on error: ${status.error}` : ''}
        </p>
      )}
      <p className="muted small">
        Quick run sends a bulk mix of scenarios. Detailed run also lets you choose the counter-parties, books,
        maturity window and trade ids.
      </p>

      {dialog === 'quick' && (
        <Modal title="Quick run" onClose={() => setDialog(null)}>
          <div className="dialog-body form-grid">
            {[...VOLUME_FIELDS, ...MIX_FIELDS].map(({ key, label }) => (
              <label key={key}>
                {label}
                <input
                  type="number"
                  min={0}
                  value={bulk[key]}
                  onChange={(e) => setBulk({ ...bulk, [key]: Number(e.target.value) })}
                />
              </label>
            ))}
          </div>
          <div className="dialog-body">
            {quickErrors.map((message) => (
              <p className="error" key={message}>
                {message}
              </p>
            ))}
            {refusal && (
              <p className="error" role="alert">
                {refusal}
              </p>
            )}
          </div>
          <div className="dialog-footer">
            <button onClick={() => setDialog(null)}>Cancel</button>
            <button className="primary" onClick={startQuick} disabled={quickErrors.length > 0}>
              Start run
            </button>
          </div>
        </Modal>
      )}

      {dialog === 'detailed' && (
        <Modal title="Detailed run" onClose={() => setDialog(null)}>
          <div className="dialog-body">
            <h3>Volume</h3>
            <div className="form-grid">
              {VOLUME_FIELDS.map(({ key, label }) => (
                <label key={key}>
                  {label}
                  <input
                    type="number"
                    min={1}
                    value={bulk[key]}
                    onChange={(e) => setBulk({ ...bulk, [key]: Number(e.target.value) })}
                  />
                </label>
              ))}
            </div>

            <h3>Scenario mix</h3>
            {MIX_FIELDS.map(({ key, label, hint }) => (
              <div className="slider-row" key={key}>
                <label htmlFor={`mix-${key}`}>{label}</label>
                <input
                  id={`mix-${key}`}
                  type="range"
                  min={0}
                  max={100}
                  value={bulk[key]}
                  onChange={(e) => setBulk({ ...bulk, [key]: Number(e.target.value) })}
                />
                <span className="slider-value">{bulk[key]}</span>
                <span className="muted small">{hint}</span>
              </div>
            ))}
            <p className="muted">{`New trades: ${100 - total} %`}</p>

            <h3>Trade variety</h3>
            <div className="form-grid">
              <label>
                Counter-party ids
                <textarea
                  rows={3}
                  placeholder="e.g. ACME, GLOBEX. Blank uses CP-1 to CP-50"
                  value={variety.counterParties}
                  onChange={(e) => setVariety({ ...variety, counterParties: e.target.value })}
                />
              </label>
              <label>
                Book ids
                <textarea
                  rows={3}
                  placeholder="e.g. EQ-1, FX-2. Blank uses B1 to B10"
                  value={variety.books}
                  onChange={(e) => setVariety({ ...variety, books: e.target.value })}
                />
              </label>
              <label>
                Earliest maturity (days ahead)
                <input
                  type="number"
                  min={0}
                  max={3650}
                  value={variety.minMaturityDays}
                  onChange={(e) => setVariety({ ...variety, minMaturityDays: Number(e.target.value) })}
                />
              </label>
              <label>
                Latest maturity (days ahead)
                <input
                  type="number"
                  min={0}
                  max={3650}
                  value={variety.maxMaturityDays}
                  onChange={(e) => setVariety({ ...variety, maxMaturityDays: Number(e.target.value) })}
                />
              </label>
              <label>
                Trade id prefix
                <input
                  value={variety.tradeIdPrefix}
                  maxLength={16}
                  placeholder="Blank uses the run id"
                  onChange={(e) => setVariety({ ...variety, tradeIdPrefix: e.target.value })}
                />
              </label>
            </div>
            {detailedErrors.map((message) => (
              <p className="error" key={message}>
                {message}
              </p>
            ))}
            {refusal && (
              <p className="error" role="alert">
                {refusal}
              </p>
            )}
            <p className="muted small">
              Zero days ahead means the trade matures today, which is accepted. Reusing a prefix from an earlier run
              continues the same trade ids, so the store will replace or reject them by version.
            </p>
          </div>
          <div className="dialog-footer">
            <button onClick={() => setDialog(null)}>Cancel</button>
            <button
              className="primary"
              onClick={startDetailed}
              disabled={detailedErrors.length > 0}
            >
              Start run
            </button>
          </div>
        </Modal>
      )}
    </section>
  );
}
