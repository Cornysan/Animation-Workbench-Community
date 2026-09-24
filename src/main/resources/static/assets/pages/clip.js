/**
 * Die Seite einer Animation: Kopf, Aktionen, Zahlen, Nachbarschaft.
 *
 * Das Gespraech darunter gehoert `clip-comments.js`, die Buehne
 * `clip-viewer.js` - drei Dateien, drei Zustaendigkeiten, kein gemeinsamer
 * Zustand ausser dem Slug in der Adresse.
 */
(async () => {
  const {
    api, ensureCsrf, me, el, notice, formatDuration, formatDate, copyText, param,
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

  //  Eine Zeile statt vier Tabellenzeilen: was hier steht, liest man im
  //  Vorbeigehen. Nullen bleiben weg - ein frischer Clip ist nicht unbeliebt.
  const sub = ["Shared " + formatDate(clip.createdAt)];
  if (clip.downloads > 0) sub.push(clip.downloads === 1 ? "used once" : "used " + clip.downloads + "×");
  document.getElementById("clip-sub").textContent = sub.join("  ·  ");

  //  Die Nachbarschaft braucht nur die Schlagworte - sie wartet nicht auf
  //  das Konto.
  showRelated();

  //  Alles, was der Besitzer im Bearbeiten-Dialog aendern kann, steht in
  //  EINER Funktion: sie laeuft beim Laden und nach dem Speichern. Zwei
  //  Stellen, die dieselben Felder fuellen, liefen sonst auseinander.
  function showClip() {
    document.title = clip.title + " - Animation Workbench Community (Beta)";
    document.getElementById("title").textContent = clip.title;
    document.getElementById("description").textContent = clip.description || "No description.";
    document.getElementById("tags").replaceChildren(
      ...clip.tags.map((tag) => el("a", { class: "tag", href: "/?tag=" + encodeURIComponent(tag) }, tag)));
    showFacts();
  }

  //  "Used in projects" und "Likes" stehen nicht mehr hier: die eine Zahl steht
  //  in der Zeile unter dem Titel, die andere IM Herz-Knopf. Eine Zahl, die man
  //  anfassen kann, gehoert nicht in eine Tabelle daneben.
  function showFacts() {
    document.getElementById("facts").replaceChildren(
      el("dt", {}, "Duration"), el("dd", {}, formatDuration(clip.durationSeconds)),
      el("dt", {}, "Frame rate"), el("dd", {}, Math.round(clip.frameRate) + " fps"),
      el("dt", {}, "Curves"), el("dd", {}, clip.curveCount),
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

  if (clip.isOwner) {
    //  Was nicht Bewegung ist, laesst sich hier aendern. Die Bewegung selbst
    //  kommt als neue Fassung aus der Workbench.
    const edit = iconButton({
      name: "edit",
      tip: "Edit title, description, tags and who can see it",
      onClick: async () => {
        const saved = await editDialog(clip);
        if (!saved) return;
        clip = saved;
        showClip();
        toast("Saved.", { kind: "ok" });
      },
    });
    bar.append(edit);

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
        const page = await api("GET", "/api/v1/packages?" + query + "&size=7");
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

  // ── Bearbeiten ───────────────────────────────────────────────────────
  //  Titel, Beschreibung, Schlagworte und wer ihn sehen darf. Gebaut wie der
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
      const tags = el("input", { type: "text", name: "tags", value: current.tags.join(", "),
        placeholder: "walk, loop, stealth", autocomplete: "off", spellcheck: "false" });

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
          "Public is for good: anyone may use and change it without crediting you, and copies people take " +
          "stay theirs even if you make it private again."),
        el("label", { class: "check" }, declared, declarationLine));
      const toPrivate = el("p", { class: "muted small", hidden: true },
        "It leaves the community, your profile and other people's collections. Copies people already took stay theirs.");
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
        el("label", { class: "field" },
          el("span", {}, "Tags ", el("span", { class: "faint small" }, "up to 10, separated by commas")),
          tags),
        el("fieldset", { class: "choices" },
          el("legend", {}, "Who can see it"),
          el("label", {}, publicChoice,
            el("span", {}, el("strong", {}, "Public"), " - in the community, free to use under CC0")),
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
            //  Getrennt wie beim Teilen in der Workbench: Komma, Leerzeichen, Semikolon.
            tags: tags.value.split(/[,;\s]+/).map((tag) => tag.trim().toLowerCase()).filter(Boolean),
            license: publicChoice.checked ? PUBLIC : PRIVATE,
            declarationAccepted: goingPublic && declared.checked,
            declarationText: declaration ? declaration.declarationText : null,
            declarationVersion: declaration ? declaration.declarationVersion : null,
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
