-- ---------------------------------------------------------------------------
-- Schema 4: Münzen.
--
-- Eine Tauschwirtschaft ohne Geld. Freischalten kostet, eigene Clips
-- verdienen, Wochenaufgabe und Meilensteine füllen auf. Münzen sind nicht
-- kaufbar, nicht übertragbar und haben keinen Geldwert - sonst wäre das ein
-- Marktplatz, und der ist ausdrücklich nicht gebaut.
--
-- coin_entry ist anhängend wie moderation_action und audit_log: der Code hat
-- keinen Update- und keinen Löschpfad. Eine Rückbuchung ist eine NEUE Zeile
-- mit umgekehrtem Vorzeichen, kein Zurücknehmen der alten.
--
-- account.coin_balance ist der abgeleitete Wert. Er wird in derselben
-- Transaktion fortgeschrieben wie die Buchung; das Protokoll bleibt die
-- Wahrheit, der Saldo ist die schnelle Antwort.
--
-- package_unlock ist die Quittung. Ihr zusammengesetzter Schlüssel macht
-- doppeltes Abbuchen unmöglich, ohne dass der Code prüfen muss - dasselbe
-- Mittel wie bei package_like. Sie ersetzt zugleich den anonymen Zähler aus
-- /taken: take_count zählt ab hier Quittungen und ist damit kontogedeckt.
-- ---------------------------------------------------------------------------

alter table account add column coin_balance bigint not null default 0;

create table coin_entry (
    id               uuid         primary key,
    account_id       uuid         not null references account (id),

    -- Vorzeichenbehaftet: negativ ist eine Ausgabe.
    amount           bigint       not null,

    -- grant | unlock | earn | quest | milestone | reversal | admin
    reason           varchar(32)  not null,

    ref_type         varchar(16),
    ref_id           uuid,

    -- Derselbe Vorgang darf nie zweimal buchen. Der Schlüssel trägt den
    -- Vorgang, nicht den Zeitpunkt.
    idempotency_key  varchar(160) not null unique,

    created_at       timestamp with time zone not null
);

create index idx_coin_entry_account on coin_entry (account_id, created_at);

create table package_unlock (
    package_id  uuid   not null references animation_package (id),
    account_id  uuid   not null references account (id),
    cost_paid   bigint not null,

    -- true = der Besitzer wurde dafuer gutgeschrieben. Traegt zugleich den
    -- Meilensteinfortschritt, damit private Clips und Selbstabrufe ihn nicht
    -- aufblaehen - und damit er weiterlaeuft, wenn das Tor aus ist.
    earned      boolean not null default false,
    created_at  timestamp with time zone not null,
    primary key (package_id, account_id)
);

create index idx_package_unlock_account on package_unlock (account_id, created_at);

-- period_key trennt die Läufe: 'weekly:2026-W38' für die Wochenaufgabe,
-- 'lifetime' für Meilensteine. Tagesaufgaben wären 'daily:2026-09-20' - das
-- Schema kann sie, gebaut sind sie nicht.
create table quest_progress (
    account_id   uuid        not null references account (id),
    quest_key    varchar(32) not null,
    period_key   varchar(24) not null,
    progress     integer     not null default 0,
    completed_at timestamp with time zone,
    primary key (account_id, quest_key, period_key)
);
