// Gemeinsam fuer alle Seiten: API-Aufrufe, CSRF, Abmelden und ein paar
// Helfer zum Bauen von DOM. Kopf und Fuss kommen NICHT mehr von hier -
// sie stehen im ausgelieferten HTML (templates/fragments/shell.html).
// Kein Framework, kein Build-Schritt, kein CDN - die Content Security Policy
// erlaubt nur Skripte von dieser Adresse.

const AW = (() => {
  /**
   * Wo dieses Skript liegt - `/assets/v/<commit>/`, mit Schraegstrich am Ende.
   * Nachgeladene Module muessen unter DERSELBEN Version geholt werden, sonst
   * mischt eine Seite zwei Staende (siehe StaticAssets.kt). Gelesen wird es aus
   * der eigenen Adresse, solange das Skript noch laeuft: `currentScript` ist
   * nur waehrend der ersten Ausfuehrung gesetzt.
   */
  const assetBase = (() => {
    const src = document.currentScript && document.currentScript.src;
    return src ? src.replace(/[^/]*$/, "") : "/assets/";
  })();

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
      const error = data && data.error ? data.error : { code: "http-" + response.status, message: fallbackMessage(response.status) };
      throw new ApiError(response.status, error.code, error.message);
    }
    return data;
  }

  /**
   * Was ein Mensch lesen soll, wenn der Server keinen eigenen Satz mitschickt.
   * "Request failed (401)" stand vorher in jeder Meldung - richtig, aber fuer
   * niemanden ausser dem Entwickler lesbar.
   */
  function fallbackMessage(status) {
    if (status === 401) return "Your sign-in has ended. Sign in again to do that.";
    if (status === 403) return "You are not allowed to do that.";
    if (status === 404) return "That is not there anymore.";
    if (status === 429) return "Too many tries. Wait a moment and try again.";
    if (status >= 500) return "The community is having trouble right now. Try again in a moment.";
    return "That did not work (" + status + ").";
  }

  // Ein GET vorab, damit das CSRF-Cookie sicher gesetzt ist, bevor ein POST folgt.
  async function ensureCsrf() {
    if (!cookie("XSRF-TOKEN")) await fetch("/api/v1/status", { credentials: "same-origin" });
  }

  /**
   * Wer hier ist - oder null.
   *
   * OB jemand angemeldet ist, steht schon im Dokument: der Server setzt
   * `data-signed-in` am <body>, weil er es ohnehin weiss. Abgemeldet wird
   * deshalb gar nicht erst gefragt. Das spart nicht nur einen Rundweg je
   * Seitenaufruf - es nimmt auch den roten 401-Eintrag aus der Konsole, den
   * bis hierher JEDER Besucher auf JEDER Seite zu sehen bekam, sobald er sie
   * oeffnete. Ein abgefangener Fehler bleibt ein Fehler im Protokoll des
   * Browsers, und wer die Konsole aufmacht, liest ihn als Stoerung.
   *
   * WAS jemand heisst, steht dort nicht - dafuer bleibt der Aufruf.
   */
  let mePromise = null;
  function me() {
    if (!mePromise) {
      mePromise = document.body.dataset.signedIn === "true"
        ? api("GET", "/api/v1/me").catch((e) => (e.status === 401 ? null : Promise.reject(e)))
        : Promise.resolve(null);
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

  // ── Rueckmeldung ─────────────────────────────────────────────────────

  let toastRegion = null;

  /**
   * Eine kurze Meldung am unteren Rand, die von selbst wieder geht.
   *
   * WARUM. Rueckmeldung kam bisher auf drei Wegen: als Kasten an einer Stelle
   * der Seite (`notice`), als ausgetauschter Tooltip ("Link copied" - den sah
   * nur, wer mit der Maus darueber stand, und kein Vorleser) oder gar nicht:
   * ein Herz auf einer Karte, das am Server scheiterte, schwieg. Jetzt gibt es
   * einen Ort fuer "das hat geklappt" und "das nicht", und er wird vorgelesen.
   *
   * `kind` ist "ok", "error" oder leer; `action` ein `{ label, run }` fuer
   * einen Knopf in der Meldung. Zurueck kommt eine Funktion, die sie schliesst.
   * Mehr als drei stehen nie da - die aelteste geht zuerst.
   */
  function toast(message, options = {}) {
    if (!toastRegion) {
      toastRegion = el("div", { class: "toasts", role: "status", "aria-live": "polite" });
      document.body.append(toastRegion);
    }

    const kind = options.kind || "";
    const item = el("div", { class: "toast" + (kind ? " " + kind : "") },
      icon(kind === "error" ? "alert" : "check"), el("span", { class: "toast-text" }, message));

    let timer = null;
    let gone = false;
    const dismiss = () => {
      if (gone) return;
      gone = true;
      clearTimeout(timer);
      item.classList.add("leaving");
      //  Nach der Ausblende-Zeit entfernen, nicht auf `transitionend` warten:
      //  bei reduzierter Bewegung gibt es keinen Uebergang, und das Ereignis
      //  kaeme nie.
      setTimeout(() => item.remove(), 220);
    };

    if (options.action) {
      const action = el("button", { type: "button", class: "toast-action" }, options.action.label);
      action.addEventListener("click", () => { dismiss(); options.action.run(); });
      item.append(action);
    }

    //  Wer die Meldung mit der Maus festhaelt, will sie lesen.
    const arm = () => { timer = setTimeout(dismiss, options.duration || (kind === "error" ? 6000 : 3200)); };
    item.addEventListener("mouseenter", () => clearTimeout(timer));
    item.addEventListener("mouseleave", arm);

    toastRegion.append(item);
    while (toastRegion.children.length > 3) toastRegion.firstElementChild.remove();
    arm();
    return dismiss;
  }

  /** Den Fehler einer Handlung melden, die keinen eigenen Platz auf der Seite hat. */
  function toastError(error) {
    toast(error && error.message ? error.message : "Something went wrong.", { kind: "error" });
  }

  let dialogCount = 0;

  /**
   * Eine Rueckfrage im Stil der Seite statt `window.confirm`.
   *
   * Der Browser-Kasten sah auf jeder Plattform anders aus, trug die Adresse
   * statt einer Ueberschrift und konnte keinen Knopf "Delete" heissen - er
   * hiess immer "OK". Hier steht auf dem Knopf, was passiert.
   *
   * `typeToConfirm` verlangt ein getipptes Wort, bevor der Knopf aufgeht - die
   * zweite Huerde fuer Endgueltiges (Konto schliessen). Antwort: true/false.
   *
   * Aufgeraeumt wird ueber EINE Stelle (`finish`), nicht ueber das
   * `close`-Ereignis - Begruendung bei [collectionDialog].
   */
  function confirmDialog({ title, body, confirm = "OK", cancel = "Cancel", danger = false, typeToConfirm = null }) {
    return new Promise((resolve) => {
      const id = "aw-confirm-" + (++dialogCount);
      const typed = typeToConfirm
        ? el("input", { type: "text", autocomplete: "off", spellcheck: "false" })
        : null;
      const yes = el("button", { type: "submit", class: danger ? "danger" : "primary" }, confirm);
      //  `cancel: null` macht aus der Rueckfrage eine Mitteilung mit einem Knopf.
      const no = cancel === null ? null : el("button", { type: "button", class: "ghost" }, cancel);

      const paragraphs = (Array.isArray(body) ? body : [body]).filter(Boolean)
        .map((part) => (typeof part === "string" ? el("p", { class: "muted" }, part) : part));

      const form = el("form", {},
        el("h2", { id }, title),
        paragraphs,
        typed ? el("label", { class: "field" },
          el("span", {}, "Type ", el("strong", {}, typeToConfirm), " to confirm"), typed) : null,
        el("div", { class: "dialog-actions" }, no, yes));

      const dialog = el("dialog", { class: "sheet", "aria-labelledby": id }, form);
      document.body.append(dialog);

      let done = false;
      const finish = (answer) => {
        if (done) return;
        done = true;
        dialog.close();
        dialog.remove();
        resolve(answer);
      };

      if (typed) {
        yes.disabled = true;
        typed.addEventListener("input", () => { yes.disabled = typed.value.trim() !== typeToConfirm; });
      }

      if (no) no.addEventListener("click", () => finish(false));
      dialog.addEventListener("cancel", (event) => { event.preventDefault(); finish(false); });
      form.addEventListener("submit", (event) => {
        event.preventDefault();
        if (!yes.disabled) finish(true);
      });

      dialog.showModal();
      //  Bei etwas Endgueltigem steht der Fokus auf "Abbrechen": ein
      //  versehentliches Enter soll nichts loeschen.
      (typed || (danger && no ? no : yes)).focus();
    });
  }

  /** Eine Adresse kopieren und sagen, ob es geklappt hat. */
  async function copyLink(url) {
    const ok = await copyText(url);
    toast(ok ? "Link copied" : "Could not copy the link", { kind: ok ? "ok" : "error" });
    return ok;
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
   * Auf der Vorschau steht die FIGUR, dieselbe wie auf der Clip-Seite und aus
   * demselben Blickwinkel. Dass das geht, obwohl eine Seite bis zu 24 Karten
   * zeigt, liegt an einem einzigen WebGL-Kontext, den sich alle Karten teilen
   * (`assets/card-stage.js`) - nicht an 24 Kontexten, die kein Browser
   * hergibt. Das Strichmaennchen bleibt der Rueckfall.
   */
  /**
   * Wer Bewegung reduziert haben will, bekommt sie reduziert.
   *
   * Eine Katalogseite sind bis zu 24 gleichzeitig laufende Figuren. Fuer
   * manche Menschen ist das keine Kleinigkeit, und das Betriebssystem sagt es
   * laengst - man muss nur hinhoeren.
   */
  const stillPreviews = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;

  /**
   * Die Figur fuer die Karten liegt in einem Modul, und three.js liegt darin.
   * Geholt wird es erst, wenn wirklich eine Karte im Bild ist - wer die Regeln
   * liest oder sein Konto ansieht, laedt kein 3D.
   */
  let cardStageModule = null;
  function cardStage() {
    if (!cardStageModule) {
      cardStageModule = import(assetBase + "card-stage.js").catch((error) => {
        console.warn("[cards] no figure, drawing stick figures", error);
        return null;
      });
    }
    return cardStageModule;
  }

  let skeletonFallbackWarned = false;

  /**
   * Die Vorschau einer Karte: Daten holen, Figur aufbauen, und ab dann an- und
   * abschalten, je nachdem ob die Karte im Bild ist.
   */
  function cardPreview(canvas) {
    let viewer = null;
    let visible = true;
    let destroyed = false;

    //  Unsichtbar bis zum ersten Bild, dann einblenden (app.css, "Bewegung").
    canvas.classList.add("fade-in");

    api("GET", "/api/v1/packages/" + canvas.dataset.slug + "/preview")
      .then(async (preview) => {
        if (destroyed) return;
        //  Ein generischer Clip bekommt die Figur gar nicht erst zu sehen -
        //  auf seinem Skelett waere sie erfunden. Die Begruendung steht ganz
        //  bei `mountViewer` in viewer-ui.js.
        const stage = canvas.dataset.rig === "generic" ? null : await cardStage();
        if (stage) {
          try {
            viewer = await stage.mountCardStage(canvas, preview, { autoplay: !stillPreviews });
          } catch (error) {
            //  Eine Vorschau, die sich nicht auf die Figur umrechnen laesst,
            //  bekommt ihr Strichmaennchen. Die Konsole bekommt eine Zeile,
            //  nicht 24.
            if (!skeletonFallbackWarned) {
              skeletonFallbackWarned = true;
              console.warn("[cards] falling back to the skeleton", error);
            }
          }
        }

        //  Waehrend des Wartens abgeraeumt: die Figur gleich wieder abbauen,
        //  sonst haengt sie an der gemeinsamen Flaeche, ohne je gezeigt zu werden.
        if (destroyed) {
          if (viewer) viewer.destroy();
          return;
        }

        if (!viewer) {
          viewer = new SkeletonViewer(canvas, preview, { interactive: false, autoplay: !stillPreviews });
        }

        //  Stillgestellt heisst nicht Frame 0: viele Clips fangen in der
        //  Ruhelage an, und ein Laufzyklus saehe dann aus wie jemand, der
        //  steht. Ein Viertel hinein steht fast immer eine Pose.
        if (stillPreviews) viewer.setTime(viewer.duration * 0.25);
        viewer.setVisible(visible);
      })
      .catch(() => canvas.replaceWith(el("div", { class: "viewer-empty" }, "No preview")));

    return {
      setVisible(on) {
        visible = on;
        if (viewer) viewer.setVisible(on);
      },
      destroy() {
        destroyed = true;
        if (viewer) viewer.destroy();
        viewer = null;
      },
    };
  }

  /**
   * Die Vorschauen unter `root` abbauen, bevor die Karten verschwinden.
   *
   * Eine Karte, die einfach aus dem DOM faellt, nimmt ihre Figur nicht mit:
   * die Szene haengt weiter an der gemeinsamen Flaeche (card-stage.js), und der
   * Beobachter haelt die Leinwand fest. Wer Karten ersetzt, ruft vorher das hier.
   */
  /**
   * Platzhalter in eine Wand stellen, solange sie laedt - dieselbe Form wie
   * eine Karte, damit beim Eintreffen nichts springt. Die echten Karten
   * ersetzen sie mit `replaceChildren`, dafuer braucht es keinen eigenen Schritt.
   */
  function placeholderCards(container, count) {
    container.replaceChildren(...Array.from({ length: count }, () =>
      el("div", { class: "card placeholder", "aria-hidden": "true" },
        el("div", { class: "card-stage" }),
        el("div", { class: "card-body" },
          el("span", { class: "ph-line" }),
          el("span", { class: "ph-line short" })))));
  }

  function releasePreviews(root) {
    root.querySelectorAll("canvas").forEach((canvas) => {
      if (canvas.__observer) canvas.__observer.unobserve(canvas);
      if (canvas.__preview) canvas.__preview.destroy();
      canvas.__preview = null;
    });
  }

  /**
   * WAS IM BILD IST, LAEUFT - der Rest ruht.
   *
   * Vorher meldete sich eine Karte beim ersten Erscheinen ab und lief von da
   * an fuer immer weiter. Wer zwei Seiten weit blaettert, hatte danach zwei
   * Dutzend Figuren im Ruecken, die fuer niemanden rechnen. Die Beobachtung
   * bleibt jetzt bestehen und schaltet in beide Richtungen.
   */
  function previewObserver() {
    return new IntersectionObserver((entries) => {
      for (const entry of entries) {
        const canvas = entry.target;
        if (canvas.__preview) {
          canvas.__preview.setVisible(entry.isIntersecting);
        } else if (entry.isIntersecting) {
          canvas.__preview = cardPreview(canvas);
        }
      }
    }, { rootMargin: "300px" });
  }

  // ── Zeichen statt Woerter ────────────────────────────────────────────
  //  Ein Zeichen wird schneller erkannt als ein Wort gelesen, und es kostet
  //  ein Viertel der Breite. Der Preis ist, dass ein Zeichen falsch geraten
  //  werden kann - deshalb traegt JEDER Icon-Knopf hier seinen Satz mit sich
  //  (`data-tip` zeigt ihn beim Hovern, `aria-label` sagt ihn vor). Ein Icon
  //  ohne Tooltip ist ein Raetsel, keine Bedienung.
  //
  //  Alles aus EINEM Vorrat, im Strichstil der Navigation (24er Raster,
  //  1.7 Strichstaerke, currentColor). Gefuellt wird nur, was einen Zustand
  //  hat: ein gesetztes Herz, ein gesetzter Stern.
  const ICONS = {
    heart: '<path d="M12 20.3 4.8 13.1a4.6 4.6 0 0 1 6.5-6.5l.7.7.7-.7a4.6 4.6 0 0 1 6.5 6.5z"/>',
    star: '<path d="m12 4.5 2.3 4.7 5.2.8-3.8 3.6.9 5.2-4.6-2.5-4.6 2.5.9-5.2-3.8-3.6 5.2-.8z"/>',
    comment: '<path d="M4 5h16v11H9.5L4 20z"/>',
    link: '<path d="M10.5 13.5a3.5 3.5 0 0 0 5 0l2.5-2.5a3.5 3.5 0 0 0-5-5l-1 1"/><path d="M13.5 10.5a3.5 3.5 0 0 0-5 0L6 13a3.5 3.5 0 0 0 5 5l1-1"/>',
    flag: '<path d="M6 21V4"/><path d="M6 5h11l-2 3.5L17 12H6z"/>',
    follow: '<circle cx="9.5" cy="8" r="3.2"/><path d="M3.5 20c0-3.3 2.7-5.2 6-5.2s6 1.9 6 5.2"/><path d="M18.5 8v6"/><path d="M15.5 11h6"/>',
    following: '<circle cx="9.5" cy="8" r="3.2"/><path d="M3.5 20c0-3.3 2.7-5.2 6-5.2s6 1.9 6 5.2"/><path d="m16.5 10.5 2 2 4-4"/>',
    users: '<circle cx="9" cy="8" r="3.2"/><path d="M3 20c0-3.3 2.7-5.2 6-5.2s6 1.9 6 5.2"/><path d="M16 5.4a3.2 3.2 0 0 1 0 5.2"/><path d="M18.4 14.9c1.7.6 2.6 2 2.6 5.1"/>',
    folder: '<path d="M4 6h5l2 2.5h9V19H4z"/>',
    plus: '<path d="M12 5.5v13"/><path d="M5.5 12h13"/>',
    check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
    alert: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.8v5.2"/><path d="M12 16.4v.1"/>',
    close: '<path d="m6.5 6.5 11 11"/><path d="m17.5 6.5-11 11"/>',
    search: '<circle cx="11" cy="11" r="6.5"/><path d="m16 16 4.5 4.5"/>',
    edit: '<path d="M5 19h3.2l8.6-8.6-3.2-3.2L5 15.8z"/><path d="m14 6.4 3.2 3.2"/>',
    trash: '<path d="M4.5 7h15"/><path d="M9.5 7V4.5h5V7"/><path d="m6.8 7 1 12.5h8.4l1-12.5"/>',
    download: '<path d="M12 4v10.5"/><path d="m8 11 4 4 4-4"/><path d="M5 19.5h14"/>',
    clock: '<circle cx="12" cy="12" r="8"/><path d="M12 7.2V12l3 2"/>',
    award: '<circle cx="12" cy="9" r="4.6"/><path d="m9.2 13.4-1.7 6.6 4.5-2.6 4.5 2.6-1.7-6.6"/>',
    share: '<path d="M12 4v11"/><path d="m8 8 4-4 4 4"/><path d="M5 14v5.5h14V14"/>',
    grid: '<path d="M4 4h6v6H4z"/><path d="M14 4h6v6h-6z"/><path d="M4 14h6v6H4z"/><path d="M14 14h6v6h-6z"/>',
    dots: '<circle cx="6" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="18" cy="12" r="1.4"/>',
  };

  /** Ein Zeichen aus dem Vorrat. `filled` fuellt die Flaeche - fuer Zustaende. */
  function icon(name, filled) {
    const node = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    node.setAttribute("viewBox", "0 0 24 24");
    node.setAttribute("class", "icon" + (filled ? " filled" : ""));
    node.setAttribute("aria-hidden", "true");
    node.setAttribute("fill", filled ? "currentColor" : "none");
    node.setAttribute("stroke", "currentColor");
    node.setAttribute("stroke-width", "1.7");
    node.setAttribute("stroke-linejoin", "round");
    node.setAttribute("stroke-linecap", "round");
    //  Fester Vorrat aus dieser Datei, kein fremder Text - deshalb reicht
    //  innerHTML, und die Content Security Policy hat nichts dagegen.
    node.innerHTML = ICONS[name] || "";
    return node;
  }

  /**
   * Ein Knopf, der aus einem Zeichen besteht.
   *
   * `tip` ist Pflicht: er wird zum Tooltip UND zur Vorlesefassung. Wer keinen
   * Satz dafuer hat, hat auch kein Icon dafuer.
   */
  function iconButton({ name, tip, count, on, className, onClick, href }) {
    const attrs = {
      class: "icon-action" + (on ? " on" : "") + (className ? " " + className : ""),
      "data-tip": tip,
      "aria-label": tip,
      title: null,
    };

    const children = [icon(name, on), count === undefined || count === null
      ? null
      : el("span", { class: "count" }, String(count))];

    if (href) return el("a", { ...attrs, href }, children);

    const button = el("button", { ...attrs, type: "button" }, children);
    if (onClick) button.addEventListener("click", onClick);
    return button;
  }

  /** Den Zustand eines Icon-Knopfes umschalten: gefuelltes Zeichen, neue Zahl. */
  function setIconState(button, name, on, count) {
    button.classList.toggle("on", !!on);
    button.replaceChildren(icon(name, on), count === undefined || count === null
      ? null
      : el("span", { class: "count" }, String(count)));
  }

  // ── Ein Kasten, der an etwas haengt ──────────────────────────────────

  let openPopover = null;

  /**
   * Ein kleiner Kasten unter einem Knopf - fuer die Sammlungsauswahl.
   *
   * Er haengt am <body> und nicht im Knopf: die Karte hat `overflow: hidden`
   * und wuerde ihn abschneiden. Es gibt immer nur einen; ein Klick daneben,
   * Escape oder Scrollen schliesst ihn.
   */
  function popover(anchor, content) {
    closePopover();

    const box = el("div", { class: "popover" }, content);
    document.body.append(box);

    const rect = anchor.getBoundingClientRect();
    const width = box.offsetWidth;
    const left = Math.min(Math.max(8, rect.left + rect.width / 2 - width / 2), window.innerWidth - width - 8);

    //  Nach unten, ausser es ist unten kein Platz mehr.
    const below = rect.bottom + 8 + box.offsetHeight < window.innerHeight;
    box.style.left = left + window.scrollX + "px";
    box.style.top = (below ? rect.bottom + 8 : rect.top - box.offsetHeight - 8) + window.scrollY + "px";

    const onDown = (event) => {
      if (!box.contains(event.target) && !anchor.contains(event.target)) closePopover();
    };
    const onKey = (event) => { if (event.key === "Escape") closePopover(); };

    //  Erst im naechsten Takt horchen, sonst schliesst der Klick, der ihn
    //  geoeffnet hat, ihn gleich wieder.
    setTimeout(() => {
      document.addEventListener("mousedown", onDown);
      document.addEventListener("keydown", onKey);
    }, 0);

    //  Solange der Kasten offen ist, schweigt der Tooltip des Knopfes: er
    //  stuende sonst als Zettel ueber dem Kasten, den er erklaert.
    anchor.classList.add("tip-off");
    anchor.setAttribute("aria-expanded", "true");

    openPopover = () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
      anchor.classList.remove("tip-off");
      anchor.setAttribute("aria-expanded", "false");
      box.remove();
    };

    return box;
  }

  function closePopover() {
    if (openPopover) {
      const close = openPopover;
      openPopover = null;
      close();
    }
  }

  // ── Herz und Stern ───────────────────────────────────────────────────
  //  Zwei Handgriffe an einem fremden Clip, und beide sind ein Klick auf ein
  //  Zeichen: das Herz sagt "gut", der Stern legt ihn in eine Sammlung. Sie
  //  stehen ueberall gleich - auf der Karte im Katalog, auf dem Profil, in
  //  einer Sammlung und unter der grossen Vorschau.

  const signedIn = () => document.body.dataset.signedIn === "true";

  /** Der Hinweis fuer alles, was ein Konto braucht - statt eines toten Knopfes. */
  function signInHint(anchor, sentence) {
    popover(anchor, el("div", { class: "popover-note" },
      el("p", {}, sentence),
      signInButton("Sign in", true)));
  }

  /**
   * Das Herz. Setzt oder nimmt zurueck und traegt die Zahl selbst - eine Zahl,
   * die man anfassen kann, gehoert in den Knopf und nicht daneben.
   */
  function likeButton(item, onError = toastError) {
    let liked = !!item.likedByMe;
    let count = item.likes || 0;
    let pending = false;

    const show = () => {
      setIconState(button, "heart", liked, count || null);
      button.dataset.tip = liked ? "Remove your like" : "Like this clip";
      button.setAttribute("aria-label", button.dataset.tip);
      button.setAttribute("aria-pressed", String(liked));
    };

    const button = iconButton({
      name: "heart",
      tip: liked ? "Remove your like" : "Like this clip",
      count: count || null,
      on: liked,
      className: "like",
      //  SOFORT UMSCHALTEN, DANN FRAGEN. Vorher stand der Knopf gesperrt, bis
      //  der Server antwortete - ein Herz, das eine Viertelsekunde ueberlegt,
      //  fuehlt sich kaputt an. Schlaegt es fehl, springt es zurueck und sagt
      //  warum; sonst gilt die Zahl des Servers.
      onClick: async () => {
        if (!signedIn()) return signInHint(button, "Sign in to like a clip.");
        if (pending) return;

        const before = { liked, count };
        liked = !liked;
        count = Math.max(0, count + (liked ? 1 : -1));
        show();
        if (liked) pulse(button);

        pending = true;
        try {
          await ensureCsrf();
          const result = await api("POST", "/api/v1/packages/" + encodeURIComponent(item.slug) + "/like",
            { liked });
          count = result.likes;
          show();
        } catch (e) {
          ({ liked, count } = before);
          show();
          if (onError) onError(e);
        } finally {
          pending = false;
        }
      },
    });
    button.setAttribute("aria-pressed", String(liked));

    return button;
  }

  /**
   * Die Klasse, an der die Bewegung haengt, einmal neu setzen - auch wenn sie
   * noch vom letzten Mal dransteht. Was sich bewegt, steht in app.css; ohne
   * Stil passiert hier nichts.
   */
  function pulse(node) {
    node.classList.remove("pulse");
    void node.offsetWidth;
    node.classList.add("pulse");
  }

  /**
   * Der Stern: in eine Sammlung legen.
   *
   * Ein Klick oeffnet die Auswahl - die eigenen Sammlungen, jede mit der
   * Angabe, ob dieser Clip schon drin liegt, und darunter der Weg zu einer
   * neuen. Der Stern steht gefuellt, sobald er in irgendeiner liegt; die Zahl
   * daneben zaehlt PERSONEN, nicht Sammlungen.
   */
  function saveButton(item, onError) {
    let saved = !!item.savedByMe;
    let count = item.saves || 0;

    const button = iconButton({
      name: "star",
      tip: saved ? "In one of your collections" : "Save to a collection",
      count: count || null,
      on: saved,
      className: "save",
      onClick: () => {
        if (!signedIn()) return signInHint(button, "Sign in to collect clips.");

        collectionPicker(button, item.slug, saved, (nowSaved) => {
          if (nowSaved === saved) return;
          count = Math.max(0, count + (nowSaved ? 1 : -1));
          saved = nowSaved;
          setIconState(button, "star", saved, count || null);
          if (saved) pulse(button);
          button.dataset.tip = saved ? "In one of your collections" : "Save to a collection";
          button.setAttribute("aria-label", button.dataset.tip);
        }, onError || toastError);
      },
    });
    //  Der Stern schaltet nicht, er oeffnet eine Auswahl - das sagt er dem
    //  Vorleser, statt sich als Schalter auszugeben.
    button.setAttribute("aria-haspopup", "dialog");

    return button;
  }

  /**
   * Die Auswahl unter dem Stern.
   *
   * Suchfeld, Liste, und ganz unten der Weg zu einer neuen Sammlung. Jede
   * Zeile ist ein Schalter: Klick legt hinein, noch ein Klick nimmt wieder
   * heraus. Kein Speichern-Knopf - die Zeile IST die Handlung.
   */
  async function collectionPicker(anchor, slug, savedNow, onChange, onError) {
    const list = el("div", { class: "picker-list" }, el("p", { class: "faint small" }, "Loading…"));
    const search = el("input", { type: "search", placeholder: "Find a collection", "aria-label": "Find a collection" });

    const create = el("button", { class: "picker-new", type: "button" },
      icon("plus"), el("span", {}, "Add to a new collection"));

    const box = popover(anchor, el("div", { class: "picker" },
      el("div", { class: "picker-search" }, icon("search"), search),
      list,
      create));

    const report = (error) => {
      list.replaceChildren(el("p", { class: "faint small" }, error.message));
      if (onError) onError(error);
    };

    let rows = [];
    const anySaved = () => rows.some((row) => row.contains);

    const draw = () => {
      const term = search.value.trim().toLowerCase();
      const shown = rows.filter((row) => !term || row.title.toLowerCase().includes(term));

      if (!rows.length) {
        list.replaceChildren(el("p", { class: "faint small" }, "No collections yet - make the first one."));
        return;
      }
      if (!shown.length) {
        list.replaceChildren(el("p", { class: "faint small" }, "Nothing matches that."));
        return;
      }

      list.replaceChildren(...shown.map((row) => {
        const entry = el("button", { class: "picker-row" + (row.contains ? " on" : ""), type: "button" },
          icon("star", row.contains),
          el("span", { class: "picker-title" }, row.title),
          el("span", { class: "faint small" }, String(row.items)));

        entry.addEventListener("click", async () => {
          entry.disabled = true;
          try {
            await ensureCsrf();
            const path = "/api/v1/collections/" + encodeURIComponent(row.slug) + "/items";
            const result = row.contains
              ? await api("DELETE", path + "/" + encodeURIComponent(slug))
              : await api("POST", path, { slug });

            row.contains = !row.contains;
            row.items = result.items;
            draw();
            onChange(anySaved());
          } catch (e) {
            report(e);
          } finally {
            entry.disabled = false;
          }
        });

        return entry;
      }));
    };

    search.addEventListener("input", draw);

    create.addEventListener("click", async () => {
      const made = await collectionDialog();
      if (!made) return;

      try {
        await ensureCsrf();
        const result = await api("POST", "/api/v1/collections/" + encodeURIComponent(made.slug) + "/items", { slug });
        rows.unshift({ slug: made.slug, title: made.title, items: result.items, contains: true });
        draw();
        onChange(true);
      } catch (e) {
        report(e);
      }
    });

    try {
      rows = await api("GET", "/api/v1/me/collections?contains=" + encodeURIComponent(slug));
      //  Der Kasten steht schon - die Liste macht ihn hoeher, und er soll
      //  danach immer noch unter seinem Knopf haengen.
      draw();
      if (box.isConnected && savedNow !== anySaved()) onChange(anySaved());
    } catch (e) {
      report(e);
    }
  }

  /**
   * Der Dialog fuer eine neue Sammlung: Name, Beschreibung, Sichtbarkeit.
   *
   * Ein eigener Kasten statt eines Feldes im Popover, weil hier drei Angaben
   * zusammenkommen und eine davon eine Entscheidung ist ("sieht das jemand?").
   */
  function collectionDialog(existing) {
    return new Promise((resolve) => {
      const name = el("input", { type: "text", name: "title", maxlength: "60", required: true,
        placeholder: "Attack animations", value: existing ? existing.title : "" });

      const description = el("textarea", { name: "description", maxlength: "500", rows: "4",
        placeholder: "What belongs in here?" }, existing ? existing.description : "");

      const counter = el("span", { class: "faint small counter" }, (description.value.length) + "/500");
      description.addEventListener("input", () => { counter.textContent = description.value.length + "/500"; });

      const publicChoice = el("input", { type: "radio", name: "visibility", value: "PUBLIC",
        checked: !existing || existing.visibility !== "UNLISTED" });
      const unlistedChoice = el("input", { type: "radio", name: "visibility", value: "UNLISTED",
        checked: existing ? existing.visibility === "UNLISTED" : false });

      const error = el("div", {});

      const form = el("form", { method: "dialog" },
        el("h2", {}, existing ? "Edit collection" : "New collection"),
        el("label", { class: "field" }, el("span", {}, "Name"), name),
        el("label", { class: "field" },
          el("span", {}, "Description ", el("span", { class: "faint small" }, "optional"), counter),
          description),
        el("fieldset", { class: "choices" },
          el("legend", {}, "Who can see it"),
          el("label", {}, publicChoice,
            el("span", {}, el("strong", {}, "Public"), " - on your profile and in the collection list")),
          el("label", {}, unlistedChoice,
            el("span", {}, el("strong", {}, "Unlisted"), " - only for people with the link"))),
        error,
        el("div", { class: "dialog-actions" },
          el("button", { value: "cancel", type: "button", class: "ghost" }, "Cancel"),
          el("button", { value: "save", class: "primary" }, existing ? "Save" : "Create")));

      const dialog = el("dialog", { class: "sheet" }, form);
      document.body.append(dialog);

      //  Aufraeumen haengt NICHT am `close`-Ereignis des Dialogs. Das ist zwar
      //  der vorgesehene Weg, aber er ist von der Browserfassung abhaengig -
      //  in der eingebauten Vorschau dieses Rechners feuert es weder beim
      //  Absenden eines `method="dialog"`-Formulars noch bei `close()`, und
      //  dann bliebe ein unsichtbarer Dialog im Dokument stehen. Hier schliesst,
      //  entfernt und antwortet EINE Stelle.
      let done = false;
      const finish = (result) => {
        if (done) return;
        done = true;
        dialog.close();
        dialog.remove();
        resolve(result);
      };

      form.querySelector('button[value="cancel"]').addEventListener("click", (event) => {
        event.preventDefault();
        finish(null);
      });

      //  Escape schliesst den Dialog am Browser vorbei - auch dann soll der
      //  Aufrufer eine Antwort bekommen.
      dialog.addEventListener("cancel", (event) => {
        event.preventDefault();
        finish(null);
      });

      let busy = false;
      form.addEventListener("submit", async (event) => {
        event.preventDefault();
        if (busy) return;

        const payload = {
          title: name.value,
          description: description.value,
          visibility: unlistedChoice.checked ? "UNLISTED" : "PUBLIC",
        };

        busy = true;
        try {
          await ensureCsrf();
          const saved = existing
            ? await api("PATCH", "/api/v1/collections/" + encodeURIComponent(existing.slug), payload)
            : await api("POST", "/api/v1/collections", payload);
          finish(saved);
        } catch (e) {
          notice(error, e.message, "error");
        } finally {
          busy = false;
        }
      });

      dialog.showModal();
      name.focus();
    });
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
    const canvas = el("canvas", {
      "data-slug": item.slug, "data-rig": item.rig || "humanoid", width: 560, height: 420,
    });
    if (item.hasPreview && observer) {
      observer.observe(canvas);
      canvas.__observer = observer;
    }

    const fresh = Date.now() - new Date(item.createdAt).getTime() < WEEK;

    //  Der Autor ist ein Weg, kein Etikett: "mehr von dieser Person" ist die
    //  zweite Frage nach "was ist das". Seit es Profile gibt, fuehrt er
    //  dorthin statt in eine gefilterte Liste - ein Profil beantwortet
    //  dieselbe Frage und dazu die naechste ("wer ist das?").
    const author = el("a", {
      class: "card-author",
      href: profileHref(item),
      "data-tip": item.authorHandle ? "Profile of " + item.author : "All clips by " + item.author,
    }, item.author);

    //  Die zwei Handgriffe liegen auf der KARTE, nicht erst auf der Clip-Seite:
    //  wer durch einen Katalog scrollt, sammelt im Vorbeigehen. Beides sind
    //  Zeichen mit ihrer Zahl - der Text daneben ("used 3×") ist der einzige
    //  Rest, der wirklich nur Auskunft ist.
    const actions = el("div", { class: "card-actions" },
      likeButton(item), saveButton(item));

    //  ZWEI ZIELE AUF EINER KARTE, und HTML erlaubt keinen Link im Link.
    //  Der erste Entwurf loeste das mit einem Klick-Handler auf einem <span> -
    //  der mit der Maus funktioniert und mit der Tastatur nicht: kein Fokus,
    //  kein Enter, fuer einen Screenreader gar kein Ziel.
    //
    //  Also andersherum: die Karte ist ein <article>, der TITEL ist der Link,
    //  und sein `::after` deckt die ganze Karte ab. Damit bleibt die ganze
    //  Flaeche anklickbar, und der Autor daneben ist ein gewoehnlicher Link,
    //  der ueber dieser Flaeche liegt. Beide sind anfassbar, ertastbar und
    //  vorlesbar.
    return el("article", { class: "card" },
      el("div", { class: "card-stage" },
        item.hasPreview ? canvas : el("div", { class: "viewer-empty" }, "No preview"),
        fresh ? el("span", { class: "card-flag" }, "New") : null,
        el("span", { class: "card-duration" }, formatDuration(item.durationSeconds))),
      el("div", { class: "card-body" },
        el("a", {
          class: "card-title",
          href: "/clip.html?p=" + encodeURIComponent(item.slug),
          title: item.title,
        }, item.title),
        el("div", { class: "card-meta" }, author, actions)));
  }

  /** Wohin der Name eines Erstellers fuehrt - Profil, sonst der alte Filter. */
  /**
   * Das Bild einer Person, oder ihr Anfangsbuchstabe im Kreis. Scheitert das
   * Bild (geloeschtes Konto, Discord gerade nicht erreichbar), steht danach
   * der Buchstabe da - nie ein zerbrochenes Bild.
   */
  function avatar(name, url, className = "avatar") {
    const initial = ((name || "?")[0] || "?").toUpperCase();
    if (!url) return el("span", { class: className, "aria-hidden": "true" }, initial);
    const image = el("img", { class: className, src: url, alt: "", loading: "lazy", "data-initial": initial });
    image.addEventListener("error", () => image.replaceWith(el("span", { class: className }, initial)), { once: true });
    return image;
  }

  //  Dasselbe fuer Bilder, die schon im ausgelieferten HTML stehen (Kopf).
  document.querySelectorAll("img.avatar[data-initial]").forEach((image) => {
    const fallBack = () => image.replaceWith(el("span", { class: "avatar" }, image.dataset.initial));
    if (image.complete && image.naturalWidth === 0) fallBack();
    else image.addEventListener("error", fallBack, { once: true });
  });

  function profileHref(item) {
    return item.authorHandle
      ? "/u.html?u=" + encodeURIComponent(item.authorHandle)
      : "/browse.html?author=" + encodeURIComponent(item.author);
  }

  /**
   * Die Sammlungskarte.
   *
   * Sie zeigt, was in der Sammlung steht, nicht was ueber sie geschrieben
   * wurde: der erste Clip laeuft als Deckel, wie auf einer Clip-Karte. Eine
   * Sammlung ohne Bewegung darauf waere eine Zeile mit Rahmen.
   */
  function collectionCard(item, observer) {
    const cover = item.cover
      ? el("canvas", { "data-slug": item.cover.slug, "data-rig": item.cover.rig || "humanoid", width: 560, height: 420 })
      : el("div", { class: "viewer-empty" }, "Empty");

    if (item.cover && observer) observer.observe(cover);

    const author = item.ownerHandle
      ? el("a", { class: "card-author", href: "/u.html?u=" + encodeURIComponent(item.ownerHandle),
          "data-tip": "Profile of " + item.owner }, item.owner)
      : el("span", { class: "card-author" }, item.owner);

    return el("article", { class: "card collection-card" },
      el("div", { class: "card-stage" },
        cover,
        item.visibility === "UNLISTED"
          ? el("span", { class: "card-flag", "data-tip": "Only people with the link can see it" }, "Unlisted")
          : null,
        el("span", { class: "card-duration" },
          item.items === 1 ? "1 clip" : item.items + " clips")),
      el("div", { class: "card-body" },
        el("a", {
          class: "card-title",
          href: "/collection.html?c=" + encodeURIComponent(item.slug),
          title: item.title,
        }, item.title),
        el("div", { class: "card-meta" }, author)));
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
    copyText, param, signInUrl, signInButton, clipCard, collectionCard, previewObserver,
    icon, iconButton, setIconState, popover, closePopover, signInHint, signedIn,
    likeButton, saveButton, collectionDialog, profileHref, releasePreviews,
    toast, toastError, confirmDialog, copyLink, pulse, placeholderCards, avatar,
  };
})();
