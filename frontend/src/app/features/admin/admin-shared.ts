export interface Notice {
  tone: 'success' | 'warning' | 'danger';
  message: string;
}

/** `?page=` is one-based for people; the APIs are zero-based. */
export function pageParam(value: string | undefined): number {
  const page = Number(value);
  return Number.isInteger(page) && page >= 1 ? page - 1 : 0;
}

export function pageLink(index: number): Record<string, number | null> {
  return { page: index === 0 ? null : index + 1 };
}
