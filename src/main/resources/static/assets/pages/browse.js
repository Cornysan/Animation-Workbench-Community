/**
 * Der Katalog: sortieren, nach Schlagwort eingrenzen, blaettern.
 *
 * Jede Eingrenzung steht in der ADRESSE, nicht in einem Zustand im Speicher.
 * Das ist der Grund, warum es hier kaum Code gibt: der Zurueck-Knopf, ein
 * neuer Tab und ein geteilter Link tun damit von selbst das Richtige, und die
 * Seite hat nur eine Aufgabe - lesen, was in der Adresse steht.
 */
(async () => {
  const { api, el, notice, clipCard, previewObserver } = AW;

  const sortBox = document.getElementById("sort");
  const results = document.getElementById("results");
  const state = document.getElementById("state");
  const empty = document.getElementById("empty");
  const pager = document.getElementById("pager");
  const tagBar = document.getElementById("tag-bar");

  const params = new URLSearchParams(location.search);
  const sort = ["popular", "liked"].includes(params.get("sort")) ? params.get("sort") : "new";
  const activeTag = params.get("tag");
  const activeAuthor = params.get("author");
  const query = params.get("q");

  /**
   * Eine Adresse mit geaenderten Teilen. `null` loescht einen Teil.
   *
   * Wer die Auswahl aendert, landet wieder auf Seite 1 - Seite 4 eines anderen
   * Filters ist fast immer leer. Wer ausdruecklich blaettert, nennt `page`
   * selbst, und dann gilt seine Angabe.
   */
  const linkTo = (changes) => {
    const next = new URLSearchParams(location.search);
    if (!("page" in changes)) next.delete("page");
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    const search = next.toString();
    return "/browse.html" + (search ? "?" + search : "");
  };

  // ── Sortierung ───────────────────────────────────────────────────────
  //  Drei Fragen, nicht zwei: was ist neu, was wird benutzt, was gefaellt.
  //  "Benutzt" und "gemocht" laufen auseinander - ein solides Laufzyklus wird
  //  eingebaut, ein Salto bekommt Herzen.
  for (const [value, label] of [["new", "Newest"], ["popular", "Most used"], ["liked", "Most liked"]]) {
    sortBox.append(el("a", {
      class: sort === value ? "active" : null,
      href: linkTo({ sort: value === "new" ? null : value }),
    }, label));
  }

  // ── Schlagworte ──────────────────────────────────────────────────────
  //  Ein Suchfeld beantwortet nur Fragen, die jemand schon hat. Wer neu ist,
  //  hat keine - er will sehen, was da ist.
  api("GET", "/api/v1/overview").then((overview) => {
    if (!overview.tags.length) return;
    tagBar.hidden = false;
    tagBar.replaceChildren(
      el("a", {
        class: "tag" + (activeTag ? "" : " active"),
        href: linkTo({ tag: null }),
      }, "All"),
      ...overview.tags.map((entry) => el("a", {
        class: "tag" + (entry.tag === activeTag ? " active" : ""),
        href: linkTo({ tag: entry.tag }),
      }, entry.tag, el("span", { class: "tag-count" }, entry.count))),
    );
  }).catch(() => { /* Die Leiste ist Zugabe - ohne sie funktioniert der Katalog. */ });

  // ── Ergebnisse ───────────────────────────────────────────────────────
  const request = new URLSearchParams();
  for (const key of ["q", "tag", "author", "sort", "page"]) if (params.get(key)) request.set(key, params.get(key));
  request.set("size", "24");

  let page;
  try {
    page = await api("GET", "/api/v1/packages?" + request.toString());
  } catch (e) {
    notice(empty, e.message, "error");
    return;
  }

  //  Was gerade gilt, steht neben der Sortierung - mit einem Weg heraus. Eine
  //  Einschraenkung, die man nicht sieht, liest sich als leerer Katalog.
  const filters = [];
  if (query) filters.push(["Search: " + query, linkTo({ q: null })]);
  if (activeTag) filters.push(["Tag: " + activeTag, linkTo({ tag: null })]);
  if (activeAuthor) filters.push(["By " + activeAuthor, linkTo({ author: null })]);

  state.replaceChildren(
    ...filters.map(([label, href]) => el("a", { class: "chip removable", href, title: "Remove this filter" },
      label, el("span", { class: "chip-x" }, "×"))),
    el("span", { class: "faint small" },
      page.total === 1 ? "1 clip" : page.total.toLocaleString() + " clips"),
  );

  if (page.items.length === 0) {
    results.replaceChildren();
    empty.replaceChildren(el("div", { class: "empty" },
      el("p", {}, filters.length
        ? "Nothing here matches that."
        : "Nothing shared yet. Yours could be the first."),
      filters.length
        ? el("a", { class: "button", href: "/browse.html" }, "Show all clips")
        : el("a", { class: "button", href: "/rules.html" }, "How sharing works")));
    return;
  }

  const observer = previewObserver();
  results.replaceChildren(...page.items.map((item) => clipCard(item, observer)));

  // ── Seiten ───────────────────────────────────────────────────────────
  const current = page.page;
  const lastPage = Math.max(0, Math.ceil(page.total / page.size) - 1);

  if (lastPage > 0) {
    pager.replaceChildren(...[
      current > 0 ? el("a", { class: "button ghost", href: linkTo({ page: String(current - 1) }) }, "Previous") : null,
      el("span", { class: "faint small" }, `Page ${current + 1} of ${lastPage + 1}`),
      current < lastPage ? el("a", { class: "button ghost", href: linkTo({ page: String(current + 1) }) }, "Next") : null,
    ].filter(Boolean));
  }
})();
