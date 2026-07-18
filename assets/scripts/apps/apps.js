import { getDbValue } from "../system/db.js";

function resolveLabel(value, locale) {
  if (typeof value === "string") {
    return value;
  }

  if (!value) {
    return "App";
  }

  return value[locale] || value.cs || value.en || "App";
}

function renderIcons(container, icons, locale) {
  container.innerHTML = "";

  icons.forEach((entry) => {
    const iconButton = document.createElement("button");
    iconButton.className = "desktop-icon";
    iconButton.dataset.openWindow = entry.windowId;
    iconButton.type = "button";

    const iconBox = document.createElement("span");
    iconBox.className = "icon-box";
    iconBox.textContent = entry.icon || "A";

    const label = document.createElement("span");
    label.textContent = resolveLabel(entry.label, locale);

    iconButton.append(iconBox, label);
    container.append(iconButton);
  });
}

export async function initAppShortcuts(windowManager, i18n) {
  const container = document.getElementById("desktopIcons");
  const icons = (await getDbValue("desktopIcons")) || [];

  renderIcons(container, icons, i18n.getLocale());

  container.addEventListener("click", (event) => {
    const target = event.target.closest("[data-open-window]");

    if (!target) {
      return;
    }

    windowManager.openWindow(target.dataset.openWindow);
  });

  i18n.onChange((locale) => {
    renderIcons(container, icons, locale);
  });
}
