// The news page: news.json, newest first, each entry's apps shown from the catalog.
"use strict";

(async () => {
  const { el } = inicia;
  const list = document.getElementById("news");
  const status = document.getElementById("status");
  let items, apps, icons;
  try {
    [items, apps, icons] = await Promise.all([inicia.json("news.json"), inicia.loadCatalog(), inicia.loadIcons()]);
  } catch (e) {
    status.textContent = "The news couldn't be loaded (" + e.message + ").";
    return;
  }
  const byId = new Map(apps.map((a) => [a.id, a]));
  const dateFormat = new Intl.DateTimeFormat(undefined, { year: "numeric", month: "long", day: "numeric", timeZone: "UTC" });

  for (const item of [...items].sort((a, b) => b.date.localeCompare(a.date))) {
    const shown = (item.apps || []).map((id) => byId.get(id)).filter(Boolean);
    list.append(el("li", { class: "news-item" },
      el("time", { datetime: item.date }, dateFormat.format(new Date(item.date + "T00:00:00Z"))),
      el("h2", {}, item.title),
      item.text ? el("p", {}, item.text) : null,
      shown.length ? el("ul", { class: "news-apps", "aria-label": "Apps" },
        ...shown.map((app) => el("li", {},
          inicia.icon(app, icons),
          el("a", { href: "./#" + app.id }, app.name),
          el("span", { class: "version" }, app.version || "latest")))) : null));
  }
  status.hidden = true;
  list.hidden = false;
})();
