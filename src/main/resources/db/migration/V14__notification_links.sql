-- ---------------------------------------------------------------------------
-- Schema 14: Benachrichtigungen mit Absender und Ziel (2026-09-26).
--
-- Bis hierher war eine Benachrichtigung ein fertiger Satz: "Pablo follows you
-- now." Man konnte ihn lesen und sonst nichts - weder auf den Namen klicken
-- noch auf den Clip, um den es ging. Und der Name stand fest im Text: wer sein
-- Konto schloss, stand in den Postfaechern der anderen weiter mit Namen da.
--
-- Jetzt drei Spalten dazu, alle nullbar, weil die bestehenden Zeilen sie nicht
-- haben und die Moderationsnachrichten sie nicht brauchen:
--
--   kind      was es ist (follow, like, comment, ...) - fuer das Zeichen
--             davor und fuers Zusammenfassen, nie fuer den Text.
--   actor_id  wer es ausgeloest hat. Der Name kommt beim LESEN aus dem Konto,
--             nicht aus dem Text: `message` traegt bei diesen Zeilen nur noch
--             den Rest des Satzes ("follows you now."). Ein geschlossenes
--             Konto heisst danach ueberall "Deleted user".
--   link      wohin ein Klick fuehrt (eine Adresse dieses Portals). Leer mit
--             Absender = auf dessen Profil; das steht nicht hier, weil sich
--             der Handle aendern darf.
--
-- Kein Fremdschluessel auf actor_id: ein geschlossenes Konto bleibt als
-- anonyme Zeile stehen (AccountDeletionService), und seine Nachrichten bei
-- anderen raeumt der Dienst selbst ab.
-- ---------------------------------------------------------------------------

alter table notification add column kind varchar(24);
alter table notification add column actor_id uuid;
alter table notification add column link varchar(300);

create index idx_notification_actor on notification (actor_id);
