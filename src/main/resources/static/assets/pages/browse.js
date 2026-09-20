(async () => {
  const { api, el, notice, clipCard, previewObserver } = AW;

  const form = document.getElementById("filters");
  const sortBox = document.getElementById("sort");
  const results = document.getElementById("results");
  const state = document.getElementById("state");
  const pager = document.getElementById("pager");
  const params = new URLSearchParams(location.search);
  const sort = params.get("sort") === "popular" ? "popular" : "new";

  // ── Filter ───────────────────────────────────────────────────────────
  const go = (changes) => {
    const next = new URLSearchParams(location.search);
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    next.delete("page");
    location.search = next.toString();
  };

  for (const option of [["new", "Newest"], ["popular", "Most downloaded"]]) {
    sortBox.append(el("button", {
      type: "button",
      class: sort === option[0] ? "active" : null,
      onclick: () => go({ sort: option[0] === "new" ? null : option[0] }),
    }, option[1]));
  }

  form.q.value = params.get("q") || "";

  form.addEventListener("submit", (e) => {
    e.preventDefault();
    go({ q: form.q.value.trim() });
  });

  // ── Ergebnisse ───────────────────────────────────────────────────────
  const query = new URLSearchParams();
  for (const key of ["q", "tag", "sort", "page"]) if (params.get(key)) query.set(key, params.get(key));
  query.set("size", "24");

  let page;
  try {
    page = await api("GET", "/api/v1/packages?" + query.toString());
  } catch (e) {
    notice(state, e.message, "error");
    return;
  }

  if (params.get("tag")) {
    state.replaceChildren(el("div", { class: "notice" }, "Tag: ", el("strong", {}, params.get("tag")), " · ",
      el("a", { href: "/browse.html" }, "clear")));
  }

  if (page.items.length === 0) {
    results.replaceChildren();
    state.append(el("div", { class: "empty" }, "No clips found."));
    return;
  }

  const observer = previewObserver();
  results.replaceChildren(...page.items.map((item) => clipCard(item, observer)));

  // ── Seiten ───────────────────────────────────────────────────────────
  const current = page.page;
  const lastPage = Math.max(0, Math.ceil(page.total / page.size) - 1);
  const toPage = (n) => {
    const next = new URLSearchParams(location.search);
    next.set("page", String(n));
    location.search = next.toString();
  };

  if (lastPage > 0) {
    pager.replaceChildren(...[
      current > 0 ? el("button", { class: "ghost", onclick: () => toPage(current - 1) }, "Previous") : null,
      el("span", { class: "faint small" }, `Page ${current + 1} of ${lastPage + 1}`),
      current < lastPage ? el("button", { class: "ghost", onclick: () => toPage(current + 1) }, "Next") : null,
    ].filter(Boolean));
  }
})();
