(async () => {
  const { api, ensureCsrf, notice } = AW;

  const form = document.getElementById("dev-form");
  const state = document.getElementById("state");

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    try {
      await ensureCsrf();
      //  form.name wäre der Name des Formulars selbst, nicht das Feld.
      const result = await api("POST", "/api/v1/dev/login", { name: form.elements.namedItem("name").value.trim() });
      notice(state, "Signed in (" + result.role + "). Bearer token for tools: " + result.token, "ok");
      setTimeout(() => (location.href = "/"), 1500);
    } catch (err) {
      notice(state, err.status === 404 ? "Developer sign-in is not enabled on this server." : err.message, "error");
    }
  });
})();
