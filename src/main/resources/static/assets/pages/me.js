(async () => {
  const { api, ensureCsrf, me, el, notice, formatDate } = AW;

  const state = document.getElementById("state");
  const user = await me().catch(() => null);

  if (!user) {
    notice(state, "Sign in to see your clips.");
    state.append(AW.signInButton("Sign in", true));
    document.querySelectorAll("h2, table, #revoke, .muted.small").forEach((n) => n.classList.add("hidden"));
    return;
  }

  if (user.status === "RESTRICTED") notice(state, "Your account is restricted after a removed clip: fewer uploads per day.", "warn");

  //  Beide Listen zugleich anfordern - sie haengen nicht voneinander ab.
  const notificationsRequest = api("GET", "/api/v1/me/notifications").catch(() => []);
  const clipsRequest = api("GET", "/api/v1/me/packages").catch(() => []);

  const notifications = await notificationsRequest;
  document.getElementById("notifications").replaceChildren(
    notifications.length === 0
      ? el("p", { class: "muted" }, "Nothing new.")
      : el("table", { class: "list" }, el("tbody", {}, ...notifications.map((n) =>
          el("tr", {}, el("td", { class: "muted small" }, formatDate(n.createdAt)), el("td", {}, n.message))))));

  const clips = await clipsRequest;
  const body = document.querySelector("#clips tbody");
  body.replaceChildren(...(clips.length === 0
    ? [el("tr", {}, el("td", { colspan: 5, class: "muted" }, "You have not shared a clip yet."))]
    : clips.map((clip) => el("tr", {},
        el("td", {}, el("a", { href: "/clip.html?p=" + encodeURIComponent(clip.slug) }, clip.title)),
        el("td", {}, el("span", { class: "status " + clip.status }, clip.status)),
        el("td", {}, clip.version),
        el("td", {}, clip.downloads),
        el("td", { class: "muted small" }, formatDate(clip.updatedAt))))));

  /**
   * Das Konto schliessen.
   *
   * Zwei Huerden, weil es nicht rueckgaengig zu machen ist: der erste Kasten
   * sagt VORHER, was verschwindet und was bleibt, der zweite verlangt ein
   * getipptes Wort. Ein einzelnes "Sind Sie sicher?" klickt man weg, ohne es
   * gelesen zu haben - und genau dieser Klick waere hier endgueltig.
   */
  document.getElementById("delete-account").addEventListener("click", async () => {
    const warning =
      "Close this account?\n\n" +
      "Gone: your name, your profile, your collections, your likes, who you follow.\n\n" +
      "Withdrawn: your shared clips disappear from the catalogue - but copies other people " +
      "already took stay theirs, as CC BY 4.0 says.\n\n" +
      "Kept without your name: your comments, so conversations under other people's clips " +
      "stay readable.\n\n" +
      "This cannot be undone.";

    if (!confirm(warning)) return;
    if (prompt("Type DELETE to confirm.") !== "DELETE") {
      notice(state, "Nothing was deleted.", "");
      return;
    }

    try {
      await ensureCsrf();
      const result = await api("DELETE", "/api/v1/me");
      alert("Your account is closed. " + result.withdrawnClips + " clip(s) withdrawn, " +
        result.deletedCollections + " collection(s) deleted.");
      location.href = "/";
    } catch (e) {
      notice(state, e.message, "error");
    }
  });

  document.getElementById("revoke").addEventListener("click", async () => {
    if (!confirm("Sign out every Animation Workbench connected to this account?")) return;
    try {
      await ensureCsrf();
      await api("POST", "/api/v1/me/tokens/revoke-all");
      notice(state, "All Workbench sign-ins ended.", "ok");
    } catch (e) {
      notice(state, e.message, "error");
    }
  });
})();
