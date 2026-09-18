// Gemeinsamer Rahmen aller Seiten: API-Aufrufe, Anmeldung, Kopf und Fuß.
// Kein Framework, kein Build-Schritt, kein CDN - die Content Security Policy
// erlaubt nur Skripte von dieser Adresse.

const AW = (() => {
  function cookie(name) {
    const match = document.cookie.match(new RegExp("(?:^|; )" + name + "=([^;]*)"));
    return match ? decodeURIComponent(match[1]) : null;
  }

  class ApiError extends Error {
    constructor(status, code, message) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }

  async function api(method, path, body) {
    const headers = { Accept: "application/json" };
    const init = { method, headers, credentials: "same-origin" };

    if (body instanceof FormData) {
      init.body = body;
    } else if (body !== undefined) {
      headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(body);
    }

    // CSRF: Spring legt XSRF-TOKEN als Cookie ab und erwartet es als Header zurück.
    if (method !== "GET") {
      const token = cookie("XSRF-TOKEN");
      if (token) headers["X-XSRF-TOKEN"] = token;
    }

    const response = await fetch(path, init);
    const text = await response.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = null; }

    if (!response.ok) {
      const error = data && data.error ? data.error : { code: "http-" + response.status, message: "Request failed (" + response.status + ")." };
      throw new ApiError(response.status, error.code, error.message);
    }
    return data;
  }

  // Ein GET vorab, damit das CSRF-Cookie sicher gesetzt ist, bevor ein POST folgt.
  async function ensureCsrf() {
    if (!cookie("XSRF-TOKEN")) await fetch("/api/v1/status", { credentials: "same-origin" });
  }

  let mePromise = null;
  function me() {
    if (!mePromise) {
      mePromise = api("GET", "/api/v1/me").catch((e) => (e.status === 401 ? null : Promise.reject(e)));
    }
    return mePromise;
  }

  let statusPromise = null;
  function status() {
    if (!statusPromise) statusPromise = api("GET", "/api/v1/status").catch(() => null);
    return statusPromise;
  }

  function el(tag, attrs, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs || {})) {
      if (value === undefined || value === null || value === false) continue;
      if (key === "class") node.className = value;
      else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
      else node.setAttribute(key, value === true ? "" : value);
    }
    for (const child of children.flat()) {
      if (child === null || child === undefined || child === false) continue;
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
  }

  function notice(container, message, kind) {
    container.replaceChildren(el("div", { class: "notice " + (kind || "") }, message));
  }

  function formatDuration(seconds) {
    return seconds < 10 ? seconds.toFixed(2) + " s" : seconds.toFixed(1) + " s";
  }

  function formatDate(iso) {
    return new Date(iso).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
  }

  function param(name) {
    return new URLSearchParams(location.search).get(name);
  }

  /**
   * Marke aus der Docs-Site (Variante E): das Lockup in der breiten Leiste,
   * der Mark allein, wenn sie zur Zeile zusammenfaellt.
   */
  function brandArt() {
    return [
      el("img", { class: "brand-lockup", src: "/assets/brand/aw-lockup-weiss.svg", alt: "Animation Workbench", width: 178, height: 34 }),
      el("img", { class: "brand-mark", src: "/assets/brand/aw-mark-dark.svg", alt: "Animation Workbench", width: 30, height: 30 }),
    ];
  }

  /** Strichsymbole für die Seitenleiste - 20×20, erben die Textfarbe. */
  function icon(name) {
    const ns = "http://www.w3.org/2000/svg";
    const paths = {
      browse: ["M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h6v6h-6z"],
      me: ["M4 5h11l2 2h3v12H4z"],
      licenses: ["M6 3h9l4 4v14H6z", "M15 3v5h4"],
      moderation: ["M12 3l7 3v6c0 4-3 7-7 9-4-2-7-5-7-9V6z"],
    };

    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("class", "nav-icon");
    svg.setAttribute("aria-hidden", "true");

    for (const d of paths[name] || []) {
      const path = document.createElementNS(ns, "path");
      path.setAttribute("d", d);
      path.setAttribute("fill", "none");
      path.setAttribute("stroke", "currentColor");
      path.setAttribute("stroke-width", "1.7");
      path.setAttribute("stroke-linejoin", "round");
      path.setAttribute("stroke-linecap", "round");
      svg.append(path);
    }

    return svg;
  }

  /**
   * Ohne konfigurierte Discord-App landet der Knopf auf Discords Fehlerseite
   * („Ungültiger Formulartext" - client_id=unset). Dann lieber sagen, woran es
   * liegt, und lokal auf die Entwickleranmeldung zeigen.
   */
  async function signInControls() {
    const s = await status();
    if (!s || s.discordSignIn) {
      return [el("a", { class: "button primary", href: "/oauth2/authorization/discord" }, "Sign in with Discord")];
    }

    return [
      el("div", { class: "sidebar-hint" },
        el("strong", {}, "Sign-in is not set up"),
        el("span", {}, "This portal has no Discord app configured yet.")),
      s.devLogin ? el("a", { class: "button", href: "/dev.html" }, "Developer sign-in") : null,
    ].filter(Boolean);
  }

  async function signOut() {
    await ensureCsrf();
    await fetch("/logout", { method: "POST", credentials: "same-origin", headers: { "X-XSRF-TOKEN": cookie("XSRF-TOKEN") || "" } });
    location.href = "/";
  }

  async function renderShell(active) {
    const header = document.getElementById("site-header");
    const footer = document.getElementById("site-footer");

    if (header) {
      const account = el("div", { class: "account" }, el("span", { class: "faint small" }, "…"));

      //  title, weil die Beschriftung in schmalen Fenstern entfällt.
      const navItem = (key, href, label) =>
        el("a", { href, title: label, class: "nav-item" + (active === key ? " active" : "") },
          icon(key), el("span", {}, label));

      header.replaceChildren(
        el("a", { class: "brand", href: "/", title: "Animation Workbench Community" },
          brandArt(), el("small", {}, "Community")),
        el("nav", { class: "site-nav" },
          navItem("browse", "/", "Browse"),
          navItem("me", "/me.html", "My clips"),
          navItem("licenses", "/licenses.html", "Licenses"),
          el("a", { href: "/admin.html", title: "Moderation", class: "nav-item admin-link hidden" + (active === "admin" ? " active" : "") },
            icon("moderation"), el("span", {}, "Moderation"))),
        el("div", { class: "sidebar-foot" },
          el("div", { class: "sidebar-hint" },
            el("strong", {}, "Share your own"),
            el("span", {}, "In the Animation Workbench: right-click a clip, Export Clip, Share as .awclip.")),
          account));

      try {
        const user = await me();

        // Rückweg nach dem Discord-Login: Discord landet auf "/", der Code der
        // Workbench-Anmeldung wartet in der Sitzung.
        const pendingCode = sessionStorage.getItem("aw-link-code");
        if (user && pendingCode && !location.pathname.endsWith("/link.html")) {
          location.href = "/link.html?code=" + encodeURIComponent(pendingCode);
          return;
        }

        if (user) {
          if (user.role === "ADMIN") header.querySelector(".admin-link").classList.remove("hidden");

          account.replaceChildren(
            el("div", { class: "avatar" }, (user.displayName || "?").charAt(0).toUpperCase()),
            el("div", { class: "account-name" },
              el("span", {}, user.displayName),
              user.unreadNotifications > 0
                ? el("a", { class: "badge", href: "/me.html", title: "Notifications" },
                    user.unreadNotifications + (user.unreadNotifications === 1 ? " message" : " messages"))
                : el("span", { class: "faint small" }, user.role === "ADMIN" ? "Moderator" : "Signed in")),
            el("button", { class: "ghost icon-button", title: "Sign out", onclick: signOut }, "⏻"));
        } else {
          account.replaceChildren(...(await signInControls()));
        }
      } catch {
        account.replaceChildren(el("span", { class: "faint small" }, "Portal offline"));
      }
    }

    if (footer) {
      footer.replaceChildren(
        el("a", { href: "/rules.html" }, "Community rules"),
        el("a", { href: "/takedown.html" }, "Report a rights violation"),
        el("a", { href: "/terms.html" }, "Terms"),
        el("a", { href: "/privacy.html" }, "Privacy"),
        el("a", { href: "/impressum.html" }, "Impressum"));
    }

    const current = await status();
    if (current && !current.communityEnabled) {
      const main = document.querySelector("main");
      main.prepend(el("div", { class: "notice warn" }, "The community is paused right now. Rights holders can still use the takedown form."));
    }
  }

  return { api, ApiError, ensureCsrf, me, el, notice, formatDuration, formatDate, param, renderShell };
})();
