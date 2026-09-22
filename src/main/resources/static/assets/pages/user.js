/**
 * Das Profil: Kopf, Zahlen, vier Reiter.
 *
 * Jeder Reiter holt seinen Inhalt beim ersten Oeffnen und behaelt ihn danach.
 * Welcher offen ist, steht in der ADRESSE (`?u=name&tab=collections`) - damit
 * ist ein bestimmter Reiter verlinkbar, und der Zurueck-Knopf tut das
 * Richtige. Dieselbe Regel wie im Katalog, wo jede Eingrenzung in der Adresse
 * steht.
 */
(async () => {
  const {
    api, ensureCsrf, el, notice, icon, iconButton, setIconState, formatDate, copyText, toastError, copyLink, pulse,
    param, clipCard, collectionCard, previewObserver, signedIn, signInHint, collectionDialog,
  } = AW;

  const handle = param("u");
  const state = document.getElementById("state");
  const actionState = document.getElementById("action-state");

  if (!handle) {
    notice(state, "No profile selected.", "error");
    return;
  }

  let profile;
  try {
    profile = await api("GET", "/api/v1/users/" + encodeURIComponent(handle));
  } catch (e) {
    notice(state, e.status === 404 ? "There is no profile under that address." : e.message, "error");
    return;
  }

  document.title = profile.displayName + " - Animation Workbench Community (Beta)";
  document.getElementById("profile").classList.remove("hidden");

  // ── Kopf ─────────────────────────────────────────────────────────────
  const avatar = document.getElementById("avatar");
  if (profile.avatarUrl) {
    const image = el("img", { class: "profile-avatar", src: profile.avatarUrl, alt: "", width: 88, height: 88 });
    //  Faellt das Bild aus (geloeschter Avatar, Discord nicht erreichbar),
    //  bleibt der Buchstabe stehen statt eines kaputten Rahmens.
    image.addEventListener("error", () => image.replaceWith(avatar));
    avatar.replaceWith(image);
  } else {
    avatar.textContent = (profile.displayName[0] || "?").toUpperCase();
  }

  //  `replaceChildren` filtert nichts: ein `null` in der Liste landet als das
  //  Wort "null" im Dokument. Also erst aussortieren, dann einsetzen.
  document.getElementById("name").replaceChildren(...[
    document.createTextNode(profile.displayName),
    profile.moderator
      ? el("span", { class: "profile-role", "data-tip": "Looks after reports and takedowns" }, "Moderator")
      : null,
  ].filter(Boolean));

  document.getElementById("handle").textContent = "@" + profile.handle + "  ·  joined " + formatDate(profile.joinedAt);

  if (profile.bio) {
    const bio = document.getElementById("bio");
    bio.hidden = false;
    bio.textContent = profile.bio;
  }

  //  Zahlen als Zeichen mit Zahl - dieselbe Schreibweise wie auf den Karten,
  //  damit man sie nicht zweimal lernen muss. Nullen bleiben weg.
  const numbers = [
    ["grid", profile.clips, profile.clips === 1 ? "clip" : "clips"],
    ["folder", profile.collections, profile.collections === 1 ? "collection" : "collections"],
    ["users", profile.followers, profile.followers === 1 ? "follower" : "followers"],
    ["heart", profile.likesReceived, "hearts received"],
    ["download", profile.takes, "used in projects"],
  ].filter(([, value]) => value > 0);

  document.getElementById("numbers").replaceChildren(...numbers.map(([name, value, label]) =>
    el("span", { "data-tip": label }, icon(name), el("b", {}, value.toLocaleString()), label.split(" ")[0])));

  // ── Folgen, teilen, melden ───────────────────────────────────────────
  const actions = document.getElementById("actions");

  if (!profile.isMe) {
    let following = profile.followedByMe;
    let followers = profile.followers;
    let pending = false;

    const showFollow = () => {
      setIconState(follow, following ? "following" : "follow", following, followers || null);
      follow.dataset.tip = following ? "You follow " + profile.displayName : "Follow " + profile.displayName;
      follow.setAttribute("aria-label", follow.dataset.tip);
      follow.setAttribute("aria-pressed", String(following));
    };

    const follow = iconButton({
      name: following ? "following" : "follow",
      tip: following ? "You follow " + profile.displayName : "Follow " + profile.displayName,
      count: followers || null,
      on: following,
      //  Wie das Herz: sofort umschalten, bei einem Fehler zurueckspringen.
      onClick: async () => {
        if (!signedIn()) return signInHint(follow, "Sign in to follow someone.");
        if (pending) return;

        const before = { following, followers };
        following = !following;
        followers = Math.max(0, followers + (following ? 1 : -1));
        showFollow();
        if (following) pulse(follow);

        pending = true;
        try {
          await ensureCsrf();
          const result = await api("POST", "/api/v1/users/" + encodeURIComponent(profile.handle) + "/follow",
            { following });
          followers = result.followers;
          showFollow();
        } catch (e) {
          ({ following, followers } = before);
          showFollow();
          toastError(e);
        } finally {
          pending = false;
        }
      },
    });
    follow.setAttribute("aria-pressed", String(following));
    actions.append(follow);
  }

  const share = iconButton({
    name: "link",
    tip: "Copy a link to this profile",
    onClick: () => copyLink(location.origin + "/u.html?u=" + encodeURIComponent(profile.handle)),
  });
  actions.append(share);

  if (profile.isMe) {
    const edit = iconButton({
      name: "edit",
      tip: "Edit your profile",
      onClick: () => editProfile(),
    });
    actions.append(edit);
  } else if (signedIn()) {
    const dialog = document.getElementById("report-dialog");
    const form = document.getElementById("report-form");

    const report = iconButton({
      name: "flag",
      tip: "Report this account",
      className: "danger",
      onClick: () => dialog.showModal(),
    });

    //  Abbrechen schliesst, ohne zu senden - und ohne auf den `returnValue`
    //  des Dialogs angewiesen zu sein.
    form.querySelector('button[value="cancel"]').addEventListener("click", (event) => {
      event.preventDefault();
      dialog.close();
    });

    //  Gesendet wird am SUBMIT des Formulars, nicht am `close` des Dialogs:
    //  was hier ankommt, ist immer der Melde-Knopf.
    form.addEventListener("submit", async (event) => {
      event.preventDefault();

      try {
        await ensureCsrf();
        await api("POST", "/api/v1/users/" + encodeURIComponent(profile.handle) + "/reports", {
          category: form.category.value,
          message: form.message.value,
        });
        AW.toast("Thank you. A moderator will look at it. Nothing was hidden by your report.", { kind: "ok", duration: 6000 });
        report.disabled = true;
      } catch (e) {
        toastError(e);
      } finally {
        dialog.close();
      }
    });

    actions.append(report);
  }

  /**
   * Das eigene Profil aendern: Handle und Bio.
   *
   * Der Handle darf gewechselt werden - der erste kam aus einem Discord-Namen,
   * den sich niemand ausgesucht hat. Was er kostet, steht im Dialog: der alte
   * Link fuehrt danach ins Leere.
   */
  function editProfile() {
    const handleField = el("input", { type: "text", name: "handle", maxlength: "32", value: profile.handle });
    const bioField = el("textarea", { name: "bio", maxlength: "500", rows: "4" }, profile.bio || "");
    const error = el("div", {});

    const form = el("form", { method: "dialog" },
      el("h2", {}, "Your profile"),
      el("label", { class: "field" },
        el("span", {}, "Handle"),
        handleField),
      el("p", { class: "faint small" },
        "Your address is /u.html?u=", el("b", {}, profile.handle),
        ". Changing it breaks links you already shared."),
      el("label", { class: "field" }, el("span", {}, "About you"), bioField),

      //  Der Weg ins private Fach. Er steht HIER, weil dies die Stelle ist, an
      //  der jemand ohnehin an seinem Konto arbeitet - und weil ein zweiter
      //  Eintrag in der Navigation dieselbe Person an zwei Adressen gezeigt
      //  haette. Was dort liegt, steht dabei, sonst ist es ein Link ins Dunkle.
      el("p", { class: "faint small dialog-aside" },
        el("a", { href: "/me.html" }, "Notifications, clip statuses and your account"),
        " - messages, what each of your clips is doing (including withdrawn ones), " +
        "Workbench sign-ins, and closing the account."),

      error,
      el("div", { class: "dialog-actions" },
        el("button", { value: "cancel", type: "button", class: "ghost" }, "Cancel"),
        el("button", { value: "save", class: "primary" }, "Save")));

    const dialog = el("dialog", { class: "sheet" }, form);
    document.body.append(dialog);

    //  Schliessen und Aufraeumen an einer Stelle, nicht am `close`-Ereignis -
    //  siehe `collectionDialog` in app.js.
    const close = () => { dialog.close(); dialog.remove(); };

    form.querySelector('button[value="cancel"]').addEventListener("click", (event) => {
      event.preventDefault();
      close();
    });
    dialog.addEventListener("cancel", (event) => { event.preventDefault(); close(); });

    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      try {
        await ensureCsrf();
        const saved = await api("PATCH", "/api/v1/me/profile", {
          handle: handleField.value,
          bio: bioField.value,
        });
        close();
        //  Der Handle steht in der Adresse - nach einer Umbenennung waere die
        //  alte Seite eine Seite, die es nicht mehr gibt.
        location.href = "/u.html?u=" + encodeURIComponent(saved.handle);
      } catch (e) {
        notice(error, e.message, "error");
      }
    });

    dialog.showModal();
  }

  // ── Reiter ───────────────────────────────────────────────────────────
  const panel = document.getElementById("panel");
  const tabsBox = document.getElementById("tabs");

  const TABS = [
    { id: "clips", label: "Clips", count: profile.clips, load: loadClips },
    { id: "collections", label: "Collections", count: profile.collections, load: loadCollections },
    { id: "following", label: "Following", count: null, load: loadFollowing },
    { id: "awards", label: "Awards", count: profile.achievements.filter((a) => a.earned).length, load: loadAwards },
  ];

  let current = TABS.some((tab) => tab.id === param("tab")) ? param("tab") : "clips";

  const drawTabs = () => {
    tabsBox.replaceChildren(...TABS.map((tab) => {
      const button = el("button", { type: "button", class: tab.id === current ? "active" : null },
        tab.label, tab.count ? el("span", { class: "tag-count" }, tab.count) : null);
      button.addEventListener("click", () => {
        if (current === tab.id) return;
        current = tab.id;

        //  Der Reiter steht in der Adresse, aber ohne neuen Eintrag in der
        //  Verlaufsliste: viermal Zurueck durch vier Reiter zu muessen, bis
        //  man wieder im Katalog ist, waere eine Falle.
        const next = new URLSearchParams(location.search);
        next.set("tab", tab.id);
        history.replaceState(null, "", "/u.html?" + next.toString());

        drawTabs();
        show();
      });
      return button;
    }));
  };

  const loaded = {};

  async function show() {
    const tab = TABS.find((entry) => entry.id === current);
    if (loaded[tab.id]) {
      panel.replaceChildren(loaded[tab.id]);
      return;
    }

    panel.replaceChildren(el("p", { class: "faint small" }, "Loading…"));
    try {
      const node = await tab.load();
      loaded[tab.id] = node;
      panel.replaceChildren(node);
    } catch (e) {
      notice(panel, e.message, "error");
    }
  }

  function empty(text, action) {
    return el("div", { class: "empty" }, el("p", {}, text), action || null);
  }

  async function loadClips() {
    const page = await api("GET", "/api/v1/users/" + encodeURIComponent(profile.handle) + "/packages?size=24");
    if (!page.items.length) {
      return empty(profile.isMe
        ? "You have not shared a clip yet. In the Workbench: right-click a clip, Share with the Community."
        : profile.displayName + " has not shared a clip yet.");
    }

    const observer = previewObserver();
    return el("div", { class: "grid" }, ...page.items.map((item) => clipCard(item, observer)));
  }

  async function loadCollections() {
    const list = await api("GET", "/api/v1/collections?owner=" + encodeURIComponent(profile.handle));

    const make = profile.isMe
      ? el("button", { class: "button", type: "button" }, "New collection")
      : null;

    if (make) {
      make.addEventListener("click", async () => {
        const made = await collectionDialog();
        if (!made) return;
        delete loaded.collections;
        location.href = "/collection.html?c=" + encodeURIComponent(made.slug);
      });
    }

    if (!list.length) {
      return empty(profile.isMe
        ? "A collection is a set of clips you picked - yours and other people's."
        : profile.displayName + " has no public collections.", make);
    }

    const observer = previewObserver();
    return el("div", {},
      make ? el("div", { class: "filters" }, make) : null,
      el("div", { class: "grid" }, ...list.map((item) => collectionCard(item, observer))));
  }

  async function loadFollowing() {
    const list = await api("GET", "/api/v1/users/" + encodeURIComponent(profile.handle) + "/following");
    if (!list.length) {
      return empty(profile.isMe
        ? "You are not following anyone yet."
        : profile.displayName + " is not following anyone yet.");
    }

    return el("div", { class: "awards" }, ...list.map((person) => el("a", {
      class: "award",
      href: "/u.html?u=" + encodeURIComponent(person.handle),
    },
      person.avatarUrl
        ? el("img", { class: "profile-avatar", src: person.avatarUrl, alt: "", width: 40, height: 40,
            style: "width:40px;height:40px;font-size:16px" })
        : el("span", { class: "avatar" }, (person.displayName[0] || "?").toUpperCase()),
      el("span", {},
        el("div", { class: "award-name" }, person.displayName),
        el("div", { class: "award-sub" },
          (person.clips === 1 ? "1 clip" : person.clips + " clips") +
          (person.followers ? "  ·  " + person.followers + (person.followers === 1 ? " follower" : " followers") : ""))))));
  }

  async function loadAwards() {
    const shown = profile.achievements;
    if (!shown.length) return empty("Nothing earned yet.");

    return el("div", { class: "awards" }, ...shown.map((award) => el("div", {
      class: "award" + (award.earned ? "" : " locked"),
      "data-tip": award.goal
        ? award.description + "  Next: " + award.goal.toLocaleString()
        : award.description,
    },
      icon(award.key === "veteran" ? "clock" : award.key === "beta" ? "award" : awardIcon(award.key), award.earned),
      el("span", {},
        el("div", { class: "award-name" }, award.name),
        el("div", { class: "award-sub" },
          award.earned && award.tiers > 1
            ? "Tier " + award.tier + " of " + award.tiers
            : award.earned ? "Earned" : award.progress.toLocaleString() + " / " + award.goal.toLocaleString())))));
  }

  function awardIcon(key) {
    return { clips: "share", likes: "heart", unlocks: "download", collections: "folder",
      comments: "comment", followers: "users" }[key] || "award";
  }

  drawTabs();
  show();
})();
