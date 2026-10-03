/**
 * Die Seite einer Animation: Kopf, Aktionen, Zahlen, Nachbarschaft.
 *
 * Das Gespraech darunter gehoert `clip-comments.js`, die Buehne
 * `clip-viewer.js` - drei Dateien, drei Zustaendigkeiten, kein gemeinsamer
 * Zustand ausser dem Slug in der Adresse.
 */
//  Wo diese Datei liegt - fuer das Nachladen von og-card.js. Spaeter, im
//  asynchronen Teil, gibt es `document.currentScript` nicht mehr.
const CLIP_SCRIPT = document.currentScript ? document.currentScript.src : location.href;

(async () => {
  const {
    api, ensureCsrf, me, el, notice, formatDuration, formatRelative, copyText, param,
    iconButton, likeButton, saveButton, profileHref, toast, toastError, confirmDialog, copyLink,
  } = AW;

  const slug = param("p");
  const state = document.getElementById("state");
  const actionState = document.getElementById("action-state");

  if (!slug) {
    notice(state, "No clip selected.", "error");
    return;
  }

  //  Was nicht vom Clip abhaengt, laeuft gleich mit los. Vorher stand jede
  //  Abfrage hinter der vorigen - Clip, dann Lizenzen, dann Konto, dann
  //  Nachbarschaft -, und die Seite wartete viermal nacheinander auf das Netz.
  const userRequest = me().catch(() => null);

  let clip;
  try {
    clip = await api("GET", "/api/v1/packages/" + encodeURIComponent(slug));
  } catch (e) {
    notice(state, e.status === 404 ? "This clip is not available. It may be under review or removed." : e.message, "error");
    return;
  }

  document.getElementById("clip").classList.remove("hidden");
  //  Von hier aus zu allem anderen derselben Person. Ohne diesen Weg ist jeder
  //  Clip eine Insel, und niemand baut sich einen Namen auf. Seit es Profile
  //  gibt, fuehrt er dorthin: das beantwortet dieselbe Frage und die naechste
  //  dazu.
  document.getElementById("author").replaceChildren(el("a", {
    href: profileHref(clip),
    "data-tip": clip.authorHandle ? "Profile of " + clip.author : "All clips by " + clip.author,
  }, clip.author));
  document.getElementById("author-initial").replaceWith(AW.avatar(clip.author, clip.authorAvatar));

  //  AUFRUFE UND WANN, WIE BEI YOUTUBE: "1,234 views  2 weeks ago" oben im
  //  Kasten mit der Beschreibung, dahinter die Schlagworte mit # (showClip).
  //  Jedes Oeffnen zaehlt (catalog/ClipCounts.kt), also auch dieses: die Zahl
  //  kam vor der Meldung unten, deshalb eins dazu. Ohne das hinkte sie einen
  //  Aufruf hinterher, und wer als Erster kam, las keinen. Dieselbe Zeile
  //  schreibt der Server ins HTML (PageController, `clipPage`), ohne diesen.
  const seen = clip.views + 1;
  const views = seen === 1 ? "1 view" : seen.toLocaleString("en") + " views";
  document.getElementById("clip-when").textContent = views + "  " + formatRelative(clip.createdAt);

  //  DOWNLOADS ALS ZEICHEN MIT ZAHL, vorn in der Aktionsleiste - wie Herz und
  //  Stern daneben, nur ohne Knopf: ablesen, nicht anfassen. Beide Wege
  //  zusammen (.awclip und FBX/GLB), ohne Tooltip: wie sie sich aufteilen,
  //  will beim Stoebern niemand wissen (Pablo).
  if (clip.downloads > 0) {
    const label = clip.downloads === 1 ? "1 download" : clip.downloads.toLocaleString("en") + " downloads";
    document.getElementById("clip-actions").prepend(el("div", { class: "clip-stats" },
      el("span", { class: "clip-stat", role: "img", "aria-label": label },
        AW.icon("download"), el("b", {}, clip.downloads.toLocaleString("en")))));
  }

  //  DAS VORSCHAUBILD FUER DISCORD & CO. Fehlt es noch, rendert es die Seite
  //  des Besitzers - still, wenn die Seite steht, mit derselben Buehne
  //  (og-card.js). Hochladen darf es nur er (oder ein Admin), deshalb
  //  passiert es hier und nicht bei jedem Besucher. Scheitert es, bleibt das
  //  Bild der Seite stehen; niemand muss davon erfahren.
  if (clip.isOwner && clip.license === "CC0-1.0" && clip.rig === "humanoid" && !clip.hasCard && clip.hasPreview
      && clip.status === "PUBLISHED") {
    setTimeout(async () => {
      try {
        const { refreshClipCard } = await import(new URL("../og-card.js", CLIP_SCRIPT).href);
        await refreshClipCard(slug, null, { figure: clip.figure, look: clip.look });
      } catch (error) {
        console.warn("[clip] preview image not rendered", error);
      }
    }, 2500);
  }

  //  Der Aufruf - jedes Mal, auch neu geladen und am eigenen Clip; nur Bots
  //  laesst der Server weg. Scheitert er, merkt es niemand - eine fehlende
  //  Eins ist kein Fehler, den jemand beheben koennte.
  ensureCsrf()
    .then(() => api("POST", "/api/v1/packages/" + encodeURIComponent(slug) + "/viewed"))
    .catch(() => {});

  //  Die Nachbarschaft braucht nur die Schlagworte - sie wartet nicht auf
  //  das Konto.
  showRelated();

  //  Alles, was der Besitzer im Bearbeiten-Dialog aendern kann, steht in
  //  EINER Funktion: sie laeuft beim Laden und nach dem Speichern. Zwei
  //  Stellen, die dieselben Felder fuellen, liefen sonst auseinander.
  function showClip() {
    //  Derselbe Titel, den der Server schreibt (web/Seo.kt, clipTitle) -
    //  Suchmaschinen lesen ihn NACH diesem Skript, und ein anderer hier
    //  machte die Zeile im Suchergebnis wieder zu "Name - Portal".
    document.title = clip.title +
      (/animation/i.test(clip.title) ? " – Free Download (FBX, GLB, Unity)" : " – Free Animation (FBX, GLB, Unity)") +
      " | Playmations";
    document.getElementById("title").textContent = clip.title;
    document.getElementById("description").textContent = clip.description || "No description.";
    //  Ein Starter-Clip hat keinen Ersteller in der Community - er kommt aus
    //  einer oeffentlichen CC0-Sammlung, und genau das steht hier, mit Link.
    //  Der Name "Starter Clips" allein saehe aus wie ein Mensch.
    if (clip.source) {
      const source = document.getElementById("clip-source");
      source.hidden = false;
      source.replaceChildren("Original: ",
        clip.source.url
          ? el("a", { href: clip.source.url, rel: "noopener", target: "_blank" }, clip.source.credit)
          : clip.source.credit,
        " · added by the operator, not made by a community member");
    }
    //  Ein Clip aus einem Pack hat Geschwister - und wer ueber die Suche hier
    //  gelandet ist, weiss das sonst nicht.
    const packLine = document.getElementById("clip-pack");
    packLine.hidden = !clip.pack;
    if (clip.pack) {
      packLine.replaceChildren(AW.icon("pack"), "Part of the pack ",
        el("a", { href: "/pack.html?k=" + encodeURIComponent(clip.pack.slug) }, clip.pack.title));
    }
    //  Die Schlagworte mit #, in der Zeile ueber der Beschreibung - wie bei
    //  YouTube. Vorher standen sie als Pillen in einer eigenen Reihe.
    const meta = document.getElementById("clip-meta");
    meta.querySelectorAll(".hashtag").forEach((node) => node.remove());
    meta.append(...clip.tags.map((tag) => el("a", { class: "hashtag", href: "/?tag=" + encodeURIComponent(tag) }, "#" + tag)));
    showFacts();
  }

  //  "Used in projects" und "Likes" stehen nicht mehr hier: die eine Zahl steht
  //  in der Zeile unter dem Titel, die andere IM Herz-Knopf. Eine Zahl, die man
  //  anfassen kann, gehoert nicht in eine Tabelle daneben.
  function showFacts() {
    document.getElementById("facts").replaceChildren(
      el("dt", {}, "Duration"), el("dd", {}, formatDuration(clip.durationSeconds)),
      el("dt", {}, "Frame rate"), el("dd", {}, Math.round(clip.frameRate) + " fps"),
      //  Ein Clip aus einer hochgeladenen Datei hat keine Kurven - die backt
      //  erst die Workbench beim Import (Format 2). "0" saehe kaputt aus.
      ...(clip.curveCount > 0 ? [el("dt", {}, "Curves"), el("dd", {}, clip.curveCount)] : []),
      el("dt", {}, "Rig"), el("dd", {}, clip.rig === "generic" ? "Generic" : "Humanoid"),
      el("dt", {}, "Version"), el("dd", {}, clip.version),
      //  Die Lizenz steht nicht mehr auf der Seite: jeder oeffentliche Clip hat
      //  dieselbe, und die erklaert "Licenses" unten im Fuss. Ein Kasten mit
      //  demselben Satz ueber jedem Download-Knopf war der lauteste Teil der
      //  Spalte. Was bleibt, ist der eine Fall, der nicht fuer alle gilt: ein
      //  privater Clip, den nur sein Besitzer sieht.
      ...(clip.license === "ARR" ? [el("dt", {}, "Visibility"), el("dd", {}, "Only you")] : []),
      ...(clip.status ? [el("dt", {}, "Status"), el("dd", {}, el("span", { class: "status " + clip.status }, clip.status))] : []));
  }

  showClip();

  //  Die Vorschau gehoert `pages/clip-viewer.js` - einem Modul, weil die Buehne
  //  three.js laedt. Es holt seine Daten selbst und ist der einzige Schreiber
  //  auf `#viewer`; zwei Stellen, die denselben Kasten fuellen, ueberholen
  //  einander irgendwann.

  const user = await userRequest;

  // ── Herz, Stern, Link, Meldung ───────────────────────────────────────
  //  Was man an einer fremden Animation tun kann, steht nebeneinander unter
  //  dem Titel - und zwar als ZEICHEN mit Zahl, dieselben wie auf der Karte
  //  im Katalog: Herz heisst "gut", Stern heisst "in eine Sammlung". Wer sie
  //  einmal auf einer Karte benutzt hat, muss sie hier nicht neu lernen.
  //  Jedes traegt seinen Satz als Tooltip, damit kein Zeichen geraten werden
  //  muss.
  const bar = document.getElementById("clip-actions");

  bar.append(likeButton(clip));
  bar.append(saveButton(clip));

  //  Ein Portal, das von Links lebt, braucht einen Knopf dafuer. Die Adresse
  //  aus der Leiste zu fischen ist eine Huerde, die niemand nehmen muss.
  const share = iconButton({
    name: "link",
    tip: "Copy a link to this clip",
    onClick: () => copyLink(location.origin + "/clip.html?p=" + encodeURIComponent(slug)),
  });
  bar.append(share);

  //  Geredet wird ueber einen Clip im Thread seines Schaufenster-Posts -
  //  falls sein Ersteller ihn beim Teilen dorthin geschickt hat.
  if (clip.discordUrl) bar.append(AW.discordButton(clip.discordUrl, clip.isOwner));

  if (clip.isOwner) {
    //  Was nicht Bewegung ist, laesst sich hier aendern. Die Bewegung selbst
    //  kommt als neue Fassung aus der Workbench.
    const edit = iconButton({
      name: "edit",
      //  Kurz: der unsichtbare Zettel liegt mit voller Breite im Layout, und
      //  die alte Aufzaehlung machte die Seite auf dem Telefon 443 px breit.
      //  Was sich aendern laesst, zeigt der Dialog.
      tip: "Edit this clip",
      onClick: async () => {
        const before = clip;
        const saved = await editDialog(clip);
        if (!saved) return;
        clip = saved;
        showClip();
        toast("Saved.", { kind: "ok" });
        if (saved.figure !== before.figure || saved.look !== before.look) redressed();
      },
    });
    bar.append(edit);

    //  In einen Pack - von dort aus, wo man den Clip vor sich hat. Bis
    //  2026-10-04 ging das nur ueber "New pack" im eigenen Profil, und das
    //  fand niemand. Ein privater Clip darf nicht hinein (ein Pack steht auf
    //  der Wand), einer in einem Pack hat oben schon seine Zeile.
    if (!clip.pack && clip.license === "CC0-1.0" && clip.status === "PUBLISHED" && clip.rig === "humanoid") {
      const toPack = iconButton({
        name: "pack",
        tip: "Add to a pack",
        onClick: async () => {
          const pack = await choosePack();
          if (!pack) return;
          clip = { ...clip, pack: { slug: pack.slug, title: pack.title } };
          showClip();
          toPack.remove();
        },
      });
      bar.append(toPack);
    }

    //  Aus der Workbench ("Edit on the portal") kommt man mit `edit=1` -
    //  dann steht der Dialog gleich offen. Der Parameter geht aus der
    //  Adresse, damit ein Neuladen ihn nicht wieder aufmacht.
    if (param("edit")) {
      const url = new URL(location.href);
      url.searchParams.delete("edit");
      history.replaceState(null, "", url);
      edit.click();
    }

    const withdraw = iconButton({
      name: "trash",
      tip: "Withdraw this clip",
      className: "danger",
      onClick: async () => {
        const sure = await confirmDialog({
          title: "Withdraw this clip?",
          body: "“" + clip.title + "” disappears from the community. Copies other people already took stay theirs.",
          confirm: "Withdraw",
          danger: true,
        });
        if (!sure) return;
        try {
          await ensureCsrf();
          await api("DELETE", "/api/v1/packages/" + encodeURIComponent(slug));
          location.href = "/me.html";
        } catch (e) {
          toastError(e);
        }
      },
    });
    bar.append(withdraw);
  } else if (user) {
    const dialog = document.getElementById("report-dialog");
    const form = document.getElementById("report-form");
    const report = iconButton({
      name: "flag",
      tip: "Report this clip",
      className: "danger",
      onClick: () => dialog.showModal(),
    });

    //  Am Absenden, nicht am `close`-Ereignis: das feuert nicht in jedem
    //  Browser (siehe collectionDialog in app.js), und dann ging die Meldung
    //  still verloren. Beide Knoepfe senden das Formular; welcher es war,
    //  sagt `submitter`.
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      const send = event.submitter && event.submitter.value === "send";
      dialog.close();
      if (!send) return;
      try {
        await ensureCsrf();
        await api("POST", "/api/v1/packages/" + encodeURIComponent(slug) + "/reports", {
          category: form.category.value,
          message: form.message.value,
        });
        toast("Thank you. The clip is hidden until a moderator has reviewed it.", { kind: "ok", duration: 6000 });
        report.disabled = true;
      } catch (e) {
        toastError(e);
      }
    });
    bar.append(report);
  }

  // ── Mitnehmen ────────────────────────────────────────────────────────
  //  Herunterladen - alle Formate, die .awclip eingeschlossen - gehoert
  //  `clip-download.js`: ein Knopf, dahinter die Wahl.

  // ── Nachbarschaft ────────────────────────────────────────────────────
  //  Wer einen Laufzyklus ansieht, will meistens Laufzyklen sehen. Das erste
  //  Schlagwort traegt den Clip am besten - es steht beim Hochladen vorn, weil
  //  der Hochladende es zuerst eingetippt hat.
  //
  //  Das ist bewusst KEIN eigener Endpunkt: die Suche nach einem Schlagwort
  //  kann der Katalog schon, und "aehnlich" heisst hier genau das.
  async function showRelated() {
    const related = document.getElementById("related");
    const relatedList = document.getElementById("related-list");
    const relatedTag = clip.tags[0];

    try {
      const fetchSome = async (query) => {
        const page = await api("GET", "/api/v1/packages?" + query + "&size=7&previewClips=true");
        return page.items.filter((item) => item.slug !== slug).slice(0, 6);
      };

      //  Beim Schlagwort anfangen, aber nicht dabei stehenbleiben: ein Clip mit
      //  einem seltenen Schlagwort haette sonst eine leere Spalte, und gerade er
      //  braucht den Weg weiter.
      let others = relatedTag ? await fetchSome("tag=" + encodeURIComponent(relatedTag) + "&sort=popular") : [];
      let byTag = others.length > 0;
      if (!byTag) others = await fetchSome("sort=popular");

      if (others.length) {
        document.getElementById("related-title").textContent =
          byTag ? "More “" + relatedTag + "”" : "Popular right now";
        related.hidden = false;
        relatedList.replaceChildren(...others.map((item) => el("a", {
          class: "related-item",
          href: "/clip.html?p=" + encodeURIComponent(item.slug),
        },
          el("span", { class: "related-title" }, item.title),
          el("span", { class: "related-meta" },
            item.author, " · ", formatDuration(item.durationSeconds)))));
      }
    } catch { /* Nachbarschaft ist Zugabe. */ }
  }

  /**
   * Figur oder Look haben gewechselt: die Buehne zieht um (clip-viewer.js),
   * und das Vorschaubild fuer Discord & Co. entsteht neu - der Server hat das
   * alte beim Speichern verworfen. Derselbe Weg wie beim ersten Laden, oben.
   */
  function redressed() {
    document.dispatchEvent(new CustomEvent("aw:dress", { detail: { figure: clip.figure, look: clip.look } }));
    if (clip.license !== "CC0-1.0" || clip.rig !== "humanoid" || !clip.hasPreview || clip.status !== "PUBLISHED") return;
    import(new URL("../og-card.js", CLIP_SCRIPT).href)
      .then(({ refreshClipCard }) => refreshClipCard(slug, null, { figure: clip.figure, look: clip.look }))
      .catch((error) => console.warn("[clip] preview image not rendered", error));
  }

  // ── In einen Pack ────────────────────────────────────────────────────
  //  Gibt es schon eigene Packs, fragt ein kleiner Dialog, in welchen - oder
  //  ob ein neuer entsteht. Ohne eigene Packs geht es gleich zum neuen, mit
  //  diesem Clip schon angehakt. Antwort: der Pack, oder null.
  async function choosePack() {
    let mine;
    try {
      mine = await api("GET", "/api/v1/me/packs");
    } catch (e) {
      toastError(e);
      return null;
    }

    const NEW = "";
    const target = mine.length ? await packChoice(mine, NEW) : NEW;
    if (target === null) return null;

    if (target === NEW) {
      let candidates;
      try {
        candidates = await AW.packCandidates();
      } catch (e) {
        toastError(e);
        return null;
      }
      const made = await AW.packDialog({ candidates, preselect: [slug] });
      if (made) toast("Pack created", { kind: "ok" });
      return made;
    }

    try {
      await ensureCsrf();
      const saved = await api("POST", "/api/v1/packs/" + encodeURIComponent(target) + "/clips", { clips: [slug] });
      toast("Added to “" + saved.title + "”", { kind: "ok" });
      return saved;
    } catch (e) {
      toastError(e);
      return null;
    }
  }

  /** Neuer Pack oder einer der eigenen? Antwort: der Slug, `fresh` fuer neu, null fuer Abbrechen. */
  function packChoice(mine, fresh) {
    return new Promise((resolve) => {
      const option = (value, label, sub, checked) => el("label", {},
        el("input", { type: "radio", name: "pack", value, checked }),
        el("span", {}, el("strong", {}, label), sub ? " - " + sub : ""));

      const form = el("form", { method: "dialog" },
        el("h2", {}, "Add to a pack"),
        el("fieldset", { class: "choices" },
          option(fresh, "A new pack", "with this clip and others you pick", true),
          ...mine.map((pack) => option(pack.slug, pack.title, pack.clips === 1 ? "1 clip" : pack.clips + " clips", false))),
        el("div", { class: "dialog-actions" },
          el("button", { value: "cancel", type: "button", class: "ghost" }, "Cancel"),
          el("button", { value: "next", class: "primary" }, "Continue")));

      const dialog = el("dialog", { class: "sheet" }, form);
      document.body.append(dialog);

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
      dialog.addEventListener("cancel", (event) => {
        event.preventDefault();
        finish(null);
      });
      form.addEventListener("submit", (event) => {
        event.preventDefault();
        //  Mindestens zwei Knoepfe (neu + ein eigener Pack): `pack` ist eine
        //  RadioNodeList, ihr `value` der angehakte.
        finish(form.elements.pack.value);
      });

      dialog.showModal();
      form.querySelector("button.primary").focus();
    });
  }

  // ── Bearbeiten ───────────────────────────────────────────────────────
  //  Titel, Beschreibung, Schlagworte, Figur und wer ihn sehen darf. Gebaut wie der
  //  Dialog fuer Sammlungen (collectionDialog in app.js), und aus demselben
  //  Grund schliesst und antwortet EINE Stelle - das `close`-Ereignis feuert
  //  nicht in jedem Browser.
  //
  //  Der Satz zum Bestaetigen erscheint nur beim Wechsel von privat auf
  //  oeffentlich: das ist ein neues Teilen, und der Server verlangt dafuer
  //  dieselbe Erklaerung im Wortlaut wie beim Hochladen. /api/v1/status nennt
  //  sie - der Satz hier ist nur der Platzhalter, bis die Antwort da ist.
  function editDialog(current) {
    const PUBLIC = "CC0-1.0";
    const PRIVATE = "ARR";
    const wasPublic = current.license === PUBLIC;

    return new Promise((resolve) => {
      const title = el("input", { type: "text", name: "title", maxlength: "80", required: true, value: current.title });
      const description = el("textarea", { name: "description", maxlength: "2000", rows: "5" }, current.description || "");
      const counter = el("span", { class: "faint small counter" }, description.value.length + "/2000");
      description.addEventListener("input", () => { counter.textContent = description.value.length + "/2000"; });

      //  Chips statt Kommaliste (tag-input.js). Die Vorschlaege lesen Titel
      //  und Beschreibung aus DIESEM Dialog, nicht vom gespeicherten Clip -
      //  wer den Titel aendert, bekommt Vorschlaege zum neuen.
      const tags = AWTags.field({
        tags: current.tags,
        context: () => ({ title: title.value, text: description.value, slug: current.slug }),
      });
      let retitled = 0;
      const refreshTags = () => {
        clearTimeout(retitled);
        retitled = setTimeout(tags.refresh, 500);
      };
      title.addEventListener("input", refreshTags);
      description.addEventListener("input", refreshTags);

      //  Figur und Look (app.js, lookPicker). Ein generischer Clip laeuft
      //  auf seinem eigenen Skelett, nicht auf dem Mannequin - fuer ihn gibt
      //  es nichts zu waehlen.
      const looks = current.rig === "humanoid" ? AW.lookPicker({ figure: current.figure, look: current.look }) : null;

      const publicChoice = el("input", { type: "radio", name: "visibility", value: PUBLIC, checked: wasPublic });
      const privateChoice = el("input", { type: "radio", name: "visibility", value: PRIVATE, checked: !wasPublic });

      let declaration = null;
      const declared = el("input", { type: "checkbox" });
      const declarationLine = el("span", {}, "I created this animation myself and have the right to share it here.");
      api("GET", "/api/v1/status").then((status) => {
        declaration = status;
        declarationLine.textContent = status.declarationText;
      }).catch(() => {});

      //  Was der Wechsel bedeutet, steht erst da, wenn jemand wechselt.
      const toPublic = el("div", { hidden: true },
        el("p", { class: "muted small" },
          "Copies people take stay theirs, even if you make it private again."),
        el("label", { class: "check" }, declared, declarationLine));
      const toPrivate = el("p", { class: "muted small", hidden: true },
        "Copies people already took stay theirs.");
      const sync = () => {
        toPublic.hidden = wasPublic || !publicChoice.checked;
        toPrivate.hidden = !wasPublic || !privateChoice.checked;
      };
      publicChoice.addEventListener("change", sync);
      privateChoice.addEventListener("change", sync);

      const error = el("div", {});

      const form = el("form", { method: "dialog" },
        el("h2", {}, "Edit clip"),
        el("label", { class: "field" }, el("span", {}, "Title"), title),
        el("label", { class: "field" },
          el("span", {}, "Description ", el("span", { class: "faint small" }, "optional"), counter),
          description),
        //  Kein <label> um das Feld: es enthaelt Knoepfe, und ein Klick auf
        //  die Beschriftung loeste sonst den ersten davon aus - den, der den
        //  ersten Chip entfernt.
        el("div", { class: "field" },
          el("label", { class: "field-label", for: tags.inputId },
            "Tags ", el("span", { class: "faint small" }, "up to " + AWTags.MAX_TAGS)),
          tags.element),
        looks ? el("div", { class: "field" }, el("span", { class: "field-label" }, "Figure"), looks.element) : null,
        el("fieldset", { class: "choices" },
          el("legend", {}, "Who can see it"),
          el("label", {}, publicChoice,
            el("span", {}, el("strong", {}, "Public"), " - in the community, for everyone to use")),
          el("label", {}, privateChoice,
            el("span", {}, el("strong", {}, "Private"), " - only you"))),
        toPublic,
        toPrivate,
        error,
        el("div", { class: "dialog-actions" },
          el("button", { value: "cancel", type: "button", class: "ghost" }, "Cancel"),
          el("button", { value: "save", class: "primary" }, "Save")));

      const dialog = el("dialog", { class: "sheet" }, form);
      document.body.append(dialog);

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
      dialog.addEventListener("cancel", (event) => {
        event.preventDefault();
        finish(null);
      });

      let busy = false;
      form.addEventListener("submit", async (event) => {
        event.preventDefault();
        if (busy) return;

        const goingPublic = !wasPublic && publicChoice.checked;
        if (goingPublic && !declared.checked) {
          notice(error, "Confirm that you made this animation to share it publicly.", "error");
          return;
        }

        busy = true;
        try {
          await ensureCsrf();
          finish(await api("PATCH", "/api/v1/packages/" + encodeURIComponent(current.slug), {
            title: title.value,
            description: description.value,
            //  Was noch getippt im Feld steht, zaehlt mit.
            tags: tags.commit(),
            license: publicChoice.checked ? PUBLIC : PRIVATE,
            declarationAccepted: goingPublic && declared.checked,
            declarationText: declaration ? declaration.declarationText : null,
            declarationVersion: declaration ? declaration.declarationVersion : null,
            //  Ohne Auswahl null - dann bleibt, was der Clip traegt.
            figure: looks ? looks.value().figure : null,
            look: looks ? looks.value().look : null,
          }));
        } catch (e) {
          notice(error, e.message, "error");
        } finally {
          busy = false;
        }
      });

      dialog.showModal();
      title.focus();
    });
  }
})();
