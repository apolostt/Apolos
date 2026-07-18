export function initWindowManager() {
  const taskbarApps = document.getElementById("taskbarApps");
  const windows = document.querySelectorAll(".window");

  let zCounter = 20;
  const windowState = new Map();

  windows.forEach((windowElement) => {
    windowState.set(windowElement.id, {
      isOpen: !windowElement.classList.contains("hidden-window"),
      isMinimized: windowElement.classList.contains("hidden-window"),
      isMaximized: false,
      restore: null,
    });
  });

  function focusWindow(windowElement) {
    if (!windowElement || windowElement.classList.contains("hidden-window")) {
      return;
    }

    windows.forEach((item) => item.classList.remove("active-window"));
    windowElement.classList.add("active-window");
    zCounter += 1;
    windowElement.style.zIndex = zCounter;
  }

  function getTaskButton(windowId) {
    return taskbarApps.querySelector(`[data-task-window="${windowId}"]`);
  }

  function ensureTaskButton(windowElement) {
    const windowId = windowElement.id;

    if (getTaskButton(windowId)) {
      return;
    }

    const taskButton = document.createElement("button");
    taskButton.type = "button";
    taskButton.className = "task-btn";
    taskButton.dataset.taskWindow = windowId;
    taskButton.textContent = windowElement.dataset.app || windowId;

    taskButton.addEventListener("click", () => {
      toggleWindow(windowId);
    });

    taskbarApps.append(taskButton);
  }

  function syncTaskState(windowElement) {
    const state = windowState.get(windowElement.id);
    const taskButton = getTaskButton(windowElement.id);

    if (!taskButton || !state) {
      return;
    }

    taskButton.classList.toggle("active", !state.isMinimized && windowElement.classList.contains("active-window"));
    taskButton.classList.toggle("minimized", state.isMinimized);
  }

  function activateTopMostWindow() {
    const visible = Array.from(windows).filter((windowElement) => {
      const state = windowState.get(windowElement.id);
      return state && state.isOpen && !state.isMinimized;
    });

    if (visible.length === 0) {
      return;
    }

    visible.sort((a, b) => {
      const zA = Number(a.style.zIndex || 0);
      const zB = Number(b.style.zIndex || 0);
      return zB - zA;
    });

    focusWindow(visible[0]);
  }

  function syncAllTasks() {
    windows.forEach((windowElement) => {
      const state = windowState.get(windowElement.id);

      if (state && state.isOpen) {
        ensureTaskButton(windowElement);
      }

      syncTaskState(windowElement);
    });
  }

  function openWindow(windowId) {
    const windowElement = document.getElementById(windowId);
    const state = windowState.get(windowId);

    if (!windowElement || !state) {
      return;
    }

    state.isOpen = true;
    state.isMinimized = false;
    windowElement.classList.remove("hidden-window");
    ensureTaskButton(windowElement);
    focusWindow(windowElement);
    syncAllTasks();
  }

  function minimizeWindow(windowElement) {
    const state = windowState.get(windowElement.id);

    if (!state) {
      return;
    }

    state.isOpen = true;
    state.isMinimized = true;
    windowElement.classList.add("hidden-window");
    windowElement.classList.remove("active-window");
    activateTopMostWindow();
    syncAllTasks();
  }

  function closeWindow(windowElement) {
    const state = windowState.get(windowElement.id);

    if (!state) {
      return;
    }

    state.isOpen = false;
    state.isMinimized = true;
    state.isMaximized = false;
    state.restore = null;

    windowElement.classList.remove("maximized", "active-window");
    windowElement.classList.add("hidden-window");
    windowElement.style.removeProperty("left");
    windowElement.style.removeProperty("top");
    windowElement.style.removeProperty("width");
    windowElement.style.removeProperty("height");
    windowElement.style.removeProperty("transform");

    const taskButton = getTaskButton(windowElement.id);

    if (taskButton) {
      taskButton.remove();
    }

    activateTopMostWindow();
    syncAllTasks();
  }

  function maximizeWindow(windowElement) {
    const state = windowState.get(windowElement.id);

    if (!state || state.isMaximized) {
      return;
    }

    const rect = windowElement.getBoundingClientRect();

    state.restore = {
      left: windowElement.style.left,
      top: windowElement.style.top,
      width: windowElement.style.width,
      height: windowElement.style.height,
      transform: windowElement.style.transform,
      currentLeft: `${rect.left}px`,
      currentTop: `${rect.top}px`,
      currentWidth: `${rect.width}px`,
      currentHeight: `${rect.height}px`,
    };

    state.isMaximized = true;
    windowElement.classList.add("maximized");
    focusWindow(windowElement);
    syncAllTasks();
  }

  function restoreWindow(windowElement) {
    const state = windowState.get(windowElement.id);

    if (!state || !state.isMaximized) {
      return;
    }

    const restore = state.restore;
    windowElement.classList.remove("maximized");

    if (restore) {
      windowElement.style.left = restore.left || restore.currentLeft;
      windowElement.style.top = restore.top || restore.currentTop;
      windowElement.style.width = restore.width || "";
      windowElement.style.height = restore.height || "";
      windowElement.style.transform = restore.transform || "none";
    }

    state.isMaximized = false;
    state.restore = null;
    focusWindow(windowElement);
    syncAllTasks();
  }

  function toggleMaximize(windowElement) {
    const state = windowState.get(windowElement.id);

    if (!state) {
      return;
    }

    if (state.isMaximized) {
      restoreWindow(windowElement);
      return;
    }

    maximizeWindow(windowElement);
  }

  function toggleWindow(windowId) {
    const windowElement = document.getElementById(windowId);
    const state = windowState.get(windowId);

    if (!windowElement || !state) {
      return;
    }

    if (!state.isOpen || state.isMinimized) {
      openWindow(windowId);
      return;
    }

    if (windowElement.classList.contains("active-window")) {
      minimizeWindow(windowElement);
    } else {
      focusWindow(windowElement);
      syncAllTasks();
    }
  }

  function minimizeAllWindows() {
    windows.forEach((windowElement) => {
      const state = windowState.get(windowElement.id);

      if (state && state.isOpen) {
        minimizeWindow(windowElement);
      }
    });
  }

  function showAllWindows() {
    windows.forEach((windowElement) => {
      const state = windowState.get(windowElement.id);

      if (state && state.isOpen) {
        openWindow(windowElement.id);
      }
    });
  }

  function setupWindowControls() {
    windows.forEach((windowElement) => {
      const titlebar = windowElement.querySelector("[data-drag-handle]");

      windowElement.addEventListener("mousedown", () => {
        focusWindow(windowElement);
        syncAllTasks();
      });

      titlebar.addEventListener("mousedown", (event) => {
        if (event.target.closest("[data-window-action]")) {
          return;
        }

        const state = windowState.get(windowElement.id);

        if (!state || state.isMaximized) {
          return;
        }

        focusWindow(windowElement);

        const rect = windowElement.getBoundingClientRect();
        const startX = event.clientX;
        const startY = event.clientY;
        const startLeft = rect.left;
        const startTop = rect.top;

        windowElement.style.left = `${startLeft}px`;
        windowElement.style.top = `${startTop}px`;
        windowElement.style.transform = "none";

        function onMove(moveEvent) {
          const nextLeft = startLeft + (moveEvent.clientX - startX);
          const nextTop = startTop + (moveEvent.clientY - startY);

          windowElement.style.left = `${Math.max(0, nextLeft)}px`;
          windowElement.style.top = `${Math.max(36, nextTop)}px`;
        }

        function onUp() {
          document.removeEventListener("mousemove", onMove);
          document.removeEventListener("mouseup", onUp);
        }

        document.addEventListener("mousemove", onMove);
        document.addEventListener("mouseup", onUp);
      });

      titlebar.addEventListener("dblclick", () => {
        toggleMaximize(windowElement);
      });

      windowElement.querySelectorAll("[data-window-action]").forEach((button) => {
        button.addEventListener("click", () => {
          const action = button.dataset.windowAction;

          if (action === "close") {
            closeWindow(windowElement);
            return;
          }

          if (action === "min") {
            minimizeWindow(windowElement);
            return;
          }

          if (action === "max") {
            toggleMaximize(windowElement);
          }
        });
      });
    });
  }

  setupWindowControls();
  syncAllTasks();
  focusWindow(document.getElementById("homeWindow"));
  syncAllTasks();

  return {
    openWindow,
    minimizeAllWindows,
    showAllWindows,
  };
}
