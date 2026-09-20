-- ---------------------------------------------------------------------------
-- Schema 7: Sammlungen.
--
-- "Angriffsanimationen" war bis hierher ein Schlagwort, das jeder anders
-- vergibt. Eine Sammlung ist das Gegenteil davon: eine Auswahl, die ein Mensch
-- getroffen hat, mit Namen, Adresse und Besitzer. Sie darf FREMDE Clips
-- enthalten - genau das ist ihr Sinn, sonst waere sie ein Ordner fuers eigene
-- Werk und niemand braeuchte sie.
--
-- Was hineindarf, entscheidet der Katalog, nicht die Sammlung: nur
-- veroeffentlichte Clips mit oeffentlicher Lizenz. Damit kann eine Sammlung
-- keinen privaten Clip weiterreichen, und die Sichtbarkeitsregel bleibt an
-- EINER Stelle.
--
-- collection_item traegt seinen Schluessel aus beiden Seiten: ein Clip steht
-- in einer Sammlung hoechstens einmal. `position` haelt die Reihenfolge - eine
-- Sammlung ist eine Auswahl, keine Menge, und "welcher zuerst" ist Teil der
-- Aussage.
--
-- animation_package.save_count ist der Stern an der Karte: wie viele
-- PERSONEN den Clip in einer Sammlung haben. Gezaehlt werden Besitzer, nicht
-- Zeilen - wer denselben Clip in drei eigene Sammlungen legt, ist trotzdem
-- eine Person.
--
-- Die Sammlung traegt denselben Statusreigen wie ein Paket (PUBLISHED,
-- AUTO_HIDDEN, REMOVED), weil Titel und Beschreibung Nutzertext sind und damit
-- meldbar. report.collection_id ist das Gegenstueck dazu; Meldungen bleiben
-- EINE Liste (siehe V3__comments.sql).
-- ---------------------------------------------------------------------------

create table collection (
    id           uuid          primary key,
    slug         varchar(16)   not null unique,
    owner_id     uuid          not null references account (id),
    title        varchar(80)   not null,
    description  varchar(1000) not null,

    -- PUBLIC steht auf dem Profil und im Katalog, UNLISTED nur hinter seinem
    -- Link - dieselbe Trennung wie zwischen oeffentlichem und privatem Clip.
    visibility   varchar(16)   not null,

    status       varchar(24)   not null,

    -- Sichtbare Eintraege. Abgeleitet wie like_count: die Tabelle ist die
    -- Wahrheit, die Spalte die schnelle Antwort.
    item_count   bigint        not null default 0,

    created_at   timestamp with time zone not null,
    updated_at   timestamp with time zone not null
);

create index idx_collection_owner on collection (owner_id);
create index idx_collection_status on collection (status);

create table collection_item (
    collection_id uuid    not null references collection (id),
    package_id    uuid    not null references animation_package (id),
    position      integer not null,
    added_at      timestamp with time zone not null,
    primary key (collection_id, package_id)
);

create index idx_collection_item_package on collection_item (package_id);

alter table animation_package add column save_count bigint not null default 0;

alter table report add column collection_id uuid references collection (id);
