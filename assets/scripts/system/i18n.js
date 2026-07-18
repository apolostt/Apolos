import { getDbValue, setDbValue } from "./db.js";

function resolve(dict, locale, key) {
  const scoped = dict?.[locale] || {};
  return scoped[key] || key;
}

export async function initI18n() {
  const i18nData = (await getDbValue("i18n")) || {};
  let locale = (await getDbValue("locale")) || "cs";
  const listeners = [];

  function t(key) {
    return resolve(i18nData, locale, key);
  }

  function applyStaticTexts() {
    document.querySelectorAll("[data-i18n]").forEach((node) => {
      const key = node.dataset.i18n;
      node.textContent = t(key);
    });
  }

  async function setLocale(nextLocale) {
    locale = nextLocale;
    await setDbValue("locale", nextLocale);
    applyStaticTexts();
    listeners.forEach((callback) => callback(locale));
  }

  function onChange(callback) {
    listeners.push(callback);
  }

  applyStaticTexts();

  return {
    t,
    getLocale: () => locale,
    setLocale,
    onChange,
    applyStaticTexts,
  };
}
