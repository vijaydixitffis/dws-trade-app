import { formatDate, formatTime } from '../format';
import type { Rejection, RejectionReason } from '../types';

const REASONS: Record<RejectionReason, string> = {
  LOWER_VERSION: 'Lower version',
  MATURITY_DATE_PASSED: 'Maturity date passed',
  INVALID_TRADE: 'Invalid trade',
};

export function RejectionTable({ rejections }: { rejections: Rejection[] }) {
  return (
    <div>
      <div className="toolbar">
        <span className="muted">Latest {rejections.length} rejected trades, newest first</span>
      </div>
      <table>
        <thead>
          <tr>
            <th>Rejected at</th>
            <th>Trade Id</th>
            <th className="num">Version</th>
            <th>Maturity Date</th>
            <th>Reason</th>
            <th>Rejected by</th>
            <th>Detail</th>
          </tr>
        </thead>
        <tbody>
          {rejections.map((rejection) => (
            <tr key={rejection.id}>
              <td>{formatTime(rejection.at)}</td>
              <td>{rejection.trade.tradeId ?? '-'}</td>
              <td className="num">{rejection.trade.version ?? '-'}</td>
              <td>{formatDate(rejection.trade.maturityDate)}</td>
              <td>
                <span className="pill pill-grey">{REASONS[rejection.reason] ?? rejection.reason}</span>
              </td>
              <td>{rejection.source === 'CAPTURE' ? 'Capture API' : 'Trade store'}</td>
              <td className="muted">{rejection.message}</td>
            </tr>
          ))}
          {rejections.length === 0 && (
            <tr>
              <td colSpan={7} className="empty">
                No trades have been rejected.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
