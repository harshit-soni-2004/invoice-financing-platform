/** Sample buyer directory shown on the dashboard. No service provides buyer data yet. */
export type CreditRating = 'AAA' | 'AA' | 'A' | 'BBB';

export interface PotentialBuyer {
  id: string;
  name: string;
  industry: string;
  city: string;
  rating: CreditRating;
  /** Average days from invoice to payment over the last year. */
  avgDaysToPay: number;
  /** Annual purchases from small suppliers, in rupees. */
  annualPurchases: number;
  /** How well the buyer fits invoice financing, 0 to 100. */
  matchScore: number;
}

export const POTENTIAL_BUYERS: readonly PotentialBuyer[] = [
  {
    id: 'b-01',
    name: 'Northwind Retail Pvt Ltd',
    industry: 'Retail',
    city: 'Mumbai',
    rating: 'AAA',
    avgDaysToPay: 32,
    annualPurchases: 184_000_000,
    matchScore: 94,
  },
  {
    id: 'b-02',
    name: 'Sahyadri Auto Components Ltd',
    industry: 'Automotive',
    city: 'Pune',
    rating: 'AA',
    avgDaysToPay: 45,
    annualPurchases: 126_500_000,
    matchScore: 88,
  },
  {
    id: 'b-03',
    name: 'Ganga Pharma Distributors',
    industry: 'Healthcare',
    city: 'Lucknow',
    rating: 'AA',
    avgDaysToPay: 38,
    annualPurchases: 92_000_000,
    matchScore: 86,
  },
  {
    id: 'b-04',
    name: 'Coastal Foods & Beverages',
    industry: 'FMCG',
    city: 'Kochi',
    rating: 'A',
    avgDaysToPay: 52,
    annualPurchases: 67_800_000,
    matchScore: 79,
  },
  {
    id: 'b-05',
    name: 'Deccan Infra Projects Ltd',
    industry: 'Construction',
    city: 'Hyderabad',
    rating: 'A',
    avgDaysToPay: 68,
    annualPurchases: 210_000_000,
    matchScore: 74,
  },
  {
    id: 'b-06',
    name: 'Indus Textiles Exports',
    industry: 'Textiles',
    city: 'Surat',
    rating: 'BBB',
    avgDaysToPay: 61,
    annualPurchases: 48_300_000,
    matchScore: 68,
  },
  {
    id: 'b-07',
    name: 'Bluepeak Electronics Pvt Ltd',
    industry: 'Electronics',
    city: 'Bengaluru',
    rating: 'AA',
    avgDaysToPay: 41,
    annualPurchases: 143_900_000,
    matchScore: 90,
  },
  {
    id: 'b-08',
    name: 'Aravali Logistics Ltd',
    industry: 'Logistics',
    city: 'Gurugram',
    rating: 'A',
    avgDaysToPay: 47,
    annualPurchases: 75_200_000,
    matchScore: 81,
  },
];

export const BUYER_INDUSTRIES: readonly string[] = [...new Set(POTENTIAL_BUYERS.map((b) => b.industry))].sort();

/** Compact Indian-style amount, e.g. ₹18.4 Cr or ₹48.3 L. */
export function formatCrore(amount: number): string {
  if (amount >= 10_000_000) {
    return `₹${(amount / 10_000_000).toFixed(1)} Cr`;
  }
  return `₹${(amount / 100_000).toFixed(1)} L`;
}
