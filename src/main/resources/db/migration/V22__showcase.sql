-- ---------------------------------------------------------------------------
-- Schema 22: neue Clips im Discord-Schaufenster (2026-10-02).
--
-- Wer teilt, darf ankreuzen, dass der Clip (oder Pack) im Discord-Forum
-- erscheint. Gepostet wird nicht sofort: das Vorschaubild rendert erst der
-- Browser des Besitzers (web/PreviewCards.kt), und ein Clip aus Unity hat
-- beim Hochladen noch keins. Eine Zeile hier ist darum zuerst ein Auftrag,
-- den ein Takt abarbeitet, sobald das Bild da ist oder die Wartezeit um ist
-- (showcase/ShowcaseService.kt) - und danach die Quittung, mit der sich der
-- Post wieder loeschen laesst, wenn der Clip verschwindet.
--
-- Eine eigene Tabelle statt Spalten am Paket: das Paket wird von Herz und
-- Kommentar als Ganzes gespeichert und wuerde einen Stand, den der Takt
-- inzwischen geschrieben hat, wieder zuruecksetzen (siehe ClipCounts).
--
-- `on delete set null`: ein aufgeloester Pack nimmt die Zeile nicht mit. Ohne
-- beide Verweise heisst sie "Ziel weg", und der Takt loescht den Post.
-- ---------------------------------------------------------------------------

create table showcase_post (
    id            uuid        primary key,
    package_id    uuid        references animation_package (id) on delete set null,
    pack_id       uuid        references clip_pack (id) on delete set null,
    account_id    uuid        not null,
    requested_at  timestamp with time zone not null,
    posted_at     timestamp with time zone,
    retracted_at  timestamp with time zone,
    -- Was Discord zurueckgab - zum Loeschen. thread_id nur, wenn der Post ein
    -- eigener Forum-Thread wurde; in einem Textkanal steht er ohne.
    message_id    varchar(32),
    thread_id     varchar(32),
    attempts      integer     not null default 0,
    -- Warum nie gepostet wurde: not-public, failed. Leer = offen oder gepostet.
    outcome       varchar(24)
);

create index idx_showcase_package on showcase_post (package_id);
create index idx_showcase_pack on showcase_post (pack_id);
create index idx_showcase_open on showcase_post (posted_at, outcome);
