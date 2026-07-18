import { getDbValue, setDbValue } from "../system/db.js";

function renderAppCard(container, payload) {
  const title = payload.title || "Aplikace";
  const items = Array.isArray(payload.items) ? payload.items : [];

  const html = [
    `<h2>${title}</h2>`,
    ...items.map((item) => `<p>${item}</p>`),
  ].join("");

  container.innerHTML = html;
}

function renderError(container) {
  container.innerHTML = "<h2>Chyba</h2><p>Data aplikace se nepodařilo načíst.</p>";
}

function renderNotesEditor(container, payload, onSave) {
  const title = payload.title || "Poznámky";
  const items = Array.isArray(payload.items) ? payload.items : [];

  container.innerHTML = "";

  const heading = document.createElement("h2");
  heading.textContent = title;

  const textarea = document.createElement("textarea");
  textarea.className = "notes-editor";
  textarea.value = items.join("\n");

  const button = document.createElement("button");
  button.className = "notes-save-btn";
  button.type = "button";
  button.textContent = "Uložit";

  button.addEventListener("click", () => {
    const updatedItems = textarea.value
      .split("\n")
      .map((line) => line.trim())
      .filter(Boolean);

    onSave({
      ...payload,
      items: updatedItems,
    });
  });

  container.append(heading, textarea, button);
}

export async function initAppContent() {
  const targets = document.querySelectorAll("[data-app-content]");
  const appContent = (await getDbValue("appContent")) || {};

  Array.from(targets).forEach((target) => {
    const key = target.dataset.appContent;
    const payload = appContent[key];

    if (!payload) {
      renderError(target);
      return;
    }

    if (key === "notesWindow") {
      renderNotesEditor(target, payload, async (nextValue) => {
        appContent.notesWindow = nextValue;
        await setDbValue("appContent", appContent);
        renderNotesEditor(target, nextValue, async (newerValue) => {
          appContent.notesWindow = newerValue;
          await setDbValue("appContent", appContent);
        });
      });
      return;
    }

    renderAppCard(target, payload);
  });
}
