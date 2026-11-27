// The picker: reads catalog.json (the same file built into inicia.exe), lets you tick apps, and
// offers inicia.exe under a name that carries the selection: inicia_7zip_vlc.exe. The exe reads
// its own name (Selection.fromFileName), so one file on a static host serves every selection.
// A whole category goes in the name as cat-<category>, and everything as "all"
// (Catalog.expand), so even large selections keep a short name.
"use strict";

const EXE = "inicia.exe";
// Windows allows 255 characters in a file name; leave room for a browser's " (1)" and the path
const MAX_NAME = 200;

const state = { apps: [], icons: {}, selected: new Set(), categories: new Map() };
const RELEASED = new Intl.DateTimeFormat(undefined, { year: "numeric", month: "short", day: "numeric", timeZone: "UTC" });
const { el } = inicia;

/** The same selector Catalog.categoryToken makes: "Developer tools" → cat-developer-tools. */
function categoryToken(category) {
  return "cat-" + category.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
}

/** The selection in catalog order, so the same picks always give the same file name. */
function selectedIds() {
  return state.apps.map((a) => a.id).filter((id) => state.selected.has(id));
}

/** The same selector Catalog.suiteToken makes: "Vitrina" → suite-vitrina. */
function suiteToken(suite) {
  return "suite-" + suite.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
}

/** Apps by suite, for suites with more than one app. */
function suites() {
  const bySuite = new Map();
  for (const app of state.apps) {
    if (!app.suite) continue;
    if (!bySuite.has(app.suite)) bySuite.set(app.suite, []);
    bySuite.get(app.suite).push(app);
  }
  return [...bySuite].filter(([, apps]) => apps.length > 1);
}

/**
 * The selection as the exe reads it: "all", whole suites as suite-…, whole categories as cat-…,
 * and the other apps by id. The exe expands each the same way (Catalog.expand).
 */
function selectionTokens() {
  const ids = selectedIds();
  if (ids.length > 0 && ids.length === state.apps.length) return ["all"];
  const tokens = [];
  const covered = new Set();
  const whole = (apps) => apps.every((a) => state.selected.has(a.id));
  for (const [suite, apps] of suites()) {
    if (whole(apps)) {
      tokens.push(suiteToken(suite));
      apps.forEach((a) => covered.add(a.id));
    }
  }
  for (const [cat, apps] of state.categories) {
    const rest = apps.filter((a) => !covered.has(a.id));
    if (apps.length > 1 && rest.length > 1 && whole(apps)) {
      tokens.push(categoryToken(cat));
      apps.forEach((a) => covered.add(a.id));
    }
  }
  return tokens.concat(ids.filter((id) => !covered.has(id)));
}

function fileName(tokens) {
  return "inicia_" + tokens.join("_") + ".exe";
}

function nameOf(id) {
  const app = state.apps.find((a) => a.id === id);
  return app ? app.name : id;
}

function select(apps, on) {
  for (const app of apps) {
    if (on) state.selected.add(app.id);
    else state.selected.delete(app.id);
    const input = document.getElementById("app-" + app.id);
    if (input) input.checked = on;
  }
  update();
}

function card(app) {
  const input = el("input", { type: "checkbox", name: "app", value: app.id, id: "app-" + app.id,
    "aria-describedby": "desc-" + app.id });
  input.checked = state.selected.has(app.id);
  input.addEventListener("change", () => select([app], input.checked));
  const meta = [app.version || "always the latest",
    app.released ? "released " + RELEASED.format(new Date(app.released + "T00:00:00Z")) : null,
    app.installers.map((i) => i.arch).join(" · ")].filter(Boolean).join(" · ");
  const extras = [];
  if (app.suite) extras.push(el("span", { class: "pill" }, "Part of the " + app.suite + " Suite"));
  if (app.requires && app.requires.length) {
    extras.push(el("span", { class: "app-extra" }, "Also installs " + app.requires.map(nameOf).join(", ")));
  }
  if (app.note) extras.push(el("span", { class: "app-extra warn" }, app.note));

  const label = el("label", { class: "app", for: input.id },
    input,
    inicia.icon(app, state.icons),
    el("span", { class: "app-text" },
      el("span", { class: "app-name" }, app.name),
      el("span", { class: "app-desc", id: "desc-" + app.id }, app.description || ""),
      el("span", { class: "app-meta" }, meta),
      ...extras));
  if (app.homepage) {
    const home = el("a", { class: "app-home", href: app.homepage, target: "_blank", rel: "noopener",
      title: app.name + "'s website", "aria-label": app.name + "'s website" }, "↗");
    // the link sits inside the card's label: following it mustn't tick the box
    home.addEventListener("click", (e) => e.stopPropagation());
    label.append(home);
  }
  return label;
}

