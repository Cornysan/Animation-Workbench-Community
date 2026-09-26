-- ---------------------------------------------------------------------------
-- Schema 15: Woher Name und Bild kommen, laesst sich waehlen (2026-09-26).
--
-- Bisher kamen sie immer von der AELTESTEN Anmeldung eines Kontos. Das bleibt
-- die Vorgabe - wer Google verbindet, hat damit nicht darum gebeten, dass sein
-- Klarname auf dem Profil steht. Aber wer sich mit GitHub angemeldet und
-- Discord verbunden hat, will vielleicht den Discord-Namen und das
-- Discord-Bild, und konnte das bis hierher nirgends sagen.
--
-- Eine Spalte am Konto, nicht an der Anmeldung: ein Konto hat hoechstens eine
-- Anmeldung je Anbieter (AccountService.connect), der Anbieter bezeichnet sie
-- also eindeutig. Leer = die aelteste, wie bisher. Wird die gewaehlte
-- Anmeldung geloest, wird die Spalte wieder leer.
-- ---------------------------------------------------------------------------

alter table account add column profile_provider varchar(32);
