-- ---------------------------------------------------------------------------
-- Schema 6: Aus Namen werden Leute.
--
-- Bis hierher war ein Ersteller eine Zeichenkette. Die Karte verlinkte auf
-- `/browse.html?author=<Anzeigename>` - eine gefilterte Liste, kein Ort. Wer
-- zwanzig Clips geteilt hatte, hatte trotzdem kein Profil, keine Follower und
-- keine Adresse, die man weitergeben kann.
--
-- DER HANDLE IST DIE ADRESSE, NICHT DER ANZEIGENAME. Anzeigenamen sind nicht
-- eindeutig (`AccountRepository.findByDisplayName` gibt deshalb eine Liste
-- zurueck), und sie aendern sich, sobald jemand seinen Discord-Namen aendert.
-- Beides ist fuer eine Adresse toedlich: das eine fuehrt zwei Leute auf
-- dieselbe Seite, das andere bricht jeden geteilten Link. Der Handle wird
-- einmal vergeben, ist eindeutig und ueberlebt jede Umbenennung.
--
-- Die Spalte ist NULLBAR, obwohl am Ende jedes Konto einen Handle hat: Flyway
-- laeuft vor dem ersten Login, und eine Vergaberegel mit Kollisionsaufloesung
-- gehoert nicht in SQL. `AccountHandles` vergibt beim Login, `HandleBackfill`
-- holt die Bestandskonten beim Start nach.
--
-- account_follow traegt seinen Schluessel aus beiden Seiten - wie
-- `package_like` in V2: doppeltes Folgen wird unmoeglich, ohne dass der Code
-- pruefen muss.
--
-- report.package_id wird nullbar, weil eine Meldung jetzt auch einem KONTO
-- gelten kann. Meldungen bleiben EINE Liste (die Begruendung steht in
-- V3__comments.sql); ein zweiter Meldungstisch waere ein zweiter Ort, an dem
-- man nachsehen muss.
-- ---------------------------------------------------------------------------

alter table account add column handle varchar(32);
alter table account add column bio varchar(500);

-- Der Avatar-Hash von Discord, nicht das Bild. Das Bild liegt bei Discord
-- (cdn.discordapp.com steht dafuer schon in der Content Security Policy);
-- hier steht nur, wie es heisst. Wer sein Bild wechselt, bekommt beim
-- naechsten Login den neuen Hash - ein zwischengespeichertes Bild waere
-- Datenhaltung ohne Auftrag.
alter table account add column avatar varchar(64);

alter table account add column follower_count bigint not null default 0;

create unique index uq_account_handle on account (handle);

create table account_follow (
    follower_id uuid not null references account (id),
    followee_id uuid not null references account (id),
    created_at  timestamp with time zone not null,
    primary key (follower_id, followee_id)
);

create index idx_follow_followee on account_follow (followee_id);

alter table report alter column package_id drop not null;
alter table report add column account_id uuid references account (id);
