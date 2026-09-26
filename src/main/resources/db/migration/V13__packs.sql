-- ---------------------------------------------------------------------------
-- Schema 13: Packs (2026-09-26).
--
-- Ein Pack ist die Einheit, in der ein Ersteller veroeffentlicht - wie bei
-- Mixamo das "Longbow Locomotion Pack" mit seinen zwoelf Clips. Ohne Packs
-- belegen achtzehn Gesten aus EINER Quelle die ganze erste Seite des Katalogs;
-- mit ihnen steht dort eine Karte mit einem Stapel dahinter.
--
-- Nicht dasselbe wie eine Sammlung (V7): eine Sammlung ist eine Auswahl, die
-- jemand aus FREMDEN Clips trifft, und ein Clip kann in beliebig vielen
-- liegen. Ein Pack enthaelt nur eigene Clips, und jeder Clip gehoert
-- hoechstens EINEM - deshalb steht die Zugehoerigkeit als Spalte am Clip und
-- nicht in einer Zwischentabelle. Die Datenbank verhindert so den zweiten
-- Pack, ohne dass der Code pruefen muss.
--
-- Was ein Pack zeigt, leitet sich aus seinen Clips ab: Zahl, Deckel,
-- Schlagworte, Beliebtheit, Quelle. Gespeichert ist nur, was der Ersteller
-- selbst sagt - Titel und Beschreibung. Wird ein Clip zurueckgezogen oder
-- privat, faellt er still aus dem Pack, wie aus einer Sammlung; ein Pack ohne
-- sichtbaren Clip steht nirgends.
-- ---------------------------------------------------------------------------

create table clip_pack (
    id           uuid          primary key,
    slug         varchar(16)   not null unique,
    owner_id     uuid          not null references account (id),
    title        varchar(80)   not null,
    description  varchar(2000) not null,
    created_at   timestamp with time zone not null,
    updated_at   timestamp with time zone not null
);

create index idx_clip_pack_owner on clip_pack (owner_id);

alter table animation_package add column pack_id uuid references clip_pack (id);

-- Die Reihenfolge im Pack. Nur gesetzt, solange pack_id gesetzt ist.
alter table animation_package add column pack_position integer;

create index idx_package_pack on animation_package (pack_id);
