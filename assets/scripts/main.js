import { initAppShortcuts } from "./apps/apps.js";
import { initAppContent } from "./apps/app-content.js";
import { initStartMenu } from "./menu/start-menu.js";
import { initClock } from "./system/clock.js";
import { initDatabase } from "./system/db.js";
import { initI18n } from "./system/i18n.js";
import { initThemeToggle } from "./system/theme.js";
import { initWindowManager } from "./windows/window-manager.js";

async function init() {
	await initDatabase();

	const i18n = await initI18n();
	const windowManager = initWindowManager();

	await initAppShortcuts(windowManager, i18n);
	await initStartMenu(windowManager, i18n);
	await initAppContent();
	initClock();
	initThemeToggle(i18n);
}

init();
