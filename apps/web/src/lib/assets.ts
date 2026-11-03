import type { Fixture } from "./api";

/** The site-record asset a scanned label names: its fixture and asset id. */
export interface ScannedAsset { fixtureId: string; assetId: string; displayName: string }

/**
 * Matches a scanned QR or barcode payload to an asset on a site record. The payload is the asset id itself, or text or a
 * URL that contains it as a whole token ("https://site/assets/LAB-SW-01", "LAB-SW-01 rack 2"). Only ids from the site
 * records count: the app never invents an asset from a code. The longest id wins when one is a prefix of another. The
 * same rule as the Android client's `assetFromScan`.
 */
export function assetFromScan(payload: string, fixtures: Fixture[] | undefined): ScannedAsset | undefined {
  const text = payload.trim();
  if (!text || !fixtures) return undefined;
  const whole = text.toLowerCase();
  const tokens = new Set(text.split(/[^A-Za-z0-9._-]+/).filter(Boolean).map((t) => t.toLowerCase()));
  return fixtures.flatMap((f) => f.assets.map((a) => ({ fixtureId: f.fixtureId, assetId: a.assetId, displayName: a.displayName })))
    .filter((a) => a.assetId.toLowerCase() === whole || tokens.has(a.assetId.toLowerCase()))
    .sort((a, b) => b.assetId.length - a.assetId.length)[0];
}
