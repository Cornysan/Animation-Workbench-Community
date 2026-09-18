(async () => {
  const { api, ensureCsrf, me, el, notice, formatDate, renderShell } = AW;
  renderShell("admin");

  const state = document.getElementById("state");
  const casesBox = document.getElementById("cases");
  const user = await me().catch(() => null);

  if (!user || user.role !== "ADMIN") {
    notice(state, "Moderators only.", "error");
    document.querySelectorAll(".panel, h2").forEach((n) => n.classList.add("hidden"));
    return;
  }

  await ensureCsrf();

  // ── Schalter ─────────────────────────────────────────────────────────
  const community = document.getElementById("community-enabled");
  const uploads = document.getElementById("uploads-enabled");
  const status = await api("GET", "/api/v1/status");
  community.checked = status.communityEnabled;
  uploads.checked = status.uploadsEnabled;

  const saveSwitch = async (body, input) => {
    try {
      const result = await api("POST", "/api/v1/admin/settings", body);
      community.checked = result.communityEnabled;
      uploads.checked = result.uploadsEnabled;
    } catch (e) {
      input.checked = !input.checked;
      notice(state, e.message, "error");
    }
  };
  community.addEventListener("change", () => saveSwitch({ communityEnabled: community.checked }, community));
  uploads.addEventListener("change", () => saveSwitch({ uploadsEnabled: uploads.checked }, uploads));

  // ── Fälle ────────────────────────────────────────────────────────────
  async function load() {
    const cases = await api("GET", "/api/v1/admin/cases");
    if (cases.length === 0) {
      casesBox.replaceChildren(el("p", { class: "muted" }, "No open cases."));
      return;
    }
    casesBox.replaceChildren(...cases.map(renderCase));
  }

  function renderCase(item) {
    const note = el("input", { placeholder: "Note (sent to the people involved)", class: "note" });
    note.style.width = "100%";

    const decide = (fn) => async () => {
      try {
        await fn(note.value.trim());
        await load();
      } catch (e) {
        notice(state, e.message, "error");
      }
    };

    const packages = item.packages.map((p) =>
      el("div", {},
        el("a", { href: "/clip.html?p=" + encodeURIComponent(p.slug), target: "_blank" }, p.title),
        " ", el("span", { class: "status " + p.status }, p.status),
        el("span", { class: "muted small" }, " by " + p.owner + (p.ownerStrikes ? " (" + p.ownerStrikes + " strikes)" : ""))));

    const buttons = el("div", { class: "buttons" });
    if (item.kind === "report") {
      const slug = item.packages[0] && item.packages[0].slug;
      buttons.append(
        el("button", { class: "primary", onclick: decide((n) => api("POST", "/api/v1/admin/packages/" + slug + "/restore", { note: n })) }, "Unfounded - restore"),
        el("button", { onclick: decide((n) => api("POST", "/api/v1/admin/reports/" + item.id + "/dismiss", { note: n, falseReport: true })) }, "Dismiss as false report"),
        el("button", { class: "danger", onclick: decide((n) => api("POST", "/api/v1/admin/packages/" + slug + "/remove", { note: n, strike: false })) }, "Remove"),
        el("button", { class: "danger", onclick: decide((n) => api("POST", "/api/v1/admin/packages/" + slug + "/remove", { note: n, strike: true })) }, "Remove + strike"));
    } else {
      buttons.append(
        el("button", { onclick: decide((n) => api("POST", "/api/v1/admin/takedowns/" + item.id + "/resolve", { note: n, upheld: false })) }, "Reject request"),
        el("button", { class: "danger", onclick: decide((n) => api("POST", "/api/v1/admin/takedowns/" + item.id + "/resolve", { note: n, upheld: true, strike: false })) }, "Uphold - remove"),
        el("button", { class: "danger", onclick: decide((n) => api("POST", "/api/v1/admin/takedowns/" + item.id + "/resolve", { note: n, upheld: true, strike: true })) }, "Uphold + strike"));
    }

    return el("div", { class: "panel case" },
      el("header", {},
        el("strong", {}, item.kind === "report" ? "Report · " + item.category : "Takedown request"),
        el("span", { class: "muted small" }, formatDate(item.createdAt))),
      ...packages,
      el("p", {}, item.message || el("span", { class: "muted" }, "No details.")),
      item.reporter ? el("div", { class: "muted small" }, "Reported by " + item.reporter) : null,
      item.contact ? el("div", { class: "muted small" }, "Contact: " + item.contact) : null,
      note,
      buttons);
  }

  load().catch((e) => notice(state, e.message, "error"));
})();
