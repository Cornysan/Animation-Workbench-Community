(async () => {
  const { api, ensureCsrf, me, el, notice, formatDate } = AW;

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
  const characters = document.getElementById("characters-enabled");
  const status = await api("GET", "/api/v1/status");

  //  Die Antwort des Servers ist die Wahrheit, nicht der Klick. `uploads`
  //  haengt an `community`, und ein Schalter, der etwas anderes zeigt als
  //  das, was gilt, ist schlimmer als gar keiner.
  const show = (s) => {
    community.checked = s.communityEnabled;
    uploads.checked = s.uploadsEnabled;
    characters.checked = s.charactersEnabled;
  };
  show(status);

  const saveSwitch = async (body, input) => {
    try {
      show(await api("POST", "/api/v1/admin/settings", body));
    } catch (e) {
      input.checked = !input.checked;
      notice(state, e.message, "error");
    }
  };
  community.addEventListener("change", () => saveSwitch({ communityEnabled: community.checked }, community));
  uploads.addEventListener("change", () => saveSwitch({ uploadsEnabled: uploads.checked }, uploads));
  //  Wirkt erst beim naechsten Seitenaufbau: der Schalter steht im Kopf
  //  jeder Seite, und der wird auf dem Server gesetzt.
  characters.addEventListener("change", () => saveSwitch({ charactersEnabled: characters.checked }, characters));

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
    } else if (item.kind === "comment-report") {
      //  Ein Kommentarfall hat seinen eigenen Ausgang: der Clip war nie
      //  versteckt und darf hier nicht angefasst werden.
      buttons.append(
        el("button", { class: "primary", onclick: decide((n) => api("POST", "/api/v1/admin/comments/" + item.commentId + "/restore", { note: n })) }, "Unfounded - restore comment"),
        el("button", { onclick: decide((n) => api("POST", "/api/v1/admin/reports/" + item.id + "/dismiss", { note: n, falseReport: true })) }, "Dismiss as false report"),
        el("button", { class: "danger", onclick: decide((n) => api("POST", "/api/v1/admin/comments/" + item.commentId + "/remove", { note: n, strike: false })) }, "Remove comment"),
        el("button", { class: "danger", onclick: decide((n) => api("POST", "/api/v1/admin/comments/" + item.commentId + "/remove", { note: n, strike: true })) }, "Remove + strike"));
    } else if (item.kind === "account-report") {
      //  Hier ist NICHTS versteckt worden - eine Kontomeldung ist eine
      //  Bitte um Hinsehen, kein Auto-Hide. Entsprechend gibt es kein
      //  "wiederherstellen", sondern nur: abweisen, einschraenken, sperren.
      const account = item.account || {};
      buttons.append(
        el("button", { class: "primary", onclick: decide((n) => api("POST", "/api/v1/admin/reports/" + item.id + "/dismiss", { note: n, falseReport: false })) }, "Nothing to do"),
        el("button", { onclick: decide((n) => api("POST", "/api/v1/admin/reports/" + item.id + "/dismiss", { note: n, falseReport: true })) }, "Dismiss as false report"),
        el("button", { class: "danger", onclick: decide(async (n) => {
          await api("POST", "/api/v1/admin/accounts/" + account.id + "/status", { status: "RESTRICTED", note: n });
          await api("POST", "/api/v1/admin/reports/" + item.id + "/dismiss", { note: n, falseReport: false });
        }) }, "Restrict account"),
        el("button", { class: "danger", onclick: decide(async (n) => {
          await api("POST", "/api/v1/admin/accounts/" + account.id + "/status", { status: "BANNED", note: n });
          await api("POST", "/api/v1/admin/reports/" + item.id + "/dismiss", { note: n, falseReport: false });
        }) }, "Ban account"));
    } else {
      buttons.append(
        el("button", { onclick: decide((n) => api("POST", "/api/v1/admin/takedowns/" + item.id + "/resolve", { note: n, upheld: false })) }, "Reject request"),
        el("button", { class: "danger", onclick: decide((n) => api("POST", "/api/v1/admin/takedowns/" + item.id + "/resolve", { note: n, upheld: true, strike: false })) }, "Uphold - remove"),
        el("button", { class: "danger", onclick: decide((n) => api("POST", "/api/v1/admin/takedowns/" + item.id + "/resolve", { note: n, upheld: true, strike: true })) }, "Uphold + strike"));
    }

    const heading = {
      "report": "Report · " + item.category,
      "comment-report": "Comment report · " + item.category,
      "account-report": "Account report · " + item.category,
    }[item.kind] || "Takedown request";

    //  Bei einer Kontomeldung steht kein Clip im Fall - das Ziel ist die
    //  Person. Der Moderator braucht dafuer ihren Weg (Profil) und ihre
    //  Vorgeschichte (Strikes, Zahl der Clips).
    const account = item.account
      ? el("div", {},
          el("a", { href: "/u.html?u=" + encodeURIComponent(item.account.handle || ""), target: "_blank" },
            item.account.displayName),
          " ", el("span", { class: "status " + item.account.status }, item.account.status),
          el("span", { class: "muted small" },
            " · " + item.account.clips + " clips" +
            (item.account.strikes ? " · " + item.account.strikes + " strikes" : "")))
      : null;

    return el("div", { class: "panel case" },
      el("header", {},
        el("strong", {}, heading),
        el("span", { class: "muted small" }, formatDate(item.createdAt))),
      account,
      ...packages,
      el("p", {}, item.message || el("span", { class: "muted" }, "No details.")),
      item.reporter ? el("div", { class: "muted small" }, "Reported by " + item.reporter) : null,
      item.contact ? el("div", { class: "muted small" }, "Contact: " + item.contact) : null,
      note,
      buttons);
  }

  load().catch((e) => notice(state, e.message, "error"));

  // ── Starter-Clips ────────────────────────────────────────────────────
  //  Eine Datei je Aufruf, nacheinander: jede bekommt ihre eigene Zeile mit
  //  Ergebnis, und ein Duplikat in der Mitte haelt den Rest nicht auf.
  const starterFiles = document.getElementById("starter-files");
  const starterAdd = document.getElementById("starter-add");
  const starterLog = document.getElementById("starter-log");

  starterAdd.addEventListener("click", async () => {
    const files = [...starterFiles.files];
    const credit = document.getElementById("starter-credit").value.trim();
    if (!files.length || !credit) {
      notice(state, "Pick .awclip files and name their source.", "error");
      return;
    }

    starterAdd.disabled = true;
    starterLog.replaceChildren();
    let added = 0;

    for (const file of files) {
      const row = el("li", {}, file.name + " …");
      starterLog.append(row);

      const form = new FormData();
      form.append("file", file);
      form.append("credit", credit);
      form.append("url", document.getElementById("starter-url").value.trim());
      form.append("tags", document.getElementById("starter-tags").value);
      form.append("tidyTitle", document.getElementById("starter-tidy").checked ? "true" : "false");

      try {
        const clip = await api("POST", "/api/v1/admin/starter-clips", form);
        added++;
        row.replaceChildren("✓ ", el("a", { href: "/clip.html?p=" + encodeURIComponent(clip.slug) }, clip.title),
          el("span", { class: "muted" }, " ← " + file.name));
      } catch (e) {
        row.replaceChildren(el("span", { class: "danger-text" }, "✗ " + file.name + ": " + e.message));
      }
    }

    starterAdd.disabled = false;
    starterFiles.value = "";
    notice(state, added + " of " + files.length + " added.", added === files.length ? "ok" : "error");
  });
})();
