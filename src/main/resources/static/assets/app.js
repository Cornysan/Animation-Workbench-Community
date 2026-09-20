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

  /**
   * "3 days ago" statt "17. Sept. 2026".
   *
   * An einem Kommentar zaehlt der Abstand, nicht das Datum - "gestern" sagt,
   * ob das Gespraech laeuft, ein Datum sagt es nicht. Ab einem Monat kippt es
   * zurueck aufs Datum: dann ist "vor 43 Tagen" eine Rechenaufgabe.
   */
  function formatRelative(iso) {
    const then = new Date(iso);
    const seconds = Math.round((Date.now() - then.getTime()) / 1000);
    if (seconds < 45) return "just now";

    const units = [
      ["minute", 60], ["hour", 3600], ["day", 86400], ["week", 604800],
    ];
    let label = null;
    for (const [unit, size] of units) {
      const value = Math.floor(seconds / size);
      if (value < 1) break;
      if (unit === "week" && value > 4) break;
      label = [unit, value];
    }
    if (!label) return formatDate(iso);
    const [unit, value] = label;
    return value === 1 ? "1 " + unit + " ago" : value + " " + unit + "s ago";
  }

  /**
   * Text in die Zwischenablage. Der Rueckweg ueber ein Hilfs-<textarea> ist
   * fuer Browser da, die `navigator.clipboard` nur auf https anbieten - die
   * Entwicklung laeuft auf http://localhost.
   */
  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      const field = el("textarea", { style: "position:fixed;opacity:0" });
      field.value = text;
      document.body.append(field);
      field.select();
      const ok = document.execCommand("copy");
      field.remove();
      return ok;
    }
  }

  function param(name) {
    return new URLSearchParams(location.search).get(name);
  }

  /**
   * Wohin "Sign in" fuehrt - oder null, wenn dieses Portal keine Anmeldung hat.
   *
   * Der Weg stand an fuenf Stellen fest verdrahtet auf Discord. Auf einem
   * Server ohne Discord-Zugangsdaten landet das bei `?client_id=unset`, und
   * Discord antwortet mit "Invalid Form Body" - eine Fehlerseite ohne Ausweg
   * (Befund B6). Der Server nennt den Weg jetzt im Kopf der Seite.
   */
  const signInUrl = document.querySelector('meta[name="aw-signin"]')?.content || null;

  /**
   * Der Knopf dazu. Ohne Anmeldeweg wird daraus ein Satz statt eines Knopfes,
   * der nirgendwohin fuehrt.
   */
  function signInButton(label, primary) {
    if (!signInUrl) return el("span", { class: "faint small" }, "Sign-in is not set up on this portal.");
    return el("a", { class: "button" + (primary ? " primary" : ""), href: signInUrl }, label);
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

  /**
   * Die Clip-Karte.
   *
   * NULLEN WERDEN WEGGELASSEN. Vorher stand auf jeder Karte "used 0 times · 0 ♥".
   * Ein junger Katalog besteht fast nur aus solchen Karten, und eine Reihe aus
   * Nullen liest sich als "hier benutzt niemand etwas" - dabei sagt sie bloss
   * "dieser Clip ist neu". Was null ist, steht nicht da; was zaehlt, faellt
   * dann auf.
   *
   * DIE DAUER LIEGT AUF DER VORSCHAU, wie die Laufzeit auf einem Video. Sie
   * gehoert zum Bild ("wie lang ist diese Bewegung"), nicht zur Zeile mit
   * Autor und Beliebtheit.
   *
   * DER KNOPF "View clip" IST WEG. Die ganze Karte war schon der Link; der
   * Knopf war ein zweiter Weg zum selben Ziel und hat auf jeder Karte 50 px
   * gekostet.
   */
  function clipCard(item, observer) {
    const canvas = el("canvas", { "data-slug": item.slug, width: 560, height: 420 });
    if (item.hasPreview && observer) {
      canvas.__observer = observer;
      observer.observe(canvas);
    }

    const fresh = Date.now() - new Date(item.createdAt).getTime() < WEEK;

    //  Der Autor ist ein Weg, kein Etikett: "mehr von dieser Person" ist die
    //  zweite Frage nach "was ist das". Ein <span> in einem <a> kann kein
    //  zweiter Link sein - deshalb traegt die Karte hier einen Klick, der das
    //  Weiterreichen an die Karte abbricht.
    const author = el("span", {
      class: "card-author",
      title: "All clips by " + item.author,
      onclick: (event) => {
        event.preventDefault();
        event.stopPropagation();
        location.href = "/browse.html?author=" + encodeURIComponent(item.author);
      },
    }, item.author);

    const meta = [author];
    const add = (text) => {
      meta.push(el("span", { class: "dot" }, "·"), el("span", {}, text));
    };
    // "Used" statt "downloads": gezaehlt wird die Uebernahme in ein Projekt,
    // nicht der Dateiabruf.
    if (item.downloads > 0) add(item.downloads === 1 ? "used once" : "used " + item.downloads + "×");
    if (item.likes > 0) add(item.likes + " ♥");
    if (item.comments > 0) add(item.comments === 1 ? "1 comment" : item.comments + " comments");

    return el("a", { class: "card", href: "/clip.html?p=" + encodeURIComponent(item.slug) },
      el("div", { class: "card-stage" },
        item.hasPreview ? canvas : el("div", { class: "viewer-empty" }, "No preview"),
        fresh ? el("span", { class: "card-flag" }, "New") : null,
        el("span", { class: "card-duration" }, formatDuration(item.durationSeconds))),
      el("div", { class: "card-body" },
        el("div", { class: "card-title", title: item.title }, item.title),
        el("div", { class: "card-meta" }, meta)));
  }

  /**
   * "/" springt in die Suche - das Kuerzel, das jeder Katalog hat.
   *
   * Nicht, waehrend schon getippt wird: sonst kann in keinem Textfeld der
   * Seite ein Schraegstrich stehen, und der Kommentar unter einer Animation ist
   * genau die Stelle, an der jemand "hoch/runter" schreiben will.
   */
  function bindSearchShortcut() {
    const field = document.getElementById("site-search");
    if (!field) return;

    field.value = param("q") || "";

    document.addEventListener("keydown", (event) => {
      if (event.key !== "/" || event.ctrlKey || event.metaKey || event.altKey) return;
      const active = document.activeElement;
      if (active && (active.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(active.tagName))) return;
      event.preventDefault();
      field.focus();
      field.select();
    });
  }

  //  Die Skripte stehen am Ende des <body>, der Rahmen ist also schon da.
  const signOutButton = document.getElementById("sign-out");
  if (signOutButton) signOutButton.addEventListener("click", signOut);
  bindSearchShortcut();
  resumePendingLink();

  return {
    api, ApiError, ensureCsrf, me, el, notice, formatDuration, formatDate, formatRelative,
    copyText, param, signInUrl, signInButton, clipCard, previewObserver,
  };
})();
