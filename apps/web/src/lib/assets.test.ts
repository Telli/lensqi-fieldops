import { expect, it } from "vitest";
import type { Fixture } from "./api";
import { assetFromScan } from "./assets";

const fixtures = [
  { fixtureId: "LAB-RACK-01", assets: [{ assetId: "LAB-SW-01", displayName: "Lab switch", kind: "switch" }, { assetId: "LAB-SW-01-PSU", displayName: "Switch PSU", kind: "psu" }] },
  { fixtureId: "LAB-RACK-02", assets: [{ assetId: "LAB-NODE-A", displayName: "Node A", kind: "node" }] },
] as Fixture[];

it("matches the id itself, a URL or text carrying it as a whole token, case-insensitively", () => {
  expect(assetFromScan("LAB-SW-01", fixtures)).toMatchObject({ fixtureId: "LAB-RACK-01", assetId: "LAB-SW-01" });
  expect(assetFromScan("https://site.example/assets/lab-node-a?x=1", fixtures)).toMatchObject({ assetId: "LAB-NODE-A" });
  expect(assetFromScan("  LAB-SW-01 rack 2 ", fixtures)?.assetId).toBe("LAB-SW-01");
});

it("never invents an asset and prefers the longest id when one is a prefix of another", () => {
  expect(assetFromScan("LAB-SW-02", fixtures)).toBeUndefined();
  expect(assetFromScan("LAB-SW-0", fixtures)).toBeUndefined();
  expect(assetFromScan("", fixtures)).toBeUndefined();
  expect(assetFromScan("LAB-SW-01", undefined)).toBeUndefined();
  expect(assetFromScan("rack LAB-SW-01-PSU LAB-SW-01", fixtures)?.assetId).toBe("LAB-SW-01-PSU");
});
