(async () => {
  const { api, ensureCsrf, me, el, notice, param, signInUrl, signInButton } = AW;

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
    // Nach der Anmeldung landet man auf "/" - resumePendingLink in app.js
    // leitet von dort mit dem gemerkten Code hierher zurück.
    sessionStorage.setItem("aw-link-code", code);

    //  Nur den Weg anbieten, den dieser Server wirklich hat. Hier stand der
    //  Discord-Knopf fest verdrahtet; auf einem Server ohne Discord-Zugangsdaten
    //  fuehrte er nach ?client_id=unset, und Discord antwortet darauf mit
    //  "Invalid Form Body" - eine Fehlerseite ohne Ausweg. Jetzt gilt derselbe
    //  Weg wie im Seitenkopf: die Auswahl, der eine Anbieter oder nichts.
    if (signInUrl) {
      notice(state, "Sign in first - then confirm the code.");
      actions.append(signInButton("Sign in", true));
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

