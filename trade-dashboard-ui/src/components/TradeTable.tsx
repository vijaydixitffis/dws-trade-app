import { formatCount, formatDate } from '../format';
import type { TradePage } from '../types';

interface Props {
  page: TradePage;
  /** 1-based number of the page shown. */
  pageNumber: number;
  filter: string;
  onFilter: (value: string) => void;
  onPrevious: () => void;
  onNext: () => void;
}

export function TradeTable({ page, pageNumber, filter, onFilter, onPrevious, onNext }: Props) {
  const rows = `${formatCount(page.total)}${page.totalExact ? '' : '+'}`;
  return (
    <div>
      <div className="toolbar">
        <label>
          Trade Id starts with{' '}
          <input value={filter} onChange={(e) => onFilter(e.target.value)} placeholder="e.g. T2" maxLength={64} />
        </label>
        <span className="muted">
          {rows} row{page.total === 1 && page.totalExact ? '' : 's'}, ordered by Trade Id then highest version
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
        <button onClick={onPrevious} disabled={pageNumber <= 1}>
          Previous
        </button>
        <span>Page {pageNumber}</span>
        <button onClick={onNext} disabled={page.next === null}>
          Next
        </button>
      </div>
    </div>
  );
}
