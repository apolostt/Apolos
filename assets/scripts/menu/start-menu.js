import { getDbValue } from "../system/db.js";

function resolveLabel(value, locale) {
  if (typeof value === "string") {
    return value;
  }

  if (!value) {
    return "Item";
  }

  return value[locale] || value.cs || value.en || "Item";
}

function renderSection(container, items, locale) {
  container.innerHTML = "";

  items.forEach((item) => {
    const li = document.createElement("li");
    const button = document.createElement("button");

    button.className = "menu-link";
    button.type = "button";

    if (item.target) {
      button.dataset.target = item.target;
    }

    if (item.openWindow) {
      button.dataset.openWindow = item.openWindow;
    }

    if (item.action) {
      button.dataset.action = item.action;
    }

    button.textContent = resolveLabel(item.label, locale);
    li.append(button);
    container.append(li);
  });
}

export async function initStartMenu(windowManager, i18n) {
  const menuButton = document.getElementById("menuButton");
  const mintMenu = document.getElementById("mintMenu");
  const navigationList = document.getElementById("menuNavigationList");
  const appsList = document.getElementById("menuAppsList");
  const systemList = document.getElementById("menuSystemList");
  const startMenu = (await getDbValue("startMenu")) || {};

  function closeMenu() {
    mintMenu.setAttribute("hidden", "");
    menuButton.setAttribute("aria-expanded", "false");
    menuButton.classList.remove("active");
  }

  function openMenu() {
    mintMenu.removeAttribute("hidden");
    menuButton.setAttribute("aria-expanded", "true");
    menuButton.classList.add("active");
  }

  menuButton.addEventListener("click", () => {
    const isHidden = mintMenu.hasAttribute("hidden");

    if (isHidden) {
      openMenu();
    } else {
      closeMenu();
    }
  });

  function renderMenuByLocale(locale) {
    renderSection(navigationList, startMenu.navigation || [], locale);
    renderSection(appsList, startMenu.apps || [], locale);
    renderSection(systemList, startMenu.system || [], locale);
  }

  renderMenuByLocale(i18n.getLocale());

  mintMenu.addEventListener("click", (event) => {
    const link = event.target.closest(".menu-link");

    if (!link) {
      return;
    }

    const target = link.dataset.target;
    const openWindowId = link.dataset.openWindow;
    const action = link.dataset.action;

    if (target) {
      const section = document.getElementById(target);

      if (section) {
        windowManager.openWindow("homeWindow");
        section.scrollIntoView({ behavior: "smooth", block: "start" });
      }
    }

    if (openWindowId) {
      windowManager.openWindow(openWindowId);
    }

    if (action === "show-all") {
      windowManager.showAllWindows();
    }

    if (action === "hide-all") {
      windowManager.minimizeAllWindows();
    }

    closeMenu();
  });

  i18n.onChange((locale) => {
    renderMenuByLocale(locale);
  });

  document.addEventListener("click", (event) => {
    if (
      !mintMenu.hasAttribute("hidden") &&
      !mintMenu.contains(event.target) &&
      !menuButton.contains(event.target)
    ) {
      closeMenu();
    }
  });
}
