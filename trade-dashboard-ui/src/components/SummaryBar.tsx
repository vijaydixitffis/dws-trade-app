import { formatCount } from '../format';
import type { Summary } from '../types';

function Tile({ label, value }: { label: string; value: number }) {
  return (
    <div className="tile">
      <div className="tile-value">{formatCount(value)}</div>
      <div className="tile-label">{label}</div>
    </div>
  );
}

export function SummaryBar({ summary }: { summary: Summary }) {
  return (
    <section className="tiles" aria-label="Summary">
      <Tile label="Trades in store" value={summary.trades} />
      <Tile label="Expired" value={summary.expired} />
      <Tile label="Accepted" value={summary.accepted} />
      <Tile label="Replaced (same version)" value={summary.replaced} />
      <Tile label="Rejected: lower version" value={summary.rejectedLowerVersion} />
      <Tile label="Rejected: maturity passed" value={summary.rejectedMaturityPassed} />
    </section>
  );
}
