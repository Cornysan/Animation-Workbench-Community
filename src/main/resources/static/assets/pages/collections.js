/**
 * Die Sammlungen: die eigenen und die der Leute, denen man folgt - dieselben
 * zwei Reihen wie der Reiter "Collections" in der Workbench. "All" gab es bis
 * 2026-09-25 und ist auf Pablos Wunsch weg; eine fremde Sammlung findet man
 * ueber ihren Besitzer, und ohne Konto steht hier, wie man zu beiden kommt.
 *
 * Die Auswahl steht in der ADRESSE (`?show=`), wie Sortierung und Filter im
 * Katalog - Zurueck-Knopf und geteilter Link tun damit von selbst das
 * Richtige. Alte Links mit `?show=all` landen bei "Yours".
 */
(async () => {
  const { api, el, notice, collectionCard, previewObserver, collectionDialog } = AW;

  const filters = document.getElementById("filters");
  const showBox = document.getElementById("show");
  const makeBox = document.getElementById("make");
  const results = document.getElementById("results");
  const empty = document.getElementById("empty");

  const signedIn = document.body.dataset.signedIn === "true";
  const handle = document.body.dataset.handle || "";

  //  Beide Reihen haengen am Konto. Ohne steht der Weg zur Anmeldung da,
  //  wie in der Workbench - und keine Leiste: `.segmented` und `.filters`
  //  setzen `display` selbst und ueberstimmen `hidden`, deshalb entfernt.
  if (!signedIn) {
    filters.remove();
    empty.replaceChildren(el("div", { class: "empty" },
      el("p", {}, "Sign in to see your own collections and those of the people you follow."),
      AW.signInButton("Sign in", true)));
    return;
  }

  //  "Yours" zuerst und als Vorgabe. Ohne Handle (sollte es nicht geben,
  //  HandleBackfill) bleibt nur "Following".
  const choices = [];
  if (handle) choices.push(["mine", "Yours"]);
  choices.push(["following", "Following"]);

  const requested = new URLSearchParams(location.search).get("show");
  const show = choices.some(([value]) => value === requested) ? requested : choices[0][0];

  for (const [value, label] of choices) {
    showBox.append(el("a", {
      class: show === value ? "active" : null,
      href: value === choices[0][0] ? "/collections.html" : "/collections.html?show=" + value,
    }, label));
  }

  const make = el("button", { class: "button", type: "button" }, "New collection");
  make.addEventListener("click", async () => {
    const made = await collectionDialog();
    if (made) location.href = "/collection.html?c=" + encodeURIComponent(made.slug);
  });
  makeBox.append(make);

  if (choices.length === 1) showBox.remove();

  const say = (text) => empty.replaceChildren(el("div", { class: "empty" }, el("p", {}, text)));

  AW.placeholderCards(results, 6);

  try {
    let list;
    let emptyText;

    if (show === "following") {
      const found = await api("GET", "/api/v1/me/collections/following");
      list = found.collections;
      emptyText = found.following === 0
        ? "You are not following anyone yet. Follow a creator from their profile to see their collections here."
        : "The people you follow have no public collections yet.";
    } else {
      list = await api("GET", "/api/v1/collections?owner=" + encodeURIComponent(handle));
      emptyText = "You have no collections yet. Save a clip with the star to start one.";
    }

    if (!list.length) {
      results.replaceChildren();
      say(emptyText);
      return;
    }

    const observer = previewObserver();
    results.replaceChildren(...list.map((item) => collectionCard(item, observer)));
  } catch (error) {
    results.replaceChildren();
    notice(empty, error.message, "error");
  }
})();
