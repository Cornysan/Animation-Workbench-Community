/**
 * Direktnachrichten: links die Gespraeche, rechts eins davon.
 *
 * Was offen ist, steht in der ADRESSE (`?c=<id>`, `?to=<handle>`,
 * `&box=requests`) - ein Gespraech ist verlinkbar, und Zurueck tut das
 * Richtige. Auf dem Telefon ist das dieselbe Seite in zwei Schritten.
 *
 * NACHFRAGEN STATT PUSH. Alle zehn Sekunden holt die Seite die Liste (eine
 * kleine Abfrage) - und das offene Gespraech nur, wenn sich in der Liste
 * dessen letzte Nachricht geaendert hat. Im Hintergrund fragt sie gar nicht.
 *
 * Text wird nie zu Markup: jede Nachricht geht als Textknoten ins Dokument
 * (AW.el), Links darin bleiben Text.
 */
(async () => {
  const {
    api, ensureCsrf, el, notice, icon, iconButton, avatar, formatRelative, toast, toastError, confirmDialog,
    param, profileLink,
  } = AW;

  const state = document.getElementById("state");
  const layout = document.getElementById("layout");
  const listBox = document.getElementById("conversation-list");
  const boxes = document.getElementById("boxes");
  const thread = document.getElementById("thread");

  const POLL_MS = 10000;
  //  Wer laenger als das nichts geschrieben hat, bekommt wieder eine Zeitzeile.
  const GAP_MS = 15 * 60 * 1000;

  let conversations = [];
  let listSignature = "";
  let box = param("box") === "requests" ? "requests" : "chats";
  /** Das offene Gespraech, wie der Server es zuletzt geliefert hat - oder null. */
  let current = null;
  let sending = false;

  // ── Adresse ───────────────────────────────────────────────────────────

  function urlFor(key) {
    const query = new URLSearchParams();
    if (key && key.c) query.set("c", key.c);
    else if (key && key.to) query.set("to", key.to);
    if (box === "requests") query.set("box", "requests");
    const text = query.toString();
    return "/messages.html" + (text ? "?" + text : "");
  }

  function remember(key, mode) {
    if (!mode) return;
    const url = urlFor(key);
    if (url === location.pathname + location.search) return;
    if (mode === "push") history.pushState(null, "", url);
    else history.replaceState(null, "", url);
  }

  // ── Liste ─────────────────────────────────────────────────────────────

  async function refreshList() {
    try {
      conversations = await api("GET", "/api/v1/me/conversations");
    } catch (e) {
      if (e.status === 401) notice(state, e.message, "error");
      return;
    }
    updateBadge();

    //  Nur neu zeichnen, wenn sich etwas geaendert hat - sonst verliert eine
    //  Zeile, auf der gerade der Tastaturfokus steht, ihn alle zehn Sekunden.
    const signature = JSON.stringify([box, current && current.id, conversations]);
    if (signature === listSignature) return;
    listSignature = signature;
    renderBoxes();
    renderList();
  }

  /** Die Zahl am Papierflieger im Kopf - dieselbe Zaehlung wie beim Server (ShellModel). */
  function updateBadge() {
    const button = document.getElementById("messages-button");
    if (!button) return;
    const count = conversations.filter((c) => c.unread).length;
    let badge = button.querySelector(".bell-count");
    if (count === 0) {
      badge?.remove();
      button.setAttribute("aria-label", "Messages");
      button.title = "Messages";
      return;
    }
    if (!badge) {
      badge = el("span", { class: "bell-count" });
      button.append(badge);
    }
    badge.textContent = count > 99 ? "99+" : String(count);
    button.setAttribute("aria-label", "Messages, " + count + " new");
    button.title = "Messages (" + count + " new)";
  }

  const inBox = (c) => (box === "requests" ? c.state === "request-in" : c.state !== "request-in");

  function renderBoxes() {
    const make = (key, label) => {
      const unread = conversations.filter((c) => c.unread && (key === "requests" ? c.state === "request-in" : c.state !== "request-in")).length;
      const button = el("button", { type: "button", class: box === key ? "active" : null, "aria-pressed": String(box === key) },
        label, unread ? el("span", { class: "box-count" }, String(unread)) : null);
      button.addEventListener("click", () => {
        if (box === key) return;
        box = key;
        remember(current && current.id ? { c: current.id } : null, "replace");
        listSignature = "";
        renderBoxes();
        renderList();
      });
      return button;
    };
    boxes.replaceChildren(make("chats", "Chats"), make("requests", "Requests"));
  }

  function renderList() {
    const shown = conversations.filter(inBox);
    if (shown.length === 0) {
      listBox.replaceChildren(el("p", { class: "conversation-empty muted" }, box === "requests"
        ? "No message requests."
        : "No conversations yet. To write to someone, open their profile and use the paper plane."));
      return;
    }
    listBox.replaceChildren(...shown.map(row));
  }

  function row(c) {
    const link = el("a", {
      class: "conversation-row" + (c.unread ? " unread" : "") + (current && current.id === c.id ? " active" : ""),
      href: urlFor({ c: c.id }),
      "aria-current": current && current.id === c.id ? "true" : null,
    },
      avatar(c.with.displayName, c.with.avatarUrl),
      el("span", { class: "conversation-text" },
        el("span", { class: "conversation-top" },
          el("strong", {}, c.with.displayName),
          el("time", { datetime: c.lastMessageAt, title: new Date(c.lastMessageAt).toLocaleString() },
            formatRelative(c.lastMessageAt))),
        el("span", { class: "conversation-last" },
          c.blockedByMe ? el("span", { class: "chip" }, "Blocked") : null,
          (c.lastFromMe ? "You: " : "") + c.lastMessage)));

    link.addEventListener("click", (event) => {
      if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      openConversation({ c: c.id }, "push");
    });
    return link;
  }

  // ── Ein Gespraech ─────────────────────────────────────────────────────

  async function openConversation(key, mode) {
    let detail;
    try {
      detail = key.c
        ? await api("GET", "/api/v1/me/conversations/" + encodeURIComponent(key.c))
        : await api("GET", "/api/v1/users/" + encodeURIComponent(key.to) + "/conversation");
    } catch (e) {
      current = null;
      layout.classList.add("has-thread");
      thread.replaceChildren(backLink(), el("p", { class: "thread-empty muted" }, e.status === 404
        ? (key.c ? "That conversation is not there anymore." : "There is no profile under that address.")
        : e.message));
      return;
    }

    //  Eine Anfrage an mich steht unter "Requests" - die Liste soll zeigen, wo sie liegt.
    if (detail.state === "request-in" && box !== "requests") box = "requests";
    else if (detail.state !== "request-in" && box === "requests" && detail.id) box = "chats";

    //  `?to=` mit einem bestehenden Gespraech wird zu dessen Adresse, und ein
    //  gewechselter Reiter steht auch ohne neuen Verlaufseintrag in der Adresse.
    remember(detail.id ? { c: detail.id } : key, mode || "replace");
    renderThread(detail, { focus: true });
    listSignature = "";
    await refreshList();
  }

  function closeThread(mode) {
    current = null;
    layout.classList.remove("has-thread");
    thread.replaceChildren(el("p", { class: "thread-empty muted" }, "Pick a conversation, or write to someone from their profile."));
    remember(null, mode);
    listSignature = "";
    renderList();
  }

  function backLink() {
    const back = el("a", { class: "thread-back", href: urlFor(null), "aria-label": "All conversations", "data-tip": "All conversations" }, icon("back"));
    back.addEventListener("click", (event) => {
      event.preventDefault();
      closeThread("push");
    });
    return back;
  }

  /** Ein Zeitstempel zwischen den Nachrichten: heute nur die Uhrzeit, sonst mit Datum. */
  function stamp(iso) {
    const date = new Date(iso);
    const time = { hour: "2-digit", minute: "2-digit" };
    return date.toDateString() === new Date().toDateString()
      ? date.toLocaleTimeString(undefined, time)
      : date.toLocaleString(undefined, { month: "short", day: "numeric", ...time });
  }

  function renderThread(detail, { focus = false, draft = "" } = {}) {
    current = detail;
    layout.classList.add("has-thread");

    const name = detail.with.displayName;
    const handle = detail.with.handle;

    // ── Kopf: wer, und was man mit dem Gespraech tun kann ──
    const who = [avatar(name, detail.with.avatarUrl), el("strong", {}, name)];
    const actions = el("div", { class: "thread-actions" });

    if (handle) {
      actions.append(iconButton({
        name: "block",
        tip: detail.blockedByMe ? "Unblock " + name : "Block " + name,
        on: detail.blockedByMe,
        className: "thread-block",
        onClick: () => setBlocked(!detail.blockedByMe),
      }));
    }
    if (detail.id) {
      actions.append(iconButton({ name: "trash", tip: "Delete this conversation", onClick: () => clearConversation() }));
    }

    const head = el("header", { class: "thread-head" },
      backLink(),
      handle ? el("a", { class: "thread-who", href: profileLink(handle) }, who) : el("span", { class: "thread-who" }, who),
      actions);

    // ── Was gerade gilt: Anfrage an mich, Anfrage von mir, erste Nachricht ──
    let banner = null;
    if (detail.state === "request-in") {
      const accept = el("button", { type: "button", class: "primary" }, "Accept");
      const decline = el("button", { type: "button", class: "ghost" }, "Decline");
      accept.addEventListener("click", () => decide("accept"));
      decline.addEventListener("click", () => decide("decline"));
      banner = el("div", { class: "thread-banner" },
        el("p", {}, el("strong", {}, name), " wants to message you. Replying accepts the request."),
        el("div", { class: "thread-banner-actions" }, decline, accept));
    } else if (detail.state === "request-out" && detail.pendingLeft > 0) {
      banner = el("div", { class: "thread-banner" }, el("p", {},
        "Request sent. Until " + name + " accepts, you can send " +
          (detail.pendingLeft === 1 ? "1 more message." : detail.pendingLeft + " more messages.")));
    } else if (detail.state === "new" && detail.requestNeeded && !detail.cannotSend) {
      banner = el("div", { class: "thread-banner" }, el("p", {},
        "Your first message reaches " + name + " as a request. They decide whether to answer."));
    }

    // ── Die Nachrichten ──
    const scroll = el("div", { class: "thread-scroll" });
    const messages = el("div", { class: "thread-messages" });
    if (detail.hasMore) scroll.append(olderButton(messages));
    scroll.append(messages);
    appendMessages(messages, detail.messages);
    if (detail.messages.length === 0 && detail.state === "new") {
      messages.append(el("p", { class: "thread-empty muted" }, "No messages yet."));
    }

    //  `replaceChildren` filtert nichts - ein `null` stuende als Wort im Dokument.
    thread.replaceChildren(...[head, banner, scroll, compose(detail, draft, focus)].filter(Boolean));
    scroll.scrollTop = scroll.scrollHeight;
  }

  function olderButton(messages) {
    const button = el("button", { type: "button", class: "ghost thread-older" }, "Load earlier messages");
    button.addEventListener("click", async () => {
      const first = messages.querySelector(".dm");
      if (!first || !current) return;
      button.disabled = true;
      try {
        const page = await api("GET", "/api/v1/me/conversations/" + encodeURIComponent(current.id) +
          "?before=" + encodeURIComponent(first.dataset.at));
        const scroll = messages.parentElement;
        const fromBottom = scroll.scrollHeight - scroll.scrollTop;
        const oldLabel = messages.firstElementChild;
        const fragment = el("div", {});
        appendMessages(fragment, page.messages);
        messages.prepend(...fragment.childNodes);
        //  Die Zeitzeile ueber der bisher ersten Nachricht stand dort, weil
        //  nichts davor kam. Liegt die letzte nachgeladene nah genug, geht sie.
        const last = page.messages[page.messages.length - 1];
        if (last && oldLabel && oldLabel.classList.contains("dm-time") &&
            Date.parse(first.dataset.at) - Date.parse(last.createdAt) <= GAP_MS) oldLabel.remove();
        current.messages = page.messages.concat(current.messages);
        scroll.scrollTop = scroll.scrollHeight - fromBottom;
        if (!page.hasMore) button.remove();
      } catch (e) {
        toastError(e);
      } finally {
        button.disabled = false;
      }
    });
    return button;
  }

  function appendMessages(container, list) {
    const existing = container.querySelectorAll(".dm");
    let previous = existing[existing.length - 1] || null;
    let previousAt = previous ? Date.parse(previous.dataset.at) : 0;
    for (const message of list) {
      const at = Date.parse(message.createdAt);
      if (!previous || at - previousAt > GAP_MS) {
        container.append(el("div", { class: "dm-time" }, stamp(message.createdAt)));
      }
      previous = bubble(message);
      previousAt = at;
      container.append(previous);
    }
  }

  function bubble(message) {
    const node = el("div", {
      class: "dm" + (message.mine ? " mine" : ""),
      "data-id": message.id,
      "data-at": message.createdAt,
      title: new Date(message.createdAt).toLocaleString(),
    }, el("p", { class: "dm-text" }, message.body));

    if (!message.mine && current && current.id) {
      node.append(iconButton({
        name: "flag",
        tip: "Report this message",
        className: "dm-report",
        onClick: () => reportMessage(message),
      }));
    }
    return node;
  }

  // ── Schreiben ─────────────────────────────────────────────────────────

  function compose(detail, draft, focus) {
    const name = detail.with.displayName;
    const why = detail.cannotSend;

    if (why) {
      const reasons = {
        "blocked-by-you": "You blocked " + name + ". Neither of you can write while the block is on.",
        unavailable: "You can't send messages to this person.",
        restricted: "Your account is restricted after a removed clip, so it can't start new conversations.",
        waiting: "Request sent. You can write again once " + name + " accepts.",
        self: "That's you.",
      };
      const closed = el("div", { class: "thread-closed" }, el("p", { class: "muted" }, reasons[why] || "You can't write here right now."));
      if (why === "blocked-by-you") {
        const unblock = el("button", { type: "button" }, "Unblock");
        unblock.addEventListener("click", () => setBlocked(false));
        closed.append(unblock);
      }
      return closed;
    }

    const field = el("textarea", {
      rows: 1, maxlength: 2000, placeholder: "Write a message", "aria-label": "Message to " + name,
    });
    field.value = draft;
    const send = el("button", { type: "submit", class: "primary thread-send", "aria-label": "Send", "data-tip": "Send (Enter)" },
      icon("message"));
    const form = el("form", { class: "thread-compose" }, field, send);

    //  Das Feld waechst mit, bis es ein Viertel der Hoehe einnimmt.
    const grow = () => {
      field.style.height = "auto";
      field.style.height = Math.min(field.scrollHeight + 2, 180) + "px";
    };
    field.addEventListener("input", grow);
    //  Enter schickt ab, Umschalt+Enter macht eine neue Zeile - wie in jedem Chat.
    field.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" || event.shiftKey || event.isComposing) return;
      event.preventDefault();
      form.requestSubmit();
    });
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      submit(detail, field, send);
    });

    requestAnimationFrame(() => {
      grow();
      if (focus && matchMedia("(hover: hover)").matches) field.focus();
    });
    return form;
  }

  async function submit(detail, field, send) {
    const text = field.value.trim();
    if (!text || sending) return;
    sending = true;
    send.disabled = true;
    try {
      await ensureCsrf();
      const result = detail.id
        ? await api("POST", "/api/v1/me/conversations/" + encodeURIComponent(detail.id) + "/messages", { body: text })
        : await api("POST", "/api/v1/users/" + encodeURIComponent(detail.with.handle) + "/messages", { body: text });
      field.value = "";

      //  Im gewoehnlichen Fall nur anhaengen. Hat sich mit der Nachricht der
      //  Zustand geaendert (erste Nachricht, Antwort auf eine Anfrage, Anfrage
      //  voll), das Gespraech frisch holen - dort stehen dann Hinweis und Feld richtig.
      const unchanged = detail.id && result.state === detail.state && detail.state === "accepted";
      if (unchanged) {
        const messages = thread.querySelector(".thread-messages");
        appendMessages(messages, [result.message]);
        current.messages.push(result.message);
        const scroll = thread.querySelector(".thread-scroll");
        scroll.scrollTop = scroll.scrollHeight;
        listSignature = "";
        await refreshList();
      } else {
        await openConversation({ c: result.conversationId }, detail.id ? null : "replace");
      }
    } catch (e) {
      toastError(e);
    } finally {
      sending = false;
      send.disabled = false;
      const live = thread.querySelector(".thread-compose textarea");
      if (live) {
        live.dispatchEvent(new Event("input"));
        live.focus();
      }
    }
  }

  // ── Annehmen, ablehnen, blockieren, leeren, melden ────────────────────

  async function decide(what) {
    if (!current || !current.id) return;
    const id = current.id;
    const name = current.with.displayName;
    try {
      await ensureCsrf();
      if (what === "accept") {
        const detail = await api("POST", "/api/v1/me/conversations/" + encodeURIComponent(id) + "/accept");
        box = "chats";
        remember({ c: id }, "replace");
        renderThread(detail, { focus: true });
      } else {
        await api("POST", "/api/v1/me/conversations/" + encodeURIComponent(id) + "/decline");
        toast("Request from " + name + " declined. They are not told.", { kind: "ok" });
        closeThread("replace");
      }
      listSignature = "";
      await refreshList();
    } catch (e) {
      toastError(e);
    }
  }

  async function setBlocked(blocked) {
    if (!current || !current.with.handle) return;
    const name = current.with.displayName;
    if (blocked) {
      const ok = await confirmDialog({
        title: "Block " + name + "?",
        body: "Neither of you can message the other, you stop following each other, and " + name +
          " can't comment on your clips. They are not told.",
        confirm: "Block",
        danger: true,
      });
      if (!ok) return;
    }
    try {
      await ensureCsrf();
      await api("POST", "/api/v1/users/" + encodeURIComponent(current.with.handle) + "/block", { blocked });
      toast(blocked ? name + " is blocked." : name + " is unblocked.", { kind: "ok" });
      await reloadCurrent();
    } catch (e) {
      toastError(e);
    }
  }

  async function clearConversation() {
    if (!current || !current.id) return;
    const name = current.with.displayName;
    const ok = await confirmDialog({
      title: "Delete this conversation?",
      body: "It disappears for you. " + name + " keeps their copy, and if they write again, it comes back with the new message.",
      confirm: "Delete",
      danger: true,
    });
    if (!ok) return;
    try {
      await ensureCsrf();
      await api("DELETE", "/api/v1/me/conversations/" + encodeURIComponent(current.id));
      closeThread("replace");
      listSignature = "";
      await refreshList();
    } catch (e) {
      toastError(e);
    }
  }

  /**
   * Eine Nachricht melden. Die Moderation sieht genau das, was hier steht:
   * diese Nachricht und die davor - der Satz im Dialog sagt es, bevor man
   * absendet. Blockieren ist vorausgewaehlt: wer meldet, will meistens auch,
   * dass es aufhoert.
   */
  function reportMessage(message) {
    if (!current || !current.id) return;
    const id = current.id;
    const name = current.with.displayName;

    const category = el("select", { name: "category" },
      el("option", { value: "INAPPROPRIATE" }, "Harassment, threats or hate"),
      el("option", { value: "BROKEN" }, "Spam or scam"),
      el("option", { value: "OTHER" }, "Something else"));
    const text = el("textarea", { name: "message", maxlength: 2000, rows: 3 });
    const block = el("input", { type: "checkbox", checked: true });
    const cancel = el("button", { type: "button", class: "ghost" }, "Cancel");
    const send = el("button", { type: "submit", class: "danger" }, "Report");

    const form = el("form", {},
      el("h2", { id: "dm-report-title" }, "Report this message"),
      el("p", { class: "muted small" },
        "A moderator sees this message and up to ten before it, nothing else of the conversation."),
      el("label", { class: "field" }, el("span", {}, "Reason"), category),
      el("label", { class: "field" }, el("span", {}, "Anything to add? (optional)"), text),
      el("label", { class: "check" }, block, el("span", {}, "Also block " + name)),
      el("div", { class: "dialog-actions" }, cancel, send));
    const dialog = el("dialog", { class: "sheet", "aria-labelledby": "dm-report-title" }, form);
    document.body.append(dialog);

    //  Aufgeraeumt wird an EINER Stelle, nicht ueber das `close`-Ereignis
    //  (Begruendung bei AW.collectionDialog).
    const finish = () => {
      dialog.close();
      dialog.remove();
    };
    cancel.addEventListener("click", finish);
    dialog.addEventListener("cancel", (event) => { event.preventDefault(); finish(); });
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      send.disabled = true;
      try {
        await ensureCsrf();
        await api("POST", "/api/v1/me/conversations/" + encodeURIComponent(id) + "/messages/" +
          encodeURIComponent(message.id) + "/reports",
          { category: category.value, message: text.value, block: block.checked });
        finish();
        toast("Thank you. A moderator will look at it." + (block.checked ? " " + name + " is blocked." : ""),
          { kind: "ok", duration: 6000 });
        await reloadCurrent();
      } catch (e) {
        send.disabled = false;
        toastError(e);
      }
    });

    dialog.showModal();
    category.focus();
  }

  async function reloadCurrent() {
    if (!current) return;
    const draft = thread.querySelector(".thread-compose textarea")?.value || "";
    const key = current.id ? { c: current.id } : { to: current.with.handle };
    try {
      const detail = key.c
        ? await api("GET", "/api/v1/me/conversations/" + encodeURIComponent(key.c))
        : await api("GET", "/api/v1/users/" + encodeURIComponent(key.to) + "/conversation");
      renderThread(detail, { draft });
    } catch (e) {
      toastError(e);
    }
    listSignature = "";
    await refreshList();
  }

  // ── Nachfragen ────────────────────────────────────────────────────────

  /**
   * Neues in der Liste? Dann auch im offenen Gespraech nachsehen - und dort
   * nur ANHAENGEN, was fehlt. Neu zeichnen hiesse: Entwurf, Scrollstand und
   * Fokus gehen verloren, waehrend jemand tippt.
   */
  async function poll() {
    if (document.hidden || sending) return;
    const open = current && current.id ? conversations.find((c) => c.id === current.id) : null;
    const before = open ? open.lastMessageAt : null;
    await refreshList();
    if (!current || !current.id || sending) return;

    const now = conversations.find((c) => c.id === current.id);
    if (!now || now.lastMessageAt === before) return;

    try {
      const detail = await api("GET", "/api/v1/me/conversations/" + encodeURIComponent(current.id));
      const sameShape = detail.state === current.state && detail.cannotSend === current.cannotSend &&
        detail.blockedByMe === current.blockedByMe;
      if (!sameShape) {
        renderThread(detail, { draft: thread.querySelector(".thread-compose textarea")?.value || "" });
        return;
      }
      const known = new Set(current.messages.map((m) => m.id));
      const fresh = detail.messages.filter((m) => !known.has(m.id));
      if (fresh.length === 0) return;

      const scroll = thread.querySelector(".thread-scroll");
      const atBottom = scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 80;
      appendMessages(thread.querySelector(".thread-messages"), fresh);
      current = { ...detail, messages: current.messages.concat(fresh) };
      if (atBottom) scroll.scrollTop = scroll.scrollHeight;
      //  Geholt heisst gelesen - die Zahl im Kopf stimmt erst nach der naechsten Liste.
      listSignature = "";
      await refreshList();
    } catch {
      //  Beim naechsten Takt wieder.
    }
  }

  // ── Los ───────────────────────────────────────────────────────────────

  window.addEventListener("popstate", () => {
    box = param("box") === "requests" ? "requests" : "chats";
    const c = param("c");
    const to = param("to");
    if (c) openConversation({ c }, null);
    else if (to) openConversation({ to }, null);
    else closeThread(null);
  });

  await refreshList();
  renderBoxes();
  renderList();

  const startC = param("c");
  const startTo = param("to");
  if (startC) await openConversation({ c: startC }, null);
  else if (startTo) await openConversation({ to: startTo }, null);

  setInterval(poll, POLL_MS);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) poll(); });
})();
