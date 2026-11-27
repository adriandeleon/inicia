// Shared by the picker and the news page: the catalog, the icons, and a small element helper.
"use strict";

const inicia = (() => {
  function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === "class") node.className = v;
      else node.setAttribute(k, v === true ? "" : v);
    }
    for (const c of children) if (c !== null && c !== undefined) node.append(c);
    return node;
  }

  async function json(path) {
    const res = await fetch(path, { cache: "no-cache" });
    if (!res.ok) throw new Error(path + ": HTTP " + res.status);
    return res.json();
  }

  /** The catalog's apps, by id and in order. */
  async function loadCatalog() {
    const catalog = await json("catalog.json");
    if (catalog.catalog !== 1) throw new Error("catalog format " + catalog.catalog + " isn't one this page reads");
    return catalog.apps;
  }

  /** id → icon path; assemble.sh writes icons/index.json from what's in icons/. */
  async function loadIcons() {
    try { return await json("icons/index.json"); } catch { return {}; }
  }

  /** The app's icon, or its initial on a plain tile when there's none. */
  function icon(app, icons) {
    if (icons[app.id]) {
      return el("img", { class: "app-icon", src: icons[app.id], alt: "", width: 36, height: 36, loading: "lazy" });
    }
    return el("span", { class: "app-icon monogram", "aria-hidden": "true" }, app.name.replace(/^\W+/, "").charAt(0));
  }

  return { el, json, loadCatalog, loadIcons, icon };
})();
