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

  const notifications = await api("GET", "/api/v1/me/notifications").catch(() => []);
  document.getElementById("notifications").replaceChildren(
    notifications.length === 0
      ? el("p", { class: "muted" }, "Nothing new.")
      : el("table", { class: "list" }, el("tbody", {}, ...notifications.map((n) =>
          el("tr", {}, el("td", { class: "muted small" }, formatDate(n.createdAt)), el("td", {}, n.message))))));

  const clips = await api("GET", "/api/v1/me/packages").catch(() => []);
  const body = document.querySelector("#clips tbody");
  body.replaceChildren(...(clips.length === 0
    ? [el("tr", {}, el("td", { colspan: 5, class: "muted" }, "You have not shared a clip yet."))]
    : clips.map((clip) => el("tr", {},
        el("td", {}, el("a", { href: "/clip.html?p=" + encodeURIComponent(clip.slug) }, clip.title)),
        el("td", {}, el("span", { class: "status " + clip.status }, clip.status)),
        el("td", {}, clip.version),
        el("td", {}, clip.downloads),
        el("td", { class: "muted small" }, formatDate(clip.updatedAt))))));

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
