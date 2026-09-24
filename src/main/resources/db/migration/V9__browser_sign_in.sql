-- ---------------------------------------------------------------------------
-- Schema 9: Der Browser bleibt angemeldet, und die Workbench nimmt ihn mit.
--
-- Die Browser-Sitzung lebt im Speicher des Servers: 30 Minuten Leerlauf, und
-- jeder Neustart - jedes Ausrollen - wirft alle ab. Wer in der Workbench
-- "Your account on the portal" waehlte, stand deshalb fast immer vor der
-- Anmeldeseite, obwohl er in Unity laengst angemeldet war.
--
-- browser_login: "angemeldet bleiben". Das Cookie traegt einen Zufallswert,
--   hier steht nur sein Hash. Fehlt die Sitzung, meldet es den Browser wieder
--   an und liest dabei das Konto frisch - ein gesperrtes Konto kommt so nicht
--   mehr herein, und eine entzogene Rolle gilt ab der naechsten Sitzung.
--   Gueltig 30 Tage ab dem letzten Besuch (PortalRememberMeServices).
--
-- sign_in_handoff: die Uebergabe aus der Workbench. Unity holt sich mit
--   seinem Token einen Code, der eine Minute gilt und genau einmal einloesbar
--   ist; der Browser loest ihn ein und ist angemeldet. Das Ziel steht HIER,
--   nicht in der Adresse - wer den Link veraendert, lenkt nirgendwohin um.
-- ---------------------------------------------------------------------------

create table browser_login (
    id              uuid primary key,
    account_id      uuid         not null references account (id),
    token_hash      varchar(64)  not null unique,
    created_at      timestamp with time zone not null,
    last_used_at    timestamp with time zone,
    expires_at      timestamp with time zone not null
);

create index browser_login_account on browser_login (account_id);

create table sign_in_handoff (
    id              uuid primary key,
    code_hash       varchar(64)  not null unique,
    account_id      uuid         not null references account (id),
    next_path       varchar(200) not null,
    created_at      timestamp with time zone not null,
    expires_at      timestamp with time zone not null,
    used_at         timestamp with time zone
);
