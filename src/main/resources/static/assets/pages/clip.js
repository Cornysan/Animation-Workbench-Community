(async () => {
  const { api, ensureCsrf, me, el, notice, formatDuration, formatDate, param } = AW;

  const slug = param("p");
  const state = document.getElementById("state");
  const actionState = document.getElementById("action-state");

  if (!slug) {
    notice(state, "No clip selected.", "error");
    return;
  }

  let clip;
  try {
    clip = await api("GET", "/api/v1/packages/" + encodeURIComponent(slug));
  } catch (e) {
    notice(state, e.status === 404 ? "This clip is not available. It may be under review or removed." : e.message, "error");
    return;
  }

  document.title = clip.title + " - Animation Workbench Community";
  document.getElementById("clip").classList.remove("hidden");
  document.getElementById("title").textContent = clip.title;
  document.getElementById("author").textContent = clip.author;
  document.getElementById("description").textContent = clip.description || "No description.";

  document.getElementById("tags").replaceChildren(
    ...clip.tags.map((tag) => el("a", { class: "tag", href: "/browse.html?tag=" + encodeURIComponent(tag) }, tag)));

  document.getElementById("facts").replaceChildren(
    el("dt", {}, "Duration"), el("dd", {}, formatDuration(clip.durationSeconds)),
    el("dt", {}, "Frame rate"), el("dd", {}, Math.round(clip.frameRate) + " fps"),
    el("dt", {}, "Curves"), el("dd", {}, clip.curveCount),
    el("dt", {}, "Version"), el("dd", {}, clip.version),
    el("dt", {}, "Used in projects"), el("dd", {}, clip.downloads),
    el("dt", {}, "Likes"), el("dd", {}, clip.likes),
    el("dt", {}, "Shared"), el("dd", {}, formatDate(clip.createdAt)),
    ...(clip.status ? [el("dt", {}, "Status"), el("dd", {}, el("span", { class: "status " + clip.status }, clip.status))] : []));

  try {
    const licenses = await api("GET", "/api/v1/licenses");
    const license = licenses.find((l) => l.id === clip.license);
    document.getElementById("license").replaceChildren(...[
      el("strong", {}, license ? license.name : clip.license), el("br"),
      license ? license.summary + " " : "",
      // Der private Fall hat keine Lizenzseite, auf die man verweisen könnte.
      license && license.url ? el("a", { href: license.url, rel: "noopener", target: "_blank" }, "Full license") : null,
    ].filter(Boolean));
  } catch {
    document.getElementById("license").textContent = clip.license;
  }

  //  Die Vorschau gehoert `pages/clip-viewer.js` - einem Modul, weil die Buehne
  //  three.js laedt. Es holt seine Daten selbst und ist der einzige Schreiber
  //  auf `#viewer`; zwei Stellen, die denselben Kasten fuellen, ueberholen
  //  einander irgendwann.

  // ── Aktionen ─────────────────────────────────────────────────────────
  const actions = document.getElementById("actions");
  const user = await me().catch(() => null);

  //  Seit der Muenzwirtschaft ist Herunterladen Freischalten, und das kostet
  //  unter Umstaenden. Der Knopf muss das sagen, BEVOR er es tut - "Download"
  //  auf einem Knopf, der zehn Muenzen abbucht, waere eine Falle.
  const status = await fetch("/api/v1/status", { credentials: "same-origin" })
    .then((r) => r.json())
    .catch(() => ({}));

  const costs = status.economyEnabled && !clip.unlockedByMe && clip.license === "CC-BY-4.0";
  const download = el("button", { class: "primary" },
    costs ? "Unlock for " + status.unlockCost + " coins" : "Download .awclip");

  download.addEventListener("click", async () => {
    try {
      const link = await api("POST", "/api/v1/packages/" + encodeURIComponent(slug) + "/unlock");
      const a = el("a", { href: link.url, download: link.fileName });
      document.body.append(a);
      a.click();
      a.remove();
      notice(actionState, "Import it in Unity with Tools > Animation Workbench > Community > Import .awclip File. License: " + link.license + ".", "ok");
    } catch (e) {
      notice(actionState, e.message, "error");
    }
  });
  actions.append(download);

  if (clip.isOwner) {
    const withdraw = el("button", { class: "danger" }, "Withdraw this clip");
    withdraw.addEventListener("click", async () => {
      if (!confirm("Withdraw '" + clip.title + "'? It disappears from the community.")) return;
      try {
        await api("DELETE", "/api/v1/packages/" + encodeURIComponent(slug));
        location.href = "/me.html";
      } catch (e) {
        notice(actionState, e.message, "error");
      }
    });
    actions.append(withdraw);
  } else if (user) {
    const dialog = document.getElementById("report-dialog");
    const form = document.getElementById("report-form");
    const report = el("button", { class: "danger" }, "Report");
    report.addEventListener("click", () => dialog.showModal());

    dialog.addEventListener("close", async () => {
      if (dialog.returnValue !== "send") return;
      try {
        await ensureCsrf();
        await api("POST", "/api/v1/packages/" + encodeURIComponent(slug) + "/reports", {
          category: form.category.value,
          message: form.message.value,
        });
        notice(actionState, "Thank you. The clip is hidden until a moderator has reviewed it.", "ok");
        report.disabled = true;
      } catch (e) {
        notice(actionState, e.message, "error");
      }
    });
    actions.append(report);
  } else {
    actions.append(el("a", { class: "button", href: "/oauth2/authorization/discord" }, "Sign in to report"));
  }
})();
