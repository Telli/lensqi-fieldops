import type { Origin } from "./api";

/**
 * The last capture an inspection could not finish uploading, kept on the device with the key it was sent under, so "try
 * again with this photo" after a dropped connection sends the same bytes under the same key and the server recognises a
 * copy it already recorded. One capture per inspection; it is dropped once the upload is answered.
 */
export interface SavedCapture { inspectionId: string; blob: Blob; name: string; source: Origin; key: string; observedAt: string; savedAt: number }

const DB = "lensqi-fieldops", STORE = "captures", BY_INSPECTION = "inspectionId";
/** One record per capture, so a later failure never replaces an earlier photo still waiting to be sent. */
const recordId = (c: Pick<SavedCapture, "inspectionId" | "key">) => `${c.inspectionId}:${c.key}`;

function open(): Promise<IDBDatabase | null> {
  return new Promise((resolve) => {
    try {
      if (typeof indexedDB === "undefined") { resolve(null); return; }
      const req = indexedDB.open(DB, 2);
      req.onupgradeneeded = () => {
        const db = req.result;
        // Version 1 kept one capture per inspection under the inspection id; its records are not carried over.
        if (db.objectStoreNames.contains(STORE)) db.deleteObjectStore(STORE);
        db.createObjectStore(STORE, { keyPath: "id" }).createIndex(BY_INSPECTION, "inspectionId", { unique: false });
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => resolve(null);
      req.onblocked = () => resolve(null);
    } catch { resolve(null); }
  });
}

/** Runs one request; a write resolves only when its transaction has committed, so a reported save is really on the device. */
function run<T>(mode: IDBTransactionMode, act: (store: IDBObjectStore) => IDBRequest<T>): Promise<T | null> {
  return open().then((db) => new Promise<T | null>((resolve) => {
    if (!db) { resolve(null); return; }
    try {
      const tx = db.transaction(STORE, mode);
      const req = act(tx.objectStore(STORE));
      let result: T | null = null;
      req.onsuccess = () => { result = req.result; };
      req.onerror = () => { result = null; };
      tx.oncomplete = () => { db.close(); resolve(result); };
      tx.onabort = () => { db.close(); resolve(null); };
      tx.onerror = () => { result = null; };
    } catch { db.close(); resolve(null); }
  }));
}

/** True when the capture is on the device; false when the browser gives no storage (then there is nothing to retry later). */
export const saveCapture = (c: SavedCapture) => run("readwrite", (s) => s.put({ ...c, id: recordId(c) })).then((r) => r !== null);
/** Every capture of the inspection still waiting to be sent, oldest first. */
export const loadCaptures = (inspectionId: string) =>
  run<(SavedCapture & { id: string })[]>("readonly", (s) => s.index(BY_INSPECTION).getAll(inspectionId))
    .then((r) => (r ?? []).map(({ id: _id, ...c }) => c as SavedCapture).sort((a, b) => a.savedAt - b.savedAt));
export const clearCapture = (inspectionId: string, key: string) => run("readwrite", (s) => s.delete(recordId({ inspectionId, key }))).then(() => undefined);
