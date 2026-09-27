-- ---------------------------------------------------------------------------
-- Schema 17: Vorschaubilder aus der echten Buehne (2026-09-27).
--
-- Das Bild, das Discord, Slack und Google zu einem Clip zeigen, zeichnete der
-- Server bis hierher selbst - als flache Strichfigur, weil er weder WebGL noch
-- eine Schrift hat. Jetzt rendert es der Browser mit derselben Buehne wie die
-- Clip-Seite (og-card.js) und legt es hier ab: je Version eines, denn eine neue
-- Version ist eine andere Bewegung.
--
-- Das Bild fuer alles andere (Startseite, Share, Clips ohne eigenes Bild)
-- steht als Einstellung `site.card` in system_setting.
-- ---------------------------------------------------------------------------

alter table package_version add column card_blob_key varchar(64);