function render() {
  const form = document.getElementById("apps");
  for (const app of state.apps) {
    const cat = app.category || "Other";
    if (!state.categories.has(cat)) state.categories.set(cat, []);
    state.categories.get(cat).push(app);
  }
  for (const cat of [...state.categories.keys()].sort((a, b) => a.localeCompare(b))) {
    const apps = [...state.categories.get(cat)].sort((a, b) => a.name.localeCompare(b.name));
    const toggle = el("button", { type: "button", class: "link-button", "data-category": cat }, "Select all");
    toggle.addEventListener("click", () => select(apps, !apps.every((a) => state.selected.has(a.id))));
    form.append(el("fieldset", { class: "category" },
      el("legend", {}, el("span", {}, cat), toggle),
      el("div", { class: "grid" }, ...apps.map(card))));
  }
  form.hidden = false;
  const bulk = document.getElementById("bulk");
  for (const [suite, apps] of suites()) {
    const button = el("button", { type: "button", class: "secondary small suite-button", "data-suite": suite },
      "Select all " + suite + " apps");
    button.addEventListener("click", () => select(apps, !apps.every((a) => state.selected.has(a.id))));
    bulk.prepend(button);
  }
  bulk.hidden = false;
}

function setLink(a, href, download) {
  if (href) {
    a.href = href;
    a.download = download;
    a.removeAttribute("aria-disabled");
  } else {
    a.removeAttribute("href");
    a.removeAttribute("download");
    a.setAttribute("aria-disabled", "true");
  }
}

let listUrl = null;

function update() {
  const ids = selectedIds();
  const n = ids.length;
  document.getElementById("count").textContent =
    n === 0 ? "No apps selected" : n === 1 ? "1 app selected" : n + " apps selected";

  const tokens = selectionTokens();
  const name = fileName(tokens);
  const tooLong = name.length > MAX_NAME;
  const notes = state.apps.filter((a) => state.selected.has(a.id) && a.note).map((a) => a.name + ": " + a.note);
  if (tooLong) notes.unshift("That's too many apps for one file name. Download apps.txt and run: inicia install -f apps.txt");
  const note = document.getElementById("note");
  note.hidden = notes.length === 0;
  note.textContent = notes.join(" ");
  setLink(document.getElementById("get"), n > 0 && !tooLong ? EXE : null, name);

  if (listUrl) URL.revokeObjectURL(listUrl);
  listUrl = n > 0 ? URL.createObjectURL(new Blob(["# made with the inicia picker\n" + ids.join("\n") + "\n"],
    { type: "text/plain" })) : null;
  setLink(document.getElementById("list"), listUrl, "apps.txt");
  document.getElementById("copy").disabled = n === 0;

  document.getElementById("select-all").disabled = n === state.apps.length;
  document.getElementById("clear-all").disabled = n === 0;
  for (const button of document.querySelectorAll("[data-suite]")) {
    const apps = state.apps.filter((a) => a.suite === button.dataset.suite);
    button.textContent = (apps.every((a) => state.selected.has(a.id)) ? "Clear " : "Select all ")
      + button.dataset.suite + " apps";
  }
  for (const button of document.querySelectorAll("[data-category]")) {
    const all = state.categories.get(button.dataset.category).every((a) => state.selected.has(a.id));
    button.textContent = all ? "Clear" : "Select all";
    button.setAttribute("aria-label", (all ? "Clear " : "Select all in ") + button.dataset.category);
  }

  saveSelection(ids);

  // the selection lives in the address, so a link to the page shares it
  const hash = n > 0 ? "#" + tokens.join(",") : "";
  if (location.hash !== hash && !(hash === "" && location.hash === "#apps-heading")) {
    history.replaceState(null, "", hash || location.pathname + location.search);
  }
}

