-- ---------------------------------------------------------------------------
-- Schema 25: Benachrichtigungen zeigen auf die neuen Adressen (2026-11-22).
--
-- Seit heute haben die Seiten keine Endung mehr: `/clip/<slug>` statt
-- `/clip.html?p=<slug>` (web/PortalPaths.kt). Die alten Adressen leiten
-- weiter, ein Klick auf eine alte Nachricht kaeme also an. Umgeschrieben wird
-- trotzdem, aus zwei Gruenden:
--
--   1. Die Kontoseite zeigte sonst noch Monate lang alte Links, jeder mit
--      einem Umweg ueber eine Weiterleitung.
--   2. `Notifier.fromActor(once = true)` vergleicht den Link einer neuen
--      Nachricht mit den gespeicherten. Stuende dort noch die alte Form,
--      gaebe es dieselbe Nachricht ("hat deinen Clip gesammelt") nach dem
--      Umstieg ein zweites Mal.
--
-- Nur REPLACE, kein regulaerer Ausdruck: der laeuft auf Postgres und H2 mit
-- verschiedener Schreibweise fuer Rueckverweise. Die alten Links haben genau
-- vier Formen (NotificationLinks bis heute), und der Schluessel stand jeweils
-- am Ende - `#comments` bleibt dahinter stehen.
-- ---------------------------------------------------------------------------

update notification set link = replace(link, '/clip.html?p=', '/clip/') where link like '/clip.html?p=%';
update notification set link = replace(link, '/collection.html?c=', '/collection/') where link like '/collection.html?c=%';
update notification set link = replace(link, '/pack.html?k=', '/pack/') where link like '/pack.html?k=%';
