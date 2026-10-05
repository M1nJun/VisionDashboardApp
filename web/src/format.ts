export const fmtInt = (value: number | null | undefined): string =>
  value === null || value === undefined ? "–" : value.toLocaleString("en-US");

/** Three decimals throughout: defect rates here live in the 0.0xx% range, and two
 *  decimals would round most of the fleet to the same number. */
export const fmtPct = (value: number | null | undefined): string =>
  value === null || value === undefined ? "–" : `${value.toFixed(3)}%`;

export const fmtClock = (iso: string | null | undefined): string => {
  if (!iso) return "–";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "–" : d.toLocaleTimeString("en-GB", { hour12: false });
};

/** The judgement whose rate colours a cell - NG for every current vision type. */
export function primaryJudgement<T extends { drivesColor: boolean }>(judgements: T[]): T | undefined {
  return judgements.find((j) => j.drivesColor);
}
