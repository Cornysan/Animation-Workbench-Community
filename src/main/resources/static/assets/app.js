// Gemeinsam fuer alle Seiten: API-Aufrufe, CSRF, Abmelden und ein paar
// Helfer zum Bauen von DOM. Kopf und Fuss kommen NICHT mehr von hier -
// sie stehen im ausgelieferten HTML (templates/fragments/shell.html).
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
   * Abmelden. Der Knopf kommt jetzt fertig aus der Vorlage; hier haengt nur
   * noch die Handlung daran.
   */
  async function signOut() {
    await ensureCsrf();
    await fetch("/logout", { method: "POST", credentials: "same-origin", headers: { "X-XSRF-TOKEN": cookie("XSRF-TOKEN") || "" } });
    location.href = "/";
  }

  /**
   * Rueckweg nach dem Discord-Login: Discord landet auf "/", der Code der
   * Workbench-Anmeldung wartet in der Sitzung.
   *
   * Frueher hing das am Bauen des Rahmens und damit an der Antwort von
   * `/api/v1/me`. Ob jemand angemeldet ist, steht jetzt schon im Dokument -
   * die Weiterleitung passiert also sofort statt nach einem Rundweg zum Server.
   */
  function resumePendingLink() {
    if (document.body.dataset.signedIn !== "true") return;

    const code = sessionStorage.getItem("aw-link-code");
    if (code && !location.pathname.endsWith("/link.html")) {
      location.href = "/link.html?code=" + encodeURIComponent(code);
    }
  }

  /**
   * Die Clip-Karte. Sie stand in `pages/browse.js`, und als die Startseite
   * dieselbe Karte brauchte, war die Wahl: zweimal pflegen oder einmal hier.
   *
   * Die Vorschau ist das Strichmaennchen, nicht die Figur - eine Seite zeigt
   * bis zu 24 Karten, und so viele WebGL-Kontexte gibt kein Browser her. Die
   * Figur steht auf der Clip-Seite und im Kopf der Startseite, wo sie einzeln
   * ist und gross genug, um etwas zu erzaehlen.
   */
  function previewObserver() {
    return new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.__observer.unobserve(entry.target);
        const canvas = entry.target;
        api("GET", "/api/v1/packages/" + canvas.dataset.slug + "/preview")
          .then((preview) => new SkeletonViewer(canvas, preview,
            { interactive: false, autoplay: true, yaw: -0.7, pitch: 0.18 }))
          .catch(() => canvas.replaceWith(el("div", { class: "viewer-empty" }, "No preview")));
      }
    }, { rootMargin: "300px" });
  }

  const WEEK = 7 * 24 * 60 * 60 * 1000;

  function clipCard(item, observer) {
    const canvas = el("canvas", { "data-slug": item.slug, width: 560, height: 420 });
    if (item.hasPreview && observer) {
      canvas.__observer = observer;
      observer.observe(canvas);
    }

    const fresh = Date.now() - new Date(item.createdAt).getTime() < WEEK;

    return el("a", { class: "card", href: "/clip.html?p=" + encodeURIComponent(item.slug) },
      item.hasPreview ? canvas : el("div", { class: "viewer-empty" }, "No preview"),
      fresh ? el("span", { class: "card-flag" }, "New") : null,
      el("div", { class: "card-body" },
        el("div", { class: "card-title", title: item.title }, item.title),
        el("div", { class: "card-meta" },
          el("span", {}, item.author),
          el("span", { class: "dot" }, "·"),
          el("span", {}, formatDuration(item.durationSeconds)),
          el("span", { class: "dot" }, "·"),
          // "Used" statt "downloads": gezaehlt wird die Uebernahme in ein
          // Projekt, nicht der Dateiabruf.
          el("span", {}, item.downloads === 1 ? "used once" : "used " + item.downloads + " times"),
          el("span", { class: "dot" }, "·"),
          el("span", {}, item.likes + " ♥")),
        el("div", { class: "card-cta" }, "View clip")));
  }

  //  Die Skripte stehen am Ende des <body>, der Rahmen ist also schon da.
  const signOutButton = document.getElementById("sign-out");
  if (signOutButton) signOutButton.addEventListener("click", signOut);
  resumePendingLink();

  return {
    api, ApiError, ensureCsrf, me, el, notice, formatDuration, formatDate, param,
    clipCard, previewObserver,
  };
})();
