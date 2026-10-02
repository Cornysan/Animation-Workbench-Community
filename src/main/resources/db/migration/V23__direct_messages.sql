-- ---------------------------------------------------------------------------
-- Schema 23: Direktnachrichten (2026-10-03).
--
-- Bis hierher konnte man einander folgen, Herzen geben und unter einem Clip
-- schreiben - aber nichts davon erreichte EINE Person allein. Wer einen
-- Ersteller nach seinem Rig fragen wollte, musste es oeffentlich tun oder ihn
-- woanders suchen.
--
-- DREI REGELN, die das Schema traegt:
--
--   1. Ein Paar hat genau EIN Gespraech. `account_a` < `account_b` (so, wie
--      der Dienst sie sortiert) und ein eindeutiger Schluessel darueber -
--      wer zum zweiten Mal "Message" drueckt, landet im alten Gespraech.
--
--   2. Die erste Nachricht ist eine ANFRAGE. `accepted_at` bleibt leer, bis
--      die andere Seite annimmt oder antwortet (oder der schreibenden Person
--      schon folgt). Bis dahin darf die schreibende Seite nur wenige
--      Nachrichten nachschieben (`portal.messages.pending-limit`).
--
--   3. Jede Seite hat ihren eigenen Stand: gelesen bis (`read_*`) und
--      geleert ab (`cleared_*`). "Gespraech loeschen" leert es fuer MICH;
--      die andere Seite behaelt ihre Kopie, wie bei einer Mail. Erst wenn
--      beide geleert haben, verschwinden die Zeilen (MessageService.clear).
--
-- KEINE LESEBESTAETIGUNG. `read_*` sagt nur der eigenen Seite, was neu ist,
-- und wird der anderen nie gezeigt.
--
-- Der Text ist nur Text: keine Anhaenge, keine Bilder. Ein Bild in einer
-- privaten Nachricht ist das Einzige hier, das rechtlich wirklich wiegt, und
-- niemand ausser den beiden saehe es je.
--
-- `account_block` gilt in BEIDE Richtungen: wer blockiert, kann auch selbst
-- nicht mehr schreiben, bis er die Sperre aufhebt - sonst waere es ein
-- Megafon ohne Rueckkanal.
--
-- report.message_id / report.evidence: eine gemeldete Nachricht wird mit den
-- Nachrichten davor in die Meldung KOPIERT. Die Moderation liest Gespraeche
-- nicht mit; sie sieht nur, was ein Beteiligter ihr selbst vorlegt - und das
-- soll stehen bleiben, auch wenn das Gespraech danach geleert wird.
-- ---------------------------------------------------------------------------

create table conversation (
    id               uuid primary key,
    account_a        uuid not null references account (id),
    account_b        uuid not null references account (id),
    started_by       uuid not null,
    accepted_at      timestamp with time zone,
    declined_at      timestamp with time zone,
    created_at       timestamp with time zone not null,
    -- Die letzte Nachricht, ohne sie zu laden: fuer die Liste und fuer die
    -- Zahl am Briefsymbol im Kopf jeder Seite (ShellModel).
    last_message_at  timestamp with time zone not null,
    last_sender_id   uuid not null,
    read_a          timestamp with time zone,
    read_b           timestamp with time zone,
    cleared_a        timestamp with time zone,
    cleared_b        timestamp with time zone,
    constraint uq_conversation_pair unique (account_a, account_b)
);

create index idx_conversation_b on conversation (account_b);

create table direct_message (
    id               uuid primary key,
    conversation_id  uuid          not null references conversation (id) on delete cascade,
    sender_id        uuid          not null,
    body             varchar(2000) not null,
    created_at       timestamp with time zone not null
);

create index idx_direct_message_conversation on direct_message (conversation_id, created_at);

create table account_block (
    blocker_id  uuid not null references account (id),
    blocked_id  uuid not null references account (id),
    created_at  timestamp with time zone not null,
    primary key (blocker_id, blocked_id)
);

create index idx_account_block_blocked on account_block (blocked_id);

alter table report add column message_id uuid;
alter table report add column evidence varchar(24000);
