(async () => {
  const { api, el, notice, formatDuration, renderShell } = AW;
  renderShell("browse");

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
      el("a", { href: "/" }, "clear")));
  }

  if (page.items.length === 0) {
    results.replaceChildren();
    state.append(el("div", { class: "empty" }, "No clips found."));
    return;
  }

  // Vorschau erst laden, wenn die Karte sichtbar wird - eine Seite hat 24 davon.
  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      observer.unobserve(entry.target);
      const canvas = entry.target;
      api("GET", "/api/v1/packages/" + canvas.dataset.slug + "/preview")
        .then((preview) => new SkeletonViewer(canvas, preview, { interactive: false, autoplay: true, yaw: -0.7, pitch: 0.18 }))
        .catch(() => canvas.replaceWith(el("div", { class: "viewer-empty" }, "No preview")));
    }
  }, { rootMargin: "300px" });

  const week = 7 * 24 * 60 * 60 * 1000;

  results.replaceChildren(...page.items.map((item) => {
    const canvas = el("canvas", { "data-slug": item.slug, width: 560, height: 420 });
    if (item.hasPreview) observer.observe(canvas);

    const fresh = Date.now() - new Date(item.createdAt).getTime() < week;

    return el("a", { class: "card", href: "/clip.html?p=" + encodeURIComponent(item.slug) },
      item.hasPreview ? canvas : el("div", { class: "viewer-empty" }, "No preview"),
      fresh ? el("span", { class: "card-flag" }, "New") : null,
      el("div", { class: "card-body" },
        el("div", { class: "card-title", title: item.title }, item.title),
        el("div", { class: "card-meta" },
          el("span", {}, item.author),
          el("span", { class: "dot" }, "·"),
          el("span", {}, formatDuration(item.durationSeconds)),
          el("span", { class: "dot" }, "·"),
          // "Used" statt "downloads": gezaehlt wird die Uebernahme in ein
          // Projekt, nicht der Dateiabruf.
          el("span", {}, item.downloads === 1 ? "used once" : "used " + item.downloads + " times"),
          el("span", { class: "dot" }, "·"),
          el("span", {}, item.likes + " ♥")),
        el("div", { class: "card-cta" }, "View clip")));
  }));

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
