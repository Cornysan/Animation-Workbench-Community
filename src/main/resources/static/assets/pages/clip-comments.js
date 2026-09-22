/**
 * Das Gespraech unter einer Animation (E10).
 *
 * Die Endpunkte gibt es seit dem 20.09., in der Workbench auch die Oberflaeche
 * dazu - im Web fehlte sie. Ein Clip, den man in Discord verlinkt, fuehrte also
 * auf eine Seite, auf der man nichts sagen konnte, obwohl darunter Kommentare
 * lagen.
 *
 * FLACH, AELTESTE ZUERST. Keine Antworten auf Antworten. Unter einer Animation
 * steht "die Huefte kippt im dritten Schritt", nicht ein Nebenstrang.
 *
 * MITLESEN DARF JEDER, schreiben nur angemeldet - dieselbe Trennung wie beim
 * Herz. Wer abgemeldet liest, sieht an der Stelle des Feldes den Weg hinein,
 * keinen ausgegrauten Kasten.
 */
(async () => {
  const { api, ensureCsrf, me, el, notice, formatRelative, param, signInButton, confirmDialog, toast, toastError, avatar } = AW;

  const slug = param("p");
  if (!slug) return;

  const section = document.getElementById("comments");
  const title = document.getElementById("comments-title");
  const formBox = document.getElementById("comment-form");
  const list = document.getElementById("comment-list");
  const state = document.getElementById("comment-state");

  const base = "/api/v1/packages/" + encodeURIComponent(slug) + "/comments";
  const MAX = 2000;

  const PAGE = 30;
  let page;
  try {
    page = await api("GET", base + "?size=" + PAGE);
  } catch {
    //  Kein Gespraech ist kein Fehler, der jemanden angeht: der Clip kann
    //  versteckt sein, dann hat er auch keine Kommentare.
    return;
  }

  section.hidden = false;
  const user = await me().catch(() => null);

  function heading(total) {
    title.textContent = total === 0 ? "Comments" : total === 1 ? "1 comment" : total + " comments";
  }

  // ── Ein Kommentar ────────────────────────────────────────────────────
  function render(comment) {
    const actions = [];

    if (comment.mine) {
      const remove = el("button", { class: "link-button" }, "Delete");
      remove.addEventListener("click", async () => {
        const sure = await confirmDialog({
          title: "Delete your comment?", confirm: "Delete", danger: true,
        });
        if (!sure) return;
        try {
          await ensureCsrf();
          await api("DELETE", base + "/" + comment.id);
          node.remove();
          heading(Math.max(0, --page.total));
          if (page.total === 0) showEmpty();
          toast("Comment deleted", { kind: "ok" });
        } catch (e) {
          toastError(e);
        }
      });
      actions.push(remove);
    } else if (user) {
      //  Eine Meldung trifft den KOMMENTAR, nicht den Clip - das ist der Kern
      //  von E10. Sie versteckt ihn sofort, bis jemand hinsieht.
      const report = el("button", { class: "link-button" }, "Report");
      report.addEventListener("click", async () => {
        const sure = await confirmDialog({
          title: "Report this comment?",
          body: "It is hidden until a moderator has looked at it.",
          confirm: "Report", danger: true,
        });
        if (!sure) return;
        try {
          await ensureCsrf();
          await api("POST", base + "/" + comment.id + "/reports", { category: "INAPPROPRIATE", message: "" });
          report.replaceWith(el("span", { class: "faint small" }, "Reported"));
          toast("Thank you. A moderator will look at it.", { kind: "ok" });
        } catch (e) {
          toastError(e);
        }
      });
      actions.push(report);
    }

    //  Der Name fuehrt zur Person - wer etwas Kluges unter einen Clip
    //  schreibt, soll auffindbar sein. Ohne Handle (geloeschtes Konto) bleibt
    //  er Text.
    const name = comment.authorHandle
      ? el("a", { class: "comment-author", href: "/u.html?u=" + encodeURIComponent(comment.authorHandle) }, comment.author)
      : el("strong", {}, comment.author);

    const node = el("article", { class: "comment" + (comment.mine ? " mine" : "") },
      avatar(comment.author, comment.authorAvatar),
      el("div", { class: "comment-body" },
        el("div", { class: "comment-head" },
          name,
          el("span", { class: "faint small" }, formatRelative(comment.createdAt)),
          comment.editedAt ? el("span", { class: "faint small" }, "edited") : null,
          ...actions),
        el("p", { class: "comment-text" }, comment.body)));

    return node;
  }

  function showEmpty() {
    list.replaceChildren(el("p", { class: "faint comment-empty" },
      user ? "Nothing yet. Say what you would change." : "Nothing yet."));
  }

  //  Mehr als eine Seite: ein Knopf am Ende holt die naechste. Vorher kamen
  //  die ersten 50, und `hasMore` wurde nie gelesen - Kommentar 51 gab es
  //  im Web schlicht nicht.
  const more = el("button", { class: "ghost comment-more", type: "button" }, "Show more comments");
  more.addEventListener("click", async () => {
    more.disabled = true;
    try {
      const next = await api("GET", base + "?size=" + PAGE + "&page=" + (page.page + 1));
      page.page = next.page;
      page.hasMore = next.hasMore;
      page.total = next.total;
      page.comments.push(...next.comments);
      more.before(...next.comments.map(render));
      heading(page.total);
      if (!page.hasMore) more.remove();
    } catch (e) {
      toastError(e);
    } finally {
      more.disabled = false;
    }
  });

  function draw() {
    heading(page.total);
    if (page.comments.length === 0) showEmpty();
    else list.replaceChildren(...page.comments.map(render), ...(page.hasMore ? [more] : []));
  }

  // ── Schreiben ────────────────────────────────────────────────────────
  if (user) {
    const field = el("textarea", {
      maxlength: String(MAX),
      rows: 3,
      placeholder: "Say what works, or what you would change.",
      "aria-label": "Write a comment",
    });
    const counter = el("span", { class: "faint small" }, "");
    const send = el("button", { class: "primary" }, "Comment");

    const sync = () => {
      const left = MAX - field.value.length;
      counter.textContent = left < 200 ? left + " left" : "";
      send.disabled = field.value.trim().length === 0;
    };
    field.addEventListener("input", sync);

    //  Strg+Enter schickt ab - die Geste, die jeder aus jedem Eingabefeld
    //  kennt, das mehr als eine Zeile hat. Enter allein bleibt ein Umbruch.
    field.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && (event.ctrlKey || event.metaKey) && !send.disabled) send.click();
    });

    send.addEventListener("click", async () => {
      const body = field.value.trim();
      if (!body) return;
      send.disabled = true;
      try {
        await ensureCsrf();
        const comment = await api("POST", base, { body });
        if (page.comments.length === 0) list.replaceChildren();
        //  Hinten anhaengen - aber vor "Show more", der bleibt der letzte.
        if (more.isConnected) more.before(render(comment));
        else list.append(render(comment));
        page.comments.push(comment);
        heading(++page.total);
        field.value = "";
        state.replaceChildren();
      } catch (e) {
        notice(state, e.message, "error");
      } finally {
        sync();
      }
    });

    sync();
    formBox.replaceChildren(el("div", { class: "comment-compose" },
      avatar(user.displayName, user.avatarUrl),
      el("div", { class: "compose-body" }, field,
        el("div", { class: "compose-foot" }, counter, send))));
  } else {
    formBox.replaceChildren(el("div", { class: "comment-signin" },
      el("span", { class: "muted" }, "Sign in to join the conversation."),
      signInButton("Sign in")));
  }

  draw();
})();
