-- ---------------------------------------------------------------------------
-- Schema 3: Kommentare.
--
-- Flach, ohne Threads: eine Antwort auf eine Antwort verlangt eine Ansicht,
-- die 302 Pixel breit nicht mehr lesbar ist - so breit ist die Detailspalte
-- in der Workbench.
--
-- Gelöscht wird nie wirklich. REMOVED behält den Text für den Fall, dass
-- jemand später fragt, was da stand; die Ausgabe zeigt nur VISIBLE.
--
-- report.comment_id: Meldungen bleiben EINE Liste. Ist die Spalte gesetzt,
-- versteckt die Meldung den Kommentar statt des Pakets. package_id bleibt
-- trotzdem gefüllt, damit der Fall in der vorhandenen Fallliste am richtigen
-- Clip hängt und die Abfragen dort unverändert bleiben.
-- ---------------------------------------------------------------------------

create table package_comment (
    id              uuid primary key,
    package_id      uuid          not null references animation_package (id),
    account_id      uuid          not null references account (id),
    body            varchar(1000) not null,
    status          varchar(16)   not null,
    created_at      timestamp with time zone not null,
    edited_at       timestamp with time zone,
    removed_by      uuid references account (id),
    removed_reason  varchar(2000)
);

create index idx_comment_package on package_comment (package_id, created_at);
create index idx_comment_account on package_comment (account_id);

alter table animation_package add column comment_count bigint not null default 0;

alter table report add column comment_id uuid references package_comment (id);
