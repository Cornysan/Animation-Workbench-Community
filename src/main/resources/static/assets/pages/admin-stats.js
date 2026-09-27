/**
 * Kennzahlen fuer Admins - `/admin-stats.html`.
 *
 * Eine Abfrage (`/api/v1/admin/stats`, system/AdminStats.kt), vier Teile:
 * Kacheln mit den Zahlen, nach denen man zuerst fragt; zwei Kaesten mit dem
 * Rest; die meistgesehenen Clips; und alle Mitglieder als Tabelle, die sich
 * nach jeder Spalte sortieren und nach Namen filtern laesst.
 *
 * Die Tabelle ist die eigentliche Antwort auf "wer teilt wie viel" - die
 * Kacheln sagen nur, ob es mehr wird.
 */
(async () => {
  const { api, me, el, notice, formatDate, formatRelative, profileLink } = AW;

  const state = document.getElementById("state");
  const user = await me().catch(() => null);

  if (!user || user.role !== "ADMIN") {
    notice(state, "Administrators only.", "error");
    return;
  }

  let stats;
  try {
    stats = await api("GET", "/api/v1/admin/stats");
  } catch (e) {
    notice(state, e.message, "error");
    return;
  }
  document.getElementById("stats").classList.remove("hidden");

  const { accounts, clips, activity } = stats;

  /** 1,284 - ab zehntausend 12.9K: eine Kachel ist kein Kontoauszug. */
  const compact = (n) => (n < 10000
    ? n.toLocaleString("en")
    : new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 }).format(n));
  const full = (n) => n.toLocaleString("en");
  const plural = (n, word) => full(n) + " " + word + (n === 1 ? "" : "s");

  // ── Kacheln ─────────────────────────────────────────────────────────
  //  Unter jeder Zahl der Satz, der sie einordnet - "412" allein sagt nicht,
  //  ob das viel ist.
  const tiles = [
    ["Members", accounts.total, "+" + full(accounts.new7) + " this week · +" + full(accounts.new30) + " in 30 days"],
    ["Active this week", accounts.active7, full(accounts.active30) + " in the last 30 days"],
    ["Public clips", clips.public, full(clips.private) + " private · " + full(clips.new7) + " uploaded this week"],
    ["Views", activity.views, "clip pages, each visitor once a day"],
    ["Downloads", activity.downloads, full(activity.takes) + " .awclip · " + full(activity.fileDownloads) + " FBX/GLB"],
    ["Workbench", accounts.workbench, "signed in there · " + plural(accounts.sharing, "member") + " sharing"],
  ];
  document.getElementById("tiles").replaceChildren(...tiles.map(([label, value, sub]) =>
    el("div", { class: "stat-tile" },
      el("span", { class: "stat-label" }, label),
      el("span", { class: "stat-value", title: full(value) }, compact(value)),
      el("span", { class: "stat-sub" }, sub))));

  // ── Die zwei Kaesten ────────────────────────────────────────────────
  const facts = (id, rows) => document.getElementById(id).replaceChildren(
    ...rows.flatMap(([label, value]) => [el("dt", {}, label), el("dd", {}, value)]));

  const providerNames = { discord: "Discord", github: "GitHub", google: "Google" };
  const providers = Object.entries(accounts.providers)
    .map(([key, n]) => (providerNames[key] || key) + " " + full(n)).join(" · ") || "none";

  facts("member-facts", [
    ["Sign-ins", providers],
    ["Sharing clips", full(accounts.sharing)],
    ["Workbench signed in", full(accounts.workbench)],
    ["New", full(accounts.new7) + " this week, " + full(accounts.new30) + " in 30 days"],
    ["Restricted", full(accounts.restricted)],
    ["Banned", full(accounts.banned)],
    ["Closed accounts", full(accounts.closed)],
  ]);

  const megabytes = clips.storageBytes / (1024 * 1024);
  facts("content-facts", [
    ["Public clips", full(clips.public)],
    ["Private clips", full(clips.private)],
    ["Hidden after reports", full(clips.hidden)],
    ["Removed or withdrawn", full(clips.removed)],
    ["Uploaded", full(clips.new7) + " this week, " + full(clips.new30) + " in 30 days"],
    ["Packs", full(clips.packs)],
    ["Collections", full(activity.collections)],
    ["Comments", full(activity.comments)],
    ["Hearts", full(activity.likes)],
    ["Follows", full(activity.follows)],
    ["Stored files", plural(clips.versions, "version") + ", " + (megabytes < 10 ? megabytes.toFixed(1) : Math.round(megabytes)) + " MB"],
  ]);

  // ── Meistgesehen ────────────────────────────────────────────────────
  const top = document.querySelector("#top-clips tbody");
  top.replaceChildren(...(stats.topClips.length === 0
    ? [el("tr", {}, el("td", { colspan: 5, class: "muted" }, "No public clips yet."))]
    : stats.topClips.map((clip) => el("tr", {},
        el("td", {}, el("a", { href: "/clip.html?p=" + encodeURIComponent(clip.slug) }, clip.title)),
        el("td", { class: "muted" }, clip.author),
        numberCell(clip.views), numberCell(clip.downloads), numberCell(clip.likes)))));

  function numberCell(n) {
    return el("td", { class: n === 0 ? "num zero" : "num" }, full(n));
  }

  // ── Alle Mitglieder ─────────────────────────────────────────────────
  //  Kurze Koepfe, der ganze Satz im Tooltip - sechzehn Spalten mit langen
  //  Namen passen auf keinen Bildschirm.
  const COLUMNS = [
    { key: "name", label: "Member", text: true, render: memberCell },
    { key: "createdAt", label: "Joined", render: (m) => el("td", { class: "nowrap" }, formatDate(m.createdAt)) },
    { key: "lastSeen", label: "Last seen", render: (m) => el("td", { class: "nowrap" + (m.lastSeen ? "" : " zero") },
        m.lastSeen ? formatRelative(m.lastSeen) : "never") },
    { key: "providers", label: "Sign-in", text: true, value: (m) => m.providers.join(","),
      render: (m) => el("td", { class: "muted" }, m.providers.map((p) => providerNames[p] || p).join(", ") || "none") },
    { key: "publicClips", label: "Public", tip: "Public clips (CC0)" },
    { key: "privateClips", label: "Private", tip: "Private clips - only the member sees them" },
    { key: "hiddenClips", label: "Hidden", tip: "Hidden after reports, waiting for a decision" },
    { key: "packs", label: "Packs" },
    { key: "collections", label: "Coll.", tip: "Collections" },
    { key: "comments", label: "Comm.", tip: "Visible comments written" },
    { key: "likesGiven", label: "Hearts", tip: "Hearts given" },
    { key: "followers", label: "Followers" },
    { key: "following", label: "Follows", tip: "Members they follow" },
    { key: "views", label: "Views", tip: "Views of their clips" },
    { key: "downloads", label: "Downl.", tip: "Downloads of their clips" },
    { key: "workbench", label: "WB", tip: "Signed in in the Animation Workbench",
      value: (m) => (m.workbench ? 1 : 0),
      render: (m) => el("td", { class: m.workbench ? "num" : "num zero" }, m.workbench ? "✓" : "–") },
  ];

  function memberCell(m) {
    const name = m.handle && m.state !== "closed"
      ? el("a", { href: profileLink(m.handle) }, m.name)
      : el("span", {}, m.name);
    const flags = [];
    if (m.admin) flags.push(el("span", { class: "chip" }, "Admin"));
    if (m.state !== "active") flags.push(el("span", { class: "chip member-" + m.state }, m.state));
    if (m.strikes > 0) flags.push(el("span", { class: "chip member-restricted", "data-tip": "Confirmed violations" },
      plural(m.strikes, "strike")));
    return el("td", { class: "member-cell" },
      el("div", { class: "member-name" }, name, ...flags),
      m.handle && m.state !== "closed" ? el("div", { class: "faint small" }, "@" + m.handle) : null);
  }

  const valueOf = (column, m) => (column.value ? column.value(m) : m[column.key]);

  let sortKey = "lastSeen";
  let descending = true;
  const head = document.querySelector("#members thead tr");
  const body = document.querySelector("#members tbody");
  const filter = document.getElementById("member-filter");
  const title = document.getElementById("members-title");

  head.replaceChildren(...COLUMNS.map((column) => {
    const button = el("button", { type: "button", class: "sort-button" }, column.label);
    button.addEventListener("click", () => {
      //  Zahlen und Daten zuerst absteigend - "wer hat die meisten" ist
      //  die Frage, nicht "wer hat die wenigsten". Namen aufsteigend.
      descending = sortKey === column.key ? !descending : !column.text;
      sortKey = column.key;
      render();
    });
    const numeric = !column.text && !["createdAt", "lastSeen"].includes(column.key);
    return el("th", { class: numeric ? "num" : "", "data-key": column.key, ...(column.tip ? { "data-tip": column.tip } : {}) }, button);
  }));

  filter.addEventListener("input", render);

  function render() {
    const column = COLUMNS.find((c) => c.key === sortKey);
    const needle = filter.value.trim().toLowerCase();
    const rows = stats.members
      .filter((m) => !needle || m.name.toLowerCase().includes(needle) || (m.handle || "").includes(needle))
      .sort((a, b) => {
        const x = valueOf(column, a);
        const y = valueOf(column, b);
        //  Wer nie gesehen wurde, steht immer unten - auch aufsteigend.
        if (x == null || y == null) return x == null ? (y == null ? 0 : 1) : -1;
        const order = typeof x === "string" ? x.localeCompare(y, "en", { sensitivity: "base" }) : x - y;
        return descending ? -order : order;
      });

    head.querySelectorAll("th").forEach((th) => {
      if (th.dataset.key === sortKey) th.setAttribute("aria-sort", descending ? "descending" : "ascending");
      else th.removeAttribute("aria-sort");
    });
    title.textContent = needle
      ? rows.length + " of " + plural(stats.members.length, "member")
      : "All members (" + full(stats.members.length) + ")";

    body.replaceChildren(...(rows.length === 0
      ? [el("tr", {}, el("td", { colspan: COLUMNS.length, class: "muted" }, "Nobody matches."))]
      : rows.map((m) => el("tr", { class: m.state === "closed" ? "member-row closed" : "member-row" },
          ...COLUMNS.map((c) => (c.render ? c.render(m) : numberCell(m[c.key])))))));
  }
  render();

  document.getElementById("stats-lead").append(" As of " + new Date(stats.generatedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) + ".");
})();
