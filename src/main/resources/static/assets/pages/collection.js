/**
 * Eine Sammlung: Kopf, Handgriffe, Kachelwand.
 *
 * Die Karten sind dieselben wie im Katalog - mitsamt Herz und Stern. Wer in
 * einer fremden Sammlung etwas findet, soll es von dort aus in die eigene
 * legen koennen, ohne den Umweg ueber die Clip-Seite.
 *
 * Dem Besitzer steht an jeder Karte zusaetzlich ein Kreuz: herausnehmen ist
 * der haeufigste Handgriff an einer Sammlung, die schon steht.
 */
(async () => {
  const {
    api, ensureCsrf, el, notice, icon, iconButton, formatDate, copyText, param,
    clipCard, previewObserver, collectionDialog, releasePreviews, toast, toastError, confirmDialog, copyLink,
  } = AW;

  const slug = param("c");
  const state = document.getElementById("state");
  const actionState = document.getElementById("action-state");

  if (!slug) {
    notice(state, "No collection selected.", "error");
    return;
  }

  let collection;
  try {
    collection = await api("GET", "/api/v1/collections/" + encodeURIComponent(slug));
  } catch (e) {
    notice(state, e.status === 404 ? "This collection is not available." : e.message, "error");
    return;
  }

  document.title = collection.title + " - Animation Workbench Community (Beta)";
  document.getElementById("collection").classList.remove("hidden");

  //  Kopf und Wand getrennt: eine Umbenennung beruehrt die Karten nicht, und
  //  ein herausgenommener Clip nimmt nur SEINE Karte mit. Vorher baute beides
  //  die ganze Wand neu - jede Vorschau noch einmal geholt, jede Figur noch
  //  einmal aufgestellt.
  const drawHead = () => {
    document.getElementById("title").textContent = collection.title;

    const owner = collection.ownerHandle
      ? el("a", { href: "/u.html?u=" + encodeURIComponent(collection.ownerHandle) }, collection.owner)
      : el("span", {}, collection.owner);

    //  Erst aussortieren, dann einsetzen: `replaceChildren` macht aus einem
    //  `null` in der Liste das Wort "null" im Dokument.
    document.getElementById("sub").replaceChildren(...[
      el("span", {}, collection.items.length === 1 ? "1 clip" : collection.items.length + " clips"),
      document.createTextNode("  ·  by "), owner,
      document.createTextNode("  ·  updated " + formatDate(collection.updatedAt)),
      collection.visibility === "UNLISTED"
        ? el("span", { class: "profile-role", "data-tip": "Only people with the link can see it" }, "Unlisted")
        : null,
    ].filter(Boolean));

    if (collection.description) {
      const description = document.getElementById("description");
      description.hidden = false;
      description.textContent = collection.description;
    }
  };

  const drawGrid = () => {
    const results = document.getElementById("results");
    const empty = document.getElementById("empty");
    releasePreviews(results);

    if (!collection.items.length) {
      results.replaceChildren();
      empty.replaceChildren(el("div", { class: "empty" },
        el("p", {}, collection.isOwner
          ? "Nothing in here yet. Find a clip you like and press the star on its card."
          : "Nothing in here yet."),
        el("a", { class: "button", href: "/browse.html" }, "Browse clips")));
      return;
    }

    empty.replaceChildren();
    const observer = previewObserver();

    results.replaceChildren(...collection.items.map((item) => {
      const card = clipCard(item, observer);

      if (collection.isOwner) {
        //  Der Griff zum Herausnehmen sitzt AN der Karte, nicht in einem
        //  Bearbeiten-Modus: eine Sammlung wird beim Ansehen aufgeraeumt.
        const remove = iconButton({
          name: "close",
          tip: "Remove from this collection",
          className: "danger",
          onClick: async () => {
            remove.disabled = true;
            try {
              await ensureCsrf();
              await api("DELETE", "/api/v1/collections/" + encodeURIComponent(collection.slug) +
                "/items/" + encodeURIComponent(item.slug));
              collection.items = collection.items.filter((entry) => entry.slug !== item.slug);
              releasePreviews(card);
              card.remove();
              drawHead();
              if (!collection.items.length) drawGrid();
              toast("Removed from “" + collection.title + "”", { kind: "ok" });
            } catch (e) {
              toastError(e);
              remove.disabled = false;
            }
          },
        });
        card.querySelector(".card-actions").append(remove);
      }

      return card;
    }));
  };

  const draw = () => {
    drawHead();
    drawGrid();
  };

  // ── Handgriffe im Kopf ───────────────────────────────────────────────
  const actions = document.getElementById("actions");

  const share = iconButton({
    name: "link",
    tip: "Copy a link to this collection",
    onClick: () => copyLink(location.origin + "/collection.html?c=" + encodeURIComponent(collection.slug)),
  });
  actions.append(share);

  if (collection.isOwner) {
    actions.append(iconButton({
      name: "edit",
      tip: "Rename or describe this collection",
      onClick: async () => {
        const saved = await collectionDialog(collection);
        if (!saved) return;
        collection.title = saved.title;
        collection.description = saved.description;
        collection.visibility = saved.visibility;
        document.getElementById("description").hidden = !saved.description;
        drawHead();
      },
    }));

    actions.append(iconButton({
      name: "trash",
      tip: "Delete this collection",
      className: "danger",
      onClick: async () => {
        const sure = await confirmDialog({
          title: "Delete this collection?",
          body: "“" + collection.title + "” goes away. The clips themselves stay where they are.",
          confirm: "Delete", danger: true,
        });
        if (!sure) return;
        try {
          await ensureCsrf();
          await api("DELETE", "/api/v1/collections/" + encodeURIComponent(collection.slug));
          location.href = collection.ownerHandle ? "/u.html?u=" + encodeURIComponent(collection.ownerHandle) + "&tab=collections" : "/browse.html";
        } catch (e) {
          toastError(e);
        }
      },
    }));
  }

  draw();
})();
