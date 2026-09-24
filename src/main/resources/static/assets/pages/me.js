(async () => {
  const { api, ensureCsrf, me, el, notice, formatDate, confirmDialog, toast, toastError, param } = AW;

  const state = document.getElementById("state");
  const user = await me().catch(() => null);

  //  Ohne Sitzung kommt niemand mehr hierher - der Server schickt vorher zur
  //  Anmeldeseite (SecurityConfig). Das hier bleibt fuer den Fall, dass die
  //  Sitzung zwischen Seite und Abfrage endet, und dann verschwindet ALLES,
  //  was ein Konto braucht: vorher blieb "Close my account" stehen.
  if (!user) {
    notice(state, "Your sign-in has ended. Sign in again to see your clips.");
    state.append(AW.signInButton("Sign in", true));
    document.querySelectorAll("h2, table, button.danger, .muted.small").forEach((n) => n.classList.add("hidden"));
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
    //  Beide Huerden jetzt in EINEM Kasten: erst lesen, was verschwindet,
    //  dann das Wort tippen. Der Knopf bleibt zu, bis es dasteht.
    const sure = await confirmDialog({
      title: "Close this account?",
      body: [
        el("p", {}, el("strong", {}, "Gone: "), "your name, your profile, your collections, your likes, who you follow."),
        el("p", {}, el("strong", {}, "Withdrawn: "), "your shared clips disappear from the catalogue - but copies other people " +
          "already took stay theirs, as CC BY 4.0 says."),
        el("p", {}, el("strong", {}, "Kept without your name: "), "your comments, so conversations under other people's clips " +
          "stay readable."),
        el("p", { class: "muted" }, "This cannot be undone."),
      ],
      confirm: "Close account",
      danger: true,
      typeToConfirm: "DELETE",
    });
    if (!sure) return;

    try {
      await ensureCsrf();
      const result = await api("DELETE", "/api/v1/me");
      await confirmDialog({
        title: "Your account is closed",
        body: result.withdrawnClips + " clip(s) withdrawn, " + result.deletedCollections + " collection(s) deleted.",
        confirm: "OK",
        cancel: null,
      });
      location.href = "/";
    } catch (e) {
      toastError(e);
    }
  });

  /**
   * Die Wege hinein. Die Zeilen kommen fertig aus der Vorlage (me.html); hier
   * haengen nur die Handlungen daran, und die Datumsangaben bekommen die
   * Schreibweise des Browsers.
   */
  document.querySelectorAll("time.js-date").forEach((node) => {
    node.textContent = formatDate(node.getAttribute("datetime"));
  });

  const labelOf = (provider) =>
    document.querySelector('.signin-row[data-provider="' + CSS.escape(provider) + '"]')?.dataset.label || provider;

  //  Die Rueckkehr vom Anbieter landet hier. Die Adresse traegt nur ein Wort
  //  aus einer festen Liste (SignInFailureHandler); der Satz steht hier.
  const CONNECT_ERRORS = {
    taken: "That account already signs in to a different account here. Sign in with it and disconnect it there first.",
    "provider-connected": "An account of that provider is already connected. Disconnect it first.",
    cancelled: "Connecting was cancelled.",
    banned: "This account is banned.",
    failed: "Connecting did not work. Try again.",
  };
  const connected = param("connected");
  const connectError = param("connect-error");
  if (connected) toast(labelOf(connected) + " is connected. It signs you into this account from now on.", { kind: "ok" });
  if (connectError) toast(CONNECT_ERRORS[connectError] || CONNECT_ERRORS.failed, { kind: "error" });
  if (connected || connectError) {
    history.replaceState(null, "", location.pathname + "#sign-ins");
    //  Erst hier, nicht per Anker in der Weiterleitung: die Listen darueber
    //  kommen nachgeladen und haetten die Stelle wieder verschoben.
    document.getElementById("sign-ins")?.scrollIntoView({ block: "start" });
  }

  document.querySelectorAll("[data-connect]").forEach((button) => button.addEventListener("click", async () => {
    button.disabled = true;
    try {
      await ensureCsrf();
      //  Der Server merkt sich, dass die naechste Rueckkehr VERBINDET statt
      //  anzumelden, und nennt die Adresse. Hingehen muss die Seite selbst:
      //  ein Formular, das zum Anbieter weiterleitet, haelt die CSP auf.
      const result = await api("POST", "/api/v1/me/sign-ins/" + encodeURIComponent(button.dataset.connect) + "/connect");
      location.href = result.redirect;
    } catch (e) {
      toastError(e);
      button.disabled = false;
    }
  }));

  document.querySelectorAll("[data-disconnect]").forEach((button) => button.addEventListener("click", async () => {
    const provider = button.dataset.disconnect;
    const row = button.closest(".signin-row");
    const profile = row?.querySelector(".chip") != null;

    const sure = await confirmDialog({
      title: "Disconnect " + labelOf(provider) + "?",
      body: profile
        ? "It will no longer sign you in. Your picture came from it and goes too; your name stays until you sign in with the next one."
        : "It will no longer sign you in to this account.",
      confirm: "Disconnect",
    });
    if (!sure) return;

    try {
      await ensureCsrf();
      await api("DELETE", "/api/v1/me/sign-ins/" + encodeURIComponent(provider));
      //  Neu laden statt die Zeilen nachzubauen: was sich aendert (welche
      //  Zeile Profil ist, welche sich noch loesen laesst), rechnet der Server.
      history.replaceState(null, "", location.pathname + "#sign-ins");
      location.reload();
    } catch (e) {
      toastError(e);
    }
  }));

  document.getElementById("revoke").addEventListener("click", async () => {
    const sure = await confirmDialog({
      title: "Sign out every Workbench?",
      body: "Every Animation Workbench connected to this account has to sign in again.",
      confirm: "Sign them out",
    });
    if (!sure) return;
    try {
      await ensureCsrf();
      await api("POST", "/api/v1/me/tokens/revoke-all");
      toast("All Workbench sign-ins ended.", { kind: "ok" });
    } catch (e) {
      toastError(e);
    }
  });
})();
