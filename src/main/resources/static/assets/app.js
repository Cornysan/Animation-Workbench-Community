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

  //  Die Skripte stehen am Ende des <body>, der Rahmen ist also schon da.
  const signOutButton = document.getElementById("sign-out");
  if (signOutButton) signOutButton.addEventListener("click", signOut);
  resumePendingLink();

  return { api, ApiError, ensureCsrf, me, el, notice, formatDuration, formatDate, param };
})();
