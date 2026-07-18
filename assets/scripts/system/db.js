const DB_NAME = "apolos-desktop-db";
const STORE_NAME = "kv";
const DB_VERSION = 1;

function openDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);

    request.onupgradeneeded = () => {
      const db = request.result;

      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME, { keyPath: "key" });
      }
    };

    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function readValue(key) {
  const db = await openDatabase();

  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, "readonly");
    const store = tx.objectStore(STORE_NAME);
    const request = store.get(key);

    request.onsuccess = () => {
      resolve(request.result ? request.result.value : null);
    };

    request.onerror = () => reject(request.error);
  });
}

async function writeValue(key, value) {
  const db = await openDatabase();

  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, "readwrite");
    const store = tx.objectStore(STORE_NAME);

    store.put({ key, value });

    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

async function seedDatabase() {
  const seeded = await readValue("seeded");

  if (seeded) {
    return;
  }

  const response = await fetch("assets/data/bootstrap.json");

  if (!response.ok) {
    throw new Error(`Bootstrap loading failed: ${response.status}`);
  }

  const payload = await response.json();

  await writeValue("locale", payload.locale || "cs");
  await writeValue("i18n", payload.i18n || {});
  await writeValue("desktopIcons", payload.desktopIcons || []);
  await writeValue("startMenu", payload.startMenu || {});

  const filesData = await fetch("assets/data/apps/files.json").then((r) => r.json());
  const notesData = await fetch("assets/data/apps/notes.json").then((r) => r.json());
  const musicData = await fetch("assets/data/apps/music.json").then((r) => r.json());
  const statsData = await fetch("assets/data/apps/stats.json").then((r) => r.json());

  await writeValue("appContent", {
    filesWindow: filesData,
    notesWindow: notesData,
    musicWindow: musicData,
    statsWindow: statsData,
  });

  await writeValue("seeded", true);
}

export async function initDatabase() {
  await seedDatabase();
}

export async function getDbValue(key) {
  return readValue(key);
}

export async function setDbValue(key, value) {
  return writeValue(key, value);
}
