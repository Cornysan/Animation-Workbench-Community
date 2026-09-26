/**
 * Ein Pack: Kopf, Handgriffe, Kachelwand.
 *
 * Die Karten sind die des Katalogs, mit Herz und Stern - im Pack sammelt man
 * einzelne Clips wie ueberall sonst. Nur der Hinweis "Part of the pack" faellt
 * weg: hier ist er die Seite selbst.
 *
 * Dem Besitzer steht an jeder Karte ein Kreuz. Herausgenommen heisst nicht
 * geloescht: der Clip steht danach wieder einzeln im Katalog.
 */
(async () => {
  const {
    api, ensureCsrf, el, notice, iconButton, formatDate, param, clipCard, previewObserver,
    packDialog, packCandidates, releasePreviews, toast, toastError, confirmDialog, copyLink,
  } = AW;

  const slug = param("k");
  const state = document.getElementById("state");

  if (!slug) {
    notice(state, "No pack selected.", "error");
    return;
  }

  let pack;
  try {
    pack = await api("GET", "/api/v1/packs/" + encodeURIComponent(slug));
  } catch (e) {
    notice(state, e.status === 404 ? "This pack is not available." : e.message, "error");
    return;
  }

  document.getElementById("pack").classList.remove("hidden");

  const profileOf = (item) => item.authorHandle
    ? el("a", { href: "/u.html?u=" + encodeURIComponent(item.authorHandle) }, item.author)
    : el("span", {}, item.author);

  //  Kopf und Wand getrennt, wie bei der Sammlung: eine Umbenennung baut
  //  keine Figur neu auf, und ein herausgenommener Clip nimmt nur SEINE Karte mit.
  const drawHead = () => {
    document.title = pack.title + " - Animation Workbench Community";
    document.getElementById("title").textContent = pack.title;

    const count = pack.items.length;
    document.getElementById("sub").replaceChildren(
      el("span", {}, count === 1 ? "1 clip" : count + " clips"),
      document.createTextNode("  ·  by "), profileOf(pack),
      document.createTextNode("  ·  " + formatDate(pack.createdAt)));

    //  Die Quelle nur, wenn alle Clips dieselbe nennen - der Server rechnet das.
    const source = document.getElementById("source");
    source.hidden = !pack.source;
    if (pack.source) {
      source.replaceChildren("Original: ",
        pack.source.url
          ? el("a", { href: pack.source.url, rel: "noopener", target: "_blank" }, pack.source.credit)
          : pack.source.credit,
        el("span", { class: "faint" }, " · CC0, free to use, no credit needed"));
    }

    const description = document.getElementById("description");
    description.hidden = !pack.description;
    description.textContent = pack.description || "";

    //  Die Schlagworte seiner Clips - jedes fuehrt in den Katalog.
    document.getElementById("tags").replaceChildren(...(pack.tags || []).map((tag) =>
      el("a", { class: "tag", href: "/?tag=" + encodeURIComponent(tag) }, tag)));
  };

  const drawGrid = () => {
    const results = document.getElementById("results");
    const empty = document.getElementById("empty");
    releasePreviews(results);

    if (!pack.items.length) {
      results.replaceChildren();
      empty.replaceChildren(el("div", { class: "empty" },
        el("p", {}, pack.isOwner
          ? "No clip in this pack is public right now, so nobody else sees it."
          : "Nothing in here right now."),
        el("a", { class: "button", href: "/" }, "Browse animations")));
      return;
    }

    empty.replaceChildren();
    const observer = previewObserver();

    results.replaceChildren(...pack.items.map((item) => {
      const card = clipCard(item, observer, { inPack: true });

      if (pack.isOwner) {
        const remove = iconButton({
          name: "close",
          tip: "Take out of this pack",
          className: "danger",
          onClick: async () => {
            remove.disabled = true;
            try {
              await ensureCsrf();
              pack = await api("DELETE", "/api/v1/packs/" + encodeURIComponent(pack.slug) +
                "/clips/" + encodeURIComponent(item.slug));
              releasePreviews(card);
              card.remove();
              drawHead();
              if (!pack.items.length) drawGrid();
              toast("“" + item.title + "” stands on its own again", { kind: "ok" });
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

  // ── Handgriffe im Kopf ───────────────────────────────────────────────
  const actions = document.getElementById("actions");

  actions.append(iconButton({
    name: "link",
    tip: "Copy a link to this pack",
    onClick: () => copyLink(location.origin + "/pack.html?k=" + encodeURIComponent(pack.slug)),
  }));

  if (pack.isOwner) {
    const starter = pack.authorHandle === "starter-clips";

    actions.append(iconButton({
      name: "edit",
      tip: "Rename or describe this pack",
      onClick: async () => {
        const saved = await packDialog({ pack });
        if (!saved) return;
        pack = saved;
        drawHead();
      },
    }));

    actions.append(iconButton({
      name: "plus",
      tip: "Add clips to this pack",
      onClick: async () => {
        let candidates;
        try {
          candidates = (await packCandidates(starter)).filter((clip) => !clip.pack || clip.pack.slug !== pack.slug);
        } catch (e) {
          toastError(e);
          return;
        }
        const saved = await packDialog({ pack, candidates, starter });
        if (!saved) return;
        pack = saved;
        drawHead();
        drawGrid();
      },
    }));

    actions.append(iconButton({
      name: "trash",
      tip: "Take this pack apart",
      className: "danger",
      onClick: async () => {
        const sure = await confirmDialog({
          title: "Take this pack apart?",
          body: "“" + pack.title + "” goes away. Its clips stay and stand on their own in the catalogue again.",
          confirm: "Take apart", danger: true,
        });
        if (!sure) return;
        try {
          await ensureCsrf();
          await api("DELETE", "/api/v1/packs/" + encodeURIComponent(pack.slug));
          location.href = pack.authorHandle ? "/u.html?u=" + encodeURIComponent(pack.authorHandle) : "/";
        } catch (e) {
          toastError(e);
        }
      },
    }));
  }

  drawHead();
  drawGrid();
})();
