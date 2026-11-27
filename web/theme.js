// Light unless the visitor chose dark here before. Loaded in <head>, before the stylesheet,
// so the page never flashes the wrong theme.
"use strict";
(function () {
  const KEY = "inicia-theme";
  let theme = "light";
  try { theme = localStorage.getItem(KEY) === "dark" ? "dark" : "light"; } catch { /* storage blocked */ }
  document.documentElement.dataset.theme = theme;

  function label(button) {
    const dark = document.documentElement.dataset.theme === "dark";
    button.textContent = dark ? "Light" : "Dark";
    button.setAttribute("aria-label", dark ? "Switch to light theme" : "Switch to dark theme");
  }

  document.addEventListener("DOMContentLoaded", () => {
    for (const button of document.querySelectorAll("[data-theme-toggle]")) {
      label(button);
      button.addEventListener("click", () => {
        const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
        document.documentElement.dataset.theme = next;
        try { localStorage.setItem(KEY, next); } catch { /* storage blocked */ }
        label(button);
      });
    }
  });
})();
