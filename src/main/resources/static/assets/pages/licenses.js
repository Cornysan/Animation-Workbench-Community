(async () => {
  const { api, el, notice, renderShell } = AW;
  renderShell("licenses");

  const box = document.getElementById("licenses");
  try {
    const licenses = await api("GET", "/api/v1/licenses");
    box.replaceChildren(...licenses.map((license) =>
      el("div", { class: "panel case" },
        el("h2", {}, license.name),
        el("p", {}, license.summary),
        // Privat hat keine Lizenzseite - und steht in keiner Liste, auf die
        // man verweisen könnte.
        license.url
          ? el("p", {},
              el("a", { href: license.url, rel: "noopener", target: "_blank" }, "Read the full license"),
              " · ",
              el("a", { href: "/" }, "Browse these clips"))
          : null)));
  } catch (e) {
    notice(box, e.message, "error");
  }
})();
