import type { Origin } from "./api";

/**
 * The last capture an inspection could not finish uploading, kept on the device with the key it was sent under, so "try
 * again with this photo" after a dropped connection sends the same bytes under the same key and the server recognises a
 * copy it already recorded. One capture per inspection; it is dropped once the upload is answered.
 */
export interface SavedCapture { inspectionId: string; blob: Blob; name: string; source: Origin; key: string; observedAt: string; savedAt: number }

const DB = "lensqi-fieldops", STORE = "captures";

function open(): Promise<IDBDatabase | null> {
  return new Promise((resolve) => {
    try {
      if (typeof indexedDB === "undefined") { resolve(null); return; }
      const req = indexedDB.open(DB, 1);
      req.onupgradeneeded = () => { if (!req.result.objectStoreNames.contains(STORE)) req.result.createObjectStore(STORE, { keyPath: "inspectionId" }); };
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
export const saveCapture = (c: SavedCapture) => run("readwrite", (s) => s.put(c)).then((r) => r !== null);
export const loadCapture = (inspectionId: string) => run<SavedCapture | undefined>("readonly", (s) => s.get(inspectionId)).then((r) => r ?? null);
export const clearCapture = (inspectionId: string) => run("readwrite", (s) => s.delete(inspectionId)).then(() => undefined);
