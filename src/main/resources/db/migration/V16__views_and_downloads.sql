-- ---------------------------------------------------------------------------
-- Schema 16: Aufrufe und Downloads unter jedem Clip (2026-09-27).
--
-- take_count zaehlt seit V2 nur die Uebernahme als .awclip, eine je Konto.
-- Was dort nie ankam: wer die Seite ansieht, und wer den Clip als FBX oder
-- GLB mitnimmt - die beiden Dateien entstehen ganz im Browser, ohne Konto,
-- und der Server sah sie bis hierher nie.
--
-- Nur Zahlen, keine Zeilen je Besucher: wer schon gezaehlt ist, weiss der
-- Server einen Tag lang im Speicher (catalog/ClipCounts.kt), nicht hier.
-- ---------------------------------------------------------------------------

alter table animation_package add column view_count bigint not null default 0;
alter table animation_package add column file_download_count bigint not null default 0;
