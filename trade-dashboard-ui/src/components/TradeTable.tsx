import { formatCount, formatDate } from '../format';
import type { TradePage } from '../types';

interface Props {
  page: TradePage;
  filter: string;
  onFilter: (value: string) => void;
  onPage: (page: number) => void;
}

export function TradeTable({ page, filter, onFilter, onPage }: Props) {
  const lastPage = Math.max(0, Math.ceil(page.total / page.size) - 1);
  return (
    <div>
      <div className="toolbar">
        <label>
          Trade Id starts with{' '}
          <input value={filter} onChange={(e) => onFilter(e.target.value)} placeholder="e.g. T2" />
        </label>
        <span className="muted">
          {formatCount(page.total)} row{page.total === 1 ? '' : 's'}, ordered by Trade Id then highest version
        </span>
      </div>
      <table>
        <thead>
          <tr>
            <th>Trade Id</th>
            <th className="num">Version</th>
            <th>Counter-Party Id</th>
            <th>Book-Id</th>
            <th>Maturity Date</th>
            <th>Created Date</th>
            <th>Expired</th>
          </tr>
        </thead>
        <tbody>
          {page.items.map((trade) => (
            <tr key={`${trade.tradeId}-${trade.version}`} className={trade.expired ? 'expired' : undefined}>
              <td>{trade.tradeId}</td>
              <td className="num">{trade.version}</td>
              <td>{trade.counterPartyId}</td>
              <td>{trade.bookId}</td>
              <td>{formatDate(trade.maturityDate)}</td>
              <td>{formatDate(trade.createdDate)}</td>
              <td>
                <span className={trade.expired ? 'pill pill-grey' : 'pill pill-blue'}>{trade.expired ? 'Y' : 'N'}</span>
              </td>
            </tr>
          ))}
          {page.items.length === 0 && (
            <tr>
              <td colSpan={7} className="empty">
                No trades in the store yet.
              </td>
            </tr>
          )}
        </tbody>
      </table>
      <div className="pager">
        <button onClick={() => onPage(page.page - 1)} disabled={page.page <= 0}>
          Previous
        </button>
        <span>
          Page {page.page + 1} of {lastPage + 1}
        </span>
        <button onClick={() => onPage(page.page + 1)} disabled={page.page >= lastPage}>
          Next
        </button>
      </div>
    </div>
  );
}
