-- ---------------------------------------------------------------------------
-- Schema 18: Figur und Look eines Clips (2026-09-28).
--
-- Wie das Mannequin aussieht, das alle auf Karte, Clip-Seite und
-- Link-Vorschau sehen - vom Ersteller gewaehlt (looks/FigureLooks.kt). Am
-- Paket, nicht an der Version: eine neue Fassung ist eine andere Bewegung,
-- aber derselbe Auftritt.
--
-- Die Freischaltungen stehen nirgends: sie werden aus den Auszeichnungen
-- gerechnet, und die aus Zahlen, die ohnehin gezaehlt werden.
-- ---------------------------------------------------------------------------

alter table animation_package add column figure varchar(16) not null default 'default';
alter table animation_package add column look varchar(24) not null default 'classic';
