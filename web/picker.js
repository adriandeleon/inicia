// The picker: reads catalog.json (the same file built into inicia.exe), lets you tick apps, and
// offers inicia.exe under a name that carries the selection: inicia_7zip_vlc.exe. The exe reads
// its own name (Selection.fromFileName), so one file on a static host serves every selection.
"use strict";

const EXE = "inicia.exe";
// Windows allows 255 characters in a file name; leave room for a browser's " (1)" and the path
const MAX_NAME = 200;

const state = { apps: [], selected: new Set() };

function fileName(ids) {
  return "inicia_" + ids.join("_") + ".exe";
}

/** The selection in catalog order, so the same picks always give the same file name. */
function selectedIds() {
  return state.apps.map((a) => a.id).filter((id) => state.selected.has(id));
}

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") node.className = v;
    else node.setAttribute(k, v);
  }
  for (const c of children) node.append(c);
  return node;
}

function render(apps) {
  const form = document.getElementById("apps");
  const byCategory = new Map();
  for (const app of apps) {
    const cat = app.category || "Other";
    if (!byCategory.has(cat)) byCategory.set(cat, []);
    byCategory.get(cat).push(app);
  }
  for (const cat of [...byCategory.keys()].sort((a, b) => a.localeCompare(b))) {
    const list = el("div", { class: "grid" });
    for (const app of byCategory.get(cat)) {
      const input = el("input", { type: "checkbox", name: "app", value: app.id, id: "app-" + app.id });
      input.checked = state.selected.has(app.id);
      input.addEventListener("change", () => {
        if (input.checked) state.selected.add(app.id);
        else state.selected.delete(app.id);
        update();
      });
      const meta = [app.version ? app.version : "latest",
        app.installers.map((i) => i.arch).join(" · ")].join(" · ");
      list.append(el("label", { class: "app", for: input.id },
        input,
        el("span", { class: "app-text" },
          el("span", { class: "app-name" }, app.name),
          el("span", { class: "app-desc" }, app.description || ""),
          el("span", { class: "app-meta" }, meta))));
    }
    form.append(el("fieldset", { class: "category" }, el("legend", {}, cat), list));
  }
  form.hidden = false;
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

  const name = fileName(ids);
  const tooLong = name.length > MAX_NAME;
  const note = document.getElementById("note");
  note.hidden = !tooLong;
  note.textContent = tooLong
    ? "That's too many apps for one file name. Download apps.txt and run: inicia install -f apps.txt"
    : "";
  setLink(document.getElementById("get"), n > 0 && !tooLong ? EXE : null, name);

  if (listUrl) URL.revokeObjectURL(listUrl);
  listUrl = n > 0 ? URL.createObjectURL(new Blob(["# made with the inicia picker\n" + ids.join("\n") + "\n"],
    { type: "text/plain" })) : null;
  setLink(document.getElementById("list"), listUrl, "apps.txt");
  document.getElementById("copy").disabled = n === 0;

  // the selection lives in the address, so a link to the page shares it
  const hash = n > 0 ? "#" + ids.join(",") : "";
  if (location.hash !== hash) history.replaceState(null, "", hash || location.pathname + location.search);
}

async function copyCommand() {
  const button = document.getElementById("copy");
  const text = "inicia install " + selectedIds().join(" ");
  try {
    await navigator.clipboard.writeText(text);
    button.textContent = "Copied";
  } catch {
    window.prompt("Copy this command:", text);
  }
  setTimeout(() => { button.textContent = "Copy command"; }, 1500);
}

async function main() {
  const status = document.getElementById("status");
  try {
    const res = await fetch("catalog.json", { cache: "no-cache" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    const catalog = await res.json();
    if (catalog.catalog !== 1) throw new Error("catalog format " + catalog.catalog + " isn't one this page reads");
    state.apps = catalog.apps;
  } catch (e) {
    status.textContent = "The catalog couldn't be loaded (" + e.message + ").";
    return;
  }
  const known = new Set(state.apps.map((a) => a.id));
  for (const id of decodeURIComponent(location.hash.slice(1)).split(",")) {
    if (known.has(id)) state.selected.add(id);
  }
  status.hidden = true;
  render(state.apps);
  document.getElementById("copy").addEventListener("click", copyCommand);
  for (const id of ["get", "list"]) {
    document.getElementById(id).addEventListener("click", (e) => {
      if (e.currentTarget.getAttribute("aria-disabled") === "true") e.preventDefault();
    });
  }
  update();
}

main();
