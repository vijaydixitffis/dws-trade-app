/** ISO date (2027-05-20) to the dd/MM/yyyy form used in the trade store specification. */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) {
    return '-';
  }
  const [year, month, day] = iso.slice(0, 10).split('-');
  return `${day}/${month}/${year}`;
}

export function formatTime(isoInstant: string): string {
  return new Date(isoInstant).toLocaleString();
}

export function formatCount(value: number): string {
  return value.toLocaleString('en-GB');
}
