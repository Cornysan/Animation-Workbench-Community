(async () => {
  const { api, ensureCsrf, notice, renderShell } = AW;
  renderShell();

  const form = document.getElementById("takedown-form");
  const state = document.getElementById("state");

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const button = form.querySelector("button[type=submit]");
    button.disabled = true;

    try {
      await ensureCsrf();
      const receipt = await api("POST", "/api/v1/takedowns", {
        contactName: form.contactName.value,
        contactEmail: form.contactEmail.value,
        rightsHolder: form.rightsHolder.value,
        claimedWork: form.claimedWork.value,
        packages: form.packages.value,
        goodFaith: form.goodFaith.checked,
        accurate: form.accurate.checked,
      });

      let message = "Received (reference " + receipt.id.slice(0, 8) + "). " +
        receipt.hiddenPackages + " clip(s) are hidden now. We will reply by email.";
      if (receipt.unknownReferences.length > 0) {
        message += " We could not match: " + receipt.unknownReferences.join(", ") + ".";
      }
      notice(state, message, "ok");
      form.reset();
    } catch (err) {
      notice(state, err.message, "error");
    } finally {
      button.disabled = false;
    }
  });
})();
