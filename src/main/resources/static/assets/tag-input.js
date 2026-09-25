// Das Schlagwortfeld: Chips statt einer Kommaliste, beim Tippen die
// Schlagworte, die es schon gibt, und darunter Vorschlaege zum Clip.
//
// WARUM NICHT MEHR EIN TEXTFELD. "walk, loop, stealth" in eine Zeile zu
// schreiben, hat zwei Folgen, die niemand sieht, bis es zu spaet ist: man
// erfaehrt erst beim Speichern, dass `Walk Cycle` kein Schlagwort ist, und man
// erfindet `walking`, obwohl vierzig Clips `walk` heissen. Das Feld zeigt jetzt
// beim Tippen, was es schon gibt (GET /api/v1/tags), und formt das Getippte
// sofort so um, wie der Server es annimmt.
//
// DIESELBE UMFORMUNG steht in Tags.kt (TagText.normalize) und in der
// Workbench (AWCommunityTagField.Normalize). Aendert sich eine, aendern sich
// alle drei.
//
// Bedienung wie bei Stack Overflow oder DeviantArt: Enter, Komma und
// Leerzeichen machen aus dem Getippten einen Chip; ein Schlagwort aus
// mehreren Woertern schreibt man mit Bindestrich oder waehlt es aus der
// Liste. Rueckschritt im leeren Feld holt den letzten Chip zum Korrigieren
// zurueck. Pfeiltasten gehen durch die Liste, Escape schliesst sie, ohne den
// Dialog zu schliessen.

