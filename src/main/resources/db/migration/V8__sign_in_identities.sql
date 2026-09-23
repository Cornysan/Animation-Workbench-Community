-- ---------------------------------------------------------------------------
-- Schema 8: Ein Konto, mehrere Wege hinein.
--
-- Bis hierher WAR ein Konto seine Discord-Kennung: `account.discord_id`,
-- eindeutig und Pflicht. Wer kein Discord hat, kam nicht herein, und ein
-- zweiter Anbieter haette entweder ein zweites Konto fuer dieselbe Person
-- bedeutet oder eine Spalte je Anbieter.
--
-- Jetzt haengen die Anmeldungen AM Konto: eine Zeile je Anbieter und Kennung.
-- Die Kombination ist eindeutig - dieselbe GitHub-Kennung kann nicht an zwei
-- Konten haengen -, das Konto selbst kennt keinen Anbieter mehr.
--
-- Die Bestandsdaten ziehen mit um:
--   echte Discord-Konten      -> provider 'discord', subject = die Kennung
--   Entwickler-Logins "dev:x" -> provider 'dev',     subject = 'x'
--   geschlossene "deleted:x"  -> KEINE Anmeldung. Genau das war der Sinn des
--                                Platzhalters: dieselbe Person kommt mit einem
--                                frischen Konto wieder, nicht in die Huelle.
--
-- Die neuen Zeilen tragen die Kennung ihres Kontos als eigene. Das ist keine
-- Abkuerzung, sondern der einzige Weg ohne Zufallsfunktion: PostgreSQL und H2
-- (die Tests) nennen sie verschieden. Eindeutig ist es trotzdem - jedes Konto
-- hat zu diesem Zeitpunkt hoechstens eine Anmeldung.
--
-- DAS PROFILBILD wird zur Adresse. Gespeichert war Discords Hash, und die
-- Adresse bei Discord baute erst der Code zusammen. Ein Hash ist aber ein
-- Discord-Begriff; GitHub und Google liefern gleich eine Adresse. Welche
-- Server ueberhaupt gefragt werden duerfen, steht in `AvatarSources`.
-- ---------------------------------------------------------------------------

create table account_identity (
    id              uuid primary key,
    account_id      uuid         not null references account (id),
    provider        varchar(16)  not null,
    subject         varchar(128) not null,
    created_at      timestamp with time zone not null,
    last_login_at   timestamp with time zone
);

create unique index uq_identity_provider_subject on account_identity (provider, subject);
create index idx_identity_account on account_identity (account_id);

insert into account_identity (id, account_id, provider, subject, created_at, last_login_at)
select id, id, 'discord', discord_id, created_at, last_login_at
from account
where discord_id not like 'dev:%' and discord_id not like 'deleted:%';

insert into account_identity (id, account_id, provider, subject, created_at, last_login_at)
select id, id, 'dev', substr(discord_id, 5), created_at, last_login_at
from account
where discord_id like 'dev:%';

alter table account add column avatar_url varchar(512);

update account
set avatar_url = 'https://cdn.discordapp.com/avatars/' || discord_id || '/' || avatar || '.png?size=128'
where avatar is not null and avatar <> ''
  and discord_id not like 'dev:%' and discord_id not like 'deleted:%';

alter table account drop column avatar;
alter table account drop column discord_id;
