/**
 * Die Seite einer Animation: Kopf, Aktionen, Zahlen, Nachbarschaft.
 *
 * Das Gespraech darunter gehoert `clip-comments.js`, die Buehne
 * `clip-viewer.js` - drei Dateien, drei Zustaendigkeiten, kein gemeinsamer
 * Zustand ausser dem Slug in der Adresse.
 */
(async () => {
  const { api, ensureCsrf, me, el, notice, formatDuration, formatDate, copyText, param, signInButton } = AW;

  const slug = param("p");
  const state = document.getElementById("state");
  const actionState = document.getElementById("action-state");

  if (!slug) {
    notice(state, "No clip selected.", "error");
    return;
  }

  let clip;
  try {
    clip = await api("GET", "/api/v1/packages/" + encodeURIComponent(slug));
  } catch (e) {
    notice(state, e.status === 404 ? "This clip is not available. It may be under review or removed." : e.message, "error");
    return;
  }

  document.title = clip.title + " - Animation Workbench Community";
  document.getElementById("clip").classList.remove("hidden");
  document.getElementById("title").textContent = clip.title;
  //  Von hier aus zu allem anderen derselben Person. Ohne diesen Weg ist jeder
  //  Clip eine Insel, und niemand baut sich einen Namen auf.
  document.getElementById("author").replaceChildren(el("a", {
    href: "/browse.html?author=" + encodeURIComponent(clip.author),
    title: "All clips by " + clip.author,
  }, clip.author));
  document.getElementById("author-initial").textContent = (clip.author[0] || "?").toUpperCase();
  document.getElementById("description").textContent = clip.description || "No description.";

  //  Eine Zeile statt vier Tabellenzeilen: was hier steht, liest man im
  //  Vorbeigehen. Nullen bleiben weg - ein frischer Clip ist nicht unbeliebt.
  const sub = ["Shared " + formatDate(clip.createdAt)];
  if (clip.downloads > 0) sub.push(clip.downloads === 1 ? "used once" : "used " + clip.downloads + "×");
  document.getElementById("clip-sub").textContent = sub.join("  ·  ");

  document.getElementById("tags").replaceChildren(
    ...clip.tags.map((tag) => el("a", { class: "tag", href: "/browse.html?tag=" + encodeURIComponent(tag) }, tag)));

  //  "Used in projects" und "Likes" stehen nicht mehr hier: die eine Zahl steht
  //  in der Zeile unter dem Titel, die andere IM Herz-Knopf. Eine Zahl, die man
  //  anfassen kann, gehoert nicht in eine Tabelle daneben.
  document.getElementById("facts").replaceChildren(
    el("dt", {}, "Duration"), el("dd", {}, formatDuration(clip.durationSeconds)),
    el("dt", {}, "Frame rate"), el("dd", {}, Math.round(clip.frameRate) + " fps"),
    el("dt", {}, "Curves"), el("dd", {}, clip.curveCount),
    el("dt", {}, "Rig"), el("dd", {}, clip.rig === "generic" ? "Generic" : "Humanoid"),
    el("dt", {}, "Version"), el("dd", {}, clip.version),
    ...(clip.status ? [el("dt", {}, "Status"), el("dd", {}, el("span", { class: "status " + clip.status }, clip.status))] : []));

  try {
    const licenses = await api("GET", "/api/v1/licenses");
    const license = licenses.find((l) => l.id === clip.license);
    document.getElementById("license").replaceChildren(...[
      el("strong", {}, license ? license.name : clip.license), el("br"),
      license ? license.summary + " " : "",
      // Der private Fall hat keine Lizenzseite, auf die man verweisen könnte.
      license && license.url ? el("a", { href: license.url, rel: "noopener", target: "_blank" }, "Full license") : null,
    ].filter(Boolean));
  } catch {
    document.getElementById("license").textContent = clip.license;
  }

  //  Die Vorschau gehoert `pages/clip-viewer.js` - einem Modul, weil die Buehne
  //  three.js laedt. Es holt seine Daten selbst und ist der einzige Schreiber
  //  auf `#viewer`; zwei Stellen, die denselben Kasten fuellen, ueberholen
  //  einander irgendwann.

  const user = await me().catch(() => null);

  // ── Herz, Link, Meldung ──────────────────────────────────────────────
  //  Die drei Dinge, die man an einer fremden Animation tun kann, stehen
  //  nebeneinander unter dem Titel - nicht verstreut zwischen Tabelle und
  //  Seitenspalte.
  const bar = document.getElementById("clip-actions");

  const likeCount = el("span", { class: "count" }, String(clip.likes));
  const like = el("button", {
    class: "pill-button" + (clip.likedByMe ? " on" : ""),
    title: user ? "Like this clip" : "Sign in to like this clip",
  }, el("span", { class: "heart" }, "♥"), likeCount);

  like.addEventListener("click", async () => {
    if (!user) {
      notice(actionState, "Sign in to like a clip.", "");
      return;
    }
    const liked = !like.classList.contains("on");
    like.disabled = true;
    try {
      await ensureCsrf();
      const result = await api("POST", "/api/v1/packages/" + encodeURIComponent(slug) + "/like", { liked });
      like.classList.toggle("on", liked);
      likeCount.textContent = String(result.likes);
    } catch (e) {
      notice(actionState, e.message, "error");
    } finally {
      like.disabled = false;
    }
  });
  bar.append(like);

  //  Ein Portal, das von Links lebt, braucht einen Knopf dafuer. Die Adresse
  //  aus der Leiste zu fischen ist eine Huerde, die niemand nehmen muss.
  const share = el("button", { class: "pill-button", title: "Copy a link to this clip" },
    el("span", {}, "Copy link"));
  share.addEventListener("click", async () => {
    const ok = await copyText(location.origin + "/clip.html?p=" + encodeURIComponent(slug));
    share.replaceChildren(el("span", {}, ok ? "Link copied" : "Could not copy"));
    setTimeout(() => share.replaceChildren(el("span", {}, "Copy link")), 2000);
  });
  bar.append(share);

  if (clip.isOwner) {
    const withdraw = el("button", { class: "pill-button danger" }, "Withdraw");
    withdraw.addEventListener("click", async () => {
      if (!confirm("Withdraw '" + clip.title + "'? It disappears from the community.")) return;
      try {
        await api("DELETE", "/api/v1/packages/" + encodeURIComponent(slug));
        location.href = "/me.html";
      } catch (e) {
        notice(actionState, e.message, "error");
      }
    });
    bar.append(withdraw);
  } else if (user) {
    const dialog = document.getElementById("report-dialog");
    const form = document.getElementById("report-form");
    const report = el("button", { class: "pill-button" }, "Report");
    report.addEventListener("click", () => dialog.showModal());

    dialog.addEventListener("close", async () => {
      if (dialog.returnValue !== "send") return;
      try {
        await ensureCsrf();
        await api("POST", "/api/v1/packages/" + encodeURIComponent(slug) + "/reports", {
          category: form.category.value,
          message: form.message.value,
        });
        notice(actionState, "Thank you. The clip is hidden until a moderator has reviewed it.", "ok");
        report.disabled = true;
      } catch (e) {
        notice(actionState, e.message, "error");
      }
    });
    bar.append(report);
  }

  // ── Mitnehmen ────────────────────────────────────────────────────────
  //  Seit der Muenzwirtschaft ist Herunterladen Freischalten, und das kostet
  //  unter Umstaenden. Der Knopf muss das sagen, BEVOR er es tut - "Download"
  //  auf einem Knopf, der zehn Muenzen abbucht, waere eine Falle.
  const actions = document.getElementById("actions");
  const status = await api("GET", "/api/v1/status").catch(() => ({}));

  const costs = status.economyEnabled && !clip.unlockedByMe && clip.license === "CC-BY-4.0";

  if (!user) {
    //  Ein grauer Knopf, der nicht sagt warum, ist die haeufigste Sackgasse
    //  dieses Portals gewesen (B2). Er sagt es jetzt.
    actions.append(
      signInButton("Sign in to download", true),
      el("p", { class: "faint small" }, "Downloads are tied to an account so the counter means something."));
  } else {
    const download = el("button", { class: "primary" },
      costs ? "Unlock for " + status.unlockCost + " coins" : "Download .awclip");

    download.addEventListener("click", async () => {
      download.disabled = true;
      try {
        await ensureCsrf();
        const link = await api("POST", "/api/v1/packages/" + encodeURIComponent(slug) + "/unlock");
        const a = el("a", { href: link.url, download: link.fileName });
        document.body.append(a);
        a.click();
        a.remove();
        notice(actionState, "Downloaded. In Unity: Tools > Animation Workbench > Community > Import .awclip File. License: " + link.license + ".", "ok");
        download.replaceChildren(document.createTextNode("Download .awclip"));
      } catch (e) {
        notice(actionState, e.message, "error");
      } finally {
        download.disabled = false;
      }
    });
    actions.append(download);
  }

  // ── Nachbarschaft ────────────────────────────────────────────────────
  //  Wer einen Laufzyklus ansieht, will meistens Laufzyklen sehen. Das erste
  //  Schlagwort traegt den Clip am besten - es steht beim Hochladen vorn, weil
  //  der Hochladende es zuerst eingetippt hat.
  //
  //  Das ist bewusst KEIN eigener Endpunkt: die Suche nach einem Schlagwort
  //  kann der Katalog schon, und "aehnlich" heisst hier genau das.
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
})();
