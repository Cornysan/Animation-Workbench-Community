(async () => {
  const { api, ensureCsrf, me, el, notice, param } = AW;

  const code = (param("code") || "").toUpperCase();
  const state = document.getElementById("state");
  const actions = document.getElementById("actions");
  document.getElementById("code").textContent = code || "-";

  if (!/^[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(code)) {
    notice(state, "Open this page from the link in the Animation Workbench.", "error");
    return;
  }

  const user = await me().catch(() => null);
  if (!user) {
    // Nach dem Discord-Login landet man auf "/" - resumePendingLink in app.js
    // leitet von dort mit dem gemerkten Code hierher zurück.
    sessionStorage.setItem("aw-link-code", code);

    //  Nur den Weg anbieten, den dieser Server wirklich hat. Der Seitenrahmen
    //  prueft das laengst; hier stand der Discord-Knopf fest verdrahtet, und
    //  auf einem Server ohne Discord-Zugangsdaten fuehrte er nach
    //  ?client_id=unset - Discord antwortet darauf mit "Invalid Form Body",
    //  und der Nutzer steht vor einer Fehlerseite ohne Ausweg.
    const discord = document.body.dataset.discordSignIn === "true";
    const dev = document.body.dataset.devLogin === "true";

    if (discord) {
      notice(state, "Sign in first - then confirm the code.");
      actions.append(el("a", { class: "button primary", href: "/oauth2/authorization/discord" }, "Sign in with Discord"));
    } else if (dev) {
      notice(state, "Sign in first - then confirm the code. This portal runs the developer sign-in.");
      actions.append(el("a", { class: "button primary", href: "/dev.html" }, "Developer sign-in"));
    } else {
      notice(state, "This portal has no sign-in configured, so the Workbench cannot be connected.", "error");
    }
    return;
  }

  notice(state, "Signed in as " + user.displayName + ". The Workbench will be able to share clips in your name.");
  const confirmButton = el("button", { class: "primary" }, "Confirm and connect");
  confirmButton.addEventListener("click", async () => {
    confirmButton.disabled = true;
    try {
      await ensureCsrf();
      await api("POST", "/api/v1/auth/editor/approve", { userCode: code });
      notice(state, "Connected. You can go back to Unity now.", "ok");
      sessionStorage.removeItem("aw-link-code");
      actions.replaceChildren();
    } catch (e) {
      notice(state, e.message, "error");
      confirmButton.disabled = false;
    }
  });
  actions.append(confirmButton, el("p", { class: "muted small" }, "Did not start a sign-in in Unity? Close this page."));
})();

