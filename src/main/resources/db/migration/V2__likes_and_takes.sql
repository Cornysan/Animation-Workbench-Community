-- ---------------------------------------------------------------------------
-- Schema 2: Herzen, und ein ehrlicher Zähler.
--
-- Bis hierher zählte animation_package.download_count JEDEN Dateiabruf. Seit
-- die Workbench Clips zum Stöbern herunterlädt, wäre das ein Maß fürs
-- Scrollen. Gezählt wird jetzt die Übernahme in ein Projekt ("taken"), und die
-- Spalte heißt entsprechend - der alte Wert wandert mit, er meinte bisher
-- ungefähr dasselbe.
--
-- package_like hält je Konto und Paket höchstens eine Zeile; like_count ist
-- die daraus abgeleitete Zahl, damit Liste und Sortierung ohne Unterabfrage
-- auskommen.
-- ---------------------------------------------------------------------------

alter table animation_package rename column download_count to take_count;

alter table animation_package add column like_count bigint not null default 0;

create table package_like (
    package_id  uuid not null references animation_package (id),
    account_id  uuid not null references account (id),
    created_at  timestamp with time zone not null,
    primary key (package_id, account_id)
);

create index idx_package_like_package on package_like (package_id);
create index idx_animation_package_likes on animation_package (like_count);
