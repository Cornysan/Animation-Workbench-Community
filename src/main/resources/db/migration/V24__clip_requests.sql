-- ---------------------------------------------------------------------------
-- Schema 24: Clip-Wuensche aus einer leeren Suche (2026-10-03).
--
-- Findet eine Suche in der Workbench nichts, kann man den Suchtext ins
-- Discord-Forum fuer Clip-Wuensche stellen ("Ask for it on Discord",
-- requests/ClipRequestService.kt). Anders als im Schaufenster (Schema 22)
-- geht der Post sofort hinaus - eine Zeile entsteht erst, wenn Discord ihn
-- angenommen hat, und ist von Anfang an die Quittung, mit der er sich wieder
-- loeschen laesst.
--
-- phrase_key ist der Suchtext in Kleinbuchstaben: wer nach etwas fragt, das
-- schon offen ist, bekommt dessen Thread statt eines zweiten.
--
-- named: ob Name und Bild des Kontos im Post stehen - das Haekchen beim
-- Fragen. retract_wanted: "Take it back" oder ein gesperrtes/geschlossenes
-- Konto; der Takt loescht dann den Post und setzt retracted_at.
-- ---------------------------------------------------------------------------

create table clip_request (
    id              uuid        primary key,
    account_id      uuid        not null,
    phrase          varchar(80) not null,
    phrase_key      varchar(80) not null,
    named           boolean     not null,
    asked_at        timestamp with time zone not null,
    -- Was Discord zurueckgab. thread_id nur, wenn der Post ein eigener
    -- Forum-Thread wurde; in einem Textkanal steht er ohne.
    message_id      varchar(32) not null,
    thread_id       varchar(32),
    retract_wanted  boolean     not null default false,
    retracted_at    timestamp with time zone,
    attempts        integer     not null default 0,
    -- retract-failed: Discord hat das Loeschen zehnmal abgelehnt.
    outcome         varchar(24)
);

create index idx_clip_request_key on clip_request (phrase_key, asked_at);
create index idx_clip_request_account on clip_request (account_id, asked_at);
create index idx_clip_request_open on clip_request (retracted_at);
