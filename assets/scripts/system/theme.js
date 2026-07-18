export function initThemeToggle(i18n) {
  const themeToggle = document.getElementById("themeToggle");
  const langToggle = document.getElementById("langToggle");

  function syncThemeLabel() {
    const isLight = document.body.classList.contains("light-theme");
    themeToggle.textContent = isLight ? i18n.t("themeDark") : i18n.t("themeLight");
  }

  themeToggle.addEventListener("click", () => {
    const isLight = document.body.classList.toggle("light-theme");

    themeToggle.setAttribute("aria-pressed", String(isLight));
    syncThemeLabel();
  });

  langToggle.addEventListener("click", async () => {
    const next = i18n.getLocale() === "cs" ? "en" : "cs";
    await i18n.setLocale(next);
  });

  i18n.onChange((locale) => {
    langToggle.textContent = locale === "cs" ? "EN" : "CS";
    syncThemeLabel();
  });

  langToggle.textContent = i18n.getLocale() === "cs" ? "EN" : "CS";
  syncThemeLabel();
}
