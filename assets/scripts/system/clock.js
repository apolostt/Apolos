export function initClock() {
  const clock = document.getElementById("clock");

  function updateClock() {
    const now = new Date();
    const value = now.toLocaleTimeString("cs-CZ", {
      hour: "2-digit",
      minute: "2-digit",
    });

    clock.textContent = value;
  }

  updateClock();
  setInterval(updateClock, 1000);
}
