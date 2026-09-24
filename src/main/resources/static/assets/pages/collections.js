/**
 * Die Sammlungen: alle oeffentlichen, und wer angemeldet ist, dazu die der
 * Leute, denen er folgt, und die eigenen.
 *
 * Die Auswahl steht in der ADRESSE (`?show=`), wie Sortierung und Filter im
 * Katalog - Zurueck-Knopf und geteilter Link tun damit von selbst das
 * Richtige. Dieselben drei Blickwinkel wie der Reiter "Collections" in der
 * Workbench, dort ohne "All".
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

  //  "Following" und "Yours" gibt es nur mit Konto. Ohne bleibt eine einzige
  //  Wahl, und eine Wahl aus einem ist keine - dann steht keine Leiste da.
  //  Entfernt statt `hidden`: `.segmented` und `.filters` setzen `display`
  //  selbst und ueberstimmen damit das Attribut, uebrig blieb ein leerer
  //  Punkt ueber der ersten Karte.
  const choices = [["all", "All"]];
  if (signedIn) choices.push(["following", "Following"]);
  if (signedIn && handle) choices.push(["mine", "Yours"]);

  const requested = new URLSearchParams(location.search).get("show");
  const show = choices.some(([value]) => value === requested) ? requested : "all";

  if (choices.length > 1) {
    for (const [value, label] of choices) {
      showBox.append(el("a", {
        class: show === value ? "active" : null,
        href: value === "all" ? "/collections.html" : "/collections.html?show=" + value,
      }, label));
    }
  }

  if (signedIn) {
    const make = el("button", { class: "button", type: "button" }, "New collection");
    make.addEventListener("click", async () => {
      const made = await collectionDialog();
      if (made) location.href = "/collection.html?c=" + encodeURIComponent(made.slug);
    });
    makeBox.append(make);
  }

  if (choices.length === 1) showBox.remove();
  if (!signedIn) filters.remove();

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
    } else if (show === "mine") {
      list = await api("GET", "/api/v1/collections?owner=" + encodeURIComponent(handle));
      emptyText = "You have no collections yet. Save a clip with the star to start one.";
    } else {
      list = await api("GET", "/api/v1/collections?limit=100");
      emptyText = "No public collections yet. Save a clip with the star to start one.";
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