const AWTags = (() => {
  const { api, el, icon } = AW;

  const MAX_TAGS = 10;
  const MAX_LENGTH = 32;
  const SEPARATORS = /[,;\s]+/;

  /** `"  Walk Cycle! "` -> `walk-cycle`, `"Überschlag"` -> `uberschlag`; null, wenn nichts bleibt. */
  function normalize(raw) {
    if (raw === null || raw === undefined) return null;
    const folded = String(raw).trim().replace(/^#+/, "").replace(/ß/g, "ss")
      .normalize("NFD").replace(/\p{M}+/gu, "").toLowerCase();

    let out = "";
    for (const c of folded) {
      if ((c >= "a" && c <= "z") || (c >= "0" && c <= "9")) out += c;
      else if ((c === "-" || c === "_" || c === "." || /\s/.test(c)) && out && !out.endsWith("-")) out += "-";
    }

    out = out.replace(/^-+|-+$/g, "");
    if (out.length > MAX_LENGTH) out = out.slice(0, MAX_LENGTH).replace(/-+$/, "");
    return /^[a-z0-9][a-z0-9-]*$/.test(out) ? out : null;
  }

  const plural = (n) => n + (n === 1 ? " clip" : " clips");

  let nextId = 0;

  /**
   * Baut ein Feld.
   *
   * @param tags      die Schlagworte, mit denen es anfaengt
   * @param context   liefert `{ title, text, slug }` fuer die Vorschlaege -
   *                  eine Funktion, weil sich der Titel im selben Dialog aendert
   * @returns `{ element, inputId, tags, commit(), refresh(), focus() }`
   */
  function field({ tags = [], max = MAX_TAGS, context = () => ({}), onChange = () => {} } = {}) {
    const id = "tag-field-" + (++nextId);
    const chosen = [];
    for (const tag of tags) {
      const clean = normalize(tag);
      if (clean && !chosen.includes(clean)) chosen.push(clean);
    }

    const chips = el("ul", { class: "tag-chips", role: "list" });
    const entry = el("input", {
      type: "text", id: id + "-input", class: "tag-entry",
      role: "combobox", "aria-autocomplete": "list", "aria-expanded": "false", "aria-controls": id + "-menu",
      "aria-describedby": id + "-hint",
      autocomplete: "off", autocapitalize: "none", spellcheck: "false", enterkeyhint: "enter", maxlength: "64",
    });
    const box = el("div", { class: "tag-box" }, chips, entry);
    const menu = el("div", { class: "tag-menu", id: id + "-menu", role: "listbox", "aria-label": "Tags already in use", hidden: true });
    const room = el("span", { class: "tag-room" });
    const hintText = el("span", {}, "");
    const hint = el("div", { class: "tag-hint", id: id + "-hint" }, hintText, room);
    const suggestRow = el("div", { class: "tag-suggest", hidden: true });
    const live = el("span", { class: "visually-hidden", "aria-live": "polite" });
    const root = el("div", { class: "tag-field" }, el("div", { class: "tag-anchor" }, box, menu), hint, suggestRow, live);

    //  Die Liste gehoert in die oberste Ebene: der Bearbeiten-Dialog ist ein
    //  modales <dialog> und schnitte eine absolut gesetzte Liste an seinem
    //  unteren Rand ab. Wo es die Popover-Schnittstelle nicht gibt, bleibt sie
    //  unter dem Feld haengen - mit dem Schnitt.
    const floating = typeof menu.showPopover === "function";
    if (floating) menu.setAttribute("popover", "manual");

    const HINT = "Enter, comma or space adds a tag";
    let hintTimer = 0;

    let options = [];
    let active = -1;
    let isOpen = false;
    let querySeq = 0;
    let queryTimer = 0;
    const answers = new Map();

    let suggestions = [];
    let suggestSeq = 0;
    let suggestTimer = 0;

    // ── Chips ───────────────────────────────────────────────────────────

    function renderChips() {
      chips.replaceChildren(...chosen.map((tag, i) => el("li", { class: "tag-chip" },
        el("span", { class: "tag-chip-name" }, tag),
        el("button", {
          type: "button", class: "tag-chip-remove", "aria-label": "Remove tag " + tag,
          onpointerdown: (event) => event.preventDefault(),
          onclick: (event) => {
            event.stopPropagation();
            remove(i);
            entry.focus();
          },
        }, icon("close")))));

      const full = chosen.length >= max;
      entry.placeholder = full ? "That's " + max + " - remove one to add another"
        : chosen.length ? "Add another tag" : "Add a tag";
      room.textContent = chosen.length + "/" + max;
      room.classList.toggle("full", full);
      if (!hintTimer) hintText.textContent = full ? "" : HINT;
    }

    function say(message) {
      live.textContent = message;
    }

    /** Eine Meldung statt des Hinweises, fuer einen Augenblick. */
    function flash(message) {
      clearTimeout(hintTimer);
      hint.classList.add("warn");
      hintText.textContent = message;
      say(message);
      hintTimer = setTimeout(() => {
        hintTimer = 0;
        hint.classList.remove("warn");
        renderChips();
      }, 2600);
    }

    function nudge(index) {
      const chip = chips.children[index];
      if (!chip) return;
      chip.classList.remove("nudge");
      void chip.offsetWidth;
      chip.classList.add("nudge");
    }

    function add(raw) {
      const added = [];
      for (const part of String(raw).split(SEPARATORS)) {
        if (!part) continue;
        const tag = normalize(part);
        if (!tag) {
          flash("“" + part + "” has no letters or digits to keep.");
          continue;
        }
        const at = chosen.indexOf(tag);
        if (at >= 0) {
          nudge(at);
          continue;
        }
        if (chosen.length >= max) {
          flash("Up to " + max + " tags.");
          break;
        }
        chosen.push(tag);
        added.push(tag);
      }
      if (added.length) {
        renderChips();
        changed();
        say("Added " + added.join(", "));
      }
    }

    function remove(index) {
      const [tag] = chosen.splice(index, 1);
      renderChips();
      changed();
      say("Removed " + tag);
    }

    function changed() {
      renderSuggestions();
      scheduleSuggestions();
      onChange(chosen.slice());
    }

    /** Was im Feld steht, wird ein Chip - beim Tippen eines Trenners, bei Enter, beim Verlassen. */
    function commit() {
      const text = entry.value;
      entry.value = "";
      closeMenu();
      if (text.trim()) add(text);
    }

    // ── Liste beim Tippen ───────────────────────────────────────────────

    function query() {
      clearTimeout(queryTimer);
      const q = normalize(entry.value);
      if (!q) {
        closeMenu();
        return;
      }
      if (answers.has(q)) {
        show(answers.get(q), q);
        return;
      }
      queryTimer = setTimeout(async () => {
        const seq = ++querySeq;
        try {
          const result = await api("GET", "/api/v1/tags?limit=8&q=" + encodeURIComponent(q));
          answers.set(q, result.tags || []);
          if (seq === querySeq && normalize(entry.value) === q) show(answers.get(q), q);
        } catch {
          //  Ohne Liste tippt man eben weiter - das Feld selbst geht auch so.
        }
      }, 120);
    }

    function marked(tag, q) {
      if (tag.startsWith(q)) return [el("mark", {}, q), tag.slice(q.length)];
      const at = tag.indexOf("-" + q);
      if (at >= 0) return [tag.slice(0, at + 1), el("mark", {}, q), tag.slice(at + 1 + q.length)];
      return [tag];
    }

    function meta(option) {
      const parts = [];
      if (option.count > 0) parts.push(plural(option.count));
      if (option.mine) parts.push("yours");
      return parts.length ? el("span", { class: "tag-option-meta" }, parts.join(" · ")) : null;
    }

    function show(list, q) {
      options = list.filter((option) => !chosen.includes(option.tag));
      active = -1;
      entry.removeAttribute("aria-activedescendant");
      if (!options.length) {
        closeMenu();
        return;
      }
      menu.replaceChildren(...options.map((option, i) => el("div", {
        class: "tag-option", role: "option", id: id + "-option-" + i, "aria-selected": "false",
        onpointerdown: (event) => event.preventDefault(),
        onclick: () => pick(option.tag),
      }, el("span", { class: "tag-option-name" }, marked(option.tag, q)), meta(option))));
      openMenu();
    }

    function pick(tag) {
      entry.value = "";
      closeMenu();
      add(tag);
      entry.focus();
    }

    function move(delta) {
      let next = active + delta;
      if (next < -1) next = options.length - 1;
      if (next >= options.length) next = -1;
      active = next;
      [...menu.children].forEach((row, i) => row.setAttribute("aria-selected", i === active ? "true" : "false"));
      if (active >= 0) {
        const row = menu.children[active];
        entry.setAttribute("aria-activedescendant", row.id);
        row.scrollIntoView({ block: "nearest" });
      } else {
        entry.removeAttribute("aria-activedescendant");
      }
    }

    function place() {
      if (!floating) return;
      const r = box.getBoundingClientRect();
      menu.style.left = r.left + "px";
      menu.style.top = r.bottom + 6 + "px";
      menu.style.width = r.width + "px";
    }

    function openMenu() {
      if (!isOpen) {
        isOpen = true;
        menu.hidden = false;
        if (floating) {
          try { menu.showPopover(); } catch { /* schon offen */ }
        }
        entry.setAttribute("aria-expanded", "true");
        window.addEventListener("scroll", place, true);
        window.addEventListener("resize", place);
      }
      place();
    }

    function closeMenu() {
      clearTimeout(queryTimer);
      querySeq++;
      if (!isOpen) return;
      isOpen = false;
      active = -1;
      entry.removeAttribute("aria-activedescendant");
      entry.setAttribute("aria-expanded", "false");
      if (floating) {
        try { menu.hidePopover(); } catch { /* schon zu */ }
      }
      menu.hidden = true;
      window.removeEventListener("scroll", place, true);
      window.removeEventListener("resize", place);
    }

    // ── Vorschlaege ─────────────────────────────────────────────────────

    function scheduleSuggestions(delay = 350) {
      clearTimeout(suggestTimer);
      suggestTimer = setTimeout(loadSuggestions, delay);
    }

    async function loadSuggestions() {
      const seq = ++suggestSeq;
      const ctx = context() || {};
      const params = new URLSearchParams({ limit: "8" });
      if (ctx.title) params.set("title", ctx.title);
      if (ctx.text) params.set("text", String(ctx.text).slice(0, 400));
      if (ctx.slug) params.set("slug", ctx.slug);
      if (chosen.length) params.set("tags", chosen.join(","));
      try {
        const result = await api("GET", "/api/v1/tags/suggest?" + params);
        if (seq !== suggestSeq) return;
        suggestions = result.suggestions || [];
      } catch {
        if (seq !== suggestSeq) return;
        suggestions = [];
      }
      renderSuggestions();
    }

    function renderSuggestions() {
      const list = suggestions.filter((s) => !chosen.includes(s.tag));
      suggestRow.hidden = chosen.length >= max || list.length === 0;
      if (suggestRow.hidden) return;
      suggestRow.replaceChildren(
        el("span", { class: "tag-suggest-label" }, "Suggested"),
        ...list.map((s) => el("button", {
          type: "button", class: "tag-suggestion",
          "data-tip": s.count > 0 ? s.reason + " · " + plural(s.count) : s.reason,
          "aria-label": "Add tag " + s.tag + " (" + s.reason + ")",
          //  Der Fokus bleibt im Feld: sonst machte das Verlassen aus dem
          //  halb Getippten einen Chip, bevor der Vorschlag ankommt.
          onpointerdown: (event) => event.preventDefault(),
          onclick: () => add(s.tag),
        }, icon("plus"), s.tag)));
    }

    // ── Tastatur ────────────────────────────────────────────────────────

    entry.addEventListener("keydown", (event) => {
      if (event.isComposing) return;
      switch (event.key) {
        case "ArrowDown":
          if (!options.length) return;
          event.preventDefault();
          if (!isOpen) openMenu();
          move(1);
          return;
        case "ArrowUp":
          if (!isOpen) return;
          event.preventDefault();
          move(-1);
          return;
        case "Enter":
          //  Nie das Formular abschicken: wer im Schlagwortfeld Enter drueckt,
          //  meint den Chip, nicht "Speichern".
          event.preventDefault();
          if (isOpen && active >= 0) pick(options[active].tag);
          else commit();
          return;
        case "Tab":
          if (!entry.value.trim()) return;
          event.preventDefault();
          if (isOpen && active >= 0) pick(options[active].tag);
          else commit();
          return;
        case ",":
        case ";":
        case " ":
          event.preventDefault();
          commit();
          return;
        case "Backspace":
          if (entry.value !== "" || !chosen.length) return;
          event.preventDefault();
          entry.value = chosen[chosen.length - 1];
          remove(chosen.length - 1);
          query();
          return;
        case "Escape":
          //  Die Liste zuerst - der Dialog erst beim zweiten Mal.
          if (!isOpen) return;
          event.preventDefault();
          event.stopPropagation();
          closeMenu();
          return;
      }
    });

    //  Mobile Tastaturen melden Komma und Leerzeichen oft nicht als Taste,
    //  nur als geaenderten Text.
    entry.addEventListener("input", () => {
      if (SEPARATORS.test(entry.value)) {
        const parts = entry.value.split(SEPARATORS);
        const rest = parts.pop();
        entry.value = rest;
        add(parts.join(" "));
      }
      query();
    });

    entry.addEventListener("paste", (event) => {
      const text = event.clipboardData && event.clipboardData.getData("text");
      if (!text || !SEPARATORS.test(text.trim())) return;
      event.preventDefault();
      add(entry.value + text);
      entry.value = "";
      closeMenu();
    });

    entry.addEventListener("blur", () => {
      commit();
    });

    box.addEventListener("click", (event) => {
      if (event.target === box || event.target === chips) entry.focus();
    });

    renderChips();
    scheduleSuggestions(0);

    return {
      element: root,
      inputId: entry.id,
      get tags() { return chosen.slice(); },
      /** Was noch getippt im Feld steht, mitnehmen - vor dem Speichern aufrufen. */
      commit() {
        commit();
        return chosen.slice();
      },
      /** Titel oder Beschreibung haben sich geaendert. */
      refresh: () => scheduleSuggestions(),
      focus: () => entry.focus(),
    };
  }

  return { normalize, field, MAX_TAGS };
})();