// Picks are kept in this browser's localStorage (never sent anywhere, unlike a cookie), so they're
// there on the next visit. "Forget my picks" removes them.
const SAVED = "inicia-picks";

function savedSelection() {
  try { return JSON.parse(localStorage.getItem(SAVED) || "[]"); } catch { return []; }
}

function saveSelection(ids) {
  try {
    if (ids.length) localStorage.setItem(SAVED, JSON.stringify(ids));
    else localStorage.removeItem(SAVED);
  } catch { /* storage blocked: nothing is kept */ }
  document.getElementById("saved").hidden = ids.length === 0;
}

function forgetSelection() {
  try { localStorage.removeItem(SAVED); } catch { /* storage blocked */ }
  select(state.apps, false);
}

async function copyCommand() {
  const button = document.getElementById("copy");
  const text = "inicia install " + selectionTokens().join(" ");
  try {
    await navigator.clipboard.writeText(text);
    button.textContent = "Copied";
  } catch {
    window.prompt("Copy this command:", text);
  }
  setTimeout(() => { button.textContent = "Copy command"; }, 1500);
}

/** The ids an address like #cat-media,git stands for, the way the exe expands them. */
function expand(tokens) {
  const ids = [];
  for (const token of tokens) {
    if (token === "all") ids.push(...state.apps.map((a) => a.id));
    else if (token.startsWith("cat-")) ids.push(...state.apps.filter((a) => categoryToken(a.category || "Other") === token).map((a) => a.id));
    else if (token.startsWith("suite-")) ids.push(...state.apps.filter((a) => a.suite && suiteToken(a.suite) === token).map((a) => a.id));
    else ids.push(token);
  }
  return ids;
}

async function main() {
  const status = document.getElementById("status");
  try {
    [state.apps, state.icons] = await Promise.all([inicia.loadCatalog(), inicia.loadIcons()]);
  } catch (e) {
    status.textContent = "The catalog couldn't be loaded (" + e.message + ").";
    return;
  }
  const known = new Set(state.apps.map((a) => a.id));
  // a shared link wins; otherwise this browser's saved picks
  const fromLink = expand(decodeURIComponent(location.hash.slice(1)).split(",")).filter((id) => known.has(id));
  for (const id of fromLink.length ? fromLink : savedSelection()) {
    if (known.has(id)) state.selected.add(id);
  }
  status.hidden = true;
  render();
  document.getElementById("copy").addEventListener("click", copyCommand);
  document.getElementById("select-all").addEventListener("click", () => select(state.apps, true));
  document.getElementById("clear-all").addEventListener("click", () => select(state.apps, false));
  document.getElementById("forget").addEventListener("click", forgetSelection);
  // a link to this same page with other picks (#7zip,vlc) changes only the address's hash
  window.addEventListener("hashchange", () => {
    const ids = expand(decodeURIComponent(location.hash.slice(1)).split(",")).filter((id) => known.has(id));
    if (!ids.length) return;
    state.selected.clear();
    select(state.apps.filter((a) => ids.includes(a.id)), true);
    for (const app of state.apps) document.getElementById("app-" + app.id).checked = state.selected.has(app.id);
  });
  for (const id of ["get", "list"]) {
    document.getElementById(id).addEventListener("click", (e) => {
      if (e.currentTarget.getAttribute("aria-disabled") === "true") e.preventDefault();
    });
  }
  update();
}

main();
