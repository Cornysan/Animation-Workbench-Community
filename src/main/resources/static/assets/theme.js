/**
 * Hell oder dunkel - entschieden, bevor das erste Pixel steht.
 *
 * ── Warum eine eigene Datei im Kopf ──────────────────────────────────────
 *
 * Diese Entscheidung muss VOR dem ersten Malen fallen. Faellt sie spaeter,
 * sieht jeder mit heller Einstellung bei jedem Seitenwechsel einen dunklen
 * Blitz - und die Seiten wechseln hier oft, das Portal besteht aus echten
 * Dokumenten und nicht aus einer App mit einem einzigen Laden.
 *
 * Ueblich waere ein Inline-Skript im `<head>`. Das geht hier nicht: die
 * Richtlinie traegt `script-src 'self'` ohne Nonce (SecurityConfig), und ein
 * Inline-Skript waere schlicht blockiert. Eine eigene Datei, SYNCHRON im Kopf
 * geladen - kein `defer`, kein `async` -, tut dasselbe und bleibt innerhalb
 * der Richtlinie. Sie kostet eine Anfrage, die mit dem Stylesheet zusammen
 * laeuft und aus dem Cache kommt, sobald jemand die zweite Seite oeffnet.
 *
 * ── Wer entscheidet ──────────────────────────────────────────────────────
 *
 * Wer nie umgeschaltet hat, bekommt, was das Geraet sagt - und folgt ihm
 * weiter, auch wenn es sich waehrend des Lesens umstellt (manche Systeme
 * wechseln zur Daemmerung). Wer EINMAL umgeschaltet hat, hat damit gesagt,
 * dass er es anders will; ab dann gilt seine Wahl, auf diesem Geraet, bis er
 * sie wieder aendert. Das System ueberstimmt sie nicht mehr.
 *
 * Es gibt bewusst keinen dritten Zustand ("dem System folgen") im Knopf. Ein
 * Knopf mit drei Stellungen erklaert sich nicht von selbst, und wer wirklich
 * zurueck zum System will, loescht die Seitendaten - oder wir bauen es ein,
 * wenn es jemand vermisst.
 *
 * ── Und der Rest der Seite ───────────────────────────────────────────────
 *
 * Alles Weitere steht im Stylesheet: `:root` ist dunkel, `[data-theme=light]`
 * ist hell, und die Buehnen (`--on-stage-*`) bleiben in beiden Faellen
 * dunkel. Hier wird nur das Attribut gesetzt.
 */
(function () {
  var KEY = 'aw-theme';
  var root = document.documentElement;
  var media = window.matchMedia('(prefers-color-scheme: light)');

  //  Die Farbe der Browserleiste auf dem Telefon - dieselben zwei Werte wie
  //  `--bg` im Stylesheet. Steht ein drittes Mal nirgends.
  var BAR = { light: '#f7f6fa', dark: '#0e0d12' };

  /**
   * Die gespeicherte Wahl, oder `null`.
   *
   * In Kapseln, weil `localStorage` wirft statt `null` zu liefern, sobald
   * Speicher gesperrt ist (privates Fenster, Drittanbieter-Kontext). Ohne
   * Gedaechtnis soll die Seite hell oder dunkel sein - nicht kaputt.
   */
  function stored() {
    try {
      var value = localStorage.getItem(KEY);
      return value === 'light' || value === 'dark' ? value : null;
    } catch (e) {
      return null;
    }
  }

  function apply(theme) {
    root.dataset.theme = theme;

    var bar = document.querySelector('meta[name="theme-color"]');
    if (bar) bar.setAttribute('content', BAR[theme]);

    var toggles = document.querySelectorAll('.theme-toggle');
    for (var i = 0; i < toggles.length; i++) {
      //  Der Knopf sagt, WOHIN er fuehrt, nicht wo man steht - das ist die
      //  Frage, die jemand hat, wenn er ihn ansieht.
      var label = theme === 'light' ? 'Switch to dark theme' : 'Switch to light theme';
      toggles[i].setAttribute('aria-label', label);
      toggles[i].setAttribute('title', label);
      toggles[i].hidden = false;
    }
  }

  apply(stored() || (media.matches ? 'light' : 'dark'));

  media.addEventListener('change', function () {
    if (!stored()) apply(media.matches ? 'light' : 'dark');
  });

  //  Der Knopf steht im Koerper und existiert hier oben noch nicht.
  document.addEventListener('DOMContentLoaded', function () {
    apply(root.dataset.theme);

    document.addEventListener('click', function (event) {
      var button = event.target.closest && event.target.closest('.theme-toggle');
      if (!button) return;

      var next = root.dataset.theme === 'light' ? 'dark' : 'light';
      try {
        localStorage.setItem(KEY, next);
      } catch (e) {
        //  Kein Gedaechtnis - dann gilt die Wahl eben nur fuer diese Seite.
      }
      apply(next);
    });
  });
})();
